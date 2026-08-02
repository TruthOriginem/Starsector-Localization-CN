package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import org.fossic.starsector.optimization.SpeculativeResourceContext;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.Test;

final class ResourceLookupSynchronizationPatchTest {
    private static final String TARGET = "com/fs/util/C.class";
    private static final String CONTEXT =
            "org/fossic/starsector/optimization/SpeculativeResourceContext";
    private static final String OWNER = "com/fs/util/C";
    private static final String OPEN_CONTEXT = "$fossic$takeOpenContext";

    @Test
    void speculativeWorkersDoNotConsumeOneShotResourceState()
            throws ReflectiveOperationException, IOException {
        ClassNode resourceLoader = load();

        PatchContext patchContext = new PatchContext(
                JarWorkspace.COMMON_OBF_JAR, TARGET);
        new ResourceStreamDynFontPatch().applyAndVerify(
                resourceLoader, patchContext).requireSuccess();
        new ResourceLeafSynchronizationPatch().applyAndVerify(
                resourceLoader, patchContext).requireSuccess();
        new ResourceLookupSynchronizationPatch().applyAndVerify(
                resourceLoader, patchContext)
                .requireSuccess();

        assertEquals(1, AsmUtil.countMethodCall(
                resourceLoader, CONTEXT, "isActive", "()Z"));
        assertOpenContextFrames(resourceLoader);
        assertDynFontBranchFrame(resourceLoader);

        Path commonJar = Path.of("..", "game data", "fs.common_obf.jar");
        try (PatchedClassLoader loader = new PatchedClassLoader(
                commonJar.toUri().toURL())) {
            Class<?> type = loader.defineAndResolve(write(resourceLoader));
            Object instance = type.getConstructor().newInstance();
            Field rootsField = uniqueField(type, List.class, false);
            Field selectorField = uniqueField(type, String.class, false);
            Field skipModsField = uniqueField(type, boolean.class, true);
            Method takeContext = type.getDeclaredMethod(
                    OPEN_CONTEXT, boolean.class);
            rootsField.setAccessible(true);
            selectorField.setAccessible(true);
            skipModsField.setAccessible(true);
            takeContext.setAccessible(true);

            ArrayList<Object> roots = new ArrayList<>(List.of("root"));
            rootsField.set(instance, roots);
            selectorField.set(instance, "selected-mod");
            skipModsField.setBoolean(null, true);

            Object[] speculative;
            SpeculativeResourceContext.enter();
            try {
                speculative = (Object[]) takeContext.invoke(instance, true);
            } finally {
                SpeculativeResourceContext.exit();
            }

            assertNull(speculative[0]);
            assertEquals(Boolean.TRUE, speculative[1]);
            assertEquals(roots, speculative[2]);
            assertNotSame(roots, speculative[2]);
            assertEquals("selected-mod", selectorField.get(instance));
            assertTrue(skipModsField.getBoolean(null));
            assertFalse(SpeculativeResourceContext.isActive());

            Object[] ordinary = (Object[]) takeContext.invoke(instance, true);
            assertEquals("selected-mod", ordinary[0]);
            assertEquals(Boolean.FALSE, ordinary[1]);
            assertNull(selectorField.get(instance));
            assertFalse(skipModsField.getBoolean(null));

            // Both one-shot values were consumed exactly once by the ordinary
            // caller, not by the preceding speculative lookup.
            Object[] afterConsumption =
                    (Object[]) takeContext.invoke(instance, true);
            assertNull(afterConsumption[0]);
            assertEquals(Boolean.TRUE, afterConsumption[1]);
        }
    }

    private static void assertOpenContextFrames(ClassNode classNode) {
        MethodNode helper = classNode.methods.stream()
                .filter(method -> OPEN_CONTEXT.equals(method.name))
                .filter(method -> "(Z)[Ljava/lang/Object;".equals(method.desc))
                .findFirst()
                .orElseThrow();
        List<JumpInsnNode> branches = AsmUtil.instructions(helper).stream()
                .filter(JumpInsnNode.class::isInstance)
                .map(JumpInsnNode.class::cast)
                .filter(branch -> branch.getOpcode() == Opcodes.IFEQ)
                .toList();
        assertEquals(2, branches.size());

        FrameNode ordinary = assertInstanceOf(
                FrameNode.class, branches.get(0).label.getNext());
        assertEquals(Opcodes.F_FULL, ordinary.type);
        assertEquals(List.of(OWNER, Opcodes.INTEGER), ordinary.local);
        assertTrue(ordinary.stack == null || ordinary.stack.isEmpty());

        FrameNode noSkip = assertInstanceOf(
                FrameNode.class, branches.get(1).label.getNext());
        assertEquals(Opcodes.F_FULL, noSkip.type);
        assertEquals(List.of(
                OWNER, Opcodes.INTEGER, "[Ljava/lang/Object;"),
                noSkip.local);
        assertTrue(noSkip.stack == null || noSkip.stack.isEmpty());
    }

    private static void assertDynFontBranchFrame(ClassNode classNode) {
        MethodNode open = classNode.methods.stream()
                .filter(method -> "(Ljava/lang/String;)Ljava/io/InputStream;"
                        .equals(method.desc))
                .filter(method -> (method.access & Opcodes.ACC_STATIC) == 0)
                .findFirst()
                .orElseThrow();
        JumpInsnNode branch = AsmUtil.instructions(open).stream()
                .filter(JumpInsnNode.class::isInstance)
                .map(JumpInsnNode.class::cast)
                .filter(jump -> jump.getOpcode() == Opcodes.IFNULL)
                .findFirst()
                .orElseThrow();
        FrameNode frame = assertInstanceOf(
                FrameNode.class, branch.label.getNext());
        assertEquals(Opcodes.F_FULL, frame.type);
        assertEquals(List.of(OWNER, "java/lang/String"), frame.local);
        assertEquals(List.of("java/io/InputStream"), frame.stack);
    }

    private static Field uniqueField(
            Class<?> owner, Class<?> type, boolean requireStatic) {
        return Arrays.stream(owner.getDeclaredFields())
                .filter(field -> field.getType() == type)
                .filter(field -> Modifier.isStatic(field.getModifiers())
                        == requireStatic)
                .reduce((first, second) -> {
                    throw new AssertionError(
                            "Multiple fields of type " + type.getName());
                })
                .orElseThrow();
    }

    private static byte[] write(ClassNode source) {
        ClassWriter writer = new ClassWriter(0);
        source.accept(writer);
        return writer.toByteArray();
    }

    private static ClassNode load() throws IOException {
        Path jar = Path.of("..", "game data", "fs.common_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(TARGET)))
                    .accept(node, 0);
            return node;
        }
    }

    private static final class PatchedClassLoader extends URLClassLoader {
        private PatchedClassLoader(URL commonJar) {
            super(new URL[] {commonJar},
                    ResourceLookupSynchronizationPatchTest.class
                            .getClassLoader());
        }

        private Class<?> defineAndResolve(byte[] bytes) {
            Class<?> defined = defineClass(null, bytes, 0, bytes.length);
            resolveClass(defined);
            return defined;
        }
    }
}
