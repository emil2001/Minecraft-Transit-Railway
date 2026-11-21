package org.mtr.chunkloader.route;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** The simulator owns all MTR reads. The Minecraft tick consumes detached chunk coordinates and UUIDs. */
public final class RoutePlans {
    // MTR measures blocks per millisecond. Separate thresholds avoid repeatedly rebuilding player watches.
    private static final double ENTER_SPEED = 36.0 / 3600;
    private static final double EXIT_SPEED = 33.0 / 3600;
    private static final int MAX_AHEAD = 256;
    private static final int SAMPLE_DISTANCE = 4;
    private static final double MIN_INTERVAL = 0.0625;
    private static final double GEOMETRY_EPSILON = 0.00001;
    private static final long SAMPLE_INTERVAL = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long MAX_AGE = TimeUnit.SECONDS.toNanos(2);
    private static final ConcurrentMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Riders> RIDERS = ThreadLocal.withInitial(Riders::new);

    private RoutePlans() {}

    public static final class Plan {
        public final long id;
        public final Set<UUID> passengers;
        /** Rail chunks in forward order, including possible grazes at boundaries. Low 32 bits X, high 32 bits Z. */
        public final List<Long> chunks;
        private final long sampledAt, sidingId;
        // Identity only. All reads of simulator-owned state stay on its own thread.
        private final Simulator simulator;

        private Plan(Simulator simulator, long sidingId, long id, Set<UUID> passengers, Set<Long> chunks, long sampledAt) {
            this.simulator = simulator;
            this.sidingId = sidingId;
            this.id = id;
            this.passengers = Collections.unmodifiableSet(new HashSet<>(passengers));
            this.chunks = Collections.unmodifiableList(new ArrayList<>(chunks));
            this.sampledAt = sampledAt;
        }

        public boolean authorizes(EntityPlayerMP player, WorldServer world) {
            return passengers.contains(player.getUniqueID())
                    && RiderAuthorization.matches(player, world, simulator, sidingId, id);
        }
    }

    private static final class Session {
        final Simulator simulator;
        final boolean loaded;
        final ConcurrentMap<Long, Plan> plans = new ConcurrentHashMap<>();

        Session(Simulator simulator, boolean loaded) {
            this.simulator = simulator;
            this.loaded = loaded;
        }
    }

    private static final class Riders implements Consumer<VehicleRidingEntity> {
        final Set<UUID> ids = new HashSet<>();

        @Override public void accept(VehicleRidingEntity entity) {
            if (entity.isOnVehicle()) ids.add(entity.uuid);
        }
    }

    public static void simulatorOpened(Simulator simulator) {
        SESSIONS.compute(simulator.dimension, (dimension, old) ->
                new Session(simulator, old == null || old.loaded));
    }

    public static void simulatorClosed(Simulator simulator) {
        SESSIONS.computeIfPresent(simulator.dimension, (dimension, old) ->
                old.simulator == simulator ? new Session(null, old.loaded) : old);
    }

    public static void worldLoaded(String dimension) {
        SESSIONS.computeIfPresent(dimension, (key, old) -> old.loaded ? old : new Session(old.simulator, true));
    }

    public static void clear(String dimension) {
        SESSIONS.compute(dimension, (key, old) -> new Session(old == null ? null : old.simulator, false));
    }

    public static void clear() {
        SESSIONS.clear();
    }

    public static List<Plan> current(String dimension) {
        return current(dimension, System.nanoTime());
    }

    static List<Plan> current(String dimension, long now) {
        Session session = SESSIONS.get(dimension);
        if (session == null || session.plans.isEmpty()) return Collections.emptyList();
        List<Plan> result = new ArrayList<>();
        session.plans.forEach((id, plan) -> {
            if (now - plan.sampledAt >= MAX_AGE) session.plans.remove(id, plan);
            else if (!plan.chunks.isEmpty()) result.add(plan);
        });
        return Collections.unmodifiableList(result);
    }

    /** Called after the real Vehicle.simulate frame has updated motion and pruned riders. */
    public static void observe(Vehicle vehicle, Data data, double progress, double speed,
                               int repeatIndex, int repeatLimit, double routeEnd) {
        if (!(data instanceof Simulator) || vehicle.getTransportMode() != TransportMode.TRAIN) return;
        Session session = SESSIONS.get(((Simulator) data).dimension);
        if (session == null || session.simulator != data || !session.loaded) return;
        long id = vehicle.getId();
        Plan previous = session.plans.isEmpty() ? null : session.plans.get(id);
        long sidingId = vehicle.vehicleExtraData.getSidingId();
        boolean sameTrain = previous != null && previous.sidingId == sidingId;
        double minSpeed = sameTrain && !previous.chunks.isEmpty() ? EXIT_SPEED : ENTER_SPEED;
        if (!(speed >= minSpeed) || !Double.isFinite(speed) || !Double.isFinite(progress)) {
            if (previous != null) session.plans.remove(id);
            return;
        }
        Riders riders = RIDERS.get();
        riders.ids.clear();
        vehicle.vehicleExtraData.iterateRidingEntities(riders);
        if (riders.ids.isEmpty()) {
            if (previous != null) session.plans.remove(id);
            return;
        }

        long now = System.nanoTime();
        if (sameTrain && now - previous.sampledAt < SAMPLE_INTERVAL && previous.passengers.equals(riders.ids)) return;
        Set<Long> chunks = chunksAhead(vehicle.vehicleExtraData.immutablePath, progress, speed, repeatIndex, repeatLimit, routeEnd);
        // An unusable route is retried at the normal sampling interval and never reduces the rider's view.
        session.plans.put(id, new Plan(session.simulator, sidingId, id, riders.ids, chunks, now));
    }

    static Set<Long> chunksAhead(List<PathData> path, double progress, double speed,
                                 int repeatIndex, int repeatLimit, double routeEnd) {
        // Repeating routes end with a dummy segment at the world origin. It has no traversable rail.
        if (repeatLimit > 0 && repeatLimit < path.size()) path = path.subList(0, repeatLimit);
        if (path.isEmpty() || !Double.isFinite(routeEnd)) return Collections.emptySet();
        double end = Math.min(routeEnd, path.get(path.size() - 1).getEndDistance());
        double ahead = Math.min(MAX_AHEAD, 128 + 3000 * speed);
        Set<Long> chunks = new LinkedHashSet<>();
        if (!sample(path, progress, Math.min(progress + ahead, end), chunks)) return Collections.emptySet();
        if (repeatLimit > 0 && progress <= end && progress + ahead > end && repeatIndex >= 0 && repeatIndex < path.size()) {
            double restart = path.get(repeatIndex).getStartDistance();
            if (!sample(path, restart, Math.min(end, restart + (progress + ahead - end)), chunks)) return Collections.emptySet();
        }
        return chunks;
    }

    private static boolean sample(List<PathData> path, double start, double end, Set<Long> chunks) {
        if (!Double.isFinite(start) || !(end >= start) || end - start > MAX_AHEAD + 1) return false;
        int index = Utilities.getIndexFromConditionalList(path, start);
        if (index < 0) return false;
        // Start fresh on every rail, including rails shorter than a step and discontinuous route joins.
        for (; index < path.size(); index++) {
            PathData rail = path.get(index);
            double railStart = rail.getStartDistance();
            double nextStart = index + 1 < path.size() ? path.get(index + 1).getStartDistance() : Double.POSITIVE_INFINITY;
            if (nextStart == start) continue; // A later rail owns this progress, even for zero-length records.
            double stop = Math.min(end, nextStart);
            if (!Double.isFinite(railStart) || !(railStart <= start && start <= stop && stop <= rail.getEndDistance())) return false;
            try {
                if (!sampleRail(rail, start - railStart, stop - railStart, chunks)) return false;
            } catch (RuntimeException invalidRail) {
                return false;
            }
            if (end < nextStart) return true;
            start = stop;
        }
        return false;
    }

    private static boolean sampleRail(PathData rail, double start, double end, Set<Long> chunks) {
        Vector from = rail.getPosition(start);
        if (!valid(from)) return false;
        chunks.add(chunk(chunkIndex(from.x), chunkIndex(from.z)));
        int steps = (int) Math.ceil((end - start) / SAMPLE_DISTANCE);
        double distance = start;
        for (int step = 1; step <= steps; step++) {
            double next = Math.min(start + step * (double) SAMPLE_DISTANCE, end);
            Vector to = rail.getPosition(next);
            if (!valid(to) || !coverInterval(rail, distance, from, next, to, chunks)) return false;
            from = to;
            distance = next;
        }
        return true;
    }

    private static boolean coverInterval(PathData rail, double start, Vector from, double end, Vector to, Set<Long> chunks) {
        double span = end - start;
        double dx = to.x - from.x, dz = to.z - from.z;
        long last = chunk(chunkIndex(to.x), chunkIndex(to.z));
        // Travelling the entire distance on one axis leaves no distance for a lateral excursion.
        if ((dx == 0 && Math.abs(dz) == span) || (dz == 0 && Math.abs(dx) == span)) {
            chunks.add(last);
            return true;
        }

        // RailMath joins at most two horizontal arcs/lines. At its internal join some valid rails
        // jump. Every position on either side is within one interval's travel of its own endpoint.
        long first = chunk(chunkIndex(from.x), chunkIndex(from.z));
        double reach = span + GEOMETRY_EPSILON;
        boolean collect = span <= MIN_INTERVAL;
        if (!coverReach(from, reach, first, last, chunks, collect) || !coverReach(to, reach, first, last, chunks, collect)) {
            double middle = start + span / 2;
            if (middle <= start || middle >= end) return false;
            Vector point = rail.getPosition(middle);
            return valid(point) && coverInterval(rail, start, from, middle, point, chunks)
                    && coverInterval(rail, middle, point, end, to, chunks);
        }
        chunks.add(last);
        return true;
    }

    // Check the endpoint's reach, adding previously unseen candidates only at the refinement limit.
    private static boolean coverReach(Vector point, double reach, long first, long last, Set<Long> chunks, boolean collect) {
        int x = chunkIndex(point.x), z = chunkIndex(point.z);
        int sideX = chunkIndex(point.x - reach), sideZ = chunkIndex(point.z - reach);
        if (sideX == x) sideX = chunkIndex(point.x + reach);
        if (sideZ == z) sideZ = chunkIndex(point.z + reach);
        // A four-block reach can cross at most one boundary per axis. Visit the side cells before
        // the diagonal one, including Minecraft's floor-selected chunk on an exact corner crossing.
        if (sideX != x && !candidate(chunk(sideX, z), first, last, chunks, collect)) return false;
        if (sideZ != z && !candidate(chunk(x, sideZ), first, last, chunks, collect)) return false;
        if (sideX != x && sideZ != z) {
            double dx = 16.0 * Math.max(x, sideX) - point.x;
            double dz = 16.0 * Math.max(z, sideZ) - point.z;
            if (dx * dx + dz * dz <= reach * reach)
                return candidate(chunk(sideX, sideZ), first, last, chunks, collect);
        }
        return true;
    }

    private static boolean candidate(long key, long first, long last, Set<Long> chunks, boolean collect) {
        if (key == first || key == last || chunks.contains(key)) return true;
        if (!collect) return false;
        chunks.add(key);
        return true;
    }

    private static boolean valid(Vector point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.z);
    }

    private static int chunkIndex(double coordinate) { return (int) Math.floor(coordinate / 16); }

    private static long chunk(long x, long z) { return (z << 32) | (x & 0xffffffffL); }
}
