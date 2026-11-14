package org.mtr.gtnh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.Main;
import org.mtr.core.data.Client;
import org.mtr.core.data.Lift;
import org.mtr.core.data.LiftFloor;
import org.mtr.core.data.Position;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ThreadedSimulationSchedulerTest {
    @TempDir Path directory;

    @Test @Timeout(20)
    void manualMainPublishesDetachedReadsInBothDimensions() throws Exception { exercise(false); }

    @Test @Timeout(20)
    void scheduledMainPublishesDetachedReadsInBothDimensions() throws Exception { exercise(true); }

    private void exercise(boolean threaded) throws Exception {
        String testThreadName = Thread.currentThread().getName();
        Main main = new Main(directory, -1, threaded, false, null, "dim0", "dim1");
        ScheduledExecutorService scheduler = scheduler(main);
        try {
            // Main finishes constructing this final immutable simulator list before scheduling its ticks.
            Field simulatorsField = Main.class.getDeclaredField("simulators"); simulatorsField.setAccessible(true);
            List<?> simulators = (List<?>) simulatorsField.get(main);
            assertEquals(2, simulators.size());
            Dimension[] dimensions = {new Dimension((Simulator) simulators.get(0)), new Dimension((Simulator) simulators.get(1))};
            CountDownLatch configured = new CountDownLatch(2);
            for (Dimension dimension : dimensions) dimension.configure(configured);
            await(main, threaded, () -> configured.getCount() == 0);
            for (Dimension dimension : dimensions) {
                dimension.checkFailure();
                assertNotNull(dimension.callbackThread.get());
                if (threaded) assertNotEquals(testThreadName, dimension.callbackThread.get(), "Core must use its real scheduler");
                else assertEquals(testThreadName, dimension.callbackThread.get(), "Manual ticks must execute the Core queue on the calling thread");
            }
            assertTrue(Modifier.isVolatile(CoreRidingSnapshots.Pending.class.getDeclaredField("done").getModifiers()),
                "The detached result write must happen-before the consumer observes done");

            for (int iteration = 0; iteration < 32; iteration++) {
                CoreRidingSnapshots.Pending[] reads = new CoreRidingSnapshots.Pending[2];
                String[] expected = new String[2];
                for (int index = 0; index < dimensions.length; index++) {
                    Dimension dimension = dimensions[index]; expected[index] = iteration + ":" + dimension.uuid;
                    dimension.rename(expected[index], null);
                    reads[index] = dimension.source.request(dimension.uuid, Collections.singleton(dimension.liftId.get()), Collections.emptySet(), iteration);
                    assertNotNull(reads[index], "A completed previous read must release its UUID gate");
                }
                // In threaded mode no test-created worker or join supplies an alternative publication barrier.
                await(main, threaded, () -> reads[0].done && reads[1].done);
                CountDownLatch renamed = new CountDownLatch(2);
                for (int index = 0; index < dimensions.length; index++) {
                    Dimension dimension = dimensions[index]; dimension.checkFailure();
                    assertEquals(iteration, reads[index].requestedAt);
                    assertNotNull(reads[index].result);
                    assertEquals(1, reads[index].result.lifts.size()); assertTrue(reads[index].result.vehicles.isEmpty());
                    String json = reads[index].result.lifts.get(dimension.liftId.get()); assertNotNull(json);
                    assertEquals(expected[index], Utilities.parseJson(json).get("name").getAsString());
                    CoreRidingSnapshots.Result result = reads[index].result;
                    assertThrows(UnsupportedOperationException.class, () -> result.lifts.clear());
                    dimension.rename("changed:" + dimension.uuid, renamed);
                }
                await(main, threaded, () -> renamed.getCount() == 0);
                for (int index = 0; index < dimensions.length; index++) {
                    dimensions[index].checkFailure();
                    String detached = reads[index].result.lifts.get(dimensions[index].liftId.get());
                    assertEquals(expected[index], Utilities.parseJson(detached).get("name").getAsString(),
                        "A later Core mutation must not alter the previously published string");
                }
            }
        } finally {
            main.stop();
            if (scheduler != null) {
                assertTrue(scheduler.isShutdown(), "Main.stop must shut down its scheduler");
                assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS), "Main.stop must not leave scheduler threads running");
            }
        }
    }

    private static ScheduledExecutorService scheduler(Main main) throws Exception {
        Field field = Main.class.getDeclaredField("scheduledExecutorService"); field.setAccessible(true);
        return (ScheduledExecutorService) field.get(main);
    }

    private static void await(Main main, boolean threaded, BooleanSupplier ready) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean()) {
            assertFalse(Thread.currentThread().isInterrupted(), "Scheduler test was interrupted");
            assertTrue(System.nanoTime() - deadline < 0, "Timed out waiting for the real Core queue");
            if (!threaded) main.manualTick();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static final class Dimension {
        final Simulator simulator;
        final CoreRidingSnapshots.Source source;
        final UUID uuid = UUID.randomUUID();
        final AtomicLong liftId = new AtomicLong();
        final AtomicReference<String> callbackThread = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        private Lift lift; // Accessed only by callbacks submitted to this simulator's queue.
        Dimension(Simulator simulator) { this.simulator = simulator; source = new CoreRidingSnapshots.Source(simulator); }
        void configure(CountDownLatch configured) {
            simulator.run(() -> {
                try {
                    callbackThread.set(Thread.currentThread().getName());
                    lift = new Lift(simulator); lift.setName("initial:" + uuid); lift.setDimensions(3, 3, 3, 0, 0, 0); lift.setAngle(Angle.S);
                    lift.setFloors(ObjectArrayList.of(new LiftFloor(new Position(0, 64, 0), "1", ""), new LiftFloor(new Position(0, 80, 0), "2", "")));
                    simulator.lifts.add(lift); liftId.set(lift.getId());
                    Client client = new Client(uuid); client.setPositionAndUpdateRadius(new Position(0, 64, 0), 128); simulator.clients.add(client);
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { configured.countDown(); }
            });
        }
        void rename(String name, CountDownLatch renamed) {
            simulator.run(() -> {
                try { lift.setName(name); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { if (renamed != null) renamed.countDown(); }
            });
        }
        void checkFailure() { if (failure.get() != null) throw new AssertionError(failure.get()); }
    }
}
