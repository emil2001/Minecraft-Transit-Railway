package org.mtr.gtnh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded CPU snapshots; all region reads, merges and writes run on one worker. */
public final class DashboardMapCache implements AutoCloseable {
    private static final int MAX_REGIONS = 128;
    private static final int MAX_PENDING = 256;
    private static final int MAX_MISSING = 256;
    private static final int CHUNK_PIXELS = 16;
    private static final Map<Path, DashboardMapCache> OWNERS = new HashMap<>();

    private final DashboardMapStore store;
    private final Path directory;
    private final LinkedHashMap<Long, Region> regions = new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<Long, Boolean> missing = new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<Long, Work> pending = new LinkedHashMap<>();
    private final Thread shutdownHook;
    private DashboardMapCache predecessor;
    private long revision;
    private int ioFailureCount;
    private boolean closing;
    private boolean closed;

    public DashboardMapCache(Path directory) {
        this(new DashboardMapStore(directory));
    }

    DashboardMapCache(DashboardMapStore store) {
        this.store = store;
        directory = store.directory().toAbsolutePath().normalize();
        synchronized (OWNERS) {
            predecessor = OWNERS.put(directory, this);
        }
        if (predecessor != null) predecessor.close();
        shutdownHook = new Thread(() -> {
            close();
            try {
                awaitClosed(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, "MTR dashboard map flush");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException | SecurityException ignored) {
            // Explicit close still flushes when the host disallows shutdown hooks.
        }
        Thread worker = new Thread(this::run, "MTR dashboard map cache");
        worker.setDaemon(true);
        worker.start();
    }

    /** Returns the current immutable snapshot, or requests an asynchronous load. */
    public synchronized Region get(int regionX, int regionZ) {
        long key = key(regionX, regionZ);
        Region region = regions.get(key);
        if (region != null || closing || missing.get(key) != null) return region;
        if (!pending.containsKey(key) && pending.size() < MAX_PENDING) {
            pending.put(key, new Work(key, regionX, regionZ, false));
            notifyAll();
        }
        return null;
    }

    /** Copies one ABGR chunk; false means the bounded queue did not accept it. */
    public synchronized boolean updateChunk(int chunkX, int chunkZ, int[] pixels) {
        if (pixels == null || pixels.length != CHUNK_PIXELS * CHUNK_PIXELS) {
            throw new IllegalArgumentException("A map chunk must contain 256 pixels");
        }
        if (closing) return false;
        int regionX = Math.floorDiv(chunkX, DashboardMapStore.REGION_CHUNKS);
        int regionZ = Math.floorDiv(chunkZ, DashboardMapStore.REGION_CHUNKS);
        long key = key(regionX, regionZ);
        Work work = pending.get(key);
        if (work == null) {
            if (pending.size() >= MAX_PENDING) return false;
            work = new Work(key, regionX, regionZ, missing.remove(key) != null);
            pending.put(key, work);
        }
        int localX = Math.floorMod(chunkX, DashboardMapStore.REGION_CHUNKS);
        int localZ = Math.floorMod(chunkZ, DashboardMapStore.REGION_CHUNKS);
        work.chunks.put(localZ * DashboardMapStore.REGION_CHUNKS + localX, pixels.clone());
        notifyAll();
        return true;
    }

    @Override
    public synchronized void close() {
        closing = true;
        notifyAll();
    }

    public synchronized boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        long started = System.nanoTime();
        while (!closed && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = unit.toNanos(timeout) - (System.nanoTime() - started);
        }
        return closed;
    }

    public synchronized boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        long started = System.nanoTime();
        while (!pending.isEmpty() && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = unit.toNanos(timeout) - (System.nanoTime() - started);
        }
        return pending.isEmpty();
    }

    public synchronized int getIoFailureCount() {
        return ioFailureCount;
    }

    public synchronized boolean isPending(int regionX, int regionZ) {
        return pending.containsKey(key(regionX, regionZ));
    }

    private void run() {
        try {
            awaitPredecessor();
            while (true) {
                Work work;
                Region cached;
                synchronized (this) {
                    while (pending.isEmpty() && !closing) {
                        try {
                            wait();
                        } catch (InterruptedException exception) {
                            closing = true;
                        }
                    }
                    if (pending.isEmpty()) return;
                    work = pending.values().iterator().next();
                    cached = regions.get(work.key);
                }
                process(work, cached);
            }
        } finally {
            synchronized (OWNERS) {
                OWNERS.remove(directory, this);
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException | SecurityException ignored) {
                // The JVM may already be running this hook.
            }
            synchronized (this) {
                closed = true;
                notifyAll();
            }
        }
    }

    private void awaitPredecessor() {
        DashboardMapCache previous = predecessor;
        predecessor = null;
        if (previous == null) return;
        while (true) {
            try {
                if (previous.awaitClosed(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) return;
            } catch (InterruptedException exception) {
                close();
            }
        }
    }

    private void process(Work work, Region cached) {
        int[] current = cached == null ? null : cached.pixels;
        BitSet unmergedChunks = cached == null ? null : cached.unmergedChunks;
        boolean needsWrite = cached != null && cached.needsWrite;
        boolean readFailed = false;
        if (current == null && !work.knownMissing || unmergedChunks != null) {
            try {
                int[] loaded = store.read(work.regionX, work.regionZ);
                if (unmergedChunks != null && current != null) {
                    if (loaded == null) loaded = new int[DashboardMapStore.PIXEL_COUNT];
                    for (int chunk = unmergedChunks.nextSetBit(0); chunk >= 0;
                         chunk = unmergedChunks.nextSetBit(chunk + 1)) {
                        copyChunk(loaded, current, chunk);
                    }
                    needsWrite = true;
                    cached = null;
                }
                current = loaded;
                unmergedChunks = null;
            } catch (IOException exception) {
                recordIoFailure();
                readFailed = true;
                unmergedChunks = unmergedChunks == null ? new BitSet(256) : (BitSet) unmergedChunks.clone();
            }
        }
        while (true) {
            Map<Integer, int[]> chunks;
            synchronized (this) {
                chunks = new LinkedHashMap<>(work.chunks);
                work.chunks.clear();
            }
            if (current == null && !chunks.isEmpty()) current = new int[DashboardMapStore.PIXEL_COUNT];
            int[] merged = current;
            for (Map.Entry<Integer, int[]> chunk : chunks.entrySet()) {
                if (unmergedChunks != null) unmergedChunks.set(chunk.getKey());
                int x = chunk.getKey() % DashboardMapStore.REGION_CHUNKS * CHUNK_PIXELS;
                int z = chunk.getKey() / DashboardMapStore.REGION_CHUNKS * CHUNK_PIXELS;
                for (int row = 0; row < CHUNK_PIXELS; row++) {
                    for (int column = 0; column < CHUNK_PIXELS; column++) {
                        int target = (z + row) * DashboardMapStore.REGION_PIXELS + x + column;
                        int color = chunk.getValue()[row * CHUNK_PIXELS + column];
                        if (merged[target] != color) {
                            if (merged == current) merged = current.clone();
                            merged[target] = color;
                        }
                    }
                }
            }
            boolean changed = merged != current;
            current = merged;
            needsWrite |= changed;
            if (needsWrite && !readFailed) {
                try {
                    store.write(work.regionX, work.regionZ, current);
                    needsWrite = false;
                } catch (IOException exception) {
                    recordIoFailure();
                }
            }
            synchronized (this) {
                if (current == null) {
                    if (!readFailed) {
                        missing.put(work.key, Boolean.TRUE);
                        trim(missing, MAX_MISSING);
                    }
                } else {
                    long currentRevision = changed || cached == null ? ++revision : cached.revision;
                    cached = new Region(work.regionX, work.regionZ, currentRevision, current,
                        unmergedChunks == null ? null : (BitSet) unmergedChunks.clone(), needsWrite);
                    regions.put(work.key, cached);
                    trim(regions, MAX_REGIONS);
                    missing.remove(work.key);
                }
                if (work.chunks.isEmpty()) {
                    pending.remove(work.key);
                    notifyAll();
                    return;
                }
            }
        }
    }

    private static void copyChunk(int[] target, int[] source, int chunk) {
        int x = chunk % DashboardMapStore.REGION_CHUNKS * CHUNK_PIXELS;
        int z = chunk / DashboardMapStore.REGION_CHUNKS * CHUNK_PIXELS;
        for (int row = 0; row < CHUNK_PIXELS; row++) {
            int offset = (z + row) * DashboardMapStore.REGION_PIXELS + x;
            System.arraycopy(source, offset, target, offset, CHUNK_PIXELS);
        }
    }

    private synchronized void recordIoFailure() {
        ioFailureCount++;
    }

    private static <T> void trim(LinkedHashMap<Long, T> map, int limit) {
        while (map.size() > limit) map.remove(map.keySet().iterator().next());
    }

    private static long key(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }

    private static final class Work {
        final long key;
        final int regionX;
        final int regionZ;
        final boolean knownMissing;
        final Map<Integer, int[]> chunks = new LinkedHashMap<>();

        Work(long key, int regionX, int regionZ, boolean knownMissing) {
            this.key = key;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.knownMissing = knownMissing;
        }
    }

    public static final class Region {
        public final int regionX;
        public final int regionZ;
        public final long revision;
        private final int[] pixels;
        // Failed reads must be retried before replacing pixels we have never loaded.
        private final BitSet unmergedChunks;
        private final boolean needsWrite;

        private Region(int regionX, int regionZ, long revision, int[] pixels,
                       BitSet unmergedChunks, boolean needsWrite) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.revision = revision;
            this.pixels = pixels;
            this.unmergedChunks = unmergedChunks;
            this.needsWrite = needsWrite;
        }

        public int[] copyPixels() {
            return pixels.clone();
        }
    }
}
