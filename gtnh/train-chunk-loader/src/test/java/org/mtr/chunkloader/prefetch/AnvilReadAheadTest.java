package org.mtr.chunkloader.prefetch;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
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
