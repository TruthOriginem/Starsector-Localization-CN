package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.junit.jupiter.api.Test;

final class ParallelSpecParsePatchTest {
    private static final String TARGET =
            "com/fs/starfarer/loading/SpecStore.class";
    private static final String HELPER =
            "org/fossic/starsector/optimization/OrderedParallelLoader";

    @Test
    void realVariantLoopUsesOrderedParallelJsonStage()
            throws IOException {
        ClassNode specStore = load(TARGET);

        var result = new ParallelSpecParsePatch().applyAndVerify(
                specStore,
                new PatchContext(JarWorkspace.OBF_JAR, TARGET));
        result.requireSuccess();

        assertEquals(1, AsmUtil.countMethodCall(
                specStore,
                HELPER,
                "begin",
                "(Ljava/util/List;Ljava/lang/invoke/MethodHandle;)V"));
        assertEquals(1, AsmUtil.countMethodCall(
                specStore,
                HELPER,
                "load",
                "(Ljava/lang/String;Ljava/lang/invoke/MethodHandle;)"
                        + "Ljava/lang/Object;"));
        assertEquals(1, AsmUtil.countMethodCall(
                specStore,
                HELPER,
                "finish",
                "()V"));
        assertEquals(1, AsmUtil.countMethodCall(
                specStore, HELPER, "abort", "()V"));
        assertAbortHandlers(specStore, 1);
        assertAbortHandlers(roundTrip(specStore), 1);
    }

    @Test
    void realWeaponLoopsUseFourOrderedStagesAndTwoJsonBridges()
            throws IOException {
        String target = "com/fs/starfarer/loading/WeaponSpecLoader.class";
        ClassNode loader = load(target);

        new ParallelSpecParsePatch().applyAndVerify(
                loader,
                new PatchContext(JarWorkspace.OBF_JAR, target))
                .requireSuccess();

        assertEquals(4, AsmUtil.countMethodCall(
                loader, HELPER, "begin",
                "(Ljava/util/List;Ljava/lang/invoke/MethodHandle;)V"));
        assertEquals(2, AsmUtil.countMethodCall(
                loader, HELPER, "load",
                "(Ljava/lang/String;Ljava/lang/invoke/MethodHandle;)"
                        + "Ljava/lang/Object;"));
        assertEquals(4, AsmUtil.countMethodCall(
                loader, HELPER, "finish", "()V"));
        assertEquals(4, AsmUtil.countMethodCall(
                loader, HELPER, "abort", "()V"));
        assertAbortHandlers(loader, 4);
        assertAbortHandlers(roundTrip(loader), 4);
    }

    @Test
    void realHullAndSkinLoopsUseTwoOrderedStagesAndJsonBridges()
            throws IOException {
        String target = "com/fs/starfarer/loading/ShipHullSpecLoader.class";
        ClassNode loader = load(target);

        new ParallelSpecParsePatch().applyAndVerify(
                loader,
                new PatchContext(JarWorkspace.OBF_JAR, target))
                .requireSuccess();

        assertEquals(2, AsmUtil.countMethodCall(
                loader, HELPER, "begin",
                "(Ljava/util/List;Ljava/lang/invoke/MethodHandle;)V"));
        assertEquals(2, AsmUtil.countMethodCall(
                loader, HELPER, "load",
                "(Ljava/lang/String;Ljava/lang/invoke/MethodHandle;)"
                        + "Ljava/lang/Object;"));
        assertEquals(2, AsmUtil.countMethodCall(
                loader, HELPER, "finish", "()V"));
        assertEquals(2, AsmUtil.countMethodCall(
                loader, HELPER, "abort", "()V"));
        assertAbortHandlers(loader, 2);
        assertAbortHandlers(roundTrip(loader), 2);
    }

    @Test
    void appendedCatchAllIsJvmVerifiableWithClassWriterZero()
            throws ReflectiveOperationException {
        ClassNode generated = new ClassNode();
        generated.version = Opcodes.V17;
        generated.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        generated.name = "org/fossic/starsector/preprocessing/patches/"
                + "GeneratedAbortHandlerFixture";
        generated.superName = "java/lang/Object";

        MethodNode run = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run",
                "()V",
                null,
                null);
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        run.instructions.add(tryStart);
        run.instructions.add(new InsnNode(Opcodes.NOP));
        run.instructions.add(tryEnd);
        run.instructions.add(new InsnNode(Opcodes.RETURN));
        ParallelSpecParsePatch.appendAbortHandler(run, tryStart, tryEnd);
        run.maxStack = 1;
        run.maxLocals = 0;
        generated.methods.add(run);

        byte[] bytes = write(generated);
        Class<?> fixture = new VerifyingClassLoader()
                .defineAndResolve(bytes);

        // Invocation forces HotSpot to verify the method body as well as link
        // the generated class.  A missing handler frame fails here with a
        // VerifyError when ClassWriter(0) is used.
        fixture.getMethod("run").invoke(null);
    }

    @Test
    void realLoadingUtilsRoutesInfoThroughOrderedCapture()
            throws IOException {
        String target = "com/fs/starfarer/loading/LoadingUtils.class";
        ClassNode loadingUtils = load(target);

        new ParallelSpecParsePatch().applyAndVerify(
                loadingUtils,
                new PatchContext(JarWorkspace.OBF_JAR, target))
                .requireSuccess();

        assertEquals(15, AsmUtil.countMethodCall(
                loadingUtils,
                HELPER,
                "info",
                "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        assertEquals(0, AsmUtil.countMethodCall(
                loadingUtils,
                "org/apache/log4j/Logger",
                "info",
                "(Ljava/lang/Object;)V"));
    }

    private static ClassNode load(String target) throws IOException {
        Path jar = Path.of("..", "game data", "starfarer_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(target)))
                    .accept(node, 0);
            return node;
        }
    }

    private static void assertAbortHandlers(
            ClassNode classNode, int expected) {
        List<TryCatchBlockNode> handlers = classNode.methods.stream()
                .flatMap(method -> method.tryCatchBlocks.stream())
                .filter(block -> block.type == null)
                .filter(ParallelSpecParsePatchTest::isAbortHandler)
                .toList();
        assertEquals(expected, handlers.size());
        for (TryCatchBlockNode handler : handlers) {
            FrameNode frame = assertInstanceOf(
                    FrameNode.class, handler.handler.getNext());
            assertEquals(Opcodes.F_FULL, frame.type);
            assertTrue(frame.local == null || frame.local.isEmpty());
            assertEquals(List.of("java/lang/Throwable"), frame.stack);

            MethodInsnNode abort = assertInstanceOf(
                    MethodInsnNode.class,
                    AsmUtil.nextReal(handler.handler));
            assertEquals(HELPER, abort.owner);
            assertEquals("abort", abort.name);
            assertEquals("()V", abort.desc);
            assertEquals(Opcodes.ATHROW,
                    AsmUtil.nextReal(abort).getOpcode());
        }
    }

    private static boolean isAbortHandler(TryCatchBlockNode block) {
        AbstractInsnNode first = AsmUtil.nextReal(block.handler);
        return first instanceof MethodInsnNode call
                && HELPER.equals(call.owner)
                && "abort".equals(call.name)
                && "()V".equals(call.desc);
    }

    private static ClassNode roundTrip(ClassNode source) {
        ClassNode result = new ClassNode();
        new ClassReader(write(source)).accept(result, 0);
        return result;
    }

    private static byte[] write(ClassNode source) {
        ClassWriter writer = new ClassWriter(0);
        source.accept(writer);
        return writer.toByteArray();
    }

    private static final class VerifyingClassLoader extends ClassLoader {
        private VerifyingClassLoader() {
            super(ParallelSpecParsePatchTest.class.getClassLoader());
        }

        private Class<?> defineAndResolve(byte[] bytes) {
            Class<?> defined = defineClass(null, bytes, 0, bytes.length);
            resolveClass(defined);
            return defined;
        }
    }
}
