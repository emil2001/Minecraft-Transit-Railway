package org.mtr.gtnh.build;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.FieldVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/** Applies the pinned Core's legacy logging and simulation-thread handoff compatibility hooks. */
public final class PatchCoreLogging {
    private static final String LOGGER = "org/apache/logging/log4j/Logger";
    private static final String BRIDGE = "org/mtr/gtnh/compat/LegacyLogging";
    private static final String VARARGS = "(Ljava/lang/String;[Ljava/lang/Object;)V";
    private static final String SIMULATOR = "org/mtr/core/simulation/Simulator";
    private static final String SERIALIZED = "Lorg/mtr/core/serializer/SerializedDataBase;";
    private static final String SEND_S2C = "(Ljava/lang/String;" + SERIALIZED + "Ljava/util/function/Consumer;Ljava/lang/Class;)V";
    private static final String HANDOFF = "org/mtr/gtnh/CoreThreadHandoff";
    private static final String SYSTEM_MAP = "org/mtr/core/servlet/SystemMapServlet";
    private static final String MAP_CONTENT = "(Ljava/lang/String;Ljava/lang/String;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/Object2ObjectAVLTreeMap;"
        + "Lorg/mtr/core/serializer/JsonReader;L" + SIMULATOR + ";Ljava/util/function/Consumer;)V";
    private static final Type STRING = Type.getType(String.class);
    private static final Type OBJECT = Type.getType(Object.class);

    private PatchCoreLogging() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected Core JAR, target Log4j API JAR, patched Core JAR and generated Java directory");
        }
        Path original = Path.of(args[0]);
        Set<Signature> supported = readLoggerMethods(Path.of(args[1]));
        Path output = Path.of(args[2]).toAbsolutePath();
        Path source = Path.of(args[3]).toAbsolutePath().resolve(BRIDGE + ".java");
        Map<Signature, Integer> wrappers = new LinkedHashMap<>();
        int[] coreHooks = {0, 0, 0};

        Files.createDirectories(output.getParent());
        Path temporaryJar = Files.createTempFile(output.getParent(), "core-logging-", ".jar");
        Path temporarySource = null;
        try {
            try (JarFile input = new JarFile(original.toFile());
                 JarOutputStream patched = new JarOutputStream(Files.newOutputStream(temporaryJar))) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    byte[] bytes;
                    try (InputStream content = input.getInputStream(entry)) {
                        bytes = content.readAllBytes();
                    }
                    if (entry.getName().startsWith("org/mtr/core/") && entry.getName().endsWith(".class")) {
                        bytes = patchClass(bytes, supported, wrappers, coreHooks);
                    }
                    JarEntry copied = new JarEntry(entry);
                    copied.setSize(bytes.length);
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    copied.setCrc(crc.getValue());
                    // Deflated data is recompressed; its original compressed size is no longer valid.
                    copied.setCompressedSize(copied.getMethod() == ZipEntry.STORED ? bytes.length : -1);
                    patched.putNextEntry(copied);
                    patched.write(bytes);
                    patched.closeEntry();
                }
            }
            if (coreHooks[0] != 1 || coreHooks[1] != 1 || coreHooks[2] != 1) {
                throw new IllegalArgumentException("Expected exactly one Core S2C handoff, autoSave field and system map entry point: "
                    + coreHooks[0] + ", " + coreHooks[1] + ", " + coreHooks[2]);
            }
            Files.createDirectories(source.getParent());
            temporarySource = Files.createTempFile(source.getParent(), "LegacyLogging-", ".java");
            Files.writeString(temporarySource, bridgeSource(wrappers), StandardCharsets.UTF_8);
            replace(temporarySource, source);
            replace(temporaryJar, output);
        } finally {
            Files.deleteIfExists(temporaryJar);
            if (temporarySource != null) Files.deleteIfExists(temporarySource);
        }
    }

    private static Set<Signature> readLoggerMethods(Path api) throws IOException {
        Set<Signature> supported = new HashSet<>();
        try (JarFile jar = new JarFile(api.toFile())) {
            JarEntry logger = jar.getJarEntry(LOGGER + ".class");
            if (logger == null) throw new IllegalArgumentException("Target API does not contain " + LOGGER);
            try (InputStream content = jar.getInputStream(logger)) {
                new ClassReader(content.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM8) {
                    @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                        if (!LOGGER.equals(name) || (access & Opcodes.ACC_INTERFACE) == 0) {
                            throw new IllegalArgumentException("Target Logger must be the Log4j Logger interface");
                        }
                    }

                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if ((access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) == 0) {
                            supported.add(new Signature(name, descriptor));
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        return supported;
    }

    private static byte[] patchClass(byte[] original, Set<Signature> supported, Map<Signature, Integer> wrappers, int[] coreHooks) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean simulator = SIMULATOR.equals(reader.getClassName());
        boolean systemMap = SYSTEM_MAP.equals(reader.getClassName());
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM8, writer) {
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (simulator && name.equals("autoSave")) {
                    if (!descriptor.equals("Z") || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != 0) {
                        throw new IllegalArgumentException("Unexpected Core autoSave field: " + descriptor);
                    }
                    coreHooks[1]++;
                    access |= Opcodes.ACC_VOLATILE;
                    changed[0] = true;
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (systemMap && name.equals("getContent")) {
                    if (!descriptor.equals(MAP_CONTENT) || (access & Opcodes.ACC_PUBLIC) == 0
                        || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        throw new IllegalArgumentException("Unexpected Core system map entry point: " + descriptor);
                    }
                    coreHooks[2]++;
                    // One servlet serves all dimension threads and owns their shared mutable cache maps.
                    access |= Opcodes.ACC_SYNCHRONIZED;
                    changed[0] = true;
                }
                boolean handoff = simulator && name.equals("sendMessageS2C");
                if (handoff) {
                    if (!descriptor.equals(SEND_S2C) || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        throw new IllegalArgumentException("Unexpected Core S2C handoff method: " + descriptor);
                    }
                    coreHooks[0]++;
                    changed[0] = true;
                }
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM8, method) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (handoff) {
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HANDOFF, "detach", "(" + SERIALIZED + "L" + SIMULATOR + ";)" + SERIALIZED, false);
                            super.visitVarInsn(Opcodes.ASTORE, 2);
                        }
                    }

                    @Override public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean isInterface) {
                        if (owner.equals(HANDOFF) && called.equals("detach")) {
                            throw new IllegalArgumentException("Core S2C handoff is already patched");
                        }
                        Signature invocation = new Signature(called, desc);
                        if (LOGGER.equals(owner) && !supported.contains(invocation)) {
                            Type[] arguments = Type.getArgumentTypes(desc);
                            if (opcode != Opcodes.INVOKEINTERFACE || !isInterface || !Type.VOID_TYPE.equals(Type.getReturnType(desc))
                                || arguments.length < 2 || !STRING.equals(arguments[0])
                                || !supported.contains(new Signature(called, VARARGS))) {
                                throw new IllegalArgumentException("Unsupported Logger invocation in " + reader.getClassName() + ": " + called + desc);
                            }
                            for (int i = 1; i < arguments.length; i++) {
                                if (!OBJECT.equals(arguments[i])) {
                                    throw new IllegalArgumentException("Unsupported Logger argument in " + reader.getClassName() + ": " + called + desc);
                                }
                            }
                            wrappers.putIfAbsent(invocation, arguments.length - 1);
                            changed[0] = true;
                            // Prepending the receiver preserves the operand stack and existing stack-map frames.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, called, "(L" + LOGGER + ";" + desc.substring(1), false);
                        } else {
                            super.visitMethodInsn(opcode, owner, called, desc, isInterface);
                        }
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : original;
    }

    private static String bridgeSource(Map<Signature, Integer> wrappers) {
        StringBuilder source = new StringBuilder("package org.mtr.gtnh.compat;\n\nimport org.apache.logging.log4j.Logger;\n\n"
            + "public final class LegacyLogging {\n    private LegacyLogging() {}\n");
        wrappers.forEach((signature, count) -> {
            source.append("\n    public static void ").append(signature.name()).append("(Logger logger, String message");
            for (int i = 0; i < count; i++) source.append(", Object arg").append(i);
            source.append(") {\n        logger.").append(signature.name()).append("(message, new Object[]{");
            for (int i = 0; i < count; i++) {
                if (i > 0) source.append(", ");
                source.append("arg").append(i);
            }
            source.append("});\n    }\n");
        });
        return source.append("}\n").toString();
    }

    private static void replace(Path temporary, Path output) throws IOException {
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Signature(String name, String descriptor) {}
}
