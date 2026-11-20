package org.mtr.chunkloader;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import org.mtr.chunkloader.prefetch.ChunkPrefetcher;
import org.mtr.chunkloader.route.RoutePlans;
import org.mtr.chunkloader.watch.PassengerView;
import org.mtr.mapping.holder.World;
import org.mtr.mod.Init;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod(modid = "mtrtrainchunkloader", name = "MTR Train Chunk Loader", version = "0.1.0",
        acceptedMinecraftVersions = "[1.7.10]", dependencies = "required-after:mtr", acceptableRemoteVersions = "*")
public final class TrainChunkLoader {
    private final Map<WorldServer, WorldRoutes> worlds = new IdentityHashMap<>();

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world instanceof WorldServer) {
            WorldServer world = (WorldServer) event.world;
            worlds.computeIfAbsent(world, WorldRoutes::new);
        }
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.world instanceof WorldServer) {
            WorldRoutes routes = worlds.get(event.world);
            if (routes != null) routes.update((WorldServer) event.world);
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!(event.world instanceof WorldServer)) return;
        WorldRoutes routes = worlds.remove(event.world);
        if (routes != null) {
            PassengerView.clear((WorldServer) event.world);
            routes.close();
        }
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        worlds.values().forEach(WorldRoutes::close);
        worlds.clear();
        RoutePlans.clear();
        PassengerView.clear();
    }

    private static final class WorldRoutes {
        final String dimension;
        final ChunkPrefetcher prefetch;
        final Map<UUID, RoutePlans.Plan> riders = new HashMap<>();
        final Map<Long, List<Long>> occupied = new LinkedHashMap<>();
        final Map<Long, EntityPlayerMP> leadWatchers = new HashMap<>();

        WorldRoutes(WorldServer world) {
            dimension = Init.getWorldId(new World(world));
            prefetch = new ChunkPrefetcher(world);
            RoutePlans.worldLoaded(dimension);
        }

        void update(WorldServer world) {
            riders.clear();
            occupied.clear();
            leadWatchers.clear();
            for (RoutePlans.Plan plan : RoutePlans.current(dimension)) {
                for (UUID rider : plan.passengers) riders.put(rider, plan);
            }
            if (!riders.isEmpty()) {
                for (Object entry : world.playerEntities) {
                    if (!(entry instanceof EntityPlayerMP)) continue;
                    EntityPlayerMP player = (EntityPlayerMP) entry;
                    RoutePlans.Plan plan = player.isDead ? null : riders.get(player.getUniqueID());
                    if (plan != null) {
                        leadWatchers.merge(plan.id, player, (a, b) -> a.getUniqueID().compareTo(b.getUniqueID()) <= 0 ? a : b);
                    }
                }
            }
            leadWatchers.forEach((id, player) -> {
                List<Long> path = riders.get(player.getUniqueID()).chunks;
                occupied.put(id, PassengerView.aheadOfView(player, path));
            });
            prefetch.tick(occupied);
            // A single native watcher per train keeps the corridor loaded for every passenger.
            for (Object entry : world.playerEntities) {
                if (!(entry instanceof EntityPlayerMP)) continue;
                EntityPlayerMP player = (EntityPlayerMP) entry;
                UUID uuid = player.getUniqueID();
                RoutePlans.Plan plan = player.isDead ? null : riders.get(uuid);
                List<Long> ahead = plan != null && player == leadWatchers.get(plan.id)
                        ? prefetch.ready(plan.id) : Collections.emptyList();
                PassengerView.update(player, plan != null, ahead);
            }
        }

        void close() {
            RoutePlans.clear(dimension);
            prefetch.close();
        }
    }
}
