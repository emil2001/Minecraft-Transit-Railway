package org.mtr.chunkloader.watch;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedList;

import static org.junit.jupiter.api.Assertions.*;

class ForgeSubscriptionsTest {
    @TempDir Path save;

    @Test void rechecksLagAndResidencyBeforeCallingTheNativeWatcherConstructor() throws Exception {
        // Run the real PlayerManager/PlayerInstance and ChunkProviderServer fast path without starting a server.
        TestWorld world = allocate(TestWorld.class);
        world.server = allocate(DedicatedServer.class);
        world.server.func_152361_a(allocate(DedicatedPlayerList.class));
        set(MinecraftServer.class, world.server, "tickTimeArray", new long[100]);
        PlayerManager manager = new PlayerManager(world);
        // A stale region/pending-save record could make Forge report a chunk that its worker cannot load.
        AnvilChunkLoader loader = new AnvilChunkLoader(save.toFile()) {
            @Override public boolean chunkExists(World world, int x, int z) { return true; }
        };
        CountingProvider provider = new CountingProvider(world, loader);
        world.theChunkProviderServer = provider;
        EntityPlayerMP rider = allocate(EntityPlayerMP.class);
        set(EntityPlayerMP.class, rider, "loadedChunks", new LinkedList<>());
        ForgeSubscriptions watches = new ForgeSubscriptions(manager, rider);

        int x = 23, z = -7;
        long key = ChunkCoordIntPair.chunkXZ2Int(x, z);
        Chunk chunk = new Chunk(world, x, z);
        provider.loadedChunkHashMap.add(key, chunk);
        assertFalse(watches.watchResident(x, z), "An unfinished resident is ineligible");
        chunk.isTerrainPopulated = true;
        provider.loadedChunkHashMap.remove(key);
        assertFalse(watches.watchResident(x, z), "The chunk was evicted after candidate selection");
        provider.loadedChunkHashMap.add(key, chunk);
        world.server.tickTimeArray[99] = 50_000_000L;
        assertFalse(watches.watchResident(x, z), "Lag since candidate selection blocks a new subscription");
        assertFalse(manager.func_152621_a(x, z));
        assertEquals(0, provider.nativeLoads);

        world.server.tickTimeArray[99] = 0;
        assertTrue(watches.watchResident(x, z));
        assertEquals(1, provider.nativeLoads, "The constructor called Forge's resident fast path once");
        assertEquals(1, rider.loadedChunks.size(), "The Forge callback completed synchronously");
        world.server.tickTimeArray[99] = 50_000_000L;
        assertTrue(watches.watchResident(x, z), "An existing player membership survives a slow tick");
        assertEquals(1, provider.nativeLoads);
    }

    private static class CountingProvider extends ChunkProviderServer {
        int nativeLoads;

        CountingProvider(WorldServer world, AnvilChunkLoader loader) { super(world, loader, null); }

        @Override public Chunk loadChunk(int x, int z, Runnable callback) {
            assertTrue(chunkExists(x, z), "A speculative acquisition reached Forge's cold path");
            nativeLoads++;
            return super.loadChunk(x, z, callback);
        }

        @Override public Chunk originalLoadChunk(int x, int z) {
            throw new AssertionError("Speculative acquisition entered world generation");
        }
    }

    private static class TestWorld extends WorldServer {
        MinecraftServer server;
        private TestWorld() { super(null, null, null, 0, null, null); }
        @Override public MinecraftServer func_73046_m() { return server; }
        @Override public long getTotalWorldTime() { return 1; }
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // Forge's constructors start the server or initialize a dimension. Only the collaborators above are needed.
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
}
