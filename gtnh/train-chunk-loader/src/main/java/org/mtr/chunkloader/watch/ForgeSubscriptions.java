package org.mtr.chunkloader.watch;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ChunkCoordComparator;
import org.mtr.chunkloader.prefetch.ChunkPrefetcher;
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

    public boolean watchResident(int x, int z) {
        WorldServer world = manager.getWorldServer();
        Object watcher = call(WATCHER, manager, x, z, false);
        // Executed on the server thread. PlayerInstance's constructor and addPlayer can both
        // call Forge loadChunk. With this exact map entry present, Forge runs their callbacks
        // inline without scheduling disk IO, entering originalLoadChunk or populating neighbors.
        if (!ChunkPrefetcher.isSendReadyResident(world.theChunkProviderServer, ChunkCoordIntPair.chunkXZ2Int(x, z))) return false;
        if (member(watcher)) return true;
        long tick = world.getTotalWorldTime();
        if (lastAheadAdmission == tick || !ChunkPrefetcher.serverHasHeadroom(world.func_73046_m())) return false;
        if (watcher == null) watcher = call(WATCHER, manager, x, z, true);
        call(ADD, watcher, player);
        lastAheadAdmission = tick;
        return true;
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
