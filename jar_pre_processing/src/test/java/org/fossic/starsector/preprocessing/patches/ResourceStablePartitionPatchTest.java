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

final class ResourceStablePartitionPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/loading/ResourceLoaderState.class";
    private static final String PARTITION =
            "org/fossic/starsector/optimization/StableListPartition";

    @Test
    void patchesAndVerifiesTheRealResourceLoader()
            throws IOException, ClassNotFoundException {
        ClassNode resourceLoader = load();

        new ResourceStablePartitionPatch().applyAndVerify(
                resourceLoader,
                new PatchContext(JarWorkspace.OBF_JAR, TARGET))
                .requireSuccess();

        assertEquals(1, AsmUtil.countMethodCall(
                resourceLoader,
                PARTITION,
                "prioritizedSubsequenceFirst",
                "(Ljava/util/List;Ljava/util/List;)V"));
        assertEquals(0, AsmUtil.countMethodCall(
                resourceLoader,
                "java/util/List",
                "removeAll",
                "(Ljava/util/Collection;)Z"));
        assertEquals(0, AsmUtil.countMethodCall(
                resourceLoader,
                "java/util/List",
                "addAll",
                "(ILjava/util/Collection;)Z"));
        GameDataPatchVerifier.roundTrip(resourceLoader);
        GameDataPatchVerifier.verifyWithJvm(resourceLoader);
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
