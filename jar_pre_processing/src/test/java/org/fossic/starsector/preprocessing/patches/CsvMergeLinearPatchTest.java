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

final class CsvMergeLinearPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/loading/LoadingUtils.class";
    private static final String HELPER =
            "org/fossic/starsector/optimization/CsvMergeOptimizer";

    @Test
    void realLoadingUtilsUsesBothLinearMergeContainers()
            throws IOException {
        ClassNode loadingUtils = load();

        var result = new CsvMergeLinearPatch().applyAndVerify(
                loadingUtils,
                new PatchContext(JarWorkspace.OBF_JAR, TARGET));
        result.requireSuccess();

        assertEquals(1, AsmUtil.countMethodCall(
                loadingUtils,
                HELPER,
                "baseFirstRows",
                "()Ljava/util/List;"));
        assertEquals(1, AsmUtil.countMethodCall(
                loadingUtils,
                HELPER,
                "overrideRows",
                "()Ljava/util/List;"));
        assertEquals(1, AsmUtil.countMethodCall(
                loadingUtils,
                HELPER,
                "putMovingToEnd",
                "(Ljava/util/List;Ljava/lang/String;Ljava/lang/String;"
                        + "Ljava/lang/Object;)V"));
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
}
