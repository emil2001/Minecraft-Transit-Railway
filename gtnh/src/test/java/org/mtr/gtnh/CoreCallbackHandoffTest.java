package org.mtr.gtnh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.*;
import org.mtr.core.operation.*;
import org.mtr.core.serializer.*;
import org.mtr.core.servlet.MessageQueue;
import org.mtr.core.servlet.QueueObject;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CoreCallbackHandoffTest {
    @TempDir Path directory;

    @Test void copiesEveryKnownReplyWithoutChangingItsTypeOrPayload() {
        JsonObject payload = new JsonObject();
        JsonObject station = new JsonObject(); station.addProperty("id", 51); station.addProperty("name", "before");
        JsonArray stations = new JsonArray(); stations.add(station); payload.add("stations", stations);
        JsonArray ids = new JsonArray(); ids.add(51); payload.add("stationIds", ids);
        List<SerializedDataBase> replies = Arrays.asList(
            new DataResponse(new JsonReader(payload), new ClientData()),
            new UpdateDataResponse(new JsonReader(payload), new ClientData()),
            new ListDataResponse(new JsonReader(payload), new ClientData()),
            new NearbyAreasResponse(new JsonReader(payload), new ClientData()),
            new RailsResponse(new JsonReader(payload)), new ArrivalsResponse(123),
            new DeleteDataResponse(new JsonReader(payload)), new UpdateVehicleRidingEntities(51, 77));
        for (SerializedDataBase source : replies) {
            JsonObject expected = Utilities.getJsonObjectFromData(source);
            for (Class<? extends SerializedDataBase> type : Arrays.asList(source.getClass(), SerializedDataBase.class)) {
                SerializedDataBase copy = reply(source, type);
                assertNotSame(source, copy); assertEquals(source.getClass(), copy.getClass());
                assertEquals(expected, Utilities.getJsonObjectFromData(copy));
            }
        }
    }

    @Test void typedNearbyReplyFreezesLiveStationBeforeNativeTaskIsQueued() throws Exception {
        Simulator simulator = simulator();
        try {
            JsonObject definition = new JsonObject(); definition.addProperty("id", 51); definition.addProperty("name", "before");
            Station station = new Station(new JsonReader(definition), simulator); simulator.stations.add(station);
            NearbyAreasResponse live = new NearbyAreasRequest<Station, Platform>(new Position(0, 0, 0), Long.MAX_VALUE).query(simulator, simulator.stations);
            assertSame(station, live.getStations().get(0), "The actual query must expose the live Core object before the handoff");
            MessageQueue<Runnable> nativeTasks = new MessageQueue<>();
            AtomicReference<NearbyAreasResponse> received = new AtomicReference<>();
            AtomicReference<NearbyAreasResponse> previous = new AtomicReference<>();
            QueueObject previousBoundary = new QueueObject("nearby_stations", live,
                response -> nativeTasks.put(() -> previous.set(response)), NearbyAreasResponse.class);
            previousBoundary.runCallback(live);
            QueueObject queue = new QueueObject("nearby_stations", live, response -> {
                NearbyAreasResponse copy = (NearbyAreasResponse) reply(response, NearbyAreasResponse.class);
                nativeTasks.put(() -> received.set(copy));
            }, NearbyAreasResponse.class);
            queue.runCallback(live);
            station.setName("after");
            assertNull(received.get()); nativeTasks.process(Runnable::run);
            assertSame(live, previous.get()); assertEquals("after", previous.get().getStations().get(0).getName(),
                "The previous real QueueObject boundary must expose the mutation after publication");
            NearbyAreasResponse copy = received.get();
            assertNotSame(station, copy.getStations().get(0)); assertEquals("before", copy.getStations().get(0).getName());
            assertNotSame(simulator, field(copy, "data")); assertNotSame(simulator, field(copy.getStations().get(0), "data"));
            assertEquals("after", station.getName()); assertTrue(simulator.stations.contains(station));
        } finally { simulator.stop(); }
    }

    @Test void genericPacketReplyFreezesLiveDepotBeforeNativeSerialization() throws Exception {
        Simulator simulator = simulator();
        try {
            Depot depot = new Depot(TransportMode.TRAIN, simulator); depot.setName("before"); simulator.depots.add(depot);
            UpdateDataResponse live = new UpdateDataResponse(simulator); live.addDepot(depot);
            JsonObject expected = Utilities.getJsonObjectFromData(live);
            MessageQueue<Runnable> nativeTasks = new MessageQueue<>();
            AtomicReference<SerializedDataBase> received = new AtomicReference<>();
            QueueObject queue = new QueueObject("update_data", live, response -> {
                SerializedDataBase copy = reply(response, SerializedDataBase.class);
                nativeTasks.put(() -> received.set(copy));
            }, SerializedDataBase.class);
            queue.runCallback(live); depot.setName("after"); nativeTasks.process(Runnable::run);
            assertEquals(expected, Utilities.getJsonObjectFromData(received.get()));
            assertNotSame(simulator, field(received.get(), "data"));
            List<?> depots = (List<?>) field(received.get(), "depots"); assertNotSame(depot, depots.get(0));
            assertNotSame(simulator, field(depots.get(0), "data")); assertTrue(simulator.depots.contains(depot));
        } finally { simulator.stop(); }
    }

    @Test void acceptsOnlyUntypedEmptyAcknowledgementAndRejectsUnknownNonemptyReplies() {
        SerializedDataBase empty = new SerializedDataBase() {
            @Override public void updateData(ReaderBase reader) {}
            @Override public void serializeData(WriterBase writer) {}
        };
        SerializedDataBase copy = reply(empty, SerializedDataBase.class);
        assertNotSame(empty, copy); assertEquals(new JsonObject(), Utilities.getJsonObjectFromData(copy));
        JsonObject mutation = new JsonObject(); mutation.addProperty("ignored", "value"); copy.updateData(new JsonReader(mutation));
        assertEquals(new JsonObject(), Utilities.getJsonObjectFromData(copy));
        assertThrows(IllegalArgumentException.class, () -> reply(empty, empty.getClass()));
        assertThrows(IllegalArgumentException.class, () -> reply(new PlayerPresentResponse("overworld"), SerializedDataBase.class));
        assertThrows(IllegalArgumentException.class, () -> reply(new ArrivalsResponse(1), RailsResponse.class));
        assertThrows(IllegalArgumentException.class, () -> reply(null, SerializedDataBase.class));
        assertThrows(IllegalArgumentException.class, () -> reply(new ArrivalsResponse(1), null));
    }

    @Test void concurrentCallbacksPublishIndependentSnapshotsInOrder() throws Exception {
        Simulator simulator = simulator(); ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            int messages = 64;
            Depot depot = new Depot(TransportMode.TRAIN, simulator);
            MessageQueue<SerializedDataBase> nativeReplies = new MessageQueue<>();
            CountDownLatch firstPublished = new CountDownLatch(1), finished = new CountDownLatch(1);
            Future<?> producer = threads.submit(() -> {
                try {
                    for (int index = 0; index < messages; index++) {
                        depot.setName("frame-" + index);
                        UpdateDataResponse live = new UpdateDataResponse(simulator); live.addDepot(depot);
                        nativeReplies.put(reply(live, SerializedDataBase.class));
                        depot.setName("changed-after-publication-" + index); firstPublished.countDown();
                    }
                } finally { finished.countDown(); }
            });
            Future<Integer> consumer = threads.submit(() -> {
                assertTrue(firstPublished.await(5, TimeUnit.SECONDS)); int[] count = {0};
                do {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    nativeReplies.process(snapshot -> {
                        JsonObject json = Utilities.getJsonObjectFromData(snapshot);
                        assertEquals("frame-" + count[0], json.getAsJsonArray("depots").get(0).getAsJsonObject().get("name").getAsString());
                        count[0]++;
                    });
                    Thread.yield();
                } while (finished.getCount() != 0 || count[0] < messages);
                return count[0];
            });
            producer.get(10, TimeUnit.SECONDS); assertEquals(messages, consumer.get(10, TimeUnit.SECONDS));
            nativeReplies.process(ignored -> fail("A drained reply must not appear twice"));
        } finally {
            threads.shutdownNow(); assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS)); simulator.stop();
        }
    }

    // Reflection lets this regression compile and fail against the previous helper without reply().
    private static SerializedDataBase reply(SerializedDataBase response, Class<? extends SerializedDataBase> expected) {
        try { return (SerializedDataBase) CoreThreadHandoff.class.getMethod("reply", SerializedDataBase.class, Class.class).invoke(null, response, expected); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause(); if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause; throw new AssertionError(cause);
        } catch (ReflectiveOperationException error) { throw new AssertionError("Core callback snapshot boundary is missing", error); }
    }
    private Simulator simulator() { return new Simulator("overworld", new String[]{"overworld"}, directory, false); }
    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
