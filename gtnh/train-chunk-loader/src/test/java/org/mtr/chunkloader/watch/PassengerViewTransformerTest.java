package org.mtr.chunkloader.watch;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
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

    @Test void hodgepodgePopulationResendsHonorThePassengerWindow() throws Exception {
        String name = "com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler";
        org.junit.jupiter.api.Assumptions.assumeTrue(PassengerViewTransformerTest.class
                .getResource("/" + name.replace('.', '/') + ".class") != null, "Hodgepodge not on test classpath");
        assertEquals(1, calls(transformedClass(name), "org/mtr/chunkloader/watch/PassengerView", "resendPlayers"));
    }

    private static ClassNode transformed(String simple) throws Exception {
        return transformedClass("net.minecraft.server.management." + simple);
    }

    private static ClassNode transformedClass(String name) throws Exception {
        try (InputStream in = PassengerViewTransformerTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            ClassNode node = new ClassNode();
            new ClassReader(new PassengerViewTransformer().transform(name, name, bytes.toByteArray())).accept(node, 0);
            return node;
        }
    }

    private static int calls(ClassNode node, String owner, String name) {
        int calls = 0;
        for (Object value : node.methods) {
            MethodNode method = (MethodNode) value;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (owner.equals(call.owner) && name.equals(call.name)) calls++;
                }
            }
        }
        return calls;
    }
}
