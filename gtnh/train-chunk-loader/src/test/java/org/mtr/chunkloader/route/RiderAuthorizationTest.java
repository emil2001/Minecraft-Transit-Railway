package org.mtr.chunkloader.route;

import com.google.gson.internal.UnsafeAllocator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.gtnh.RidingPermissions;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.tool.PlayerPhysics;
import org.mtr.mod.Init;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mtr.chunkloader.route.RoutePlansTest.*;

class RiderAuthorizationTest {
    // Forge's WorldServer and EntityPlayerMP constructors need a running server. Use their native
    // identity, connection and world-clock methods while supplying only the state they read.
    private static final UnsafeAllocator ALLOCATOR = UnsafeAllocator.create();

    @Test void planRequiresTheCurrentNativePassengerAndExactPortSelection(@TempDir Path files) throws Exception {
        Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, files, false);
        RoutePlans.simulatorOpened(simulator);
        observe(train(simulator, TransportMode.TRAIN, 0), simulator, 0.08);
        RoutePlans.Plan plan = RoutePlans.current(DIMENSION).get(0);
        WorldServer world = world();
        EntityPlayerMP passenger = player(world, 10);
        ServerPlayerEntity holder = new ServerPlayerEntity(passenger);
        try {
            select(passenger, simulator, 81, 77);
            assertTrue(plan.authorizes(passenger, world));
            EntityPlayerMP respawned = player(world, 10);
            assertEquals(passenger, respawned, "Minecraft reuses the entity ID on respawn");
            assertFalse(plan.authorizes(respawned, world));
            passenger.playerNetServerHandler.playerEntity = respawned;
            assertFalse(plan.authorizes(passenger, world), "Respawn transfers the old connection");
            passenger.playerNetServerHandler.playerEntity = passenger;
            assertFalse(plan.authorizes(player(world, 11), world), "Reconnect with the same UUID");
            WorldServer other = world();
            passenger.worldObj = other;
            assertFalse(plan.authorizes(passenger, other), "Even worlds with the same dimension ID are distinct");
            passenger.worldObj = world;
            select(passenger, simulator, 81, 78);
            assertFalse(plan.authorizes(passenger, world));
            select(passenger, simulator, 82, 77);
            assertFalse(plan.authorizes(passenger, world));
            select(passenger, new Simulator(DIMENSION, new String[]{DIMENSION}, files, false), 81, 77);
            assertFalse(plan.authorizes(passenger, world), "IDs may be reused after a simulator restart");
            select(passenger, simulator, 81, 77);
            world.getWorldInfo().incrementTotalWorldTime(40);
            assertFalse(plan.authorizes(passenger, world), "Expired native authority");
            select(passenger, simulator, 81, 77);
            PlayerPhysics.revoke(passenger);
            assertFalse(plan.authorizes(passenger, world));
            select(passenger, simulator, 81, 77);
            RidingPermissions.revoke(holder);
            assertFalse(plan.authorizes(passenger, world), "The sampled Core plan still mentions the UUID");
        } finally {
            ((Map<?, ?>) field(Init.class, "RIDING_PLAYERS").get(null)).remove(RIDER);
            RidingPermissions.revoke(holder);
            RoutePlans.clear();
        }
    }

    private static WorldServer world() throws Exception {
        WorldServer world = ALLOCATOR.newInstance(WorldServer.class);
        field(World.class, "provider").set(world, new WorldProviderSurface());
        field(World.class, "worldInfo").set(world, ALLOCATOR.newInstance(WorldInfo.class));
        return world;
    }

    private static EntityPlayerMP player(WorldServer world, int entityId) throws Exception {
        EntityPlayerMP player = ALLOCATOR.newInstance(EntityPlayerMP.class);
        player.worldObj = world;
        player.setEntityId(entityId);
        field(Entity.class, "entityUniqueID").set(player, RIDER);
        new NetHandlerPlayServer(null, new NetworkManager(false) {
            @Override public boolean isChannelOpen() { return true; }
        }, player);
        return player;
    }

    @SuppressWarnings("unchecked")
    private static void select(EntityPlayerMP player, Simulator simulator, long siding, long id) throws Exception {
        // Supply the port's post-validation selection. The production adapter must read the actual maps.
        Class<?> selectionType = Class.forName("org.mtr.gtnh.RidingPermissions$Selection");
        Object selection = ALLOCATOR.newInstance(selectionType);
        Field sourceField = field(selectionType, "source");
        Object source = ALLOCATOR.newInstance(sourceField.getType());
        field(source.getClass(), "simulator").set(source, simulator);
        sourceField.set(selection, source);
        field(selectionType, "world").set(selection, player.worldObj);
        field(selectionType, "siding").setLong(selection, siding);
        field(selectionType, "id").setLong(selection, id);
        ((Map<EntityPlayerMP, Object>) field(RidingPermissions.class, "RIDERS").get(null)).put(player, selection);
        PlayerPhysics.authorize(player, Collections.singletonList(AxisAlignedBB.getBoundingBox(0, 0, 0, 1, 1, 1)),
                player.worldObj.getTotalWorldTime() + 40);
        Init.updateRidingEntity(new ServerPlayerEntity(player), false);
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
