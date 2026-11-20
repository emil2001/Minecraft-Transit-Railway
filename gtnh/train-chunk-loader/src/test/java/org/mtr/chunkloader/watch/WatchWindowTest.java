package org.mtr.chunkloader.watch;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class WatchWindowTest {
    @Test void freesLookaheadSlotsAsThePassengerPassesEachChunk() {
        java.util.List<Long> path = java.util.Arrays.asList(0L, 6L, 7L, 8L, 9L);
        assertEquals(java.util.Arrays.asList(7L, 8L, 9L), WatchWindow.outsideSquare(path, 0, 0, 6));
        assertEquals(java.util.Arrays.asList(8L, 9L), WatchWindow.outsideSquare(path, 1, 0, 6));
    }

    @Test void trainShedsOuterWatchersAndWithdrawsOnlyItsOwnDeferredRequests() {
        MemoryWatches rider = new MemoryWatches(10);
        MemoryWatches pedestrian = new MemoryWatches(10);
        rider.watched.remove(key(2, -3));
        rider.pending.add(key(2, -3));
        rider.pending.add(key(10, 10));
        WatchWindow window = new WatchWindow(0, 0, 10);
        window.tick(0, 0, 10, true, java.util.Collections.emptyList(), rider);

        assertEquals(168, rider.watched.size());
        assertEquals(java.util.Collections.singleton(key(2, -3)), rider.pending);
        assertFalse(rider.watched.contains(key(-7, 0)));
        assertTrue(rider.watched.contains(key(6, -6)));
        assertEquals(441, pedestrian.watched.size());
    }

    @Test void movementDropsDepartedPendingChunksAndRequestsTheNewFrontEdge() {
        MemoryWatches rider = new MemoryWatches(10);
        WatchWindow window = new WatchWindow(0, 0, 10);
        window.tick(0, 0, 10, true, java.util.Collections.emptyList(), rider);
        rider.watched.remove(key(-6, 0));
        rider.pending.add(key(-6, 0));
        window.tick(1, -1, 10, true, java.util.Collections.emptyList(), rider);

        assertFalse(rider.pending.contains(key(-6, 0)));
        assertFalse(rider.watched.contains(key(-6, 1)));
        assertTrue(rider.pending.contains(key(7, -7)));
        assertTrue(rider.watched.contains(key(0, 0)));
        assertEquals(169, rider.watched.size() + rider.pending.size());
    }

    @Test void restoreAddsOneRingPerSecondAndRetargetsServerDistanceOrReboarding() {
        MemoryWatches rider = new MemoryWatches(10);
        WatchWindow window = new WatchWindow(0, 0, 10);
        window.tick(0, 0, 10, true, java.util.Collections.emptyList(), rider);
        for (int tick = 1; tick < 20; tick++) window.tick(0, 0, 10, false, java.util.Collections.emptyList(), rider);
        assertEquals(6, window.radius());
        window.tick(0, 0, 10, false, java.util.Collections.emptyList(), rider);
        assertEquals(7, window.radius());
        assertEquals(56, rider.pending.size());
        window.tick(0, 0, 10, true, java.util.Collections.emptyList(), rider);
        assertEquals(6, window.radius());
        assertTrue(rider.pending.isEmpty());
        for (int tick = 0; tick < 40; tick++) window.tick(0, 0, 8, false, java.util.Collections.emptyList(), rider);
        assertEquals(8, window.radius());
        window.tick(0, 0, 4, true, java.util.Collections.emptyList(), rider);
        assertEquals(4, window.radius());
        assertEquals(81, rider.watched.size());
    }

    @Test void savedRouteWatchesTransferIntoAndOutOfTheSquareWithoutAnUnloadOrGeneration() {
        MemoryWatches rider = new MemoryWatches(6);
        long saved = key(8, 1), missing = key(9, 1);
        rider.saved.add(saved);
        WatchWindow window = new WatchWindow(0, 0, 6);
        window.resize(0, 0, 6, java.util.Arrays.asList(saved, missing), rider);
        assertTrue(rider.watched.contains(saved));
        assertFalse(rider.pending.contains(missing));
        assertFalse(rider.watched.contains(missing));
        assertTrue(window.contains(8, 1));
        window.resize(2, 0, 6, java.util.Collections.emptyList(), rider);
        assertTrue(rider.watched.contains(saved));
        assertFalse(rider.unloaded.contains(saved));
        window.resize(0, 0, 6, java.util.Collections.singleton(saved), rider);
        assertFalse(rider.unloaded.contains(saved));
        window.resize(0, 0, 6, java.util.Collections.emptyList(), rider);
        assertTrue(rider.unloaded.contains(saved));
        assertFalse(window.contains(8, 1));
    }

    private static long key(int x, int z) { return x & 0xffffffffL | (long) z << 32; }

    private static class MemoryWatches implements WatchWindow.Subscriptions {
        final Set<Long> watched = new HashSet<>();
        final Set<Long> saved = new HashSet<>(), unloaded = new HashSet<>();
        final Set<Long> pending = new HashSet<>();

        MemoryWatches(int radius) {
            for (int x = -radius; x <= radius; x++)
                for (int z = -radius; z <= radius; z++) watched.add(key(x, z));
        }

        public void resetPending() { pending.clear(); }
        public void unwatch(int x, int z) {
            if (watched.remove(key(x, z))) unloaded.add(key(x, z));
        }
        public boolean watchSaved(int x, int z) {
            if (!saved.contains(key(x, z))) return false;
            watched.add(key(x, z));
            return true;
        }
        public void ensure(int x, int z) { if (!watched.contains(key(x, z))) pending.add(key(x, z)); }
        public void submit() {}
    }
}
