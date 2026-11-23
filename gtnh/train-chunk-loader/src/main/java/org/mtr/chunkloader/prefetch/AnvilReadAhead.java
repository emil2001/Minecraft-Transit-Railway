package org.mtr.chunkloader.prefetch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Warms the OS file cache without creating region files, inflating NBT or touching the world. */
final class AnvilReadAhead {
    private static final int SECTOR = 4096;
    private static final int BUFFER_SIZE = 32 * 1024;
    // Covers the full standard Anvil range. Extended records can warm a prefix per request.
    private static final int MAX_READ = 8 * 1024 * 1024;
    private static final ReadBudget DISK_BUDGET = new ReadBudget(System::nanoTime, TimeUnit.NANOSECONDS::sleep);

    private AnvilReadAhead() {}

    static int warm(Path regionDirectory, int x, int z) {
        return warm(regionDirectory, x, z, DISK_BUDGET);
    }

    static int warm(Path regionDirectory, int x, int z, ReadBudget budget) {
        Path region = regionDirectory.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(5);
            int slot = (x & 31) + (z & 31) * 32;
            channel.position(4L * slot);
            header.limit(4);
            if (!fill(channel, header, budget)) return 0;
            int location = header.getInt(0);
            int sector = location >>> 8;
            int count = location & 255;
            if (sector < 2 || count == 0) return 0;

            long start = (long) sector * SECTOR;
            channel.position(start);
            header.clear();
            if (!fill(channel, header, budget)) return 0;
            int size = header.getInt(0); // Compression byte followed by the record payload.
            int compression = header.get(4);
            if (size <= 1 || (compression != 1 && compression != 2)) return 0;
            long sectorsNeeded = (size + 4L + SECTOR - 1) / SECTOR;
            if (start + Math.max(count, sectorsNeeded) * SECTOR > channel.size()) return 0;
            if (sectorsNeeded > count) {
                // Extended Anvil uses a saturated count. Never read through the next record.
                if (count != 255 || overlapsNextRecord(channel, sector, sectorsNeeded, budget)) return 0;
                channel.position(start + 5);
            }

            int bytes = Math.min(size - 1, MAX_READ);
            ByteBuffer buffer = ByteBuffer.allocate(Math.min(BUFFER_SIZE, bytes));
            for (int remaining = bytes; remaining > 0; remaining -= buffer.limit()) {
                buffer.clear();
                buffer.limit(Math.min(buffer.capacity(), remaining));
                if (!fill(channel, buffer, budget)) return 0;
            }
            return bytes;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (IOException missingOrChanged) {
            return 0;
        }
    }

    private static boolean overlapsNextRecord(FileChannel channel, int start, long sectors, ReadBudget budget)
            throws IOException, InterruptedException {
        ByteBuffer locations = ByteBuffer.allocate(SECTOR);
        channel.position(0);
        if (!fill(channel, locations, budget)) return true;
        locations.flip();
        while (locations.hasRemaining()) {
            int other = locations.getInt() >>> 8;
            if (other > start && other < start + sectors) return true;
        }
        return false;
    }

    private static boolean fill(FileChannel channel, ByteBuffer buffer, ReadBudget budget)
            throws IOException, InterruptedException {
        budget.acquire(buffer.remaining());
        while (buffer.hasRemaining()) {
            if (Thread.currentThread().isInterrupted() || channel.read(buffer) <= 0) return false;
        }
        return true;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    /** A shared token bucket with one buffer of burst capacity. Only file-reader threads enter it. */
    static final class ReadBudget {
        private static final int BYTES_PER_SECOND = 4 * 1024 * 1024;
        private static final long BURST_NANOS = nanosFor(BUFFER_SIZE);
        private final LongSupplier nanoTime;
        private final Sleeper sleeper;
        // Express outstanding bytes as the time until the whole burst is available again.
        private long fullAt;

        ReadBudget(LongSupplier nanoTime, Sleeper sleeper) {
            this.nanoTime = nanoTime;
            this.sleeper = sleeper;
            fullAt = nanoTime.getAsLong();
        }

        private static long nanosFor(int bytes) {
            return (bytes * 1_000_000_000L + BYTES_PER_SECOND - 1) / BYTES_PER_SECOND;
        }

        private synchronized void acquire(int bytes) throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            long now = nanoTime.getAsLong();
            long debt = Math.max(0L, fullAt - now);
            long cost = nanosFor(bytes);
            long wait = debt + cost - BURST_NANOS;
            if (wait > 0) {
                sleeper.sleep(wait);
                now = nanoTime.getAsLong();
                debt = Math.max(0L, fullAt - now);
            }
            fullAt = now + debt + cost;
        }
    }
}
