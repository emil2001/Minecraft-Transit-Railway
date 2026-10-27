package org.mtr.gtnh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DashboardMapCacheTest {
    @TempDir Path directory;
    private final List<DashboardMapCache> caches = new ArrayList<>();

    @AfterEach
    void closeCaches() throws InterruptedException {
        for (DashboardMapCache cache : caches) cache.close();
        for (DashboardMapCache cache : caches) assertTrue(cache.awaitClosed(20, TimeUnit.SECONDS));
    }

    @Test
    void editedChunkSurvivesFreshCacheAndUnknownPixelsStayTransparent() throws Exception {
        DashboardMapCache cache = cache(directory);
        assertTrue(cache.updateChunk(1, 2, chunk(0x80765432)));
        idle(cache);
        cache.close();
        assertTrue(cache.awaitClosed(20, TimeUnit.SECONDS));

        DashboardMapCache reopened = cache(directory);
        assertNull(reopened.get(0, 0));
        idle(reopened);
        DashboardMapCache.Region region = reopened.get(0, 0);
        assertNotNull(region);
        assertEquals(0, region.regionX);
        assertEquals(0, region.regionZ);
        assertEquals(0x80765432, region.copyPixels()[8208]);
        assertEquals(0x80765432, region.copyPixels()[12063]);
        assertEquals(0, region.copyPixels()[0]);
        assertEquals(0, region.copyPixels()[65535]);
    }

    @Test
    void chunkEditBeforeLoadingPreservesOtherPixelsFromDisk() throws Exception {
        int[] saved = new int[65536];
        saved[0] = 0xFF112233;
        saved[65535] = 0xFF445566;
        new DashboardMapStore(directory).write(0, 0, saved);
        DashboardMapCache cache = cache(directory);

        assertTrue(cache.updateChunk(1, 2, chunk(0xFF778899)));
        idle(cache);

        int[] pixels = cache.get(0, 0).copyPixels();
        assertEquals(0xFF112233, pixels[0]);
        assertEquals(0xFF445566, pixels[65535]);
        assertEquals(0xFF778899, pixels[8208]);
        assertArrayEquals(pixels, new DashboardMapStore(directory).read(0, 0));
    }

    @Test
    void negativeChunkCoordinatesUseFloorDivisionAndLastChunkOfRegion() throws Exception {
        DashboardMapCache cache = cache(directory);
        assertTrue(cache.updateChunk(-1, -17, chunk(0xFF123456)));
        idle(cache);

        DashboardMapCache.Region region = cache.get(-1, -2);
        assertNotNull(region);
        assertEquals(-1, region.regionX);
        assertEquals(-2, region.regionZ);
        assertEquals(0xFF123456, region.copyPixels()[61680]);
        assertEquals(0xFF123456, region.copyPixels()[65535]);
        assertEquals(0, region.copyPixels()[0]);
        assertNull(new DashboardMapStore(directory).read(0, -1));
    }

    @Test
    void inputAndReturnedPixelsCannotMutatePublishedSnapshots() throws Exception {
        DashboardMapCache cache = cache(directory);
        int[] input = chunk(0xFF123456);
        assertTrue(cache.updateChunk(0, 0, input));
        Arrays.fill(input, 0);
        idle(cache);
        DashboardMapCache.Region before = cache.get(0, 0);
        int[] exposed = before.copyPixels();
        exposed[0] = 0;
        assertEquals(0xFF123456, before.copyPixels()[0]);

        assertTrue(cache.updateChunk(0, 0, chunk(0xFFABCDEF)));
        idle(cache);
        DashboardMapCache.Region after = cache.get(0, 0);
        assertTrue(after.revision > before.revision);
        assertEquals(0xFF123456, before.copyPixels()[0]);
        assertEquals(0xFFABCDEF, after.copyPixels()[0]);
    }

    @Test
    void unchangedChunkDoesNotRewriteFileOrAdvanceRevision() throws Exception {
        DashboardMapCache cache = cache(directory);
        assertTrue(cache.updateChunk(0, 0, chunk(0xFF123456)));
        idle(cache);
        long revision = cache.get(0, 0).revision;
        Path file = directory.resolve(DashboardMapStore.fileName(0, 0));
        FileTime originalTime = FileTime.fromMillis(1000);
        Files.setLastModifiedTime(file, originalTime);

        assertTrue(cache.updateChunk(0, 0, chunk(0xFF123456)));
        idle(cache);

        assertEquals(revision, cache.get(0, 0).revision);
        assertEquals(originalTime, Files.getLastModifiedTime(file));
    }

    @Test
    void corruptRegionBecomesAMissAndCanBeReplacedByLiveChunk() throws Exception {
        Files.write(directory.resolve(DashboardMapStore.fileName(0, 0)), new byte[] {1, 2, 3});
        DashboardMapCache cache = cache(directory);
        assertNull(cache.get(0, 0));
        idle(cache);
        assertNull(cache.get(0, 0));

        assertTrue(cache.updateChunk(0, 0, chunk(0xFF123456)));
        idle(cache);

        int[] pixels = new DashboardMapStore(directory).read(0, 0);
        assertNotNull(pixels);
        assertEquals(0xFF123456, pixels[0]);
        assertEquals(0, pixels[65535]);
    }

    @Test
    void ioFailureKeepsLivePixelsAndWorkerAcceptsLaterEdits() throws Exception {
        Path regularFile = directory.resolve("file");
        Files.write(regularFile, new byte[] {1});
        DashboardMapCache cache = cache(regularFile);
        assertTrue(cache.updateChunk(0, 0, chunk(0xFF123456)));
        idle(cache);
        assertEquals(0xFF123456, cache.get(0, 0).copyPixels()[0]);

        assertTrue(cache.updateChunk(1, 0, chunk(0xFFABCDEF)));
        idle(cache);
        assertEquals(0xFF123456, cache.get(0, 0).copyPixels()[0]);
        assertEquals(0xFFABCDEF, cache.get(0, 0).copyPixels()[16]);
    }

    @Test
    void failedReadNeverOverwritesUnloadedPixelsAndRetryIncludesAcceptedEdits() throws Exception {
        int[] saved = new int[65536];
        saved[0] = 0xFF112233;
        new DashboardMapStore(directory).write(0, 0, saved);
        DashboardMapCache cache = cache(new DashboardMapStore(directory) {
            boolean fail = true;

            @Override
            public int[] read(int x, int z) throws IOException {
                if (fail) {
                    fail = false;
                    throw new IOException("Temporary read failure");
                }
                return super.read(x, z);
            }
        });
        assertTrue(cache.updateChunk(1, 0, chunk(0xFF445566)));
        idle(cache);
        assertEquals(0xFF445566, cache.get(0, 0).copyPixels()[16]);
        assertEquals(0xFF112233, new DashboardMapStore(directory).read(0, 0)[0]);

        assertTrue(cache.updateChunk(2, 0, chunk(0xFF778899)));
        idle(cache);

        int[] persisted = new DashboardMapStore(directory).read(0, 0);
        assertEquals(0xFF112233, persisted[0]);
        assertEquals(0xFF445566, persisted[16]);
        assertEquals(0xFF778899, persisted[32]);
    }

    @Test
    void getAndEditsReturnWhileDiskReadIsBlockedAndLatestEditWins() throws Exception {
        int[] saved = new int[65536];
        saved[65535] = 0xFF778899;
        new DashboardMapStore(directory).write(0, 0, saved);
        BlockingStore store = new BlockingStore(directory);
        DashboardMapCache cache = cache(store);
        try {
            assertNull(cache.get(0, 0));
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            assertTrue(cache.isPending(0, 0));
            assertNotEquals(Thread.currentThread(), store.reader);
            assertTrue(store.reader.isDaemon());
            int[] input = chunk(0xFF123456);
            assertTrue(cache.updateChunk(0, 0, input));
            Arrays.fill(input, 0);
            assertTrue(cache.updateChunk(0, 0, chunk(0xFFABCDEF)));
            assertNull(cache.get(0, 0));
        } finally {
            store.release.countDown();
        }
        idle(cache);
        assertFalse(cache.isPending(0, 0));

        int[] pixels = cache.get(0, 0).copyPixels();
        assertEquals(0xFFABCDEF, pixels[0]);
        assertEquals(0xFF778899, pixels[65535]);
        assertEquals(1, store.reads.get());
        assertEquals(1, store.writes.get());
    }

    @Test
    void pendingRegionsAreBoundedButAcceptedEditsFlushOnClose() throws Exception {
        BlockingStore store = new BlockingStore(directory);
        DashboardMapCache cache = cache(store);
        try {
            assertNull(cache.get(0, 0));
            assertTrue(store.entered.await(5, TimeUnit.SECONDS));
            for (int region = 1; region < 256; region++) {
                assertTrue(cache.updateChunk(region * 16, 0, chunk(0xFF000000 | region)));
            }
            assertFalse(cache.updateChunk(4096, 0, chunk(0xFF123456)));
            assertTrue(cache.updateChunk(0, 0, chunk(0xFFABCDEF)));
            cache.close();
            assertFalse(cache.updateChunk(0, 0, chunk(0xFF123456)));
            assertFalse(cache.awaitClosed(1, TimeUnit.MILLISECONDS));
        } finally {
            store.release.countDown();
        }
        assertTrue(cache.awaitClosed(20, TimeUnit.SECONDS));
        assertEquals(256, store.writes.get());
        assertEquals(0xFFABCDEF, new DashboardMapStore(directory).read(0, 0)[0]);
        assertEquals(0xFF0000FF, new DashboardMapStore(directory).read(255, 0)[0]);
        assertNull(new DashboardMapStore(directory).read(256, 0));
    }

    @Test
    void reopeningSameNormalizedDirectoryWaitsForPreviousAcceptedEdits() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        DashboardMapCache previous = cache(new DashboardMapStore(directory) {
            @Override
            public void write(int x, int z, int[] pixels) throws IOException {
                writing.countDown();
                try {
                    if (!releaseWrite.await(20, TimeUnit.SECONDS)) throw new IOException("Write was not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                super.write(x, z, pixels);
            }
        });
        DashboardMapCache reopened;
        try {
            assertTrue(previous.updateChunk(0, 0, chunk(0xFF112233)));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            assertTrue(previous.updateChunk(2, 0, chunk(0xFF778899)));
            previous.close();
            reopened = cache(directory.resolve("."));
            assertTrue(reopened.updateChunk(1, 0, chunk(0xFF445566)));
            assertFalse(reopened.awaitIdle(250, TimeUnit.MILLISECONDS), "New cache wrote before the old flush finished");
        } finally {
            releaseWrite.countDown();
        }
        assertTrue(previous.awaitClosed(20, TimeUnit.SECONDS));
        idle(reopened);

        int[] persisted = new DashboardMapStore(directory).read(0, 0);
        assertEquals(0xFF112233, persisted[0]);
        assertEquals(0xFF445566, persisted[16]);
        assertEquals(0xFF778899, persisted[32]);
    }

    @Test
    void residentRegionsEvictLeastRecentlyUsedAfter128Entries() throws Exception {
        CountingStore store = new CountingStore(directory);
        store.present = true;
        DashboardMapCache cache = cache(store);
        for (int region = 0; region < 128; region++) {
            assertNull(cache.get(region, 0));
            idle(cache);
        }
        assertNotNull(cache.get(0, 0));
        assertNull(cache.get(128, 0));
        idle(cache);
        assertNotNull(cache.get(0, 0));
        assertNull(cache.get(1, 0));
        idle(cache);
        assertNotNull(cache.get(1, 0));
    }

    @Test
    void missingRegionsAreRememberedWithoutGrowingUnbounded() throws Exception {
        CountingStore store = new CountingStore(directory);
        DashboardMapCache cache = cache(store);
        assertNull(cache.get(0, 0));
        idle(cache);
        for (int i = 0; i < 10; i++) assertNull(cache.get(0, 0));
        idle(cache);
        assertEquals(1, store.reads.get());

        for (int region = 1; region <= 1024; region++) {
            assertNull(cache.get(region, 0));
            idle(cache);
        }
        int readsBefore = store.reads.get();
        assertNull(cache.get(0, 0));
        idle(cache);
        assertEquals(readsBefore + 1, store.reads.get());
    }

    @Test
    void invalidChunkSizesAreRejectedBeforeQueuing() {
        DashboardMapCache cache = cache(directory);
        assertThrows(IllegalArgumentException.class, () -> cache.updateChunk(0, 0, new int[255]));
        assertThrows(IllegalArgumentException.class, () -> cache.updateChunk(0, 0, new int[257]));
    }

    private DashboardMapCache cache(Path path) {
        DashboardMapCache cache = new DashboardMapCache(path);
        caches.add(cache);
        return cache;
    }

    private DashboardMapCache cache(DashboardMapStore store) {
        DashboardMapCache cache = new DashboardMapCache(store);
        caches.add(cache);
        return cache;
    }

    private static void idle(DashboardMapCache cache) throws InterruptedException {
        assertTrue(cache.awaitIdle(20, TimeUnit.SECONDS), "Map worker did not become idle");
    }

    private static int[] chunk(int color) {
        int[] pixels = new int[256];
        Arrays.fill(pixels, color);
        return pixels;
    }

    private static class CountingStore extends DashboardMapStore {
        final Path directory;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        volatile boolean present;

        CountingStore(Path directory) {
            super(directory);
            this.directory = directory;
        }

        @Override
        public int[] read(int x, int z) throws IOException {
            reads.incrementAndGet();
            return present ? new int[65536] : null;
        }

        @Override
        public void write(int x, int z, int[] pixels) throws IOException {
            writes.incrementAndGet();
            super.write(x, z, pixels);
        }
    }

    private static class BlockingStore extends CountingStore {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread reader;

        BlockingStore(Path directory) { super(directory); }

        @Override
        public int[] read(int x, int z) throws IOException {
            reads.incrementAndGet();
            reader = Thread.currentThread();
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Read was not released");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            return new DashboardMapStore(directory).read(x, z);
        }
    }
}
