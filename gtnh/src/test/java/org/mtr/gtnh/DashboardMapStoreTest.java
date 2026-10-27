package org.mtr.gtnh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DashboardMapStoreTest {
    @TempDir Path directory;

    @Test
    void roundTripPreservesEveryAbgrBitIncludingTransparentPixels() throws Exception {
        int[] pixels = pixels(0x80654321);
        pixels[0] = 0;
        pixels[1] = 0x00123456;
        pixels[2] = 0x7FABCDEF;
        pixels[pixels.length - 1] = 0xFF010203;
        new DashboardMapStore(directory).write(2, 4, pixels);

        assertArrayEquals(pixels, new DashboardMapStore(directory).read(2, 4));
    }

    @Test
    void missingRegionIsACacheMissWithoutCreatingDirectories() throws Exception {
        Path absent = directory.resolve("absent");
        assertNull(new DashboardMapStore(absent).read(2, 4));
        assertFalse(Files.exists(absent));
    }

    @Test
    void negativeCoordinatesAndAdjacentRegionsRemainDistinct() throws Exception {
        DashboardMapStore store = new DashboardMapStore(directory);
        int[] negative = pixels(0xFF112233);
        int[] positive = pixels(0xFF998877);
        store.write(-1, -17, negative);
        store.write(-1, 17, positive);

        assertArrayEquals(negative, store.read(-1, -17));
        assertArrayEquals(positive, store.read(-1, 17));
        assertNull(store.read(1, -17));
    }

    @Test
    void wrongMagicIsACacheMiss() throws Exception {
        writeFixture(0, 1, 2, 4, 65536, false);
        assertNull(new DashboardMapStore(directory).read(2, 4));
    }

    @Test
    void unsupportedVersionIsACacheMiss() throws Exception {
        writeFixture(0x4D54524D, 2, 2, 4, 65536, false);
        assertNull(new DashboardMapStore(directory).read(2, 4));
    }

    @Test
    void coordinatesInHeaderMustMatchRequestedRegion() throws Exception {
        writeFixture(0x4D54524D, 1, -2, 4, 65536, false);
        assertNull(new DashboardMapStore(directory).read(2, 4));
    }

    @Test
    void truncatedDecodedPixelsAreACacheMiss() throws Exception {
        writeFixture(0x4D54524D, 1, 2, 4, 65535, false);
        assertNull(new DashboardMapStore(directory).read(2, 4));
    }

    @Test
    void truncatedCompressedFileIsACacheMiss() throws Exception {
        DashboardMapStore store = new DashboardMapStore(directory);
        store.write(2, 4, pixels(0x80123456));
        Path file = directory.resolve(DashboardMapStore.fileName(2, 4));
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length / 2));

        assertNull(store.read(2, 4));
    }

    @Test
    void trailingDecodedBytesAreACacheMiss() throws Exception {
        writeFixture(0x4D54524D, 1, 2, 4, 65536, true);
        assertNull(new DashboardMapStore(directory).read(2, 4));
    }

    @Test
    void replacementPublishesACompleteRegionAndLeavesNoTemporaryFile() throws Exception {
        DashboardMapStore store = new DashboardMapStore(directory);
        store.write(2, 4, pixels(0xFF112233));
        int[] replacement = pixels(0x7F998877);
        store.write(2, 4, replacement);

        assertArrayEquals(replacement, store.read(2, 4));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void invalidPixelCountPreservesPreviousRegion() throws Exception {
        DashboardMapStore store = new DashboardMapStore(directory);
        int[] original = pixels(0xFF112233);
        store.write(2, 4, original);

        assertThrows(IllegalArgumentException.class, () -> store.write(2, 4, new int[65535]));
        assertThrows(IllegalArgumentException.class, () -> store.write(2, 4, new int[65537]));
        assertArrayEquals(original, store.read(2, 4));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void concurrentWritesToDifferentCoordinatesDoNotInterfere() throws Exception {
        DashboardMapStore store = new DashboardMapStore(directory.resolve("new"));
        int[] first = pixels(0xFF112233);
        int[] second = pixels(0xFF998877);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = executor.submit(() -> { store.write(-12, 34, first); return null; });
            Future<?> b = executor.submit(() -> { store.write(12, -34, second); return null; });
            a.get();
            b.get();
            assertArrayEquals(first, store.read(-12, 34));
            assertArrayEquals(second, store.read(12, -34));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void directoryErrorsAreReportedAsIoExceptions() throws Exception {
        Path regularFile = directory.resolve("not-a-directory");
        Files.write(regularFile, new byte[] {1});
        DashboardMapStore store = new DashboardMapStore(regularFile);

        assertThrows(IOException.class, () -> store.read(2, 4));
        assertThrows(IOException.class, () -> store.write(2, 4, pixels(1)));
    }

    private void writeFixture(int magic, int version, int x, int z, int count, boolean trailing) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(
                Files.newOutputStream(directory.resolve(DashboardMapStore.fileName(2, 4)))))) {
            output.writeInt(magic);
            output.writeInt(version);
            output.writeInt(x);
            output.writeInt(z);
            for (int i = 0; i < count; i++) output.writeInt(0xFF123456);
            if (trailing) output.writeByte(1);
        }
    }

    private static int[] pixels(int seed) {
        int[] pixels = new int[65536];
        for (int i = 0; i < pixels.length; i++) pixels[i] = seed ^ i * 0x00010507;
        return pixels;
    }
}
