package org.mtr.chunkloader.route;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Injects callbacks into the transport simulator. Neither class nor its field descriptors are Minecraft-mapped. */
public final class RouteTransformer implements IClassTransformer {
    private static final String VEHICLE = "org/mtr/core/data/Vehicle";
    private static final String EXTRA = "org/mtr/core/data/VehicleExtraData";
    private static final String DATA = "Lorg/mtr/core/data/Data;";
    private static final String SIM = "Lorg/mtr/core/simulation/Simulator;";
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
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                       String signature, String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, method, descriptor, signature, exceptions);
                boolean simulation = vehicle && "simulate".equals(method) && SIMULATE.equals(descriptor);
                boolean opened = !vehicle && "<init>".equals(method)
                        && "(Ljava/lang/String;[Ljava/lang/String;Ljava/nio/file/Path;Z)V".equals(descriptor);
                boolean closed = !vehicle && "stop".equals(method) && "()V".equals(descriptor);
                if (!simulation && !opened && !closed) return next;
                return new MethodVisitor(Opcodes.ASM5, next) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (closed) lifecycle(next, "simulatorClosed");
                    }

                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            if (simulation) observe(next);
                            if (opened) lifecycle(next, "simulatorOpened");
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void lifecycle(MethodVisitor method, String callback) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, callback, "(" + SIM + ")V", false);
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
