package org.mtr.chunkloader.watch;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

class PassengerViewTransformerTest {
    @Test void packetMovementDispatchesOnceThroughThePassengerPolicy() throws Exception {
        ClassNode node = transformed("ServerConfigurationManager");
        assertEquals(1, calls(node, "org/mtr/chunkloader/watch/PassengerView", "move"));
        assertEquals(0, calls(node, "net/minecraft/server/management/PlayerManager", "updatePlayerPertinentChunks"));
    }

    @Test void nativeRemovalAndGlobalDistanceChangesRetainPassengerLifecycle() throws Exception {
        ClassNode node = transformed("PlayerManager");
        assertEquals(1, calls(node, "org/mtr/chunkloader/watch/PassengerView", "forget"));
        assertEquals(1, calls(node, "org/mtr/chunkloader/watch/PassengerView", "resizePlayers"));
    }

    private static final String SCHEDULER = "com/mitchej123/hodgepodge/mixins/hooks/ChunkGenScheduler";
    private static final String HOOKS = "org/mtr/chunkloader/watch/PassengerView";

    @Test void packMixinSubmitsProcessesAndWithdrawsExactOwners() throws Exception {
        ClassNode mixin = read(bytes("com.mitchej123.hodgepodge.mixins.early.minecraft.fastload.MixinPlayerManager_ThrottleChunkGen"));
        assertEquals(1, calls(method(mixin, "func_72685_d"), SCHEDULER, "enqueueForPlayer"));
        assertEquals(1, calls(method(mixin, "hodgepodge$processDeferredChunks"), SCHEDULER, "processTick"));
        assertEquals(1, calls(method(mixin, "hodgepodge$cleanupDeferredChunks"), SCHEDULER, "removePlayer"));
    }

    @Test void packSchedulerGuardsGenerationFillAgedFillResendAndResendRetry() throws Exception {
        ClassNode scheduler = transformedClass(SCHEDULER.replace('/', '.'));
        assertTrue(scheduler.interfaces.contains(HOOKS + "$Installed"));
        assertEquals(1, calls(method(scheduler, "pruneAndSort"), HOOKS, "generationRequests"));
        assertEquals(1, calls(method(scheduler, "processFills"), SCHEDULER, "mtr$withinFillRange"));
        assertEquals(1, calls(method(scheduler, "mtr$withinFillRange"), HOOKS, "allowFill"));
        assertEquals(1, calls(method(scheduler, "forceProcessAged"), SCHEDULER, "minPlayerDistSq"));
        assertEquals(1, calls(method(scheduler, "minPlayerDistSq"), HOOKS, "allowFill"));
        assertEquals(1, calls(method(scheduler, "processPopulationQueue"), SCHEDULER, "isWithinChunkRange"));
        assertEquals(1, calls(method(scheduler, "isWithinChunkRange"), HOOKS, "allowResend"));
        assertEquals(1, calls(method(scheduler, "queueResend"), HOOKS, "allowResend"));
        for (Object raw : scheduler.methods) {
            new Analyzer(new BasicVerifier()).analyze(scheduler.name, (MethodNode) raw);
        }
    }

    @Test void unknownAgedPopulationPathLeavesAllPackSchedulerBytesUntouched() throws Exception {
        String name = SCHEDULER.replace('/', '.');
        ClassNode incompatible = read(bytes(name));
        method(incompatible, "forceProcessAged").name = "differentAgedWork";
        ClassWriter writer = new ClassWriter(0);
        incompatible.accept(writer);
        byte[] original = writer.toByteArray();
        assertSame(original, new PassengerViewTransformer().transform(name, name, original));
    }

    private static ClassNode transformed(String simple) throws Exception {
        return transformedClass("net.minecraft.server.management." + simple);
    }

    private static ClassNode transformedClass(String name) throws Exception {
        return read(new PassengerViewTransformer().transform(name, name, bytes(name)));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] bytes(String name) throws Exception {
        try (InputStream in = PassengerViewTransformerTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            assertNotNull(in, name + " is required on the test classpath");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return bytes.toByteArray();
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        for (Object raw : node.methods) {
            MethodNode method = (MethodNode) raw;
            if (name.equals(method.name)) return method;
        }
        throw new AssertionError(node.name + "." + name);
    }

    private static int calls(ClassNode node, String owner, String name) {
        int calls = 0;
        for (Object value : node.methods) {
            calls += calls((MethodNode) value, owner, name);
        }
        return calls;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int matches = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (owner.equals(call.owner) && name.equals(call.name)) matches++;
            }
        }
        return matches;
    }
}
