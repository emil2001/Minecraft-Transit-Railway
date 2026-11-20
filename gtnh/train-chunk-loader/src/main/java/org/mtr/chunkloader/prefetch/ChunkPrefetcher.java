package org.mtr.chunkloader.prefetch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.IChunkLoader;

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

    private final LongPredicate loaded;
    private final BooleanSupplier hasHeadroom;
    private final LongPredicate verify;
    private final ThreadPoolExecutor disk;
    private final Map<Long, Long> recent = new LinkedHashMap<>();
    private final Map<Long, Long> confirmed = new HashMap<>();
    private final Map<Long, List<Long>> forward = new HashMap<>();
    private FutureTask<Boolean> pending;
    private long pendingChunk, tick, nextAdmission;
    private int trainIndex;
    private boolean closed;

    public ChunkPrefetcher(WorldServer world) {
        this(chunk -> world.theChunkProviderServer.chunkExists((int) chunk, (int) (chunk >> 32)),
                () -> serverHasHeadroom(world.func_73046_m()), reader(world.theChunkProviderServer.currentChunkLoader), DISK);
    }

    ChunkPrefetcher(LongPredicate loaded, BooleanSupplier hasHeadroom, LongPredicate verify, ThreadPoolExecutor disk) {
        this.loaded = loaded;
        this.hasHeadroom = hasHeadroom;
        this.verify = verify;
        this.disk = disk;
    }

    private static LongPredicate reader(IChunkLoader loader) {
        if (!(loader instanceof AnvilChunkLoader)) return null;
        Path directory = ((AnvilChunkLoader) loader).chunkSaveLocation.toPath().resolve("region");
        return chunk -> AnvilReadAhead.isPopulated(directory, (int) chunk, (int) (chunk >> 32));
    }

    private static boolean serverHasHeadroom(MinecraftServer server) {
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
        if (closed || verify == null) return;
        tick++;
        if (routes.isEmpty()) {
            if (pending != null) cancelPending();
            recent.clear();
            confirmed.clear();
            forward.clear();
            trainIndex = 0;
            return;
        }

        boolean healthy = hasHeadroom.getAsBoolean();
        if (pending != null) {
            if (!healthy || !wanted(routes, pendingChunk)) cancelPending();
            else if (pending.isDone()) finishPending();
        }
        updateForward(routes);
        if (!healthy || pending != null || confirmed.size() >= MAX_FORWARD_PER_WORLD || tick < nextAdmission) return;
        nextAdmission = tick + 2;
        List<Long> trains = new ArrayList<>(routes.keySet());
        for (int i = 0; i < Math.min(trains.size(), MAX_TRAIN_CHECKS); i++) {
            trainIndex %= trains.size();
            long train = trains.get(trainIndex++);
            if (ready(train).size() >= MAX_FORWARD_PER_TRAIN) continue;
            int checks = 0;
            for (long chunk : routes.get(train)) {
                if (++checks > MAX_CHUNKS_PER_TRAIN) break;
                Long last = recent.get(chunk);
                if ((last == null || tick - last >= RETRY_TICKS) && !confirmed.containsKey(chunk) && !loaded.test(chunk)) {
                    submit(chunk);
                    return;
                }
            }
        }
    }

    private void finishPending() {
        try {
            if (pending.get() && !loaded.test(pendingChunk)) {
                confirmed.put(pendingChunk, tick);
                recent.remove(pendingChunk);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failed) {
            // A failed verification cannot grant a native subscription.
        }
        pending = null;
    }

    /** Detached, immutable targets, valid until the next world tick. */
    public List<Long> ready(long train) {
        return forward.getOrDefault(train, Collections.emptyList());
    }

    private void updateForward(Map<Long, ? extends Collection<Long>> routes) {
        if (confirmed.isEmpty()) return;
        // Unclaimed reads expire. An actual loaded watcher keeps its place until the route moves.
        confirmed.entrySet().removeIf(entry -> tick - entry.getValue() >= RETRY_TICKS && !loaded.test(entry.getKey()));
        forward.keySet().retainAll(routes.keySet());
        Set<Long> retained = new HashSet<>();
        for (Map.Entry<Long, ? extends Collection<Long>> train : routes.entrySet()) {
            List<Long> previous = ready(train.getKey());
            Collection<Long> route = train.getValue();
            List<Long> next = new ArrayList<>(MAX_FORWARD_PER_TRAIN);
            for (long chunk : previous) {
                if (confirmed.containsKey(chunk) && route.contains(chunk)) next.add(chunk);
            }
            // A second train can share an already verified target without another read.
            for (long chunk : route) {
                if (next.size() == MAX_FORWARD_PER_TRAIN) break;
                if (confirmed.containsKey(chunk) && !next.contains(chunk)) next.add(chunk);
            }
            retained.addAll(next);
            if (next.isEmpty()) forward.remove(train.getKey());
            else if (!next.equals(previous)) forward.put(train.getKey(), Collections.unmodifiableList(next));
        }
        confirmed.keySet().retainAll(retained);
    }

    private static boolean wanted(Map<Long, ? extends Collection<Long>> routes, long chunk) {
        for (Collection<Long> route : routes.values()) {
            if (route.contains(chunk)) return true;
        }
        return false;
    }

    private void submit(long chunk) {
        // Capture only the file reader and coordinates. A racing completion cannot retain or modify a world.
        LongPredicate fileReader = verify;
        FutureTask<Boolean> request = new FutureTask<>(() -> fileReader.test(chunk));
        try {
            disk.execute(request);
        } catch (RejectedExecutionException busy) {
            return;
        }
        pending = request;
        pendingChunk = chunk;
        recent.remove(chunk);
        recent.put(chunk, tick);
        if (recent.size() > MAX_RECENT) recent.remove(recent.keySet().iterator().next());
    }

    private void cancelPending() {
        pending.cancel(true);
        disk.remove(pending);
        recent.remove(pendingChunk);
        pending = null;
    }

    public void close() {
        closed = true;
        confirmed.clear();
        forward.clear();
        if (pending != null) cancelPending();
        recent.clear();
    }
}
