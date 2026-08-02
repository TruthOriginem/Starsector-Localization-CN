package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.junit.jupiter.api.Test;

final class ResourceLoaderStreamSafetyPatchTest {
    private static final String TARGET = "com/fs/util/C.class";
    private static final String BINARY_NAME = "com.fs.util.C";
    private static final String HELPER =
            "org/fossic/starsector/optimization/OwnedResourceStreams";
    private static final String TRACK_DESC =
            "(Ljava/io/InputStream;)Ljava/io/InputStream;";
    private static final String FAILURE_DESC =
            "(Ljava/lang/Throwable;)V";
    private static final String DISCARDED_DESC =
            "(Ljava/io/InputStream;Z)Z";

    @Test
    void safetyOnlyClosesPartialMultiOpenListOnEveryThrowable()
            throws Exception {
        ClassNode node = load();

        new ResourceLoaderStreamSafetyPatch()
                .applyAndVerify(node, context()).requireSuccess();

        assertHandlerAndVerify(node);
    }

    @Test
    void composesAfterBothResourceLockPatchesAndVerifies()
            throws Exception {
        ClassNode node = load();
        new ResourceLeafSynchronizationPatch()
                .applyAndVerify(node, context()).requireSuccess();
        new ResourceLookupSynchronizationPatch()
                .applyAndVerify(node, context()).requireSuccess();

        new ResourceLoaderStreamSafetyPatch()
                .applyAndVerify(node, context()).requireSuccess();

        assertHandlerAndVerify(node);
    }

    private static void assertHandlerAndVerify(ClassNode node)
            throws Exception {
        assertEquals(1, AsmUtil.countMethodCall(
                node, HELPER, "enterPartialOpen", "()V"));
        assertEquals(1, AsmUtil.countMethodCall(
                node, HELPER, "trackPartialOpenStream", TRACK_DESC));
        assertEquals(1, AsmUtil.countMethodCall(
                node, HELPER, "releasePartialOpen", "()V"));
        assertEquals(1, AsmUtil.countMethodCall(
                node,
                HELPER,
                "closePartialOpenAfterFailure",
                FAILURE_DESC));
        assertEquals(1, AsmUtil.countMethodCall(
                node, HELPER, "closeIfDiscarded", DISCARDED_DESC));
        MethodNode method = node.methods.stream()
                .filter(candidate -> AsmUtil.instructions(candidate).stream()
                        .filter(MethodInsnNode.class::isInstance)
                        .map(MethodInsnNode.class::cast)
                        .anyMatch(call -> HELPER.equals(call.owner)
                                && "enterPartialOpen".equals(call.name)
                                && "()V".equals(call.desc)))
                .findFirst()
                .orElseThrow();
        TryCatchBlockNode cleanup = method.tryCatchBlocks.stream()
                .filter(block -> block.type == null)
                .filter(block -> handlerCalls(block))
                .findFirst()
                .orElseThrow();
        AbstractInsnNode afterLabel = cleanup.handler.getNext();
        assertTrue(afterLabel instanceof FrameNode);
        FrameNode frame = (FrameNode) afterLabel;
        assertEquals(Opcodes.F_FULL, frame.type);
        assertEquals(
                java.util.List.of("java/lang/Throwable"),
                frame.stack);
        AbstractInsnNode duplicate = nextReal(frame);
        assertEquals(Opcodes.DUP, duplicate.getOpcode());
        MethodInsnNode close = (MethodInsnNode) nextReal(duplicate);
        assertEquals("closePartialOpenAfterFailure", close.name);
        assertEquals(FAILURE_DESC, close.desc);
        assertEquals(Opcodes.ATHROW, nextReal(close).getOpcode());
        verifyWithJvm(node);
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private static boolean handlerCalls(TryCatchBlockNode block) {
        for (AbstractInsnNode node = block.handler;
                node != null;
                node = node.getNext()) {
            if (node instanceof MethodInsnNode call
                    && HELPER.equals(call.owner)
                    && "closePartialOpenAfterFailure".equals(call.name)
                    && FAILURE_DESC.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static void verifyWithJvm(ClassNode node) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Map<String, byte[]> definitions = Map.of(
                BINARY_NAME, writer.toByteArray());
        URL[] gameJars;
        try (Stream<Path> jars = Files.list(
                Path.of("..", "game data"))) {
            gameJars = jars
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".jar"))
                    .map(ResourceLoaderStreamSafetyPatchTest::toUrl)
                    .toArray(URL[]::new);
        }

        try (VerificationLoader loader = new VerificationLoader(
                gameJars,
                ResourceLoaderStreamSafetyPatchTest.class.getClassLoader(),
                definitions)) {
            assertDoesNotThrow(() ->
                    Class.forName(BINARY_NAME, true, loader));
        }
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

    private static PatchContext context() {
        return new PatchContext(JarWorkspace.COMMON_OBF_JAR, TARGET);
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class VerificationLoader extends URLClassLoader {
        private final Map<String, byte[]> definitions;

        private VerificationLoader(
                URL[] urls,
                ClassLoader parent,
                Map<String, byte[]> definitions) {
            super(urls, parent);
            this.definitions = new HashMap<>(definitions);
        }

        @Override
        protected Class<?> findClass(String name)
                throws ClassNotFoundException {
            byte[] definition = definitions.remove(name);
            if (definition != null) {
                return defineClass(name, definition, 0, definition.length);
            }
            return super.findClass(name);
        }
    }
}
