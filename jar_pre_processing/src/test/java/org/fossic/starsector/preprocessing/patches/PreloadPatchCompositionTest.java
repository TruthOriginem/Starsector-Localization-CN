package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.Test;

final class PreloadPatchCompositionTest {
    private static final String OUTER = "com/fs/graphics/L.class";
    private static final String WORKER = "com/fs/graphics/L$1.class";

    @Test
    void shutdownInvalidatesCoordinatorBeforeClearingResultMaps()
            throws IOException {
        ClassNode outer = load(OUTER);
        ClassNode worker = load(WORKER);
        PatchContext outerContext = new PatchContext(
                JarWorkspace.COMMON_OBF_JAR, OUTER);
        PatchContext workerContext = new PatchContext(
                JarWorkspace.COMMON_OBF_JAR, WORKER);

        PreloadResultCoordinatorPatch coordination =
                new PreloadResultCoordinatorPatch();
        coordination.applyAndVerify(outer, outerContext).requireSuccess();
        coordination.applyAndVerify(worker, workerContext).requireSuccess();
        new ParallelImagePreloadPatch()
                .applyAndVerify(outer, outerContext)
                .requireSuccess();

        MethodNode shutdown = outer.methods.stream()
                .filter(method -> "Ò00000".equals(method.name))
                .filter(method -> "()V".equals(method.desc))
                .findFirst()
                .orElseThrow();
        List<String> lifecycleCalls = AsmUtil.instructions(shutdown)
                .stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> ("java/lang/Thread".equals(call.owner)
                                && "interrupt".equals(call.name))
                        || ("org/fossic/starsector/optimization/"
                                + "ParallelImagePreloader")
                                .equals(call.owner)
                        || ("org/fossic/starsector/optimization/"
                                + "PreloadResultCoordinator")
                                .equals(call.owner)
                        || ("java/util/Map".equals(call.owner)
                                && "clear".equals(call.name)))
                .map(call -> call.owner + "." + call.name)
                .toList();

        assertEquals(List.of(
                "java/lang/Thread.interrupt",
                "org/fossic/starsector/optimization/"
                        + "ParallelImagePreloader.stop",
                "org/fossic/starsector/optimization/"
                        + "PreloadResultCoordinator.clear",
                "java/util/Map.clear",
                "java/util/Map.clear"), lifecycleCalls);
    }

    private static ClassNode load(String target) throws IOException {
        Path jar = Path.of("..", "game data", "fs.common_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(target)))
                    .accept(node, 0);
            return node;
        }
    }
}
