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

final class LoadingUtilsResourceStreamSafetyPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/loading/LoadingUtils.class";
    private static final String BINARY_NAME =
            "com.fs.starfarer.loading.LoadingUtils";
    private static final String HELPER =
            "org/fossic/starsector/optimization/OwnedResourceStreams";
    private static final String ENTER_DESC = "(Ljava/lang/Iterable;)V";
    private static final String FAILURE_CLOSE_DESC =
            "(Ljava/lang/Throwable;)V";
    private static final String CLOSE_AND_FORGET_DESC =
            "(Ljava/io/InputStream;)V";
    private static final String FAST_TEXT =
            "org/fossic/starsector/optimization/FastTextReader";
    private static final String TEXT_READER_DESC =
            "(Ljava/io/InputStream;)Ljava/lang/String;";

    @Test
    void allFourEagerMultiStreamMethodsReceiveOwnedScopes()
            throws IOException {
        ClassNode node = patchedRealClass();

        assertEquals(4, AsmUtil.countMethodCall(
                node, HELPER, "enterPairStreams", ENTER_DESC));
        assertEquals(5, AsmUtil.countMethodCall(
                node, HELPER, "closeCurrentBeforeReturn", "()V"));
        assertEquals(4, AsmUtil.countMethodCall(
                node,
                HELPER,
                "closeCurrentAfterFailure",
                FAILURE_CLOSE_DESC));
        assertEquals(0, AsmUtil.countMethodCall(
                node,
                HELPER,
                "forgetCurrentPairStream",
                "(Ljava/io/InputStream;)V"));
        assertEquals(4, AsmUtil.countMethodCall(
                node,
                HELPER,
                "closeAndForgetCurrentPairStream",
                CLOSE_AND_FORGET_DESC));
        assertEquals(0, AsmUtil.countMethodCall(
                node, FAST_TEXT, "readTracked", TEXT_READER_DESC));

        for (MethodNode method : ownedMethods(node)) {
            TryCatchBlockNode cleanup = method.tryCatchBlocks.stream()
                    .filter(block -> block.type == null)
                    .filter(block -> handlerCalls(
                            block,
                            "closeCurrentAfterFailure",
                            FAILURE_CLOSE_DESC))
                    .findFirst()
                    .orElseThrow();
            AbstractInsnNode frame = cleanup.handler.getNext();
            assertTrue(frame instanceof FrameNode, method.name + method.desc);
            FrameNode full = (FrameNode) frame;
            assertEquals(Opcodes.F_FULL, full.type);
            assertEquals(
                    java.util.List.of("java/lang/Throwable"),
                    full.stack);
        }
    }

    @Test
    void transformedRealLoadingUtilsPassesTheJvmVerifier()
            throws Exception {
        ClassNode node = patchedRealClass();
        verifyWithJvm(node);
    }

    @Test
    void composesWithAllEarlierLoadingUtilsOptimizationsAndVerifies()
            throws Exception {
        ClassNode node = load();
        PatchContext context = new PatchContext(
                JarWorkspace.OBF_JAR, TARGET);
        new LoadingUtilsTextReadPatch()
                .applyAndVerify(node, context).requireSuccess();
        new CsvMergeLinearPatch()
                .applyAndVerify(node, context).requireSuccess();
        new ParallelSpecParsePatch()
                .applyAndVerify(node, context).requireSuccess();
        new LoadingUtilsResourceStreamSafetyPatch()
                .applyAndVerify(node, context).requireSuccess();

        assertEquals(4, AsmUtil.countMethodCall(
                node, HELPER, "enterPairStreams", ENTER_DESC));
        assertEquals(0, AsmUtil.countMethodCall(
                node,
                HELPER,
                "forgetCurrentPairStream",
                "(Ljava/io/InputStream;)V"));
        assertEquals(1, AsmUtil.countMethodCall(
                node,
                HELPER,
                "closeAndForgetCurrentPairStream",
                CLOSE_AND_FORGET_DESC));
        assertEquals(1, AsmUtil.countMethodCall(
                node, FAST_TEXT, "readTracked", TEXT_READER_DESC));
        assertEquals(0, AsmUtil.countMethodCall(
                node, FAST_TEXT, "read", TEXT_READER_DESC));
        verifyWithJvm(node);
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
                    .map(LoadingUtilsResourceStreamSafetyPatchTest::toUrl)
                    .toArray(URL[]::new);
        }

        try (VerificationLoader loader = new VerificationLoader(
                gameJars,
                LoadingUtilsResourceStreamSafetyPatchTest.class
                        .getClassLoader(),
                definitions)) {
            assertDoesNotThrow(() ->
                    Class.forName(BINARY_NAME, true, loader));
        }
    }

    private static boolean handlerCalls(
            TryCatchBlockNode block,
            String name,
            String desc) {
        for (AbstractInsnNode current = block.handler;
                current != null;
                current = current.getNext()) {
            if (current instanceof MethodInsnNode call
                    && HELPER.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<MethodNode> ownedMethods(ClassNode node) {
        return node.methods.stream()
                .filter(method -> method.desc.equals(
                                "(Ljava/util/List;Ljava/lang/String;ZZ)"
                                        + "Lorg/json/JSONArray;")
                        || method.desc.equals(
                                "(Ljava/lang/String;Ljava/util/Set;)"
                                        + "Lorg/json/JSONObject;")
                        || method.desc.equals(
                                "(Ljava/lang/String;Ljava/lang/String;"
                                        + "Ljava/lang/String;)"
                                        + "Lorg/json/JSONArray;")
                        || method.desc.equals(
                                "(Ljava/lang/String;Ljava/lang/String;)"
                                        + "Lorg/json/JSONObject;"))
                .filter(method -> AsmUtil.instructions(method).stream()
                        .filter(MethodInsnNode.class::isInstance)
                        .map(MethodInsnNode.class::cast)
                        .anyMatch(call -> HELPER.equals(call.owner)
                                && "enterPairStreams".equals(call.name)
                                && ENTER_DESC.equals(call.desc)))
                .toList();
    }

    private static ClassNode patchedRealClass() throws IOException {
        ClassNode node = load();
        new LoadingUtilsResourceStreamSafetyPatch()
                .applyAndVerify(
                        node,
                        new PatchContext(JarWorkspace.OBF_JAR, TARGET))
                .requireSuccess();
        return node;
    }

    private static ClassNode load() throws IOException {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(TARGET)))
                    .accept(node, 0);
            return node;
        }
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
            if ("org.apache.log4j.Logger".equals(name)) {
                byte[] stub = loggerStub();
                return defineClass(name, stub, 0, stub.length);
            }
            if (name.startsWith("org.json.")) {
                byte[] stub = jsonStub(name);
                return defineClass(name, stub, 0, stub.length);
            }
            return super.findClass(name);
        }

        private static byte[] loggerStub() {
            ClassWriter writer = new ClassWriter(
                    ClassWriter.COMPUTE_MAXS
                            | ClassWriter.COMPUTE_FRAMES);
            writer.visit(
                    Opcodes.V17,
                    Opcodes.ACC_PUBLIC,
                    "org/apache/log4j/Logger",
                    null,
                    "java/lang/Object",
                    null);
            var constructor = writer.visitMethod(
                    Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/Object",
                    "<init>",
                    "()V",
                    false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(0, 0);
            constructor.visitEnd();
            var getLogger = writer.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "getLogger",
                    "(Ljava/lang/Class;)Lorg/apache/log4j/Logger;",
                    null,
                    null);
            getLogger.visitCode();
            getLogger.visitTypeInsn(
                    Opcodes.NEW, "org/apache/log4j/Logger");
            getLogger.visitInsn(Opcodes.DUP);
            getLogger.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "org/apache/log4j/Logger",
                    "<init>",
                    "()V",
                    false);
            getLogger.visitInsn(Opcodes.ARETURN);
            getLogger.visitMaxs(0, 0);
            getLogger.visitEnd();
            writer.visitEnd();
            return writer.toByteArray();
        }

        private static byte[] jsonStub(String binaryName) {
            String internal = binaryName.replace('.', '/');
            String parent = "org.json.JSONException".equals(binaryName)
                    ? "java/lang/Exception"
                    : "java/lang/Object";
            ClassWriter writer = new ClassWriter(
                    ClassWriter.COMPUTE_MAXS
                            | ClassWriter.COMPUTE_FRAMES);
            writer.visit(
                    Opcodes.V17,
                    Opcodes.ACC_PUBLIC,
                    internal,
                    null,
                    parent,
                    null);
            writer.visitEnd();
            return writer.toByteArray();
        }
    }
}
