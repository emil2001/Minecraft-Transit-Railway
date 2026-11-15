package org.mtr.gtnh;

import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.com.google.gson.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.mtr.gtnh.CoreRidingVehicleTestFixtures.*;

/** Standalone diagnostic benchmark, deliberately excluded from timed JUnit assertions. */
public final class CoreRidingVehicleBenchmark {
    private static volatile double blackhole;
    private CoreRidingVehicleBenchmark() {}

    /** Optional arguments: warmup operations, operations per round, rounds. Output is one JSON document. */
    public static void main(String[] arguments) {
        int warmup = arguments.length > 0 ? Integer.parseInt(arguments[0]) : 5;
        int iterations = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 20;
        int rounds = arguments.length > 2 ? Integer.parseInt(arguments[2]) : 5;
        require(warmup >= 0 && iterations > 0 && rounds > 0, "Invalid benchmark counts");
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpuTime = threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();
        JsonObject report = new JsonObject(); report.addProperty("benchmark", "detached-vehicle-pose");
        report.addProperty("javaVersion", System.getProperty("java.version"));
        report.addProperty("scope", "One four-car update; excludes Core simulation, world ticks, cabin authorization and network scheduling");
        report.addProperty("baseline", "VehicleUpdate JSON -> VehicleUpdate -> VehicleExtraData copy -> writePathCache -> Vehicle copy -> numeric poses");
        report.addProperty("compact", "capture once -> compact JSON -> numeric poses");
        report.addProperty("warmupOperationsPerCase", warmup); report.addProperty("operationsPerRound", iterations); report.addProperty("rounds", rounds);
        report.addProperty("timing", "Median and raw samples; alternating case order; no forced GC or timing thresholds");
        JsonArray routes = new JsonArray(); String previousCompact = null;
        for (int segments : new int[]{10, 100, 1000}) {
            CountingVehicle source = train(TransportMode.TRAIN, true, false, false, segments, 4, riders());
            require(source.vehicleExtraData.immutablePath.size() == segments, "Wrong fixture route length");
            source.calls = 0; Payload baseline = baselineProduce(source); require(source.calls == 1, "Old producer pose read count");
            source.calls = 0; Payload compact = compactProduce(source); require(source.calls == 1, "Compact producer pose read count");
            CoreRidingVehicle expected = baselineAccept(baseline.json), actual = CoreRidingVehicle.parse(compact.json);
            compareSnapshots(expected, actual); compareRealPose(source, actual);
            require(Double.doubleToLongBits(baseline.bounds) == Double.doubleToLongBits(compact.bounds), "Source culling bounds changed");
            require(previousCompact == null || previousCompact.equals(compact.json), "Compact payload grew or changed with the route");
            previousCompact = compact.json;
            Operation[] operations = {
                () -> checksum(baselineAccept(baseline.json)),
                () -> checksum(CoreRidingVehicle.parse(compact.json)),
                () -> { Payload value = baselineProduce(source); return value.bounds + checksum(baselineAccept(value.json)); },
                () -> { Payload value = compactProduce(source); return value.bounds + checksum(CoreRidingVehicle.parse(value.json)); }
            };
            for (Operation operation : operations) for (int index = 0; index < warmup; index++) blackhole = operation.run();
            Timing[] timings = {new Timing(rounds), new Timing(rounds), new Timing(rounds), new Timing(rounds)};
            for (int round = 0; round < rounds; round++) for (int position = 0; position < operations.length; position++) {
                int index = round % 2 == 0 ? position : operations.length - position - 1;
                measure(operations[index], timings[index], round, iterations, threads, cpuTime);
            }
            JsonObject route = new JsonObject(); route.addProperty("routeSegments", segments); route.addProperty("cars", 4);
            route.addProperty("baselinePayloadBytes", baseline.json.getBytes(StandardCharsets.UTF_8).length);
            route.addProperty("compactPayloadBytes", compact.json.getBytes(StandardCharsets.UTF_8).length);
            route.addProperty("exactGeometryEquivalent", true); route.addProperty("sourcePoseCallsPerProducer", 1);
            route.add("baselineAcceptance", timings[0].json(cpuTime)); route.add("compactAcceptance", timings[1].json(cpuTime));
            route.add("baselineProducerAndConsumer", timings[2].json(cpuTime)); route.add("compactProducerAndConsumer", timings[3].json(cpuTime));
            route.addProperty("acceptanceWallTimeRatio", median(timings[0].wall) / median(timings[1].wall));
            route.addProperty("producerAndConsumerWallTimeRatio", median(timings[2].wall) / median(timings[3].wall));
            routes.add(route);
        }
        report.add("routes", routes); report.addProperty("compactPayloadIndependentOfRouteLength", true);
        report.addProperty("blackhole", blackhole);
        System.out.println(report);
    }

    private static Payload baselineProduce(Vehicle source) {
        Bounds bounds = new Bounds();
        source.getVehicleCarsAndPositions().forEach(car -> car.right().forEach(pair -> {
            bounds.include(pair.left().x, pair.left().y, pair.left().z);
            bounds.include(pair.right().x, pair.right().y, pair.right().z);
        }));
        final boolean[] riding = {false};
        source.vehicleExtraData.iterateRidingEntities(rider -> { if (RIDER.equals(rider.uuid)) riding[0] = true; });
        return new Payload(baselinePayload(source), bounds.checksum() + (riding[0] ? 1 : 0));
    }

    private static Payload compactProduce(Vehicle source) {
        CoreRidingVehicle pose = CoreRidingVehicle.capture(source);
        Bounds bounds = new Bounds();
        for (CoreRidingVehicle.Car car : pose.cars) for (CoreRidingVehicle.Bogie bogie : car.bogies) {
            bounds.include(bogie.x1, bogie.y1, bogie.z1); bounds.include(bogie.x2, bogie.y2, bogie.z2);
        }
        return new Payload(pose.toJson(), bounds.checksum() + (pose.hasRider(RIDER) ? 1 : 0));
    }

    private static double checksum(CoreRidingVehicle pose) {
        double result = pose.siding + pose.depot + pose.mode.ordinal() + (pose.moving ? 1 : 0);
        for (CoreRidingVehicle.Car car : pose.cars) {
            result += car.width + car.length + car.pivot + car.vehicleId.hashCode();
            for (CoreRidingVehicle.Bogie bogie : car.bogies) result += bogie.x1 + bogie.y1 + bogie.z1 + bogie.x2 + bogie.y2 + bogie.z2;
        }
        for (CoreRidingVehicle.Seat rider : pose.riders) result += rider.uuid.hashCode() + rider.car + (rider.gangway ? 1 : 0);
        return result;
    }

    private static void measure(Operation operation, Timing timing, int round, int iterations, ThreadMXBean threads, boolean cpuTime) {
        double result = 0;
        long startCpu = cpuTime ? threads.getCurrentThreadCpuTime() : 0, startWall = System.nanoTime();
        for (int index = 0; index < iterations; index++) result += operation.run();
        timing.wall[round] = (System.nanoTime() - startWall) / (double) iterations;
        timing.cpu[round] = cpuTime ? (threads.getCurrentThreadCpuTime() - startCpu) / (double) iterations : 0;
        blackhole = result;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone(); Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }
    private interface Operation { double run(); }
    private static final class Payload {
        final String json; final double bounds;
        Payload(String json, double bounds) { this.json = json; this.bounds = bounds; }
    }
    private static final class Bounds {
        final long[] low = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE}, high = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        void include(double x, double y, double z) {
            double[] coordinates = {x, y, z};
            for (int axis = 0; axis < 3; axis++) {
                require(Double.isFinite(coordinates[axis]), "Non-finite source bounds");
                low[axis] = Math.min(low[axis], (long) Math.floor(coordinates[axis]));
                high[axis] = Math.max(high[axis], (long) Math.ceil(coordinates[axis]));
            }
        }
        double checksum() { double value = 0; for (int axis = 0; axis < 3; axis++) value += (axis + 1) * (low[axis] + high[axis]); return value; }
    }
    private static final class Timing {
        final double[] wall, cpu;
        Timing(int rounds) { wall = new double[rounds]; cpu = new double[rounds]; }
        JsonObject json(boolean cpuTime) {
            JsonObject value = new JsonObject(); value.addProperty("medianWallNanosecondsPerUpdate", median(wall));
            JsonArray samples = new JsonArray(); for (double sample : wall) samples.add(sample); value.add("wallNanosecondsPerUpdate", samples);
            if (cpuTime) {
                value.addProperty("medianCpuNanosecondsPerUpdate", median(cpu));
                samples = new JsonArray(); for (double sample : cpu) samples.add(sample); value.add("cpuNanosecondsPerUpdate", samples);
            }
            return value;
        }
    }
}
