package org.mtr.chunkloader.prefetch;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AnvilReadAheadTest {
    private static final int SLOT = 30 + 32 * 3; // chunk (-2, 35)
    @TempDir Path temp;

    @Test void readsTheFullSavedRecordAbove256KiBInEitherFormat() throws Exception {
        for (int format : new int[]{1, 2}) {
            byte[] bytes = compressedTerrain(640 * 1024, format);
            Path file = store(bytes, format);
            byte[] before = Files.readAllBytes(file);
            assertTrue(bytes.length > 256 * 1024);
            assertEquals(bytes.length, AnvilReadAhead.warm(temp, -2, 35));
            assertEquals(0, AnvilReadAhead.warm(temp, -1, 35));
            assertArrayEquals(before, Files.readAllBytes(file));
        }
    }

    @Test void acceptsExtendedSectorCountsAndRejectsAnOverlapWithAnotherChunk() throws Exception {
        byte[] bytes = compressedTerrain(1200 * 1024, 2);
        Path file = store(bytes, 2);
        assertEquals(bytes.length, AnvilReadAhead.warm(temp, -2, 35));
        try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
            out.seek(4L * (SLOT + 1));
            out.writeInt((2 + 255) << 8 | 1);
        }
        assertEquals(0, AnvilReadAhead.warm(temp, -2, 35));
    }

    @Test void capsDiskWorkForVeryLargeExtendedRecords() throws Exception {
        Path file = store(new byte[]{1}, 2);
        try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
            out.setLength(10 * 1024 * 1024);
            out.seek(4L * SLOT);
            out.writeInt(2 << 8 | 255);
            out.seek(2 * 4096);
            out.writeInt(9 * 1024 * 1024);
        }
        assertEquals(8 * 1024 * 1024, AnvilReadAhead.warm(temp, -2, 35));
    }

    @Test void sharesOneBurstAcrossRegionsAndChargesHeadersAndExtendedLocationTables() throws Exception {
        byte[] payload = new byte[1024 * 1024];
        Path region = store(payload, 2);
        Path secondWorld = Files.createDirectory(temp.resolve("second-world"));
        Files.copy(region, secondWorld.resolve(region.getFileName()));
        AtomicLong nanos = new AtomicLong();
        AnvilReadAhead.ReadBudget budget = new AnvilReadAhead.ReadBudget(nanos::get, wait -> {
            assertTrue(wait > 0 && wait <= 7_812_500L, "One 32 KiB read takes at most 7.8125 ms at 4 MiB/s");
            nanos.addAndGet(wait);
        });
        assertEquals(payload.length, AnvilReadAhead.warm(temp, -2, 35, () -> false, budget));
        assertEquals(0, AnvilReadAhead.warm(temp, -1, 35, () -> false, budget));
        assertEquals(payload.length, AnvilReadAhead.warm(secondWorld, -2, 35, () -> false, budget));

        long bytes = 2L * (4 + 5 + 4096 + payload.length) + 4;
        long minNanos = (bytes - 32 * 1024) * 1_000_000_000L / (4 * 1024 * 1024);
        assertTrue(nanos.get() >= minNanos && nanos.get() < minNanos + 10, "Elapsed ns: " + nanos.get());
    }

    @Test void interruptDuringBudgetWaitStopsTheReadAndPreservesTheFlag() throws Exception {
        Path file = store(new byte[64 * 1024], 2);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AnvilReadAhead.ReadBudget budget = new AnvilReadAhead.ReadBudget(() -> 0L, wait -> {
            waiting.countDown();
            release.await(5, TimeUnit.SECONDS);
        });
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            int bytes = AnvilReadAhead.warm(file.getParent(), -2, 35, () -> false, budget);
            return bytes == 0 && Thread.currentThread().isInterrupted();
        });
        Thread reader = new Thread(task);
        reader.start();
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            reader.interrupt();
            assertTrue(task.get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            reader.interrupt();
        }
    }

    @Test void cancellationDuringTheFinalBudgetWaitSkipsTheNextBlockWithoutInterruptingTheReader() throws Exception {
        store(new byte[64 * 1024], 2);
        AtomicLong nanos = new AtomicLong();
        AtomicBoolean cancelled = new AtomicBoolean();
        AnvilReadAhead.ReadBudget budget = new AnvilReadAhead.ReadBudget(nanos::get, wait -> {
            // The first wait follows the headers. The second precedes the final 32 KiB block.
            if (nanos.getAndAdd(wait) > 0) cancelled.set(true);
        });
        assertEquals(0, AnvilReadAhead.warm(temp, -2, 35, cancelled::get, budget));
        assertTrue(cancelled.get());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test void skipsMissingTruncatedAndUnknownRecordsWithoutRepairingFiles() throws Exception {
        Path missing = temp.resolve("absent");
        assertEquals(0, AnvilReadAhead.warm(missing, 0, 0));
        assertFalse(Files.exists(missing));
        Path file = store(new byte[]{1}, 3);
        assertEquals(0, AnvilReadAhead.warm(temp, -2, 35));
        try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
            out.seek(2 * 4096);
            out.writeInt(6000);
            out.writeByte(2);
            assertEquals(0, AnvilReadAhead.warm(temp, -2, 35));
            out.setLength(4 * 4096);
            assertEquals(0, AnvilReadAhead.warm(temp, -2, 35), "A normal allocation must fit its sector count");
            out.seek(4L * SLOT);
            out.writeInt(2 << 8 | 255);
            out.seek(2 * 4096);
            out.writeInt(Integer.MAX_VALUE);
            assertEquals(0, AnvilReadAhead.warm(temp, -2, 35));
        }
    }

    private static byte[] compressedTerrain(int payloadSize, int format) throws Exception {
        byte[] noise = new byte[payloadSize];
        new Random(0).nextBytes(noise);
        NBTTagCompound level = new NBTTagCompound();
        level.setInteger("xPos", -2);
        level.setInteger("zPos", 35);
        level.setBoolean("TerrainPopulated", true);
        level.setByteArray("ModData", noise);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("Level", level);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(format == 1 ? new GZIPOutputStream(bytes) : new DeflaterOutputStream(bytes))) {
            CompressedStreamTools.write(root, out);
        }
        return bytes.toByteArray();
    }

    private Path store(byte[] payload, int format) throws Exception {
        Path file = temp.resolve("r.-1.1.mca");
        int sectors = (payload.length + 5 + 4095) / 4096;
        try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
            out.setLength((2L + sectors) * 4096);
            out.seek(4L * SLOT);
            out.writeInt(2 << 8 | Math.min(255, sectors));
            out.seek(2 * 4096);
            out.writeInt(payload.length + 1);
            out.writeByte(format);
            out.write(payload);
        }
        return file;
    }
}
