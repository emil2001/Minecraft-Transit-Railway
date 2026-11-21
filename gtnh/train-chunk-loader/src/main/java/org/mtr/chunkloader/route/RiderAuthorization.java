package org.mtr.chunkloader.route;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.LogManager;
import org.mtr.core.simulation.Simulator;
import org.mtr.gtnh.RidingPermissions;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.tool.PlayerPhysics;
import org.mtr.mod.Init;

import java.lang.reflect.Field;
import java.util.Map;

/** Reads the GTNH port's current ride on the native server thread. The .22 port has no public selection accessor. */
final class RiderAuthorization {
    private static RiderAuthorization access = resolve();
    private final Map<?, ?> ridingPlayers, selections;
    private final Field world, siding, vehicle, source, simulator;

    private RiderAuthorization() throws ReflectiveOperationException {
        ridingPlayers = (Map<?, ?>) field(Init.class, "RIDING_PLAYERS").get(null);
        selections = (Map<?, ?>) field(RidingPermissions.class, "RIDERS").get(null);
        Class<?> selection = Class.forName(RidingPermissions.class.getName() + "$Selection", false,
                RidingPermissions.class.getClassLoader());
        world = field(selection, "world");
        siding = field(selection, "siding");
        vehicle = field(selection, "id");
        source = field(selection, "source");
        simulator = field(source.getType(), "simulator");
    }

    static boolean matches(EntityPlayerMP player, WorldServer world, Simulator simulator, long siding, long vehicle) {
        if (access == null || siding == 0 || player.isDead || player.worldObj != world || player.dimension != world.provider.dimensionId
                || player.playerNetServerHandler == null || player.playerNetServerHandler.playerEntity != player
                || !player.playerNetServerHandler.netManager.isChannelOpen()) return false;
        synchronized (RidingPermissions.class) {
            try {
                // Entity.equals compares entity IDs, and Minecraft reuses the old ID on respawn.
                // The packet registers the authenticated native object here after RidingPermissions.validate.
                Object registered = access.ridingPlayers.get(player.getUniqueID());
                if (!(registered instanceof ServerPlayerEntity) || ((ServerPlayerEntity) registered).data != player) return false;
                Object selected = access.selections.get(player);
                return selected != null && access.world.get(selected) == world
                        && access.siding.getLong(selected) == siding && access.vehicle.getLong(selected) == vehicle
                        && access.simulator.get(access.source.get(selected)) == simulator && PlayerPhysics.authorized(player);
            } catch (IllegalAccessException | IllegalArgumentException | LinkageError incompatible) {
                warn(incompatible);
                access = null;
                return false;
            }
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static RiderAuthorization resolve() {
        try {
            return new RiderAuthorization();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            warn(incompatible);
            return null;
        }
    }

    private static void warn(Throwable cause) {
        LogManager.getLogger("MTR Train Chunk Loader").warn(
                "Train route loading disabled: cannot read the server's current MTR riding selection", cause);
    }
}
