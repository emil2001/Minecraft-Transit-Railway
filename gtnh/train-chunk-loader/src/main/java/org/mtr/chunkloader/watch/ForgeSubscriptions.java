package org.mtr.chunkloader.watch;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.ChunkCoordComparator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Forge's own PlayerInstance handles chunk IO callbacks, unload packets and shared watchers. */
final class ForgeSubscriptions implements WatchWindow.Subscriptions {
    private static final Method WATCHER = ReflectionHelper.findMethod(PlayerManager.class, null,
            new String[] {"getOrCreateChunkWatcher", "func_72690_a"}, int.class, int.class, boolean.class);
    private static final Class<?> INSTANCE = WATCHER.getReturnType();
    private static final Method ADD = playerMethod("addPlayer", "func_73255_a");
    private static final Method REMOVE = playerMethod("removePlayer", "func_73252_b");
    private static final Field MEMBERS = ReflectionHelper.findField(INSTANCE, "playersWatchingChunk", "field_73263_b");
    private static final Field RADIUS = ReflectionHelper.findField(PlayerManager.class, "playerViewRadius", "field_72698_e");

    private final PlayerManager manager;
    private final EntityPlayerMP player;
    private final HodgepodgeQueue queue;
    private long lastAheadAdmission = Long.MIN_VALUE;

    ForgeSubscriptions(PlayerManager manager, EntityPlayerMP player) {
        this.manager = manager;
        this.player = player;
        queue = HodgepodgeQueue.forPlayer(manager, player);
    }

    static int radius(PlayerManager manager) {
        try {
            return RADIUS.getInt(manager);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public void resetPending() {
        if (queue != null) queue.withdraw();
    }

    public void unwatch(int x, int z) {
        Object watcher = call(WATCHER, manager, x, z, false);
        if (watcher != null) call(REMOVE, watcher, player);
    }

    public boolean watchSaved(int x, int z) {
        WorldServer world = manager.getWorldServer();
        ChunkProviderServer provider = world.theChunkProviderServer;
        if (!(provider.currentChunkLoader instanceof AnvilChunkLoader)) return false;
        Object watcher = call(WATCHER, manager, x, z, false);
        boolean existing = member(watcher);
        long tick = world.getTotalWorldTime();
        if (!existing && lastAheadAdmission == tick) return false;
        // Native attachment can populate the target and three northwest neighbors.
        if (unpopulated(provider, x, z) || unpopulated(provider, x - 1, z)
                || unpopulated(provider, x, z - 1) || unpopulated(provider, x - 1, z - 1)) return false;
        if (!((AnvilChunkLoader) provider.currentChunkLoader).chunkExists(world, x, z)) return false;
        if (!existing) {
            if (watcher == null) watcher = call(WATCHER, manager, x, z, true);
            call(ADD, watcher, player);
            lastAheadAdmission = tick;
        }
        return true;
    }

    private static boolean unpopulated(ChunkProviderServer provider, int x, int z) {
        Chunk loaded = (Chunk) provider.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x, z));
        return loaded != null && !loaded.isTerrainPopulated;
    }

    public void ensure(int x, int z) {
        Object watcher = call(WATCHER, manager, x, z, queue == null);
        if (member(watcher)) return;
        if (queue == null) call(ADD, watcher, player);
        else queue.request(x, z);
    }

    private boolean member(Object watcher) {
        try {
            return watcher != null && ((List<?>) MEMBERS.get(watcher)).contains(player);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public void submit() {
        if (queue != null) queue.submit();
        Collections.sort(player.loadedChunks, new ChunkCoordComparator(player));
    }

    private static Method playerMethod(String mcp, String srg) {
        return ReflectionHelper.findMethod(INSTANCE, null, new String[] {mcp, srg}, EntityPlayerMP.class);
    }

    static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Calling " + method, e);
        }
    }
}
