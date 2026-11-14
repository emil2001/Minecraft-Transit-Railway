package org.mtr.gtnh;

import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.com.google.gson.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Detached cabin geometry; accepting a Core read must not rebuild its entire rail path. */
final class CoreRidingVehicle {
    final TransportMode mode;
    final long siding, depot;
    final boolean moving;
    final List<Car> cars;
    final List<Seat> riders;

    private CoreRidingVehicle(TransportMode mode, long siding, long depot, boolean moving, List<Car> cars, List<Seat> riders) {
        if (mode == null || cars.isEmpty()) throw new IllegalArgumentException("Missing vehicle mode or cars");
        this.mode = mode; this.siding = siding; this.depot = depot; this.moving = moving;
        this.cars = immutable(cars); this.riders = immutable(riders);
    }

    static CoreRidingVehicle capture(Vehicle vehicle) {
        List<Car> cars = new ArrayList<>();
        vehicle.getVehicleCarsAndPositions().forEach(entry -> {
            List<Bogie> bogies = new ArrayList<>();
            entry.right().forEach(pair -> bogies.add(new Bogie(pair.left().x, pair.left().y, pair.left().z,
                pair.right().x, pair.right().y, pair.right().z)));
            cars.add(new Car(entry.left().getVehicleId(), entry.left().getWidth(), entry.left().getLength(),
                (entry.left().getBogie1Position() + entry.left().getBogie2Position()) / 2, bogies));
        });
        List<Seat> riders = new ArrayList<>();
        vehicle.vehicleExtraData.iterateRidingEntities(rider -> riders.add(new Seat(rider.uuid, rider.getRidingCar(), rider.getIsOnGangway())));
        return new CoreRidingVehicle(vehicle.getTransportMode(), vehicle.vehicleExtraData.getSidingId(),
            vehicle.vehicleExtraData.getDepotId(), vehicle.isMoving(), cars, riders);
    }

    boolean hasRider(UUID uuid) {
        for (Seat rider : riders) if (rider.uuid.equals(uuid)) return true;
        return false;
    }

    String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1); root.addProperty("mode", mode.name());
        root.addProperty("siding", siding); root.addProperty("depot", depot); root.addProperty("moving", moving);
        JsonArray carRows = new JsonArray();
        for (Car car : cars) {
            JsonObject row = new JsonObject(); row.addProperty("vehicleId", car.vehicleId);
            row.addProperty("width", car.width); row.addProperty("length", car.length); row.addProperty("pivot", car.pivot);
            JsonArray bogieRows = new JsonArray();
            for (Bogie bogie : car.bogies) {
                JsonObject pair = new JsonObject();
                pair.addProperty("x1", bogie.x1); pair.addProperty("y1", bogie.y1); pair.addProperty("z1", bogie.z1);
                pair.addProperty("x2", bogie.x2); pair.addProperty("y2", bogie.y2); pair.addProperty("z2", bogie.z2);
                bogieRows.add(pair);
            }
            row.add("bogies", bogieRows); carRows.add(row);
        }
        root.add("cars", carRows);
        JsonArray riderRows = new JsonArray();
        for (Seat rider : riders) {
            JsonObject row = new JsonObject(); row.addProperty("uuid", rider.uuid.toString());
            row.addProperty("car", rider.car); row.addProperty("gangway", rider.gangway); riderRows.add(row);
        }
        root.add("riders", riderRows);
        return root.toString();
    }

    static CoreRidingVehicle parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (integer(root, "schema") != 1) throw new IllegalArgumentException("Unknown riding vehicle schema");
            List<Car> cars = new ArrayList<>();
            for (JsonElement value : array(root, "cars")) {
                JsonObject row = value.getAsJsonObject();
                List<Bogie> bogies = new ArrayList<>();
                for (JsonElement pairValue : array(row, "bogies")) {
                    JsonObject pair = pairValue.getAsJsonObject();
                    bogies.add(new Bogie(number(pair, "x1"), number(pair, "y1"), number(pair, "z1"),
                        number(pair, "x2"), number(pair, "y2"), number(pair, "z2")));
                }
                cars.add(new Car(string(row, "vehicleId"), number(row, "width"), number(row, "length"), number(row, "pivot"), bogies));
            }
            List<Seat> riders = new ArrayList<>();
            for (JsonElement value : array(root, "riders")) {
                JsonObject row = value.getAsJsonObject();
                String id = string(row, "uuid");
                UUID uuid = UUID.fromString(id);
                if (!uuid.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException("Invalid rider UUID");
                riders.add(new Seat(uuid, integer(row, "car"), bool(row, "gangway")));
            }
            return new CoreRidingVehicle(TransportMode.valueOf(string(root, "mode")), integer(root, "siding"),
                integer(root, "depot"), bool(root, "moving"), cars, riders);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid riding vehicle snapshot", invalid);
        }
    }

    static final class Car {
        final String vehicleId;
        final double width, length, pivot;
        final List<Bogie> bogies;
        private Car(String vehicleId, double width, double length, double pivot, List<Bogie> bogies) {
            if (vehicleId == null || finite(width) <= 0 || finite(length) <= 0
                    || bogies.isEmpty() || bogies.size() > 2) throw new IllegalArgumentException("Invalid car geometry");
            this.vehicleId = vehicleId; this.width = width; this.length = length; this.pivot = finite(pivot);
            this.bogies = immutable(bogies);
        }
    }

    static final class Bogie {
        final double x1, y1, z1, x2, y2, z2;
        private Bogie(double x1, double y1, double z1, double x2, double y2, double z2) {
            this.x1 = finite(x1); this.y1 = finite(y1); this.z1 = finite(z1);
            this.x2 = finite(x2); this.y2 = finite(y2); this.z2 = finite(z2);
        }
    }

    static final class Seat {
        final UUID uuid;
        final long car;
        final boolean gangway;
        private Seat(UUID uuid, long car, boolean gangway) {
            if (uuid == null) throw new IllegalArgumentException("Missing rider UUID");
            this.uuid = uuid; this.car = car; this.gangway = gangway;
        }
    }

    private static <T> List<T> immutable(List<T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite cabin geometry");
        return value;
    }
    private static JsonPrimitive primitive(JsonObject row, String key) {
        JsonElement value = row.get(key);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException("Missing primitive: " + key);
        return value.getAsJsonPrimitive();
    }
    private static String string(JsonObject row, String key) {
        JsonPrimitive value = primitive(row, key);
        if (!value.isString()) throw new IllegalArgumentException("Expected string: " + key);
        return value.getAsString();
    }
    private static long integer(JsonObject row, String key) {
        JsonPrimitive value = primitive(row, key);
        if (!value.isNumber()) throw new IllegalArgumentException("Expected integer: " + key);
        return value.getAsBigDecimal().longValueExact();
    }
    private static double number(JsonObject row, String key) {
        JsonPrimitive value = primitive(row, key);
        if (!value.isNumber()) throw new IllegalArgumentException("Expected number: " + key);
        return finite(value.getAsDouble());
    }
    private static boolean bool(JsonObject row, String key) {
        JsonPrimitive value = primitive(row, key);
        if (!value.isBoolean()) throw new IllegalArgumentException("Expected boolean: " + key);
        return value.getAsBoolean();
    }
    private static JsonArray array(JsonObject row, String key) {
        JsonElement value = row.get(key);
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Expected array: " + key);
        return value.getAsJsonArray();
    }
}
