package org.mtr.chunkloader.prefetch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkPrefetcherTest {
    // Hold disk jobs until the test advances the worker.
    final ThreadPoolExecutor disk = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4)) {
        @Override public void execute(Runnable task) { getQueue().add(task); }
    };
    final Map<Long, Collection<Long>> routes = new LinkedHashMap<>();
    final List<Long> reads = new ArrayList<>();
    boolean healthy = true;
    final Map<Long, Chunk> resident = new HashMap<>();
    final ChunkPrefetcher prefetch = new ChunkPrefetcher(resident::get, () -> healthy, reads::add, disk);

    private void loadReady(long key) {
        Chunk chunk = new Chunk(null, (int) key, (int) (key >> 32));
        chunk.isTerrainPopulated = true;
        chunk.isLightPopulated = true;
        chunk.field_150815_m = true;
        resident.put(key, chunk);
    }

    @Test void alternatesTrainsAndSkipsLoadedOrAlreadyWarmedSharedChunks() {
        loadReady(9L);
        routes.put(10L, Arrays.asList(9L, 1L, 2L));
        routes.put(20L, Arrays.asList(1L, 3L));
        for (int i = 0; i < 50; i++) {
            prefetch.tick(routes);
            Runnable job = disk.getQueue().poll();
            if (job != null) job.run();
        }
        assertEquals(Arrays.asList(1L, 3L, 2L), reads);
    }

    @Test void busyRoutesAheadOfAnEligibleTrainCannotStarveIt() {
        loadReady(9L);
        for (long train = 0; train < 4; train++) {
            routes.put(train, new ArrayList<>(Collections.nCopies(33, 9L)));
        }
        routes.get(3L).add(1L);
        for (int i = 0; i < 20; i++) {
            prefetch.tick(routes);
            Runnable job = disk.getQueue().poll();
            if (job != null) job.run();
        }
        assertEquals(Collections.singletonList(1L), reads);
    }

    @Test void admitsOnlyPopulatedResidentsAndSharesThemAcrossTrains() {
        routes.put(10L, Arrays.asList(8L, 1L, 2L, 3L, 4L, 5L, 6L, 7L));
        routes.put(20L, Collections.singleton(1L));
        for (int tick = 0; tick < 100; tick++) {
            prefetch.tick(routes);
            Runnable job = disk.getQueue().poll();
            if (job != null) job.run();
        }
        assertTrue(prefetch.ready(10).isEmpty(), "Even a completed file read cannot grant a subscription");
        for (long chunk = 1; chunk <= 7; chunk++) loadReady(chunk);
        for (int tick = 0; tick < 50; tick++) prefetch.tick(routes);
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L), prefetch.ready(10));
        assertEquals(Collections.singletonList(1L), prefetch.ready(20));
        healthy = false;
        routes.remove(10L);
        prefetch.tick(routes);
        assertTrue(prefetch.ready(10).isEmpty());
        assertEquals(Collections.singletonList(1L), prefetch.ready(20), "Existing demand survives a slow tick");
        resident.remove(1L);
        prefetch.tick(routes);
        assertTrue(prefetch.ready(20).isEmpty(), "Residency is rechecked even while shedding load");
    }

    @Test void onlyReadsColdChunksWhileWaitingForResidentSendReadiness() {
        Chunk unfinished = new Chunk(null, 1, 0);
        unfinished.isTerrainPopulated = true;
        unfinished.isLightPopulated = true;
        resident.put(1L, unfinished);
        routes.put(10L, Arrays.asList(1L, 2L));
        prefetch.tick(routes);
        disk.getQueue().remove().run();
        assertEquals(Collections.singletonList(2L), reads);
        assertTrue(prefetch.ready(10).isEmpty());

        unfinished.field_150815_m = true;
        unfinished.isLightPopulated = false;
        prefetch.tick(routes);
        prefetch.tick(routes);
        assertTrue(prefetch.ready(10).isEmpty(), "Forge still requires lighting after the first tick");

        unfinished.isLightPopulated = true;
        prefetch.tick(routes);
        prefetch.tick(routes);
        assertEquals(Collections.singletonList(1L), prefetch.ready(10));
        assertTrue(disk.getQueue().isEmpty());
    }

    @Test void releasesUnreadySlotsAndSkipsUnpopulatedTerrainWithRelaxedNativeReadiness() {
        // Hodgepodge's SendWithoutPopulation mixin changes the native predicate to the first-tick flag.
        Chunk terrainOnly = new Chunk(null, 0, 0) {
            @Override public boolean func_150802_k() { return field_150815_m; }
        };
        terrainOnly.field_150815_m = true;
        resident.put(0L, terrainOnly);
        for (long key = 1; key <= 7; key++) loadReady(key);
        routes.put(10L, Arrays.asList(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L));
        for (int tick = 0; tick < 20; tick++) prefetch.tick(routes);
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L), prefetch.ready(10));

        Chunk reloaded = new Chunk(null, 1, 0);
        reloaded.isTerrainPopulated = true;
        reloaded.isLightPopulated = true;
        resident.put(1L, reloaded);
        healthy = false;
        prefetch.tick(routes);
        assertEquals(Arrays.asList(2L, 3L, 4L, 5L, 6L), prefetch.ready(10));
        healthy = true;
        prefetch.tick(routes);
        assertEquals(Arrays.asList(2L, 3L, 4L, 5L, 6L, 7L), prefetch.ready(10));

        terrainOnly.isTerrainPopulated = true;
        routes.put(10L, Collections.singletonList(0L));
        prefetch.tick(routes);
        prefetch.tick(routes);
        assertEquals(Collections.singletonList(0L), prefetch.ready(10), "Use the pack's send predicate after population");
        assertTrue(disk.getQueue().isEmpty());
    }

    @Test void maintainsResidentWatchesWhenTheChunkLoaderHasNoFileReader() {
        ChunkPrefetcher noReader = new ChunkPrefetcher(resident::get, () -> healthy, null, disk);
        loadReady(2L);
        routes.put(10L, Arrays.asList(1L, 2L));
        noReader.tick(routes);
        assertEquals(Collections.singletonList(2L), noReader.ready(10));
        assertTrue(disk.getQueue().isEmpty());

        resident.remove(2L);
        noReader.tick(routes);
        assertTrue(noReader.ready(10).isEmpty());
    }

    @Test void aChunkLoadedWhileItsReadIsQueuedReleasesTheWorkerForTheNextColdChunk() {
        routes.put(10L, Arrays.asList(7L, 8L));
        prefetch.tick(routes);
        Runnable queued = disk.getQueue().element();
        resident.put(7L, new Chunk(null, 7, 0));
        prefetch.tick(routes);
        assertTrue(disk.getQueue().isEmpty());
        queued.run();
        prefetch.tick(routes);
        disk.getQueue().remove().run();
        assertEquals(Collections.singletonList(8L), reads);
    }

    @Test void boundsWorldAdmissionsAndCleansUpRoutesDuringLag() {
        for (long train = 100; train < 150; train++) {
            routes.put(train, Collections.singleton(train));
            loadReady(train);
        }
        int previous = 0;
        for (int i = 0; i < 150; i++) {
            prefetch.tick(routes);
            int count = 0;
            for (long train : routes.keySet()) count += prefetch.ready(train).size();
            assertTrue(count <= previous + 1);
            previous = count;
        }
        assertEquals(32, previous);
        assertTrue(disk.getQueue().isEmpty());
        healthy = false;
        routes.clear();
        prefetch.tick(routes);
        for (long train = 100; train < 150; train++) assertTrue(prefetch.ready(train).isEmpty());
    }

    @Test void lateCompletionCannotAdmitARemovedRouteOrAnUnrelatedCoordinate() {
        routes.put(10L, Collections.singleton(1L));
        prefetch.tick(routes);
        disk.getQueue().remove().run();
        routes.put(10L, Collections.singleton(2L));
        prefetch.tick(routes);
        assertTrue(prefetch.ready(10).isEmpty());
    }

    @Test void releasesQueuedWorkOnLastExitAndKeepsSharedDemand() {
        routes.put(10L, Collections.singleton(1L));
        routes.put(20L, Collections.singleton(1L));
        prefetch.tick(routes);
        routes.remove(10L);
        prefetch.tick(routes);
        assertEquals(1, disk.getQueue().size());
        routes.clear();
        prefetch.tick(routes);
        assertTrue(disk.getQueue().isEmpty());
        assertTrue(reads.isEmpty());
    }

    @Test void shedsLoadAndClosesBeforeAQueuedReadCanStart() {
        routes.put(10L, Arrays.asList(1L, 2L));
        healthy = false;
        for (int i = 0; i < 20; i++) prefetch.tick(routes);
        assertTrue(disk.getQueue().isEmpty());
        healthy = true;
        for (int i = 0; i < 20; i++) prefetch.tick(routes);
        assertEquals(1, disk.getQueue().size());
        Runnable racing = disk.getQueue().element();
        prefetch.close();
        racing.run();
        prefetch.tick(routes);
        assertTrue(reads.isEmpty());
        assertTrue(disk.getQueue().isEmpty());
    }
}
