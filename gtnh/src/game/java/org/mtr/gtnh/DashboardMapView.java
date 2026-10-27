package org.mtr.gtnh;

import java.util.ArrayList;
import java.util.List;

/** Viewport math shared by cache requests and texture clipping. */
public final class DashboardMapView {
    private DashboardMapView() {}

    public static long key(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
    public static int x(long key) { return (int) (key >> 32); }
    public static int z(long key) { return (int) key; }

    public static List<Long> cells(double x1, double z1, double x2, double z2, int size, int limit) {
        List<Long> result = new ArrayList<>();
        if (size <= 0 || limit <= 0 || x1 >= x2 || z1 >= z2 ||
            !Double.isFinite(x1 + z1 + x2 + z2)) return result;
        int minX = (int) Math.floor(x1 / size), minZ = (int) Math.floor(z1 / size);
        int maxX = (int) Math.ceil(x2 / size) - 1, maxZ = (int) Math.ceil(z2 / size) - 1;
        int centerX = (int) Math.floor((x1 + x2) / (2 * size));
        int centerZ = (int) Math.floor((z1 + z2) / (2 * size));
        int count = (int) Math.min(limit, ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1));
        add(result, centerX, centerZ, minX, minZ, maxX, maxZ, count);
        for (int radius = 1; result.size() < count; radius++) {
            for (int dx = -radius; dx <= radius && result.size() < count; dx++) {
                add(result, centerX + dx, centerZ - radius, minX, minZ, maxX, maxZ, count);
                add(result, centerX + dx, centerZ + radius, minX, minZ, maxX, maxZ, count);
            }
            for (int dz = -radius + 1; dz < radius && result.size() < count; dz++) {
                add(result, centerX - radius, centerZ + dz, minX, minZ, maxX, maxZ, count);
                add(result, centerX + radius, centerZ + dz, minX, minZ, maxX, maxZ, count);
            }
        }
        return result;
    }

    private static void add(List<Long> result, int x, int z, int minX, int minZ, int maxX, int maxZ, int limit) {
        if (result.size() < limit && x >= minX && x <= maxX && z >= minZ && z <= maxZ) result.add(key(x, z));
    }

    /** Returns screen bounds followed by matching UV bounds, or null if outside. */
    public static double[] clip(double x1, double z1, double x2, double z2, double width, double height) {
        double left = Math.max(0, x1), top = Math.max(0, z1);
        double right = Math.min(width, x2), bottom = Math.min(height, z2);
        if (left >= right || top >= bottom || x1 >= x2 || z1 >= z2) return null;
        return new double[]{left, top, right, bottom, (left - x1) / (x2 - x1),
            (top - z1) / (z2 - z1), (right - x1) / (x2 - x1), (bottom - z1) / (z2 - z1)};
    }
}
