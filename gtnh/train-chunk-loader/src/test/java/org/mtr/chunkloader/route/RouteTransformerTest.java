package org.mtr.chunkloader.route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mtr.chunkloader.route.RoutePlansTest.*;

public class RouteTransformerTest {
    @Test void missingVehicleOrSimulatorHooksLeaveTheWholeClassUntouched() throws IOException {
        assertMissingHook(Vehicle.class, "simulate");
        assertMissingHook(Simulator.class, "<init>");
        assertMissingHook(Simulator.class, "stop");
    }

    private void assertMissingHook(Class<?> original, String missing) throws IOException {
        String name = original.getName();
        ClassNode node = new ClassNode();
        try (InputStream in = original.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            new ClassReader(in).accept(node, 0);
        }
        assertTrue(node.methods.removeIf(method -> ((MethodNode) method).name.equals(missing)));
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] changed = writer.toByteArray();
        assertTrue(changed == new RouteTransformer().transform(name, name, changed), name + "." + missing);
    }

    @Test void realSimulatorPublishesAfterRiderPruningAndRetiresOnStop(@TempDir Path files) throws Throwable {
        // Exercise the emitted bytecode with ordinary JVM access checks and stack-map verification.
        ClassLoader isolated = new ClassLoader(getClass().getClassLoader()) {
            final RouteTransformer transformer = new RouteTransformer();

            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!(name.startsWith("org.mtr.core.") || name.startsWith(RoutePlans.class.getName())
                        || name.equals(Exercise.class.getName()))) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        String path = name.replace('.', '/') + ".class";
                        try (InputStream in = getParent().getResourceAsStream(path)) {
                            if (in == null) throw new ClassNotFoundException(name);
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
                            byte[] result = transformer.transform(name, name, bytes.toByteArray());
                            type = defineClass(name, result, 0, result.length);
                        } catch (IOException ex) {
                            throw new ClassNotFoundException(name, ex);
                        }
                    }
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        try {
            isolated.loadClass(Exercise.class.getName()).getMethod("run", Path.class).invoke(null, files);
        } catch (InvocationTargetException failure) {
            throw failure.getTargetException();
        }
    }

    public static class Exercise {
        public static void run(Path files) {
            Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, files, false);
            Vehicle train = train(simulator, TransportMode.TRAIN, 0);
            simulator.ride(RIDER, train.getId());
            train.simulate(0, null, null);
            assertEquals(1, RoutePlans.current(DIMENSION).size(), "Real Vehicle.simulate must dispatch the observation");
            assertEquals(chunk(-10, 1), RoutePlans.current(DIMENSION).get(0).chunks.get(0).longValue());
            simulator.stopRiding(RIDER);
            train.simulate(0, null, null);
            assertTrue(RoutePlans.current(DIMENSION).isEmpty(), "Dismount is visible in the same frame");

            Vehicle second = train(simulator, TransportMode.TRAIN, 0);
            simulator.ride(RIDER, second.getId());
            second.simulate(0, null, null);
            assertEquals(1, RoutePlans.current(DIMENSION).size());
            simulator.stop();
            assertTrue(RoutePlans.current(DIMENSION).isEmpty());
            second.simulate(0, null, null);
            assertTrue(RoutePlans.current(DIMENSION).isEmpty(), "Retired simulator cannot publish");
        }
    }
}
