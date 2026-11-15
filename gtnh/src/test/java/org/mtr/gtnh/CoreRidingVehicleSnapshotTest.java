package org.mtr.gtnh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.*;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mtr.gtnh.CoreRidingVehicleTestFixtures.*;

class CoreRidingVehicleSnapshotTest {
    @TempDir Path directory;

    @Test void preservesEmptyModelIdsAcceptedByExistingCabinChecks() {
        CountingVehicle template = train(TransportMode.TRAIN, false, false, false, 2, 4, riders());
        JsonObject json = Utilities.getJsonObjectFromData(template.vehicleExtraData);
        json.getAsJsonArray("vehicleCars").get(0).getAsJsonObject().addProperty("vehicleId", "");
        VehicleExtraData extra = new VehicleExtraData(new JsonReader(json));
        Data data = new Data() {};
        PathData.writePathCache(extra.immutablePath, data, TransportMode.TRAIN);
        Vehicle vehicle = new Vehicle(extra, null, new JsonReader(Utilities.getJsonObjectFromData(template)), data);
        assertEquals("", vehicle.vehicleExtraData.immutableVehicleCars.get(0).getVehicleId());
        CoreRidingVehicle captured = CoreRidingVehicle.capture(vehicle);
        assertEquals("", captured.cars.get(0).vehicleId);
        compareRealPose(vehicle, captured);
        compareSnapshots(captured, CoreRidingVehicle.parse(captured.toJson()));
    }

    @Test void capturesRealCoreGeometryOnceAcrossModesCurvesDirectionsAndBogieCounts() {
        for (TransportMode mode : TransportMode.values()) for (boolean curved : new boolean[]{false, true})
            for (boolean reversed : new boolean[]{false, true}) for (boolean oneBogie : new boolean[]{false, true}) {
                CountingVehicle source = train(mode, curved, reversed, oneBogie, 2, mode == TransportMode.TRAIN ? 4 : 1, riders());
                source.calls = 0;
                CoreRidingVehicle captured = CoreRidingVehicle.capture(source);
                assertEquals(1, source.calls, "Capture must evaluate the real Core poses exactly once");
                assertEquals(mode, captured.mode); assertEquals(51, captured.siding); assertEquals(52, captured.depot);
                compareRealPose(source, captured);
                CoreRidingVehicle parsed = CoreRidingVehicle.parse(captured.toJson());
                compareSnapshots(captured, parsed);
                compareSnapshots(baselineAccept(baselinePayload(source)), parsed);
            }
    }

    @Test void detachesNumericPoseAndListsWhileFreshCapturesObserveMotionAndRiders() {
        CountingVehicle source = train(TransportMode.TRAIN, false, false, false, 2, 4, riders());
        CoreRidingVehicle before = CoreRidingVehicle.capture(source);
        String frozen = before.toJson();
        assertTrue(before.hasRider(RIDER)); assertFalse(before.hasRider(UUID.randomUUID())); assertFalse(before.moving);
        JsonObject update = new JsonObject(); update.addProperty("railProgress", 220); update.addProperty("speed", .02);
        source.updateData(new JsonReader(update));
        JsonObject removeRiders = new JsonObject(); removeRiders.add("ridingEntities", new JsonArray());
        source.vehicleExtraData.updateData(new JsonReader(removeRiders));
        CoreRidingVehicle after = CoreRidingVehicle.capture(source);
        assertTrue(after.moving); assertFalse(after.hasRider(RIDER));
        assertNotEquals(frozen, after.toJson()); assertEquals(frozen, before.toJson());
        compareRealPose(source, after);
        assertThrows(UnsupportedOperationException.class, () -> before.cars.clear());
        assertThrows(UnsupportedOperationException.class, () -> before.cars.get(0).bogies.clear());
        assertThrows(UnsupportedOperationException.class, () -> before.riders.clear());
    }

    @Test void retainsDuplicateRiderRowsForAmbiguityAndCopiesTheirSeatState() {
        JsonObject value = validJson();
        JsonArray rows = value.getAsJsonArray("riders"); rows.add(seat(RIDER, 1, true));
        CoreRidingVehicle snapshot = CoreRidingVehicle.parse(value.toString());
        assertEquals(2, snapshot.riders.size()); assertTrue(snapshot.hasRider(RIDER));
        assertEquals(0, snapshot.riders.get(0).car); assertFalse(snapshot.riders.get(0).gangway);
        assertEquals(1, snapshot.riders.get(1).car); assertTrue(snapshot.riders.get(1).gangway);
        rows.get(0).getAsJsonObject().addProperty("car", 3);
        assertEquals(0, snapshot.riders.get(0).car, "Parsed snapshots must not retain the JSON tree");
    }

    @Test void rejectsMalformedFieldsNonFiniteGeometryAndUnsupportedSchema() {
        for (String invalid : new String[]{"null", "[]", "{}", "{", validJson().toString() + " garbage"}) rejected(invalid);
        for (String key : new String[]{"schema", "mode", "siding", "depot", "moving", "cars", "riders"}) {
            JsonObject value = validJson(); value.remove(key); rejected(value.toString());
            value = validJson(); value.add(key, JsonNull.INSTANCE); rejected(value.toString());
        }
        for (JsonElement schema : new JsonElement[]{new JsonPrimitive(2), new JsonPrimitive(1.5), new JsonPrimitive("1")}) {
            JsonObject value = validJson(); value.add("schema", schema); rejected(value.toString());
        }
        JsonObject value = validJson(); value.addProperty("mode", "UNKNOWN"); rejected(value.toString());
        value = validJson(); value.addProperty("moving", "false"); rejected(value.toString());
        for (String integer : new String[]{"1.5", "9223372036854775808"}) {
            value = validJson(); value.add("siding", JsonParser.parseString(integer)); rejected(value.toString());
            value = validJson(); value.getAsJsonArray("riders").get(0).getAsJsonObject().add("car", JsonParser.parseString(integer)); rejected(value.toString());
        }
        for (String uuid : new String[]{"not-a-uuid", "1-1-1-1-1"}) {
            value = validJson(); value.getAsJsonArray("riders").get(0).getAsJsonObject().addProperty("uuid", uuid); rejected(value.toString());
        }
        for (String key : new String[]{"width", "length"}) for (double number : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            value = validJson(); value.getAsJsonArray("cars").get(0).getAsJsonObject().addProperty(key, number); rejected(value.toString());
        }
        for (String key : new String[]{"pivot", "x1", "y1", "z1", "x2", "y2", "z2"}) {
            value = validJson(); JsonObject car = value.getAsJsonArray("cars").get(0).getAsJsonObject();
            (key.equals("pivot") ? car : car.getAsJsonArray("bogies").get(0).getAsJsonObject()).add(key, JsonParser.parseString("1e999"));
            rejected(value.toString());
        }
        value = validJson(); value.add("cars", new JsonArray()); rejected(value.toString());
        for (int count : new int[]{0, 3}) {
            value = validJson(); JsonObject car = value.getAsJsonArray("cars").get(0).getAsJsonObject();
            JsonArray bogies = new JsonArray();
            for (int index = 0; index < count; index++) bogies.add(car.getAsJsonArray("bogies").get(0));
            car.add("bogies", bogies); rejected(value.toString());
        }
    }

    @Test void compactPayloadIsIndependentOfTenHundredAndThousandSegmentRoutes() {
        String first = null;
        int previousBaselineBytes = 0;
        for (int segments : new int[]{10, 100, 1000}) {
            CountingVehicle source = train(TransportMode.TRAIN, true, false, false, segments, 4, riders());
            assertEquals(segments, source.vehicleExtraData.immutablePath.size());
            CoreRidingVehicle snapshot = CoreRidingVehicle.capture(source);
            compareRealPose(source, snapshot);
            String compact = snapshot.toJson();
            if (first == null) first = compact; else assertEquals(first, compact);
            assertEquals(4, snapshot.cars.size());
            assertFalse(compact.contains("railProgress")); assertFalse(compact.contains("path"));
            int baselineBytes = baselinePayload(source).length();
            assertTrue(baselineBytes > previousBaselineBytes); previousBaselineBytes = baselineBytes;
        }
    }

    @Test void actualSourceReadEmitsCompactGeometryWithOneCorePoseRead() throws Exception {
        Simulator simulator = new Simulator("overworld", new String[]{"overworld"}, directory, false);
        try {
            CountingVehicle source = train(simulator, TransportMode.TRAIN, true, false, false, 10, 4, riders());
            Client client = new Client(RIDER); client.setPositionAndUpdateRadius(new Position(0, 64, 160), 512); simulator.clients.add(client);
            simulator.sidings.add(source.siding);
            Field vehicles = Siding.class.getDeclaredField("vehicles"); vehicles.setAccessible(true);
            @SuppressWarnings("unchecked") Collection<Vehicle> nativeVehicles = (Collection<Vehicle>) vehicles.get(source.siding);
            nativeVehicles.add(source);
            CoreRidingSnapshots.Source reader = new CoreRidingSnapshots.Source(simulator);
            Method read = CoreRidingSnapshots.Source.class.getDeclaredMethod("read", UUID.class, Set.class, Set.class); read.setAccessible(true);
            source.calls = 0;
            CoreRidingSnapshots.Result result = (CoreRidingSnapshots.Result) read.invoke(reader, RIDER, Collections.emptySet(), Collections.singleton(source.getId()));
            assertEquals(1, source.calls);
            assertEquals(1, result.vehicles.size());
            CoreRidingVehicle snapshot = CoreRidingVehicle.parse(result.vehicles.get(source.getId()));
            assertEquals(4, snapshot.cars.size()); assertTrue(snapshot.hasRider(RIDER)); compareRealPose(source, snapshot);
        } finally { simulator.stop(); }
    }

    private static JsonObject validJson() {
        return JsonParser.parseString(CoreRidingVehicle.capture(train(TransportMode.TRAIN, false, false, false, 1, 4, riders())).toJson()).getAsJsonObject();
    }
    private static JsonObject seat(UUID uuid, long car, boolean gangway) {
        JsonObject row = new JsonObject(); row.addProperty("uuid", uuid.toString()); row.addProperty("car", car); row.addProperty("gangway", gangway); return row;
    }
    private static void rejected(String json) { assertThrows(IllegalArgumentException.class, () -> CoreRidingVehicle.parse(json)); }
}
