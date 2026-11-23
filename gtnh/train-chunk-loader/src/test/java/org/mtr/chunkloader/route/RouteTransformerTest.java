package org.mtr.chunkloader.route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.simulation.Simulator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mtr.chunkloader.route.RoutePlansTest.*;

public class RouteTransformerTest {
    @Test void missingVehicleOrSimulatorHooksLeaveTheWholeClassUntouched() throws IOException {
        assertMissingHook(Vehicle.class, "simulate");
        assertMissingHook(Simulator.class, "<init>");
        assertMissingHook(Simulator.class, "stop");
    }

    @Test void transformingAnInstalledBridgeAgainPreservesBothClasses() throws IOException {
        for (Class<?> type : new Class<?>[]{Vehicle.class, Simulator.class}) {
            String name = type.getName();
            try (InputStream in = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(in).accept(writer, 0);
                byte[] original = writer.toByteArray();
                RouteTransformer transformer = new RouteTransformer();
                byte[] installed = transformer.transform(name, name, original);
                assertNotSame(original, installed);
                assertSame(installed, transformer.transform(name, name, installed));
            }
        }
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
        exercise(files, "run", (name, bytes) -> bytes);
    }

    @Test void renamedInheritedFieldOrExtraGetterKeepsNativeSimulation(@TempDir Path files) throws Throwable {
        for (String member : new String[]{"railProgress", "getRepeatIndex2"}) {
            exercise(files, "incompatible", (name, bytes) -> name.startsWith("org.mtr.core.")
                    ? renameMember(bytes, member) : bytes);
        }
    }

    @Test void renamedSimulatorDimensionKeepsNativeLifecycle(@TempDir Path files) throws Throwable {
        exercise(files, "incompatibleLifecycle", (name, bytes) -> name.startsWith("org.mtr.core.")
                ? renameMember(bytes, "dimension") : bytes);
    }

    @Test void linkageErrorInNativeSimulationPropagates(@TempDir Path files) throws Throwable {
        // Leave Vehicle's own call to the removed getter intact.
        exercise(files, "nativeFailure", (name, bytes) -> name.equals(VehicleExtraData.class.getName())
                ? renameMember(bytes, "containsDriverAndDoorOverride") : bytes);
    }

    private static byte[] renameMember(byte[] bytes, String member) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new RemappingClassAdapter(writer, new Remapper() {
            @Override public String mapFieldName(String owner, String name, String desc) {
                return member.equals(name) ? name + "V2" : name;
            }

            @Override public String mapMethodName(String owner, String name, String desc) {
                return member.equals(name) ? name + "V2" : name;
            }
        }), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    // Exercise the emitted bytecode with ordinary JVM access checks and stack-map verification.
    private void exercise(Path files, String scenario, BiFunction<String, byte[], byte[]> change) throws Throwable {
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
                            byte[] result = transformer.transform(name, name, change.apply(name, bytes.toByteArray()));
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
            isolated.loadClass(Exercise.class.getName()).getMethod(scenario, Path.class).invoke(null, files);
        } catch (InvocationTargetException failure) {
            throw failure.getTargetException();
        }
    }

    public static class Exercise {
        public static void incompatible(Path files) {
            Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, files, false);
            Vehicle train = train(simulator, TransportMode.TRAIN, 0);
            simulator.ride(RIDER, train.getId());
            observe(train, simulator, 0.08);
            assertEquals(1, RoutePlans.current(DIMENSION).size());

            train.simulate(0, null, null);
            assertFalse(RoutePlans.hooksEnabled());
            assertTrue(RoutePlans.current(DIMENSION).isEmpty(), "Discard plans made before the linkage failure");

            simulator.stopRiding(RIDER);
            train.simulate(0, null, null);
            List<VehicleRidingEntity> riders = new ArrayList<>();
            train.vehicleExtraData.iterateRidingEntities(riders::add);
            assertTrue(riders.isEmpty(), "Core still prunes its riders with route loading disabled");
            simulator.stop();
        }

        public static void incompatibleLifecycle(Path files) {
            Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, files, false);
            assertFalse(RoutePlans.hooksEnabled());
            simulator.stop();
        }

        public static void nativeFailure(Path files) {
            Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, files, false);
            Vehicle train = train(simulator, TransportMode.TRAIN, 0);
            NoSuchMethodError error = assertThrows(NoSuchMethodError.class, () -> train.simulate(0, null, null));
            assertTrue(error.getMessage().contains("containsDriverAndDoorOverride"));
            assertTrue(RoutePlans.hooksEnabled());
            simulator.stop();
        }

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
