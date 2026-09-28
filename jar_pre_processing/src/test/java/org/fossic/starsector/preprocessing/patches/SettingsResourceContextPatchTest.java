package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.zip.ZipFile;
import org.fossic.starsector.optimization.ResourceReadContext;
import org.fossic.starsector.preprocessing.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Execute the real API method control flow, replacing only its game dependencies. */
public final class SettingsResourceContextPatchTest {
    private static IntConsumer probe;

    public static void onProbe(int index) { probe.accept(index); }

    @ParameterizedTest
    @ValueSource(strings = {"loadJSON", "loadCSV"})
    void clearsSkipFlagOnSuccessAndEveryFailureBoundary(String methodName) throws Exception {
        ClassNode node = new ClassNode();
        try (ZipFile zip = new ZipFile(Path.of("..", "game data", "starfarer_obf.jar").toFile())) {
            new ClassReader(zip.getInputStream(zip.getEntry(
                    "com/fs/starfarer/settings/StarfarerSettings$1.class"))).accept(node, 0);
        }
        var patch = new SettingsResourceContextPatch();
        var context = new PatchContext(JarWorkspace.OBF_JAR, node.name + ".class");
        patch.applyAndVerify(node, context).requireSuccess();
        assertThrows(PatchException.class, () -> patch.applyAndVerify(node, context));
        MethodNode method = node.methods.stream().filter(m -> m.name.equals(methodName)
                && m.desc.startsWith("(Ljava/lang/String;Z)")).findFirst().orElseThrow();
        method.exceptions.clear(); // Checked exception metadata needs the unrelated game JSON jar.
        ClassNode fixture = new ClassNode();
        fixture.version = Opcodes.V17;
        fixture.access = Opcodes.ACC_PUBLIC;
        fixture.name = node.name;
        fixture.superName = "java/lang/Object";
        fixture.methods.add(method);
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.maxStack = 1;
        constructor.maxLocals = 1;
        fixture.methods.add(constructor);
        Loader loader = new Loader();
        loader.define(emptyClass("org/json/JSONObject"));
        loader.define(emptyClass("org/json/JSONArray"));
        ClassWriter dependencies = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        dependencies.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Dependencies", null,
                "java/lang/Object", null);
        int calls = 0;
        for (AbstractInsnNode insn : AsmUtil.instructions(method)) {
            if (insn instanceof MethodInsnNode call
                    && !call.owner.endsWith("/ResourceReadContext")) {
                assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
                call.owner = "probe/Dependencies";
                call.name = "call" + calls;
                MethodVisitor stub = dependencies.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        call.name, call.desc, null, null);
                stub.visitCode();
                stub.visitLdcInsn(calls++);
                stub.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(getClass()),
                        "onProbe", "(I)V", false);
                if (Type.getReturnType(call.desc).getSort() == Type.VOID) {
                    stub.visitInsn(Opcodes.RETURN);
                } else {
                    stub.visitInsn(Opcodes.ACONST_NULL);
                    stub.visitInsn(Opcodes.ARETURN);
                }
                stub.visitMaxs(0, 0);
                stub.visitEnd();
            }
        }
        dependencies.visitEnd();
        loader.define(dependencies.toByteArray());
        ClassWriter writer = new ClassWriter(0);
        fixture.accept(writer);
        Class<?> type = loader.define(writer.toByteArray());
        Object instance = type.getConstructor().newInstance();
        var invoke = type.getMethod(methodName, String.class, boolean.class);
        RuntimeException sentinel = new RuntimeException("injected read failure");
        try {
            for (boolean includeMods : List.of(false, true)) {
                for (int boundary = -1; boundary < calls; boundary++) {
                    final int failAt = boundary;
                    AtomicInteger visited = new AtomicInteger();
                    probe = index -> {
                        visited.incrementAndGet();
                        if (index == failAt) throw sentinel;
                        if (index == 1) assertEquals(!includeMods,
                                ResourceReadContext.consumeSkipMods(false));
                    };
                    if (boundary < 0) {
                        assertNull(invoke.invoke(instance, "file", includeMods));
                    } else {
                        assertSame(sentinel, assertThrows(InvocationTargetException.class,
                                () -> invoke.invoke(instance, "file", includeMods)).getCause());
                    }
                    assertTrue(visited.get() > 0);
                    assertFalse(ResourceReadContext.consumeSkipMods(false), "leaked one-shot flag");
                }
            }
        } finally {
            ResourceReadContext.clearSkipMods();
            probe = null;
        }
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}
