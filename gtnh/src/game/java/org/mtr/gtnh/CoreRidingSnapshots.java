package org.mtr.gtnh;

import org.mtr.core.data.*;
import org.mtr.core.operation.VehicleLiftResponse;
import org.mtr.core.operation.VehicleUpdate;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;

/** Reads the original simulator on its own queue; only detached strings cross back to Minecraft. */
final class CoreRidingSnapshots {
    // shortcut: Pinned Core has no public Simulator accessor or Siding vehicle iterator. These two
    // reference-only reads avoid modifying its binary; collection contents are read only in run().
    private static final Field RESPONSE_DATA = field(VehicleLiftResponse.class, "data");
    private static final Field SIDING_VEHICLES = field(Siding.class, "vehicles");
    private static final Map<Simulator, WeakReference<Source>> SOURCES = new WeakHashMap<>();

    static synchronized Source bind(VehicleLiftResponse response, String dimension) {
        try {
            if (RESPONSE_DATA == null || SIDING_VEHICLES == null) return null;
            Object data = RESPONSE_DATA.get(response);
            if (!(data instanceof Simulator) || !dimension.equals(((Simulator) data).dimension)) return null;
            Simulator simulator = (Simulator) data;
            WeakReference<Source> reference = SOURCES.get(simulator);
            Source source = reference == null ? null : reference.get();
            if (source == null) { source = new Source(simulator); SOURCES.put(simulator, new WeakReference<>(source)); }
            return source;
        } catch (ReflectiveOperationException | RuntimeException invalid) { return null; }
    }

    private static Field field(Class<?> owner, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }

    static final class Pending {
        final long requestedAt;
        volatile boolean done;
        Result result;
        Pending(long requestedAt) { this.requestedAt = requestedAt; }
    }
    static final class Result {
        final Map<Long, String> lifts, vehicles;
        Result(Map<Long, String> lifts, Map<Long, String> vehicles) {
            this.lifts = Collections.unmodifiableMap(lifts); this.vehicles = Collections.unmodifiableMap(vehicles);
        }
    }
    static final class Source {
        private final Simulator simulator;
        // Shared across view generations: disconnect/clear cannot enqueue another read for a stuck UUID.
        private final Set<UUID> pending = new HashSet<>();
        Source(Simulator simulator) { this.simulator = simulator; }

        synchronized Pending request(UUID uuid, Set<Long> liftIds, Set<Long> vehicleIds, long now) {
            if (!pending.add(uuid)) return null;
            Pending result = new Pending(now);
            try {
                simulator.run(() -> {
                    try { result.result = read(uuid, liftIds, vehicleIds); }
                    catch (ReflectiveOperationException | RuntimeException invalid) { result.result = null; }
                    finally { synchronized (Source.this) { pending.remove(uuid); } result.done = true; }
                });
            } catch (RuntimeException unavailable) { pending.remove(uuid); result.done = true; }
            return result;
        }

        void updatePassengerHold(org.mtr.core.operation.UpdateVehicleRidingEntities update) {
            // Match the original packet's C2S FIFO; queuedRuns is processed before pending riding packets.
            simulator.sendMessageC2S(new org.mtr.core.servlet.QueueObject(org.mtr.core.servlet.OperationProcessor.UPDATE_RIDING_ENTITIES, update, null, null));
        }

        private Result read(UUID uuid, Set<Long> liftIds, Set<Long> vehicleIds) throws ReflectiveOperationException {
            Map<Long, String> lifts = new HashMap<>(), vehicles = new HashMap<>();
            Client client = null;
            for (Client candidate : simulator.clients) if (uuid.equals(candidate.uuid)) { client = candidate; break; }
            if (client == null) return new Result(lifts, vehicles);
            for (Lift lift : simulator.lifts) if (liftIds.contains(lift.getId()) && lift.isValid()) {
                long[] low = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE}, high = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
                lift.iterateFloors(floor -> include(low, high, floor.getPosition().getX(), floor.getPosition().getY(), floor.getPosition().getZ()));
                if (within(client, low, high)) lifts.put(lift.getId(), Utilities.getJsonObjectFromData(lift).toString());
            }
            if (!vehicleIds.isEmpty()) for (Siding siding : simulator.sidings) {
                for (Object value : (Collection<?>) SIDING_VEHICLES.get(siding)) {
                    Vehicle vehicle = (Vehicle) value;
                    if (!vehicleIds.contains(vehicle.getId())) continue;
                    long[] low = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE}, high = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
                    vehicle.getVehicleCarsAndPositions().forEach(car -> car.right().forEach(pair -> {
                        Vector first = pair.left(), second = pair.right();
                        include(low, high, first.x, first.y, first.z); include(low, high, second.x, second.y, second.z);
                    }));
                    boolean[] riding = {false};
                    vehicle.vehicleExtraData.iterateRidingEntities(rider -> { if (uuid.equals(rider.uuid)) riding[0] = true; });
                    if (within(client, low, high) || riding[0])
                        vehicles.put(vehicle.getId(), Utilities.getJsonObjectFromData(new VehicleUpdate(vehicle, vehicle.vehicleExtraData)).toString());
                }
            }
            return new Result(lifts, vehicles);
        }
    }
    private static void include(long[] low, long[] high, double x, double y, double z) {
        double[] values = {x, y, z};
        for (int index = 0; index < 3; index++) {
            if (!Double.isFinite(values[index])) throw new IllegalArgumentException("Non-finite Core position");
            low[index] = Math.min(low[index], (long) Math.floor(values[index]));
            high[index] = Math.max(high[index], (long) Math.ceil(values[index]));
        }
    }
    private static boolean within(Client client, long[] low, long[] high) {
        return low[0] != Long.MAX_VALUE && Utilities.isBetween(client.getPosition(), new Position(low[0], low[1], low[2]),
            new Position(high[0], high[1], high[2]), client.getUpdateRadius());
    }
}
