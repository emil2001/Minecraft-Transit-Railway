package org.mtr.chunkloader.watch;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Passenger policy shared by the server tick, watcher, scheduler and active-chunk hooks. */
public final class PassengerView {
    /** Applied only to classes for which the transformer found all expected call sites. */
    public interface Installed {}

    private static final Map<EntityPlayerMP, PassengerView> ACTIVE = new IdentityHashMap<>();

    private final PlayerManager manager;
    private final WatchWindow window;
    private final ForgeSubscriptions watches;
    private boolean fastTrain;
    private Collection<Long> route = Collections.emptyList();

    private PassengerView(PlayerManager manager, EntityPlayerMP player, int global) {
        this.manager = manager;
        // Forge casts and Hodgepodge floors negative managed coordinates. Cover
        // both old squares on entry, including the one-chunk rounding boundary.
        window = new WatchWindow(chunk(player.managedPosX), chunk(player.managedPosZ), global + 1);
        watches = new ForgeSubscriptions(manager, player);
    }

    private static boolean supported() {
        return Installed.class.isAssignableFrom(World.class) && Installed.class.isAssignableFrom(PlayerManager.class)
                && Installed.class.isAssignableFrom(ServerConfigurationManager.class) && HodgepodgeQueue.SUPPORTED;
    }

    public static void excludePlayerWindow(EntityPlayerMP player, List<Long> candidates) {
        if (candidates.isEmpty()) return;
        if (!supported()) {
            candidates.clear();
            return;
        }
        int radius = Math.min(WatchWindow.TRAIN_RADIUS, ForgeSubscriptions.radius(player.getServerForPlayer().getPlayerManager()));
        WatchWindow.excludeSquare(candidates, chunk(player.posX), chunk(player.posZ), radius);
    }

    public static void update(EntityPlayerMP player, boolean fastTrain, Collection<Long> ahead) {
        PassengerView view = ACTIVE.get(player);
        if (view == null && (!fastTrain || !supported())) return;
        PlayerManager manager = player.getServerForPlayer().getPlayerManager();
        if (view != null && view.manager != manager) {
            forget(view.manager, player);
            view = null;
        }
        int global = ForgeSubscriptions.radius(manager);
        if (view == null) {
            if (!fastTrain) return;
            view = new PassengerView(manager, player, global);
            ACTIVE.put(player, view);
        }
        view.fastTrain = fastTrain;
        view.route = fastTrain ? ahead : Collections.emptyList();
        view.window.tick(chunk(player.posX), chunk(player.posZ), global, fastTrain, view.route, view.watches);
        syncCenter(player);
        view.finishRestoration(player, global);
    }

    /** The ordinary packet-driven movement path, including other mods' overwrites. */
    public static void move(PlayerManager manager, EntityPlayerMP player) {
        PassengerView view = ACTIVE.get(player);
        if (view == null || view.manager != manager) {
            manager.updatePlayerPertinentChunks(player);
            return;
        }
        view.window.resize(chunk(player.posX), chunk(player.posZ), view.window.radius(), view.route, view.watches);
        syncCenter(player);
    }

    /** The global setter still updates the manager field and visits all ordinary players. */
    public static Iterator<EntityPlayerMP> resizePlayers(List<EntityPlayerMP> players, PlayerManager manager, int global) {
        if (ACTIVE.isEmpty()) return players.iterator();
        List<EntityPlayerMP> ordinary = new ArrayList<>(players.size());
        for (EntityPlayerMP player : players) {
            PassengerView view = ACTIVE.get(player);
            if (view == null || view.manager != manager) {
                ordinary.add(player);
            } else {
                view.window.resize(chunk(player.posX), chunk(player.posZ), Math.min(global, view.window.radius()),
                        view.route, view.watches);
                syncCenter(player);
                view.finishRestoration(player, global);
            }
        }
        return ordinary.iterator();
    }

    private void finishRestoration(EntityPlayerMP player, int global) {
        // Even at the global radius, a fast passenger still owns the scheduler's fill/resend policy.
        if (!fastTrain && window.radius() == global && !window.hasExtra()) ACTIVE.remove(player);
    }

    /** The world's own radius may already be capped by a simulation-distance mod. */
    public static int activeChunkRadius(int nativeRadius, EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) return nativeRadius;
        PassengerView view = ACTIVE.get(player);
        return view == null || view.manager.getWorldServer() != player.worldObj ? nativeRadius
                : Math.min(nativeRadius, view.window.radius());
    }

    /** Called for each owner's LongSet before Hodgepodge computes its shared generation work. */
    public static Object generationRequests(Map.Entry<?, ?> owner) {
        Object requests = owner.getValue();
        PassengerView view = ACTIVE.get(owner.getKey());
        if (view != null) {
            @SuppressWarnings("unchecked") Set<Long> chunks = (Set<Long>) requests;
            view.window.pruneGeneration(chunks);
        }
        return requests;
    }

    public static boolean allowFill(Object player, int x, int z) {
        PassengerView view = ACTIVE.get(player);
        return view == null || view.window.allowsFill(x, z,
                view.manager.getWorldServer().theChunkProviderServer.loadedChunkHashMap::containsItem);
    }

    /** Used both for the actual send and the scheduler's decision to retry a populated chunk. */
    public static boolean allowResend(Object player, int x, int z) {
        PassengerView view = ACTIVE.get(player);
        return view == null || view.window.allowsResend(x, z,
                () -> view.manager.isPlayerWatchingChunk((EntityPlayerMP) player, x, z));
    }

    public static void forget(PlayerManager manager, EntityPlayerMP player) {
        PassengerView view = ACTIVE.get(player);
        if (view != null && view.manager == manager) {
            ACTIVE.remove(player);
            view.release();
        }
        // Forge removes its native square watchers in the rest of removePlayer.
    }

    public static void clear(WorldServer world) {
        Iterator<PassengerView> entries = ACTIVE.values().iterator();
        while (entries.hasNext()) {
            PassengerView view = entries.next();
            if (view.manager.getWorldServer() == world) {
                entries.remove();
                view.release();
            }
        }
    }

    public static void clear() {
        for (PassengerView view : ACTIVE.values()) view.release();
        ACTIVE.clear();
    }

    private void release() {
        watches.resetPending();
        window.removeExtra(watches);
    }

    private static int chunk(double position) { return MathHelper.floor_double(position) >> 4; }

    private static void syncCenter(EntityPlayerMP player) {
        // Native removePlayer casts before shifting. At negative fractional chunk
        // boundaries, flooring makes both native and Hodgepodge see our center.
        player.managedPosX = Math.floor(player.posX);
        player.managedPosZ = Math.floor(player.posZ);
    }
}
