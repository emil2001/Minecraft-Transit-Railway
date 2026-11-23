package org.mtr.chunkloader.prefetch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;

/** Per-dimension read-ahead. Only the file reader leaves the server thread. */
public final class ChunkPrefetcher {
    private static final int RETRY_TICKS = 200;
    private static final int MAX_RECENT = 512;
    private static final int MAX_CHUNKS_PER_TRAIN = 96;
    private static final int MAX_TRAIN_CHECKS = 4;
    private static final int MAX_FORWARD_PER_TRAIN = 6;
    private static final int MAX_FORWARD_PER_WORLD = 32;
    private static final ThreadPoolExecutor DISK = new ThreadPoolExecutor(
            0, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), runnable -> {
                Thread thread = new Thread(runnable, "MTR region read-ahead");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    @FunctionalInterface
    interface FileReader {
        void warm(long chunk, BooleanSupplier cancelled);
    }

    private final LongFunction<Chunk> loadedChunk;
    private final BooleanSupplier hasHeadroom;
    private final FileReader warm;
    private final ThreadPoolExecutor disk;
    private final Map<Long, Long> recent = new LinkedHashMap<>();
    private final Map<Long, List<Long>> forward = new HashMap<>();
    private ReadRequest pending;
    private long tick, nextAdmission;
    private int trainIndex;
    private boolean closed;

    public ChunkPrefetcher(WorldServer world) {
        this(key -> loadedChunk(world.theChunkProviderServer, key),
                () -> serverHasHeadroom(world.func_73046_m()), reader(world.theChunkProviderServer.currentChunkLoader), DISK);
    }

    ChunkPrefetcher(LongFunction<Chunk> loadedChunk, BooleanSupplier hasHeadroom, FileReader warm, ThreadPoolExecutor disk) {
        this.loadedChunk = loadedChunk;
        this.hasHeadroom = hasHeadroom;
        this.warm = warm;
        this.disk = disk;
    }

    private static FileReader reader(IChunkLoader loader) {
        if (!(loader instanceof AnvilChunkLoader)) return null;
        Path directory = ((AnvilChunkLoader) loader).chunkSaveLocation.toPath().resolve("region");
        return (chunk, cancelled) -> AnvilReadAhead.warm(directory, (int) chunk, (int) (chunk >> 32), cancelled);
    }

    public static boolean isSendReadyResident(ChunkProviderServer provider, long key) {
        return isSendReady(loadedChunk(provider, key));
    }

    private static Chunk loadedChunk(ChunkProviderServer provider, long key) {
        return (Chunk) provider.loadedChunkHashMap.getValueByKey(key);
    }

    private static boolean isSendReady(Chunk chunk) {
        // EntityPlayerMP uses func_150802_k() for the initial packet. Hodgepodge can
        // relax it to the first-tick flag, so forward watches still require populated terrain.
        return chunk != null && chunk.isTerrainPopulated && chunk.func_150802_k();
    }

    public static boolean serverHasHeadroom(MinecraftServer server) {
        long[] times = server.tickTimeArray;
        int index = Math.floorMod(server.getTickCounter() - 1, times.length);
        long recentTime = 0;
        for (int age = 0; age < 8; age++) {
            long duration = times[index];
            if (age == 0 && duration >= 45_000_000L) return false;
            recentTime += duration;
            if (--index < 0) index = times.length - 1;
        }
        return recentTime < 8 * 40_000_000L;
    }

    public void tick(Map<Long, ? extends Collection<Long>> routes) {
        if (closed) return;
        tick++;
        if (routes.isEmpty()) {
            if (pending != null) cancelPending();
            recent.clear();
            forward.clear();
            trainIndex = 0;
            return;
        }

        boolean healthy = hasHeadroom.getAsBoolean();
        if (pending != null) {
            if (!healthy || !wanted(routes, pending.chunk) || loadedChunk.apply(pending.chunk) != null) cancelPending();
            else if (pending.done) pending = null;
        }
        retainForward(routes);
        if (!healthy || tick < nextAdmission) return;
        nextAdmission = tick + 2;
        Set<Long> admitted = new HashSet<>();
        forward.values().forEach(admitted::addAll);
        List<Long> trains = new ArrayList<>(routes.keySet());
        for (int i = 0; i < Math.min(trains.size(), MAX_TRAIN_CHECKS); i++) {
            trainIndex %= trains.size();
            long train = trains.get(trainIndex++);
            if (advance(train, routes.get(train), admitted)) return;
        }
    }

    private boolean advance(long train, Collection<Long> route, Set<Long> admitted) {
        List<Long> previous = ready(train);
        boolean selected = false;
        boolean attemptedRead = false;
        int checks = 0;
        for (long chunk : route) {
            if (++checks > MAX_CHUNKS_PER_TRAIN) break;
            if (previous.contains(chunk)) continue;
            Chunk loaded = loadedChunk.apply(chunk);
            if (loaded != null) {
                if (!selected && previous.size() < MAX_FORWARD_PER_TRAIN
                        && (admitted.size() < MAX_FORWARD_PER_WORLD || admitted.contains(chunk)) && isSendReady(loaded)) {
                    List<Long> next = new ArrayList<>(previous);
                    next.add(chunk);
                    forward.put(train, Collections.unmodifiableList(next));
                    selected = true;
                }
            } else if (warm != null && pending == null && !attemptedRead) {
                Long last = recent.get(chunk);
                if (last == null || tick - last >= RETRY_TICKS) {
                    submit(chunk);
                    attemptedRead = true;
                }
            }
            if (selected && (warm == null || attemptedRead || pending != null)) break;
        }
        return selected || attemptedRead;
    }

    /** Detached resident candidates. The subscription checks the live provider again at admission. */
    public List<Long> ready(long train) {
        return forward.getOrDefault(train, Collections.emptyList());
    }

    private void retainForward(Map<Long, ? extends Collection<Long>> routes) {
        Iterator<Map.Entry<Long, List<Long>>> it = forward.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, List<Long>> entry = it.next();
            Collection<Long> route = routes.get(entry.getKey());
            if (route == null) {
                it.remove();
                continue;
            }
            List<Long> previous = entry.getValue();
            List<Long> next = previous;
            for (Long chunk : previous) {
                if (route.contains(chunk) && isSendReady(loadedChunk.apply(chunk))) continue;
                if (next == previous) next = new ArrayList<>(previous);
                next.remove(chunk);
            }
            if (next == previous) continue;
            if (next.isEmpty()) it.remove();
            else entry.setValue(Collections.unmodifiableList(next));
        }
    }

    private static boolean wanted(Map<Long, ? extends Collection<Long>> routes, long chunk) {
        for (Collection<Long> route : routes.values()) {
            if (route.contains(chunk)) return true;
        }
        return false;
    }

    private void submit(long chunk) {
        // The worker only warms the filesystem cache. Completion can never grant a subscription.
        ReadRequest request = new ReadRequest(chunk, warm);
        try {
            disk.execute(request);
        } catch (RejectedExecutionException busy) {
            return;
        }
        pending = request;
        recent.remove(chunk);
        recent.put(chunk, tick);
        if (recent.size() > MAX_RECENT) recent.remove(recent.keySet().iterator().next());
    }

    private void cancelPending() {
        // Interrupting a FileChannel read can synchronously close it on the calling server thread.
        pending.cancelled = true;
        disk.remove(pending);
        recent.remove(pending.chunk);
        pending = null;
    }

    private static final class ReadRequest implements Runnable {
        final long chunk;
        final FileReader reader;
        volatile boolean cancelled, done;

        ReadRequest(long chunk, FileReader reader) {
            this.chunk = chunk;
            this.reader = reader;
        }

        @Override public void run() {
            try {
                if (!cancelled) reader.warm(chunk, () -> cancelled);
            } finally {
                done = true;
            }
        }
    }

    public void close() {
        closed = true;
        forward.clear();
        if (pending != null) cancelPending();
        recent.clear();
    }
}
