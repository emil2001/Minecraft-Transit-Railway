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
        assertFalse(plan.chunks.contains(chunk(-6, 4)));
        assertEquals(chunk(0, 10), plan.chunks.get(plan.chunks.size() - 1).longValue());
        assertThrows(UnsupportedOperationException.class, plan.chunks::clear);
        assertThrows(UnsupportedOperationException.class, plan.passengers::clear);

        RoutePlans.observe(train, simulator, 240, 0.08, 0, 0, 251.32743026687666);
        assertSame(plan, RoutePlans.current(DIMENSION).get(0), "Repeated frames reuse the route geometry");
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
