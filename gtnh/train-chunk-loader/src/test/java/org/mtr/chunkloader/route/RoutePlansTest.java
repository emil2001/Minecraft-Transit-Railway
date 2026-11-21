package org.mtr.chunkloader.route;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.Data;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RoutePlansTest {
    static final String DIMENSION = "minecraft/overworld";
    static final UUID RIDER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    @TempDir Path files;

    @AfterEach void cleanUp() { RoutePlans.clear(); }

    @Test void samplesTheMovingFrontAlongTheRailCurveAcrossNegativeChunks() {
        Simulator simulator = start();
        Vehicle train = train(simulator, TransportMode.TRAIN, 0);
        observe(train, simulator, 0.08);
        RoutePlans.Plan plan = RoutePlans.current(DIMENSION).get(0);
        assertEquals(77L, plan.id);
        assertEquals(Collections.singleton(RIDER), plan.passengers);
        assertEquals(chunk(-10, 0), plan.chunks.get(0).longValue());
        assertTrue(plan.chunks.contains(chunk(-9, 5)), "The middle of this quarter-turn leaves the diagonal chord");
        // The arc enters (-7,8) for just 0.20 blocks between the ordinary samples at 144 and 148.
        int beforeCorner = plan.chunks.indexOf(chunk(-7, 7));
        assertTrue(beforeCorner >= 0);
        assertEquals(Arrays.asList(chunk(-7, 7), chunk(-7, 8), chunk(-6, 8)),
                plan.chunks.subList(beforeCorner, beforeCorner + 3));
        assertFalse(plan.chunks.contains(chunk(-6, 4)));
        assertEquals(chunk(0, 10), plan.chunks.get(plan.chunks.size() - 1).longValue());
        assertThrows(UnsupportedOperationException.class, plan.chunks::clear);
        assertThrows(UnsupportedOperationException.class, plan.passengers::clear);

        RoutePlans.observe(train, simulator, 240, 0.08, 0, 0, 251.32743026687666);
        assertSame(plan, RoutePlans.current(DIMENSION).get(0), "Repeated frames reuse the route geometry");
    }

    @Test void coversAnExcursionOnAShortRailWithoutBridgingADiscontinuousJoin() {
        PathData base = new PathData(new JsonReader(new JsonObject()));
        PathData curve = new PathData(base, 0, Math.PI) {
            @Override public Vector getPosition(double distance) {
                return new Vector(15.8 + Math.sin(distance), 64, 34.5 + Math.cos(distance));
            }
        };
        PathData next = new PathData(base, Math.PI, Math.PI + 0.5) {
            @Override public Vector getPosition(double distance) {
                return new Vector(80.5 + distance, 64, 8.5);
            }
        };
        PathData inactive = new PathData(base, Math.PI, Math.PI) {
            @Override public Vector getPosition(double distance) { throw new AssertionError("Zero-duration rail"); }
        };
        List<PathData> path = Arrays.asList(curve, inactive, next);
        List<Long> expected = Arrays.asList(chunk(0, 2), chunk(1, 2), chunk(5, 0));
        assertEquals(expected, new ArrayList<>(RoutePlans.chunksAhead(path, 0, 0.08, 0, 0, next.getEndDistance())));
        assertEquals(expected, new ArrayList<>(RoutePlans.chunksAhead(path, 0, 0.08, 0, 0, next.getStartDistance())));
    }

    @Test void aStraightRailOnAChunkEdgeStaysInItsRowWithOnePositionReadPerStep() {
        int[] positionReads = {0};
        PathData base = new PathData(new JsonReader(new JsonObject()));
        PathData edge = new PathData(base, 0, 64) {
            @Override public Vector getPosition(double distance) {
                positionReads[0]++;
                return new Vector(16, 64, distance);
            }
        };
        assertEquals(Arrays.asList(chunk(1, 0), chunk(1, 1), chunk(1, 2), chunk(1, 3), chunk(1, 4)),
                new ArrayList<>(RoutePlans.chunksAhead(Collections.singletonList(edge), 0, 0.08, 0, 0, 64)));
        assertEquals(17, positionReads[0]);
    }

    @Test void includesTheChunkOwnedByTheExactCornerOnAnUncachedRail() {
        double length = Math.hypot(32, 32);
        PathData edge = new PathData(null, 0, 0, 0, 0, length,
                new Position(0, 64, 31), Angle.E, new Position(32, 64, -1), Angle.W);
        List<Long> chunks = new ArrayList<>(RoutePlans.chunksAhead(
                Collections.singletonList(edge), 0, 0.08, 0, 0, length));
        // The fallback straight line passes from (0,1) to (1,0) through (16,16), which belongs to (1,1).
        assertEquals(chunk(0, 1), chunks.get(0).longValue());
        int corner = chunks.indexOf(chunk(1, 1));
        assertTrue(corner > 0 && corner < chunks.indexOf(chunk(1, 0)));
        assertEquals(chunk(2, -1), chunks.get(chunks.size() - 1).longValue());
    }

    @Test void keepsBothSidesOfAnInternalMtrCurveJumpWithoutConnectingThem() {
        Position start = new Position(102, 64, 124), end = new Position(2, 64, 168);
        Rail rail = Rail.newRail(start, Angle.SSE, end, Angle.SSE, Rail.Shape.QUADRATIC, 0,
                new ObjectArrayList<>(), 300, 300, false, false, true, false, false, TransportMode.TRAIN);
        PathData raw = new PathData(rail, 0, 0, 0, start, end);
        PathData path = new PathData(raw, 0, raw.getRailLength());
        // RailMath jumps 54 blocks between its two arcs near progress 171.26.
        assertEquals(Arrays.asList(chunk(6, 7), chunk(3, 9)), new ArrayList<>(
                RoutePlans.chunksAhead(Collections.singletonList(path), 171.25, 0.08, 0, 0, 171.27)));
    }

    @Test void visitsTheLandingChunkBeforeItsNeighbourAfterAnInternalJump() {
        Position start = new Position(6, 32, -41), end = new Position(13, 22, -70);
        Rail rail = Rail.newRail(start, Angle.NW, end, Angle.NW, Rail.Shape.TWO_RADII, 0,
                new ObjectArrayList<>(), 300, 300, false, false, true, false, false, TransportMode.TRAIN);
        PathData raw = new PathData(rail, 0, 0, 0, start, end);
        PathData path = new PathData(raw, 0, raw.getRailLength());
        // RailMath jumps into (0,-5), then crosses x=16 at progress 17.8979.
        List<Long> chunks = new ArrayList<>(RoutePlans.chunksAhead(
                Collections.singletonList(path), 7.383503861946, 0.08, 0, 0, path.getEndDistance()));
        int beforeJump = chunks.indexOf(chunk(0, -4));
        assertTrue(beforeJump >= 0);
        assertEquals(Arrays.asList(chunk(0, -4), chunk(0, -5), chunk(1, -5)),
                chunks.subList(beforeJump, beforeJump + 3));
    }

    @Test void sameVehicleIdOnAnotherSidingReplacesThePlanImmediately() {
        Simulator simulator = start();
        observe(train(simulator, TransportMode.TRAIN, 0), simulator, 0.08);
        RoutePlans.Plan first = RoutePlans.current(DIMENSION).get(0);
        observe(train(simulator, TransportMode.TRAIN, 0, false, 82), simulator, 0.08);
        RoutePlans.Plan second = RoutePlans.current(DIMENSION).get(0);
        assertNotSame(first, second);
        assertEquals(first.id, second.id);
    }

    @Test void dropsTheLastRiderAndSlowTrainsImmediatelyAndIgnoresOtherModes() {
        Simulator simulator = start();
        Vehicle train = train(simulator, TransportMode.TRAIN, 0);
        observe(train, simulator, 0.01);
        assertEquals(1, RoutePlans.current(DIMENSION).size());
        observe(train, simulator, 0.009);
        assertTrue(RoutePlans.current(DIMENSION).isEmpty());
        observe(train, simulator, 0.02);
        assertEquals(1, RoutePlans.current(DIMENSION).size());

        JsonObject dismounted = new JsonObject();
        dismounted.add("ridingEntities", new JsonArray());
        train.vehicleExtraData.updateData(new JsonReader(dismounted));
        observe(train, simulator, 0.02);
        assertTrue(RoutePlans.current(DIMENSION).isEmpty());
        observe(train(simulator, TransportMode.TRAIN, -1), simulator, 0.08);
        observe(train(simulator, TransportMode.BOAT, 0), simulator, 0.08);
        observe(train(new Data() {}, TransportMode.TRAIN, 0), new Data() {}, 0.08);
        assertTrue(RoutePlans.current(DIMENSION).isEmpty());
    }

    @Test void smallSpeedFluctuationsDoNotRepeatedlySwitchTheWatchRadius() {
        Simulator simulator = start();
        Vehicle train = train(simulator, TransportMode.TRAIN, 0);
        observe(train, simulator, 35.5 / 3600);
        assertTrue(RoutePlans.current(DIMENSION).isEmpty());
        observe(train, simulator, 36.1 / 3600);
        observe(train, simulator, 35.5 / 3600);
        assertEquals(1, RoutePlans.current(DIMENSION).size());
        observe(train, simulator, 32.9 / 3600);
        assertTrue(RoutePlans.current(DIMENSION).isEmpty());
    }

    @Test void brokenCustomRailGeometryLeavesTheRiderOnNormalChunkLoading() {
        Simulator simulator = start();
        Vehicle template = train(simulator, TransportMode.TRAIN, 0);
        PathData original = template.vehicleExtraData.immutablePath.get(0);
        for (int failure = 0; failure < 3; failure++) {
            final int kind = failure;
            PathData customRail = new PathData(original, 0, original.getEndDistance()) {
                @Override public Vector getPosition(double distance) {
                    if (distance == 0) return new Vector(5, 64, 5);
                    if (kind == 0) return null;
                    if (kind == 1) return new Vector(Double.NaN, 64, 5);
                    throw new IllegalStateException("Unsupported rail geometry");
                }
            };
            VehicleExtraData extra = VehicleExtraData.create(1, 2, 10, new ObjectArrayList<>(),
                    new ObjectArrayList<>(), new ObjectArrayList<>(), new ObjectArrayList<>(), customRail,
                    false, 0.00001, 0.00001, false, 0.08, 0);
            JsonObject riders = new JsonObject();
            riders.add("ridingEntities", Utilities.getJsonObjectFromData(template.vehicleExtraData).get("ridingEntities"));
            extra.updateData(new JsonReader(riders));
            Vehicle custom = new Vehicle(extra, null, TransportMode.TRAIN, simulator);
            observe(custom, simulator, 0.08);
            assertTrue(RoutePlans.current(DIMENSION).isEmpty());
        }
    }

    @Test void samplesIntoTheRepeatSegmentAcrossARouteWrap() {
        Simulator simulator = start();
        Vehicle train = train(simulator, TransportMode.TRAIN, 0, true);
        RoutePlans.observe(train, simulator, 240, 0.08, 0, 1, 251.32743026687666);
        List<Long> chunks = RoutePlans.current(DIMENSION).get(0).chunks;
        assertEquals(chunk(-1, 10), chunks.get(0).longValue());
        assertEquals(chunk(0, 10), chunks.get(1).longValue(), "Sample the real rail endpoint before the loop sentinel");
        assertFalse(chunks.contains(chunk(0, 0)), "The sentinel is outside the route");
        assertTrue(chunks.indexOf(chunk(-9, 5)) > 1);
    }

    @Test void includesTheRestartPointWhenTheLookaheadEndsExactlyAtTheWrap() {
        List<PathData> path = train(start(), TransportMode.TRAIN, 0, true).vehicleExtraData.immutablePath;
        double end = path.get(0).getEndDistance();
        double ahead = 158; // 128 blocks plus three seconds at 0.01 blocks per millisecond.
        assertFalse(RoutePlans.chunksAhead(path, Math.nextDown(end) - ahead, 0.01, 0, 1, end)
                .contains(chunk(-10, 0)));
        List<Long> chunks = new ArrayList<>(RoutePlans.chunksAhead(path, end - ahead, 0.01, 0, 1, end));
        assertEquals(chunk(0, 10), chunks.get(chunks.size() - 2).longValue());
        assertEquals(chunk(-10, 0), chunks.get(chunks.size() - 1).longValue());
    }

    @Test void oldSimulatorsCannotRepopulateAReplacedUnloadedOrStoppedWorld() {
        Simulator old = start();
        Vehicle oldTrain = train(old, TransportMode.TRAIN, 0);
        observe(oldTrain, old, 0.02);
        assertEquals(1, RoutePlans.current(DIMENSION).size());
        Simulator next = start();
        observe(oldTrain, old, 0.02);
        assertTrue(RoutePlans.current(DIMENSION).isEmpty());
        Vehicle nextTrain = train(next, TransportMode.TRAIN, 0);
        observe(nextTrain, next, 0.02);
        RoutePlans.simulatorClosed(old);
        assertEquals(1, RoutePlans.current(DIMENSION).size());
        RoutePlans.clear(DIMENSION);
        observe(nextTrain, next, 0.02);
        assertTrue(RoutePlans.current(DIMENSION).isEmpty());
        RoutePlans.simulatorClosed(next);
        next = start();
        nextTrain = train(next, TransportMode.TRAIN, 0);
        observe(nextTrain, next, 0.02);
        assertTrue(RoutePlans.current(DIMENSION).isEmpty(), "An unloaded dimension stays suspended across a simulator restart");
        RoutePlans.worldLoaded(DIMENSION);
        observe(nextTrain, next, 0.02);
        assertEquals(1, RoutePlans.current(DIMENSION).size());
        RoutePlans.clear();
        observe(nextTrain, next, 0.02);
        assertTrue(RoutePlans.current(DIMENSION).isEmpty());
    }

    @Test void expiresAVehicleThatDisappearedWithoutAnotherSimulationFrame() {
        Simulator simulator = start();
        observe(train(simulator, TransportMode.TRAIN, 0), simulator, 0.02);
        assertEquals(1, RoutePlans.current(DIMENSION).size());
        assertTrue(RoutePlans.current(DIMENSION, System.nanoTime() + TimeUnit.SECONDS.toNanos(3)).isEmpty());
        assertTrue(RoutePlans.current(DIMENSION).isEmpty());
    }

    Simulator start() {
        Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, files, false);
        RoutePlans.simulatorOpened(simulator);
        return simulator;
    }

    static void observe(Vehicle vehicle, Data data, double speed) {
        RoutePlans.observe(vehicle, data, 0, speed, 0, 0, 251.32743026687666);
    }

    static Vehicle train(Data data, TransportMode mode, int riderCar) {
        return train(data, mode, riderCar, false);
    }

    static Vehicle train(Data data, TransportMode mode, int riderCar, boolean looping) {
        return train(data, mode, riderCar, looping, 81);
    }

    static Vehicle train(Data data, TransportMode mode, int riderCar, boolean looping, long siding) {
        Position start = new Position(-160, 64, 0);
        Position end = new Position(0, 64, 160);
        Rail rail = Rail.newRail(start, Angle.S, end, Angle.W, Rail.Shape.QUADRATIC, 0,
                new ObjectArrayList<>(), 300, 300, false, false, true, false, false, mode);
        PathData raw = new PathData(rail, 0, 0, 0, start, end);
        JsonArray path = new JsonArray();
        path.add(Utilities.getJsonObjectFromData(new PathData(raw, 0, raw.getRailLength())));
        if (looping) {
            // VehicleExtraData.createPathData appends this blank sentinel to a repeating route.
            PathData sentinel = new PathData(new JsonReader(new JsonObject()));
            path.add(Utilities.getJsonObjectFromData(new PathData(sentinel, raw.getRailLength(), Double.MAX_VALUE)));
        }
        JsonArray riders = new JsonArray();
        riders.add(Utilities.getJsonObjectFromData(new VehicleRidingEntity(
                RIDER, riderCar, 0, 0, 0, false, false, false, false, false, false, false)));
        JsonObject extraJson = new JsonObject();
        extraJson.addProperty("sidingId", siding);
        extraJson.add("path", path);
        extraJson.add("ridingEntities", riders);
        extraJson.addProperty("totalDistance", raw.getRailLength());
        VehicleExtraData extra = new VehicleExtraData(new JsonReader(extraJson));
        PathData.writePathCache(extra.immutablePath, data, mode);
        JsonObject state = new JsonObject();
        state.addProperty("id", 77);
        state.addProperty("transportMode", mode.name());
        state.addProperty("railProgress", 16);
        state.addProperty("speed", 0.08);
        return new Vehicle(extra, null, new JsonReader(state), data);
    }

    static long chunk(int x, int z) { return ((long) z << 32) | (x & 0xffffffffL); }
}
