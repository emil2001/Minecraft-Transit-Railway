package org.mtr.chunkloader.prefetch;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;

/** Reads saved Anvil terrain on the disk worker; Forge remains the owner of live chunks. */
final class AnvilReadAhead {
    private static final int SECTOR = 4096;
    private static final int MAX_COMPRESSED = 256 * 1024;
    private static final int MAX_NBT = 2 * 1024 * 1024;

    private AnvilReadAhead() {}

    static boolean isPopulated(Path regionDirectory, int x, int z) {
        Path region = regionDirectory.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
        // RegionFileCache would create missing files, repair short ones, and open them for writing.
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(5);
            header.limit(4);
            channel.position(4L * ((x & 31) + (z & 31) * 32));
            if (!fill(channel, header)) return false;
            int allocation = header.getInt(0);
            int sector = allocation >>> 8;
            int count = allocation & 255;
            if (sector < 2 || count == 0) return false;

            long start = (long) sector * SECTOR;
            channel.position(start);
            header.clear();
            if (!fill(channel, header)) return false;
            int size = header.getInt(0); // Compression byte followed by compressed NBT.
            int compression = header.get(4);
            if (compression != 1 && compression != 2) return false;
            if (size <= 1 || size - 1 > MAX_COMPRESSED || size > count * SECTOR - 4 || start + 4L + size > channel.size()) return false;

            ByteBuffer bytes = ByteBuffer.allocate(size - 1);
            if (!fill(channel, bytes)) return false;
            ByteArrayInputStream source = new ByteArrayInputStream(bytes.array());
            InputStream inflated = compression == 1 ? new GZIPInputStream(source) : new InflaterInputStream(source);
            try (DataInputStream data = new DataInputStream(new BufferedInputStream(inflated))) {
                NBTTagCompound root = CompressedStreamTools.func_152456_a(data, new NBTSizeTracker(MAX_NBT));
                NBTTagCompound level = root.getCompoundTag("Level");
                return data.read() == -1 && level.hasKey("xPos", 3) && level.getInteger("xPos") == x
                        && level.hasKey("zPos", 3) && level.getInteger("zPos") == z
                        && level.hasKey("Sections", 9) && level.getBoolean("TerrainPopulated");
            }
        } catch (IOException | RuntimeException invalid) {
            // Missing region, concurrent save, truncated or oversized NBT, or a cancelled read.
            return false;
        }
    }

    private static boolean fill(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0) return false;
        }
        return true;
    }
}
