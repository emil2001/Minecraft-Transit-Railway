package org.mtr.gtnh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.Main;
import org.mtr.core.data.Data;
import org.mtr.core.data.Lift;
import org.mtr.core.data.LiftDirection;
import org.mtr.core.data.LiftFloor;
import org.mtr.core.data.LiftInstruction;
import org.mtr.core.data.Position;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CoreLifecycleTest {
    @TempDir Path directory;

    @Test
    void embeddedCoreStopsAndReopensWithoutLeavingNonDaemonThreads() throws Exception {
        Set<Long> previous = Thread.getAllStackTraces().keySet().stream()
            .map(Thread::threadId).collect(Collectors.toSet());
        for (boolean threaded : new boolean[] {false, true}) {
            Main core = new Main(directory, -1, threaded, false, null, "overworld");
            try {
                if (!threaded) for (int i = 0; i < 20; i++) core.manualTick();
                core.save();
            } finally {
                core.stop();
            }
        }
        assertTimeout(Duration.ofSeconds(5), () -> {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (!previous.contains(thread.threadId()) && !thread.isDaemon()) {
                    thread.join(2000);
                    assertFalse(thread.isAlive(), "Core leaked thread " + thread.getName());
                }
            }
        });
    }

    @Test
    void threeFloorLiftRetainsItsIdSettingsAndFloorsAfterRestart() {
        Simulator simulator = new Simulator("overworld", new String[] {"overworld"}, directory, false);
        Lift lift = lift(simulator);
        long identifier = lift.getId();
        simulator.lifts.add(lift);
        simulator.stop();

        Simulator reopened = new Simulator("overworld", new String[] {"overworld"}, directory, false);
        try {
            assertEquals(1, reopened.lifts.size());
            Lift restored = reopened.lifts.iterator().next();
            assertEquals(identifier, restored.getId());
            assertEquals("Лифт GTNH", restored.getName());
            assertEquals(3, restored.getFloorCount());
            assertEquals(3, restored.getWidth());
            ObjectArrayList<String> descriptions = new ObjectArrayList<>();
            restored.iterateFloors(floor -> descriptions.add(floor.getDescription()));
            assertEquals(ObjectArrayList.of("Нижний", "Средний", "Верхний"), descriptions);
        } finally {
            reopened.stop();
        }
    }

    @Test
    void originalLiftSimulationServicesCallsInBothDirections() {
        Simulator simulator = new Simulator("overworld", new String[] {"overworld"}, directory, false);
        try {
            Lift lift = lift(simulator);
            assertTrue(lift.pressButton(new LiftInstruction(2, LiftDirection.UP), true) >= 0);
            for (int i = 0; i < 20000; i++) lift.tick(10);
            assertEquals("3", lift.getCurrentFloor().getNumber());
            assertTrue(lift.pressButton(new LiftInstruction(0, LiftDirection.DOWN), true) >= 0);
            for (int i = 0; i < 20000; i++) lift.tick(10);
            assertEquals("1", lift.getCurrentFloor().getNumber());
        } finally {
            simulator.stop();
        }
    }

    private static Lift lift(Data data) {
        Lift lift = new Lift(data);
        lift.setName("Лифт GTNH");
        lift.setDimensions(3, 3, 3, 0, 0, 0);
        lift.setFloors(ObjectArrayList.of(
            new LiftFloor(new Position(0, 64, 0), "1", "Нижний"),
            new LiftFloor(new Position(0, 80, 0), "2", "Средний"),
            new LiftFloor(new Position(0, 96, 0), "3", "Верхний")));
        return lift;
    }
}
