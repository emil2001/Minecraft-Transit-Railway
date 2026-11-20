package org.mtr.chunkloader.prefetch;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AnvilReadAheadTest {
    @TempDir Path temp;

    @Test void verifiesSavedPopulatedTerrainAtNegativeCoordinatesInBothFormats() throws Exception {
        for (int format : new int[]{1, 2}) {
            Path file = store(terrain(-2, 35, true), format);
            byte[] before = Files.readAllBytes(file);
            assertTrue(AnvilReadAhead.isPopulated(temp, -2, 35));
            assertFalse(AnvilReadAhead.isPopulated(temp, -1, 35));
            assertArrayEquals(before, Files.readAllBytes(file));
        }
    }

    @Test void skipsWrongCoordinatesMissingSectionsAndUnfinishedTerrain() throws Exception {
        store(terrain(-3, 35, true), 2);
        assertFalse(AnvilReadAhead.isPopulated(temp, -2, 35));
        store(terrain(-2, 35, false), 2);
        assertFalse(AnvilReadAhead.isPopulated(temp, -2, 35));
        NBTTagCompound incomplete = terrain(-2, 35, true);
        incomplete.getCompoundTag("Level").removeTag("Sections");
        store(incomplete, 2);
        assertFalse(AnvilReadAhead.isPopulated(temp, -2, 35));
    }

    @Test void skipsMissingCorruptOversizedAndUnknownFormatsWithoutRepairingFiles() throws Exception {
        Path missing = temp.resolve("absent");
        assertFalse(AnvilReadAhead.isPopulated(missing, 0, 0));
        assertFalse(Files.exists(missing));

        Path file = store(terrain(-2, 35, true), 3);
        assertFalse(AnvilReadAhead.isPopulated(temp, -2, 35));
        try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
            out.seek(2 * 4096 + 4);
            out.writeByte(2);
            out.writeInt(0);
            assertFalse(AnvilReadAhead.isPopulated(temp, -2, 35));
            out.seek(2 * 4096);
            out.writeInt(6000);
            assertFalse(AnvilReadAhead.isPopulated(temp, -2, 35));
            out.setLength(102 * 4096);
            out.seek((30 + 32 * 3) * 4);
            out.writeInt(2 << 8 | 100);
            out.seek(2 * 4096);
            out.writeInt(400_000);
            assertFalse(AnvilReadAhead.isPopulated(temp, -2, 35));
        }
    }

    private static NBTTagCompound terrain(int x, int z, boolean populated) {
        NBTTagCompound level = new NBTTagCompound();
        level.setInteger("xPos", x);
        level.setInteger("zPos", z);
        level.setBoolean("TerrainPopulated", populated);
        level.setTag("Sections", new NBTTagList());
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("Level", level);
        return root;
    }

    private Path store(NBTTagCompound chunk, int format) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(format == 1 ? new GZIPOutputStream(bytes) : new DeflaterOutputStream(bytes))) {
            CompressedStreamTools.write(chunk, out);
        }
        Path file = temp.resolve("r.-1.1.mca");
        try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
            out.setLength(3 * 4096);
            out.seek((30 + 32 * 3) * 4);
            out.writeInt((2 << 8) | 1);
            out.seek(2 * 4096);
            out.writeInt(bytes.size() + 1);
            out.writeByte(format);
            out.write(bytes.toByteArray());
        }
        return file;
    }
}
