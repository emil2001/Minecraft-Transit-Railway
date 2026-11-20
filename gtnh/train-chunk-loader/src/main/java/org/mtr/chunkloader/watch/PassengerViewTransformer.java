package org.mtr.chunkloader.watch;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Post-deobfuscation hooks outside Hodgepodge's overwritten movement method. */
public final class PassengerViewTransformer implements IClassTransformer {
    private static final String MANAGER = "net/minecraft/server/management/PlayerManager";
    private static final String PLAYER = "Lnet/minecraft/entity/player/EntityPlayerMP;";
    private static final String HOOKS = "org/mtr/chunkloader/watch/PassengerView";
    private static final String NATIVE_MOVE = "(" + PLAYER + ")V";
    private static final String HOOK_MOVE = "(L" + MANAGER + ";" + PLAYER + ")V";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        boolean manager = "net.minecraft.server.management.PlayerManager".equals(transformedName);
        boolean dispatch = "net.minecraft.server.management.ServerConfigurationManager".equals(transformedName);
        boolean scheduler = "com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler".equals(transformedName);
        if (bytes == null || !(manager || dispatch || scheduler)) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int hooked = 0;
        for (Object entry : node.methods) {
            MethodNode method = (MethodNode) entry;
            if (scheduler) {
                if (!"queueResend".equals(method.name) || !method.desc.equals(
                        "(Lnet/minecraft/world/WorldServer;Lnet/minecraft/world/gen/ChunkProviderServer;III)Z")) continue;
                hooked += replaceIterator(method, ILOAD, 2, ILOAD, 3, "resendPlayers", "II");
            } else if (dispatch) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (!(insn instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (MANAGER.equals(call.owner) && NATIVE_MOVE.equals(call.desc)
                            && named(call.name, "updatePlayerPertinentChunks", "func_72685_d")) {
                        method.instructions.set(call, hook("move", HOOK_MOVE));
                        hooked++;
                    }
                }
            } else if (named(method.name, "removePlayer", "func_72695_c") && NATIVE_MOVE.equals(method.desc)) {
                InsnList forget = new InsnList();
                forget.add(new VarInsnNode(ALOAD, 0));
                forget.add(new VarInsnNode(ALOAD, 1));
                forget.add(hook("forget", HOOK_MOVE));
                method.instructions.insert(forget);
                hooked++;
            } else if ("func_152622_a".equals(method.name) && "(I)V".equals(method.desc)) {
                hooked += replaceIterator(method, ALOAD, 0, ILOAD, 1, "resizePlayers", "L" + MANAGER + ";I");
            }
        }
        if (hooked != (manager ? 2 : 1)) {
            org.apache.logging.log4j.LogManager.getLogger("MTR Train Chunk Loader").warn(
                    "Keeping native passenger view distance: unexpected watch hooks in {} ({})", transformedName, hooked);
            return bytes;
        }
        node.interfaces.add(HOOKS + "$Installed");
        // Operand types at all existing labels remain unchanged.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static int replaceIterator(MethodNode method, int opA, int varA, int opB, int varB,
                                       String callback, String extraArguments) {
        int changed = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!"java/util/List".equals(call.owner) || !"iterator".equals(call.name)) continue;
            method.instructions.insertBefore(call, new VarInsnNode(opA, varA));
            method.instructions.insertBefore(call, new VarInsnNode(opB, varB));
            method.instructions.set(call, hook(callback, "(Ljava/util/List;" + extraArguments
                    + ")Ljava/util/Iterator;"));
            changed++;
        }
        return changed;
    }

    private static boolean named(String value, String mcp, String srg) {
        return value.equals(mcp) || value.equals(srg);
    }

    private static MethodInsnNode hook(String name, String desc) {
        return new MethodInsnNode(INVOKESTATIC, HOOKS, name, desc, false);
    }
}
