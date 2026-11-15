package org.mtr.gtnh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Lift;
import org.mtr.core.data.TransportMode;
import org.mtr.core.operation.PlayerPresentResponse;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.operation.VehicleLiftResponse;
import org.mtr.core.operation.VehicleUpdate;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.servlet.MessageQueue;
import org.mtr.core.servlet.QueueObject;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mtr.gtnh.CoreRidingVehicleTestFixtures.*;

class CoreThreadHandoffTest {
    @TempDir Path directory;

    @Test void queuedVehicleAndLiftUpdatesFreezeAtPublicationInsteadOfRetainingLiveObjects() throws Exception {
        Simulator simulator = simulator();
        try {
            CountingVehicle vehicle = train(simulator, TransportMode.TRAIN, true, false, false, 2, 4, riders());
            Lift lift = new Lift(simulator); lift.setName("before");
            simulator.sidings.add(vehicle.siding); simulator.lifts.add(lift); simulator.sync();
            Object[] sidingsBefore = simulator.sidings.toArray();
            VehicleLiftResponse response = new VehicleLiftResponse(RIDER, simulator);
            response.addVehicleToUpdate(new VehicleUpdate(vehicle, vehicle.vehicleExtraData));
            response.addLiftToUpdate(lift); response.addVehicleToKeep(123); response.addLiftToKeep(456);
            JsonObject expected = Utilities.getJsonObjectFromData(response);

            // The previous handoff put this same live payload into Core's real queue implementation.
            MessageQueue<QueueObject> previousBoundary = new MessageQueue<>();
            previousBoundary.put(new QueueObject("vehicles_lifts", response, null, null));
            simulator.sendMessageS2C("vehicles_lifts", response, null, null);
            lift.setName("after");
            JsonObject movement = new JsonObject(); movement.addProperty("railProgress", 220); movement.addProperty("speed", .02);
            vehicle.updateData(new JsonReader(movement));

            List<QueueObject> previous = new ArrayList<>(); previousBoundary.process(previous::add);
            assertNotEquals(expected, Utilities.getJsonObjectFromData(previous.get(0).data), "The old boundary must demonstrate the live-reference race");
            List<QueueObject> published = new ArrayList<>(); simulator.processMessagesS2C(published::add);
            assertEquals(1, published.size()); assertEquals("vehicles_lifts", published.get(0).key);
            VehicleLiftResponse detached = (VehicleLiftResponse) published.get(0).data;
            assertNotSame(response, detached); assertEquals(expected, Utilities.getJsonObjectFromData(detached));
            List<VehicleUpdate> vehicles = new ArrayList<>(); detached.iterateVehiclesToUpdate(vehicles::add);
            List<Lift> lifts = new ArrayList<>(); detached.iterateLiftsToUpdate(lifts::add);
            assertNotSame(vehicle, vehicles.get(0).getVehicle());
            assertNotSame(vehicle.vehicleExtraData, vehicles.get(0).getVehicleExtraData());
            assertNotSame(lift, lifts.get(0)); assertEquals("before", lifts.get(0).getName());
            assertSame(simulator, field(lifts.get(0), "data"));
            assertSame(simulator, field(detached, "data"));
            assertEquals(Collections.singleton(lift), simulator.lifts); assertSame(lift, simulator.liftIdMap.get(lift.getId()));
            assertArrayEquals(sidingsBefore, simulator.sidings.toArray(), "Detachment must preserve the source collection after Core validation");
        } finally { simulator.stop(); }
    }

    @Test void queuedGenerationStatusDetachesDepotWithoutWritingItBackToSimulator() throws Exception {
        Simulator simulator = simulator();
        try {
            Depot depot = new Depot(TransportMode.TRAIN, simulator); depot.setName("before");
            simulator.depots.add(depot); simulator.sync();
            UpdateDataResponse response = new UpdateDataResponse(simulator); response.addDepot(depot);
            JsonObject expected = Utilities.getJsonObjectFromData(response);
            simulator.sendMessageS2C("generation_status_update", response, null, null);
            depot.setName("after");
            List<QueueObject> published = new ArrayList<>(); simulator.processMessagesS2C(published::add);
            assertEquals(1, published.size()); assertEquals("generation_status_update", published.get(0).key);
            UpdateDataResponse detached = (UpdateDataResponse) published.get(0).data;
            assertNotSame(response, detached); assertEquals(expected, Utilities.getJsonObjectFromData(detached));
            List<?> depots = (List<?>) field(detached, "depots");
            assertEquals(1, depots.size()); assertNotSame(depot, depots.get(0));
            assertEquals("before", ((Depot) depots.get(0)).getName()); assertSame(simulator, field(detached, "data"));
            assertSame(simulator, field(depots.get(0), "data"));
            assertEquals(Collections.singleton(depot), simulator.depots); assertSame(depot, simulator.depotIdMap.get(depot.getId()));
        } finally { simulator.stop(); }
    }

    @Test void concurrentPublisherAndConsumerPreserveSnapshotsFifoAndOneCallbackPerMessage() throws Exception {
        Simulator simulator = simulator();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            int messages = 64;
            Lift source = new Lift(simulator);
            CountDownLatch firstPublished = new CountDownLatch(1), producerFinished = new CountDownLatch(1);
            List<Integer> callbacks = new ArrayList<>();
            Future<?> producer = threads.submit(() -> {
                try {
                    for (int index = 0; index < messages; index++) {
                        final int sequence = index;
                        source.setName("frame-" + sequence);
                        VehicleLiftResponse response = new VehicleLiftResponse(RIDER, simulator); response.addLiftToUpdate(source);
                        simulator.sendMessageS2C("vehicles_lifts", response, ignored -> callbacks.add(sequence), SerializedDataBase.class);
                        source.setName("changed-after-publish-" + sequence);
                        firstPublished.countDown();
                    }
                } finally { producerFinished.countDown(); }
            });
            Future<Integer> consumer = threads.submit(() -> {
                assertTrue(firstPublished.await(5, TimeUnit.SECONDS));
                int[] received = {0};
                do {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    simulator.processMessagesS2C(queue -> {
                        List<Lift> lifts = new ArrayList<>(); ((VehicleLiftResponse) queue.data).iterateLiftsToUpdate(lifts::add);
                        assertEquals("frame-" + received[0], lifts.get(0).getName());
                        assertNotSame(source, lifts.get(0));
                        queue.runCallback(queue.data);
                        received[0]++;
                    });
                    Thread.yield();
                } while (producerFinished.getCount() != 0 || received[0] < messages);
                return received[0];
            });
            producer.get(10, TimeUnit.SECONDS); assertEquals(messages, consumer.get(10, TimeUnit.SECONDS));
            assertTrue(callbacks.isEmpty(), "Responses must schedule callbacks onto the Core queue");
            @SuppressWarnings("unchecked") MessageQueue<Runnable> queuedRuns = (MessageQueue<Runnable>) field(simulator, "queuedRuns");
            queuedRuns.process(Runnable::run); queuedRuns.process(Runnable::run);
            List<Integer> expected = new ArrayList<>(); for (int index = 0; index < messages; index++) expected.add(index);
            assertEquals(expected, callbacks);
            simulator.processMessagesS2C(queue -> fail("A drained response must not be delivered twice"));
        } finally {
            threads.shutdownNow(); assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
            simulator.stop();
        }
    }

    @Test void rejectsUnknownResponseBeforePublishing() {
        Simulator simulator = simulator();
        try {
            assertThrows(IllegalArgumentException.class, () -> simulator.sendMessageS2C("unknown", new PlayerPresentResponse(RIDER.toString()), null, null));
            simulator.processMessagesS2C(queue -> fail("An unsupported live response must never enter the cross-thread queue"));
        } finally { simulator.stop(); }
    }

    @Test void saveRequestIsVisibleToTheSimulationThread() throws Exception {
        Field autoSave = Simulator.class.getDeclaredField("autoSave");
        assertTrue(Modifier.isVolatile(autoSave.getModifiers()), "The server save request crosses into the Core simulation thread");
    }

    private Simulator simulator() { return new Simulator("overworld", new String[]{"overworld"}, directory, false); }

    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
