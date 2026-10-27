package org.mtr.gtnh;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

public class DashboardMapStore {
    public static final int REGION_CHUNKS = 16;
    public static final int REGION_PIXELS = REGION_CHUNKS * 16;
    public static final int PIXEL_COUNT = REGION_PIXELS * REGION_PIXELS;
    private static final int MAGIC = 0x4D54524D;
    private static final int VERSION = 1;

    private final Path directory;

    public DashboardMapStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    Path directory() {
        return directory;
    }

    public int[] read(int regionX, int regionZ) throws IOException {
        try (InputStream raw = Files.newInputStream(directory.resolve(fileName(regionX, regionZ)));
                DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION ||
                    input.readInt() != regionX || input.readInt() != regionZ) return null;
            int[] pixels = new int[PIXEL_COUNT];
            for (int i = 0; i < pixels.length; i++) pixels[i] = input.readInt();
            return input.read() == -1 ? pixels : null;
        } catch (NoSuchFileException | EOFException | ZipException e) {
            return null;
        }
    }

    public void write(int regionX, int regionZ, int[] abgrPixels) throws IOException {
        if (abgrPixels == null || abgrPixels.length != PIXEL_COUNT) {
            throw new IllegalArgumentException("Expected " + PIXEL_COUNT + " ABGR pixels");
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, fileName(regionX, regionZ) + ".", ".tmp");
        try {
            try (OutputStream raw = Files.newOutputStream(temporary);
                    DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(regionX);
                output.writeInt(regionZ);
                for (int pixel : abgrPixels) output.writeInt(pixel);
            }
            Path target = directory.resolve(fileName(regionX, regionZ));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String fileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".bin.gz";
    }
}
