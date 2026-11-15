package org.mtr.gtnh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.Client;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.servlet.MessageQueue;
import org.mtr.core.servlet.SystemMapServlet;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CoreSystemMapThreadTest {
    @TempDir Path directory;
    private static final String[] ENDPOINTS = {"stations-and-routes", "departures", "clients"};

    @Test void sharedDimensionCachesHaveOneSynchronizedEntryPoint() throws Exception {
        Method getContent = SystemMapServlet.class.getDeclaredMethod("getContent", String.class, String.class,
            Object2ObjectAVLTreeMap.class, JsonReader.class, Simulator.class, Consumer.class);
        assertTrue(Modifier.isSynchronized(getContent.getModifiers()), "One servlet shares its three cache maps across independent simulator threads");
    }

    @Test void concurrentDimensionQueuesKeepAllEndpointCallbacksAndCachedPayloadsSeparate() throws Exception {
        String[] dimensions = {"map-first", "map-second"};
        UUID[] players = {UUID.fromString("00000000-0000-0000-0000-000000000001"), UUID.fromString("00000000-0000-0000-0000-000000000002")};
        Simulator first = new Simulator(dimensions[0], dimensions, directory, false);
        Simulator second = new Simulator(dimensions[1], dimensions, directory, false);
        Simulator[] simulators = {first, second};
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < simulators.length; index++) {
                Simulator simulator = simulators[index];
                JsonObject station = new JsonObject(); station.addProperty("id", index + 1); station.addProperty("name", "station-" + dimensions[index]);
                station.addProperty("transportMode", "TRAIN");
                simulator.stations.add(new Station(new JsonReader(station), simulator));
                Client client = new Client(players[index]); client.setPositionAndUpdateRadius(new Position(index * 100, 64, index * 100), 512);
                simulator.clients.add(client);
            }
            SystemMapServlet servlet = new SystemMapServlet(ObjectImmutableList.of(first, second));
            CountDownLatch start = new CountDownLatch(1);
            AtomicIntegerArray callbacks = new AtomicIntegerArray(ENDPOINTS.length * simulators.length);
            @SuppressWarnings("unchecked") Future<?>[] jobs = new Future<?>[simulators.length];
            int repetitions = 64;
            for (int index = 0; index < simulators.length; index++) {
                final int dimension = index;
                jobs[index] = threads.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    Simulator simulator = simulators[dimension];
                    MessageQueue<Runnable> queue = queuedRuns(simulator);
                    for (int repeat = 0; repeat < repetitions; repeat++) {
                        for (int endpointIndex = 0; endpointIndex < ENDPOINTS.length; endpointIndex++) {
                            final int endpoint = endpointIndex;
                            simulator.run(() -> servlet.getContent(ENDPOINTS[endpoint], "", new Object2ObjectAVLTreeMap<>(),
                                new JsonReader(new JsonObject()), simulator, response -> {
                                    assertNotNull(response);
                                    String json = response.toString();
                                    if (endpoint == 0) {
                                        assertTrue(json.contains("station-" + dimensions[dimension]));
                                        assertFalse(json.contains("station-" + dimensions[1 - dimension]));
                                    } else if (endpoint == 2) {
                                        assertTrue(json.contains(players[dimension].toString()));
                                        assertFalse(json.contains(players[1 - dimension].toString()));
                                    }
                                    callbacks.incrementAndGet(dimension * ENDPOINTS.length + endpoint);
                                }));
                        }
                        queue.process(Runnable::run);
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> job : jobs) job.get(15, TimeUnit.SECONDS);
            for (int index = 0; index < callbacks.length(); index++) assertEquals(repetitions, callbacks.get(index));
            for (String cache : new String[]{"stationsAndRoutesResponses", "departuresResponses", "clientsResponses"}) {
                Field field = SystemMapServlet.class.getDeclaredField(cache); field.setAccessible(true);
                Map<?, ?> entries = (Map<?, ?>) field.get(servlet);
                assertEquals(2, entries.size());
                assertNotNull(entries.get(dimensions[0])); assertNotNull(entries.get(dimensions[1]));
                assertNotSame(entries.get(dimensions[0]), entries.get(dimensions[1]), cache + " must retain a distinct cache per dimension");
            }
        } finally {
            threads.shutdownNow(); assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
            first.stop(); second.stop();
        }
    }

    @SuppressWarnings("unchecked") private static MessageQueue<Runnable> queuedRuns(Simulator simulator) throws Exception {
        Field field = Simulator.class.getDeclaredField("queuedRuns"); field.setAccessible(true);
        return (MessageQueue<Runnable>) field.get(simulator);
    }
}
