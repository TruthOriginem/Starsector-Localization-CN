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

final class HeadlessConsoleLogPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/combat/CombatMain.class";
    private static final String HELPER =
            "org/fossic/starsector/optimization/StartupLogConfigurator";

    @Test
    void insertsOneNoArgumentConfigurationCallIntoRealMain()
            throws IOException {
        ClassNode combatMain = load();

        new HeadlessConsoleLogPatch().applyAndVerify(
                combatMain,
                new PatchContext(JarWorkspace.OBF_JAR, TARGET));

        assertEquals(1, AsmUtil.countMethodCall(
                combatMain, HELPER, "configure", "()V"));
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
