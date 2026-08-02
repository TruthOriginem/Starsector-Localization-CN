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

final class PersistentCacheCleanupPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/title/TitleScreenState.class";
    private static final String OWNER =
            "org/fossic/starsector/optimization/PersistentCacheMaintenance";

    @Test
    void schedulesCleanupAfterTitlePreparationCompletes()
            throws IOException {
        ClassNode titleScreen = load();

        new PersistentCacheCleanupPatch().applyAndVerify(
                titleScreen,
                new PatchContext(JarWorkspace.OBF_JAR, TARGET));

        assertEquals(1, AsmUtil.countMethodCall(
                titleScreen, OWNER, "onStartupComplete", "()V"));
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
