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

final class RulesDuplicateIdPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/campaign/rules/Rules.class";
    private static final String TRACKER =
            "org/fossic/starsector/optimization/RuleIdTracker";

    @Test
    void patchesAndVerifiesTheRealRulesLoader()
            throws IOException, ClassNotFoundException {
        ClassNode rules = load(
                Path.of("..", "game data", "starfarer_obf.jar"), TARGET);

        new RulesDuplicateIdPatch().applyAndVerify(
                rules,
                new PatchContext(JarWorkspace.OBF_JAR, TARGET))
                .requireSuccess();

        assertEquals(1, AsmUtil.countMethodCall(
                rules, TRACKER, "reset", "()V"));
        assertEquals(1, AsmUtil.countMethodCall(
                rules,
                TRACKER,
                "candidates",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)"
                        + "Ljava/util/List;"));
        assertEquals(1, AsmUtil.countMethodCall(
                rules, TRACKER, "finish", "()V"));
        GameDataPatchVerifier.roundTrip(rules);
        GameDataPatchVerifier.verifyWithJvm(rules);
    }

    private static ClassNode load(Path jar, String entryName)
            throws IOException {
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(entryName)))
                    .accept(node, 0);
            return node;
        }
    }
}
