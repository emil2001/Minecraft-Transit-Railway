package org.mtr.chunkloader.route;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Injects callbacks into the transport simulator. Neither class nor its field descriptors are Minecraft-mapped. */
public final class RouteTransformer implements IClassTransformer {
    private static final String VEHICLE = "org/mtr/core/data/Vehicle";
    private static final String EXTRA = "org/mtr/core/data/VehicleExtraData";
    private static final String DATA = "Lorg/mtr/core/data/Data;";
    private static final String SIMULATOR = "org/mtr/core/simulation/Simulator";
    private static final String BRIDGE_PREFIX = "mtrChunkLoader$";
    private static final String HOOK = "org/mtr/chunkloader/route/RoutePlans";
    private static final String COLLECTIONS = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/";
    private static final String SIMULATE = "(J" + COLLECTIONS + "objects/ObjectArrayList;"
            + COLLECTIONS + "longs/Long2LongAVLTreeMap;)V";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        boolean vehicle = "org.mtr.core.data.Vehicle".equals(transformedName);
        if (bytes == null || (!vehicle && !"org.mtr.core.simulation.Simulator".equals(transformedName))) return bytes;
        ClassReader reader = new ClassReader(bytes);
        // All insertions are stack-neutral at existing instruction boundaries. Keep Core's frames.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        class RouteHooks extends ClassVisitor {
            boolean sawSimulation, sawOpen, sawClose, bridgeExists;

            RouteHooks() { super(Opcodes.ASM5, writer); }

            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                       String signature, String[] exceptions) {
                if ("()V".equals(descriptor) && (vehicle ? (BRIDGE_PREFIX + "observe").equals(method)
                        : (BRIDGE_PREFIX + "simulatorOpened").equals(method) || (BRIDGE_PREFIX + "simulatorClosed").equals(method))) {
                    bridgeExists = true;
                }
                MethodVisitor next = super.visitMethod(access, method, descriptor, signature, exceptions);
                if ((access & Opcodes.ACC_STATIC) != 0) return next;
                boolean simulation = vehicle && "simulate".equals(method) && SIMULATE.equals(descriptor);
                boolean opened = !vehicle && "<init>".equals(method)
                        && "(Ljava/lang/String;[Ljava/lang/String;Ljava/nio/file/Path;Z)V".equals(descriptor);
                boolean closed = !vehicle && "stop".equals(method) && "()V".equals(descriptor);
                if (!simulation && !opened && !closed) return next;
                return new MethodVisitor(Opcodes.ASM5, next) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (closed) {
                            callHook(next, SIMULATOR, "simulatorClosed");
                            sawClose = true;
                        }
                    }

                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            if (simulation) {
                                callHook(next, VEHICLE, "observe");
                                sawSimulation = true;
                            }
                            if (opened) {
                                callHook(next, SIMULATOR, "simulatorOpened");
                                sawOpen = true;
                            }
                        }
                        super.visitInsn(opcode);
                    }
                };
            }

            @Override public void visitEnd() {
                if (sawSimulation) guardedHook(cv, "observe");
                if (sawOpen && sawClose) {
                    guardedHook(cv, "simulatorOpened");
                    guardedHook(cv, "simulatorClosed");
                }
                super.visitEnd();
            }
        }
        RouteHooks hooks = new RouteHooks();
        reader.accept(hooks, 0);
        if (hooks.bridgeExists) return bytes;
        if (vehicle ? !hooks.sawSimulation : !(hooks.sawOpen && hooks.sawClose)) {
            String missing = vehicle ? "Vehicle.simulate" :
                    (!hooks.sawOpen ? "Simulator constructor" : "Simulator.stop");
            org.apache.logging.log4j.LogManager.getLogger("MTR Train Chunk Loader").warn(
                    "Train route loading disabled: missing required {} hook in {}", missing, transformedName);
            return bytes;
        }
        return writer.toByteArray();
    }

    private static void callHook(MethodVisitor method, String owner, String callback) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, BRIDGE_PREFIX + callback, "()V", false);
    }

    // Keep the handler in its own method. Only the add-on's field reads and callback can reach it.
    private static void guardedHook(ClassVisitor visitor, String callback) {
        MethodVisitor method = visitor.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                BRIDGE_PREFIX + callback, "()V", null, null);
        Label start = new Label(), done = new Label(), failed = new Label();
        method.visitCode();
        method.visitTryCatchBlock(start, done, failed, "java/lang/LinkageError");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "hooksEnabled", "()Z", false);
        method.visitJumpInsn(Opcodes.IFEQ, done);
        method.visitLabel(start);
        if ("observe".equals(callback)) {
            observe(method);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, callback, "(L" + SIMULATOR + ";)V", false);
        }
        method.visitLabel(done);
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(failed);
        method.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/LinkageError"});
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "disableHooks", "(Ljava/lang/LinkageError;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void observe(MethodVisitor method) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        field(method, "data", DATA);
        field(method, "railProgress", "D");
        field(method, "speed", "D");
        extraGetter(method, "getRepeatIndex1", "()I");
        extraGetter(method, "getRepeatIndex2", "()I");
        extraGetter(method, "getTotalDistance", "()D");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "observe", "(L" + VEHICLE + ";" + DATA + "DDIID)V", false);
    }

    private static void extraGetter(MethodVisitor method, String getter, String descriptor) {
        field(method, "vehicleExtraData", "L" + EXTRA + ";");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EXTRA, getter, descriptor, false);
    }

    private static void field(MethodVisitor method, String field, String descriptor) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, VEHICLE, field, descriptor);
    }
}
