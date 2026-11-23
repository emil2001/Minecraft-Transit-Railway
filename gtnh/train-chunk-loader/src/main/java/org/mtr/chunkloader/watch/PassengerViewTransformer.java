package org.mtr.chunkloader.watch;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Post-deobfuscation hooks outside Hodgepodge's overwritten movement method. */
public final class PassengerViewTransformer implements IClassTransformer {
    private static final String MANAGER = "net/minecraft/server/management/PlayerManager";
    private static final String BASE_WORLD = "net/minecraft/world/World";
    private static final String ENTITY_PLAYER = "net/minecraft/entity/player/EntityPlayer";
    private static final String PLAYER = "Lnet/minecraft/entity/player/EntityPlayerMP;";
    private static final String HOOKS = "org/mtr/chunkloader/watch/PassengerView";
    private static final String NATIVE_MOVE = "(" + PLAYER + ")V";
    private static final String HOOK_MOVE = "(L" + MANAGER + ";" + PLAYER + ")V";

    private static final String WORLD = "Lnet/minecraft/world/WorldServer;";
    private static final String PROVIDER = "Lnet/minecraft/world/gen/ChunkProviderServer;";
    private static final String RANGE = "(" + WORLD + "III)Z";

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        boolean manager = "net.minecraft.server.management.PlayerManager".equals(transformedName);
        boolean dispatch = "net.minecraft.server.management.ServerConfigurationManager".equals(transformedName);
        boolean scheduler = "com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler".equals(transformedName);
        boolean world = "net.minecraft.world.World".equals(transformedName);
        if (bytes == null || !(manager || dispatch || scheduler || world)) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if (node.interfaces.contains(HOOKS + "$Installed")) return bytes;
        int hooked = scheduler && patchScheduler(node) ? 1 : 0;
        for (Object entry : node.methods) {
            MethodNode method = (MethodNode) entry;
            if (scheduler) break;
            if (world) {
                if (named(method.name, "setActivePlayerChunksAndCheckLight", "func_72903_x")
                        && "()V".equals(method.desc) && patchActiveChunkRadius(method)) hooked++;
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

    /** Change only this player's contribution to the union. Forge adds forced chunks before this loop. */
    private static boolean patchActiveChunkRadius(MethodNode method) {
        MethodInsnNode radius = uniqueCall(method, BASE_WORLD, "func_152379_p", "()I");
        if (radius == null || radius.getOpcode() != INVOKEVIRTUAL) return false;
        // Find the current player in the same basic block as the radius calculation. Local slots
        // vary between the compiled MCP and production classes, and the light check reads another player later.
        for (AbstractInsnNode insn = radius.getPrevious(); insn != null; insn = insn.getPrevious()) {
            if (insn instanceof JumpInsnNode || insn instanceof FrameNode) return false;
            if (insn.getOpcode() != ASTORE) continue;
            AbstractInsnNode cast = previousInstruction(insn);
            if (!(cast instanceof TypeInsnNode) || cast.getOpcode() != CHECKCAST
                    || !ENTITY_PLAYER.equals(((TypeInsnNode) cast).desc)) return false;
            AbstractInsnNode get = previousInstruction(cast);
            if (!(get instanceof MethodInsnNode)) return false;
            MethodInsnNode lookup = (MethodInsnNode) get;
            if (!"java/util/List".equals(lookup.owner) || !"get".equals(lookup.name)
                    || !"(I)Ljava/lang/Object;".equals(lookup.desc)) return false;
            InsnList cap = new InsnList();
            cap.add(new VarInsnNode(ALOAD, ((VarInsnNode) insn).var));
            cap.add(hook("activeChunkRadius", "(IL" + ENTITY_PLAYER + ";)I"));
            method.instructions.insert(radius, cap);
            return true;
        }
        return false;
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode insn) {
        do { insn = insn.getPrevious(); } while (insn != null && insn.getOpcode() < 0);
        return insn;
    }

    /** All of these sites belong to the same 2.7.221 scheduler. Refuse partial installation. */
    @SuppressWarnings("unchecked")
    private static boolean patchScheduler(ClassNode node) {
        MethodNode range = method(node, "isWithinChunkRange", RANGE);
        MethodNode distance = method(node, "minPlayerDistSq", "(" + WORLD + "III)D");
        MethodNode resend = method(node, "queueResend", "(" + WORLD + PROVIDER + "III)Z");
        MethodNode fills = method(node, "processFills", "(J" + WORLD + PROVIDER + "I)I");
        MethodNode population = method(node, "processPopulationQueue", "(J" + WORLD + PROVIDER + "I)I");
        MethodNode aged = method(node, "forceProcessAged", "(" + WORLD + PROVIDER + "I)V");
        String pruneArgs = "(" + WORLD + PROVIDER + "Lnet/minecraft/world/chunk/storage/AnvilChunkLoader;I"
                + "L" + node.name + "$ChunkLoadCallback;)V";
        MethodNode prune = method(node, "pruneAndSort", pruneArgs);
        if (range == null || distance == null || resend == null || fills == null || population == null
                || aged == null || prune == null) return false;

        // Native uses one range query for two different purposes. A fill may load missing
        // neighbors. A POPULATED retry only needs a recipient for the already generated chunk.
        MethodNode fillRange = new MethodNode(range.access, "mtr$withinFillRange", RANGE, null, null);
        range.accept(fillRange);
        MethodInsnNode fillQuery = uniqueCall(fills, node.name, range.name, RANGE);
        if (fillQuery == null || uniqueCall(population, node.name, range.name, RANGE) == null
                || uniqueCall(aged, node.name, distance.name, distance.desc) == null
                || !guardRange(fillRange, "allowFill", 1, 2) || !guardRange(distance, "allowFill", 1, 2)
                || !guardRange(range, "allowResend", 1, 2) || !guardRange(resend, "allowResend", 2, 3)
                || !pruneGeneration(prune)) return false;
        fillQuery.name = fillRange.name;
        node.methods.add(fillRange);
        return true;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (Object raw : node.methods) {
            MethodNode method = (MethodNode) raw;
            if (name.equals(method.name) && desc.equals(method.desc)) return method;
        }
        return null;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name, String desc) {
        MethodInsnNode found = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!owner.equals(call.owner) || !name.equals(call.name) || !desc.equals(call.desc)) continue;
            if (found != null) return null;
            found = call;
        }
        return found;
    }

    /** After the two native distance comparisons, skip the same player when its policy rejects the chunk. */
    private static boolean guardRange(MethodNode method, String policy, int chunkX, int chunkZ) {
        int player = -1;
        JumpInsnNode first = null, second = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() == CHECKCAST) {
                String type = ((TypeInsnNode) insn).desc;
                if (!type.equals("net/minecraft/entity/player/EntityPlayer")
                        && !type.equals("net/minecraft/entity/player/EntityPlayerMP")) continue;
                AbstractInsnNode store = nextInstruction(insn);
                if (player >= 0 || store == null || store.getOpcode() != ASTORE) return false;
                player = ((VarInsnNode) store).var;
            } else if (insn.getOpcode() == IF_ICMPGT) {
                if (player < 0 || second != null) return false;
                if (first == null) first = (JumpInsnNode) insn;
                else second = (JumpInsnNode) insn;
            }
        }
        if (second == null || first.label != second.label) return false;
        InsnList check = new InsnList();
        check.add(new VarInsnNode(ALOAD, player));
        check.add(new VarInsnNode(ILOAD, chunkX));
        check.add(new VarInsnNode(ILOAD, chunkZ));
        check.add(hook(policy, "(Ljava/lang/Object;II)Z"));
        check.add(new JumpInsnNode(IFEQ, second.label));
        method.instructions.insert(second, check);
        return true;
    }

    /** Restrict each owner's set before native code rebuilds its union, reference counts and work list. */
    private static boolean pruneGeneration(MethodNode prune) {
        MethodInsnNode getRequests = null;
        for (AbstractInsnNode insn : prune.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!"java/util/Map$Entry".equals(call.owner) || !"getValue".equals(call.name)
                    || !"()Ljava/lang/Object;".equals(call.desc)) continue;
            AbstractInsnNode cast = nextInstruction(call);
            if (!(cast instanceof TypeInsnNode) || cast.getOpcode() != CHECKCAST
                    || !"it/unimi/dsi/fastutil/longs/LongSet".equals(((TypeInsnNode) cast).desc)) continue;
            AbstractInsnNode store = nextInstruction(cast);
            if (store == null || store.getOpcode() != ASTORE) continue;
            if (getRequests != null) return false;
            getRequests = call;
        }
        if (getRequests == null) return false;
        prune.instructions.set(getRequests, hook("generationRequests", "(Ljava/util/Map$Entry;)Ljava/lang/Object;"));
        return true;
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode insn) {
        do { insn = insn.getNext(); } while (insn != null && insn.getOpcode() < 0);
        return insn;
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
