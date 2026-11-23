package org.mtr.chunkloader.watch;

import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** A single player's square of native chunk subscriptions. Server thread only. */
final class WatchWindow {
    static final int TRAIN_RADIUS = 6;
    private static final int RESTORE_INTERVAL = 20;

    interface Subscriptions {
        void resetPending();
        void unwatch(int x, int z);
        void ensure(int x, int z);
        void submit();
        boolean watchResident(int x, int z);
    }

    private Set<Long> extra = Collections.emptySet();

    private int centerX, centerZ, radius, restoringTicks;

    WatchWindow(int x, int z, int radius) {
        centerX = x;
        centerZ = z;
        this.radius = radius;
    }

    int radius() { return radius; }

    boolean contains(int x, int z) {
        return allowsGeneration(x, z) || extra.contains(key(x, z));
    }

    boolean allowsResend(int x, int z, BooleanSupplier receivedInitialChunk) {
        return contains(x, z) && receivedInitialChunk.getAsBoolean();
    }

    boolean allowsGeneration(int x, int z) {
        return inSquare(x, z, centerX, centerZ, radius);
    }

    void pruneGeneration(Set<Long> requests) {
        requests.removeIf(packed -> !allowsGeneration(packed.intValue(), (int) (packed >> 32)));
    }

    static void excludeSquare(List<Long> candidates, int x, int z, int radius) {
        candidates.removeIf(chunk -> inSquare(chunk.intValue(), (int) (chunk >> 32), x, z, radius));
    }

    boolean hasExtra() { return !extra.isEmpty(); }

    private static boolean inSquare(int x, int z, int cx, int cz, int r) {
        return Math.abs(x - cx) <= r && Math.abs(z - cz) <= r;
    }

    private static long key(int x, int z) {
        return x & 0xffffffffL | (long) z << 32;
    }

    void tick(int x, int z, int global, boolean fast, Collection<Long> route, Subscriptions watches) {
        int target = fast ? Math.min(global, TRAIN_RADIUS) : global;
        int next = Math.min(radius, target);
        if (target > radius && ++restoringTicks >= RESTORE_INTERVAL) {
            next++;
            restoringTicks = 0;
        } else if (target <= radius) {
            restoringTicks = 0;
        }
        resize(x, z, next, route, watches);
    }

    void removeExtra(Subscriptions watches) {
        for (long p : extra) watches.unwatch((int) p, (int) (p >> 32));
        extra = Collections.emptySet();
    }

    void resize(int x, int z, int next, Collection<Long> route, Subscriptions watches) {
        Set<Long> kept = route.isEmpty() ? Collections.emptySet() : new HashSet<>(route.size());
        for (long p : route) {
            int i = (int) p, j = (int) (p >> 32);
            if (!inSquare(i, j, x, z, next) && (extra.contains(p) || watches.watchResident(i, j))) kept.add(p);
        }
        for (long p : extra) {
            int i = (int) p, j = (int) (p >> 32);
            if (!kept.contains(p) && !inSquare(i, j, x, z, next)) watches.unwatch(i, j);
        }
        extra = kept;
        if (x == centerX && z == centerZ && next == radius) return;
        watches.resetPending();
        for (int i = centerX - radius; i <= centerX + radius; i++) {
            for (int j = centerZ - radius; j <= centerZ + radius; j++) {
                if (!inSquare(i, j, x, z, next) && (kept.isEmpty() || !kept.contains(key(i, j)))) {
                    watches.unwatch(i, j);
                }
            }
        }
        // Re-submit missing members of the intersection too. Hodgepodge may still
        // have been generating them when we withdrew the old window's requests.
        for (int i = x - next; i <= x + next; i++) {
            for (int j = z - next; j <= z + next; j++) watches.ensure(i, j);
        }
        watches.submit();
        centerX = x;
        centerZ = z;
        radius = next;
    }
}
