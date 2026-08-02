package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.junit.jupiter.api.Test;

final class PreloadPathDedupPatchTest {
    private static final String TARGET = "com/fs/graphics/L.class";
    private static final String HELPER =
            "org/fossic/starsector/optimization/PreloadResultCoordinator";
    private static final String ENQUEUE_DESC =
            "(Ljava/util/List;Ljava/lang/String;)V";

    @Test
    void switchesOnlyTheRealImageQueueBridgeToUniqueEnqueue()
            throws IOException {
        ClassNode preloader = load();
        PatchContext context = new PatchContext(
                JarWorkspace.COMMON_OBF_JAR, TARGET);
        new PreloadResultCoordinatorPatch().applyAndVerify(
                preloader, context);

        new PreloadPathDedupPatch().applyAndVerify(
                preloader, context);

        assertEquals(1, AsmUtil.countMethodCall(
                preloader,
                HELPER,
                "queueImageUnique",
                ENQUEUE_DESC));
        assertEquals(0, AsmUtil.countMethodCall(
                preloader,
                HELPER,
                "queueImage",
                ENQUEUE_DESC));
        assertEquals(1, AsmUtil.countMethodCall(
                preloader,
                HELPER,
                "queueSound",
                ENQUEUE_DESC));
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
}
