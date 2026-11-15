package org.mtr.gtnh;

import org.mtr.core.data.*;
import org.mtr.core.operation.VehicleUpdate;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.UUID;

/** Actual Core fixtures shared by regression tests and the standalone benchmark. */
final class CoreRidingVehicleTestFixtures {
    static final UUID RIDER = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private CoreRidingVehicleTestFixtures() {}

    static CountingVehicle train(TransportMode mode, boolean curved, boolean reversed, boolean oneBogie, int segments, int carCount, JsonArray riders) {
        return train(new Data() {}, mode, curved, reversed, oneBogie, segments, carCount, riders);
    }

    static CountingVehicle train(Data data, TransportMode mode, boolean curved, boolean reversed, boolean oneBogie, int segments, int carCount, JsonArray riders) {
        JsonArray paths = new JsonArray();
        double distance = 0;
        for (int index = 0; index < segments; index++) {
            Position first = new Position(curved && index % 2 != 0 ? 120 : 0, curved && index % 2 != 0 ? 72 : 64, 300L * index);
            Position last = new Position(curved && index % 2 == 0 ? 120 : 0, curved && index % 2 == 0 ? 72 : 64, 300L * (index + 1));
            Rail rail = Rail.newRail(first, Angle.S, last, Angle.N, Rail.Shape.QUADRATIC, 0,
                new ObjectArrayList<>(), 80, 80, true, false, false, false, false, mode);
            PathData segment = new PathData(rail, 51, 0, 0, first, last);
            double end = distance + segment.getRailLength();
            paths.add(Utilities.getJsonObjectFromData(new PathData(segment, distance, end)));
            distance = end;
        }
        JsonObject extra = new JsonObject(); extra.addProperty("sidingId", 51); extra.addProperty("depotId", 52);
        extra.addProperty("totalVehicleLength", 20.4 * carCount); extra.add("path", paths); extra.add("ridingEntities", riders);
        JsonArray cars = new JsonArray();
        for (int index = 0; index < carCount; index++) cars.add(Utilities.getJsonObjectFromData(
            new VehicleCar("test_model_" + index, 20.4, 3, oneBogie ? .5 : -7.5, oneBogie ? .5 : 8.5, 0, 0)));
        extra.add("vehicleCars", cars);
        VehicleExtraData details = new VehicleExtraData(new JsonReader(extra)); PathData.writePathCache(details.immutablePath, data, mode);
        JsonObject definition = new JsonObject(); definition.addProperty("id", 77); definition.addProperty("transportMode", mode.name());
        definition.addProperty("railProgress", 180); definition.addProperty("reversed", reversed);
        Siding seed = new Siding(new Position(0, 64, 0), new Position(0, 64, 300), 300, mode, data);
        JsonObject sidingJson = Utilities.getJsonObjectFromData(seed); sidingJson.addProperty("id", 51);
        Siding siding = new Siding(new JsonReader(sidingJson), data);
        return new CountingVehicle(details, siding, new JsonReader(definition), data);
    }

    static JsonArray riders() {
        JsonArray rows = new JsonArray();
        rows.add(Utilities.getJsonObjectFromData(new VehicleRidingEntity(RIDER, 0, 0, 0, 0, false, false, false, false, false, false, false)));
        return rows;
    }

    static String baselinePayload(Vehicle source) {
        return Utilities.getJsonObjectFromData(new VehicleUpdate(source, source.vehicleExtraData)).toString();
    }

    /** The previous acceptance branch, ending at the same numeric pose boundary as the new codec. */
    static CoreRidingVehicle baselineAccept(String json) {
        VehicleUpdate update = new VehicleUpdate(new JsonReader(Utilities.parseJson(json)));
        Data data = new Data() {};
        VehicleExtraData extra = new VehicleExtraData(new JsonReader(Utilities.getJsonObjectFromData(update.getVehicleExtraData())));
        PathData.writePathCache(extra.immutablePath, data, update.getVehicle().getTransportMode());
        Vehicle copy = new Vehicle(extra, null, new JsonReader(Utilities.getJsonObjectFromData(update.getVehicle())), data);
        return CoreRidingVehicle.capture(copy);
    }

    static void compareRealPose(Vehicle source, CoreRidingVehicle snapshot) {
        final int[] carIndex = {0};
        source.getVehicleCarsAndPositions().forEach(entry -> {
            CoreRidingVehicle.Car car = snapshot.cars.get(carIndex[0]++);
            require(car.vehicleId.equals(entry.left().getVehicleId()), "model id");
            same(entry.left().getWidth(), car.width); same(entry.left().getLength(), car.length);
            same((entry.left().getBogie1Position() + entry.left().getBogie2Position()) / 2, car.pivot);
            require(car.bogies.size() == entry.right().size(), "bogie count");
            final int[] bogieIndex = {0};
            entry.right().forEach(pair -> {
                CoreRidingVehicle.Bogie bogie = car.bogies.get(bogieIndex[0]++);
                Vector first = pair.left(), second = pair.right();
                same(first.x, bogie.x1); same(first.y, bogie.y1); same(first.z, bogie.z1);
                same(second.x, bogie.x2); same(second.y, bogie.y2); same(second.z, bogie.z2);
            });
        });
        require(carIndex[0] == snapshot.cars.size(), "car count");
    }

    static void compareSnapshots(CoreRidingVehicle expected, CoreRidingVehicle actual) {
        require(expected.mode == actual.mode && expected.siding == actual.siding && expected.depot == actual.depot
            && expected.moving == actual.moving, "vehicle metadata");
        require(expected.cars.size() == actual.cars.size(), "car count");
        for (int index = 0; index < expected.cars.size(); index++) {
            CoreRidingVehicle.Car first = expected.cars.get(index), second = actual.cars.get(index);
            require(first.vehicleId.equals(second.vehicleId), "model id");
            same(first.width, second.width); same(first.length, second.length); same(first.pivot, second.pivot);
            require(first.bogies.size() == second.bogies.size(), "bogie count");
            for (int bogie = 0; bogie < first.bogies.size(); bogie++) {
                CoreRidingVehicle.Bogie one = first.bogies.get(bogie), two = second.bogies.get(bogie);
                same(one.x1, two.x1); same(one.y1, two.y1); same(one.z1, two.z1);
                same(one.x2, two.x2); same(one.y2, two.y2); same(one.z2, two.z2);
            }
        }
        require(expected.riders.size() == actual.riders.size(), "rider count");
        for (int index = 0; index < expected.riders.size(); index++) {
            CoreRidingVehicle.Seat first = expected.riders.get(index), second = actual.riders.get(index);
            require(first.uuid.equals(second.uuid) && first.car == second.car && first.gangway == second.gangway, "rider identity");
        }
    }

    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void same(double expected, double actual) {
        require(Double.isFinite(actual) && Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual),
            "numeric pose changed: " + expected + " / " + actual);
    }

    static final class CountingVehicle extends Vehicle {
        int calls;
        final Siding siding;
        CountingVehicle(VehicleExtraData extra, Siding siding, ReaderBase reader, Data data) { super(extra, siding, reader, data); this.siding = siding; }
        @Override @SuppressWarnings("rawtypes") public ObjectArrayList getVehicleCarsAndPositions() { calls++; return super.getVehicleCarsAndPositions(); }
    }
}
