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

final class CsvLazyErrorFormattingPatchTest {
    private static final String TARGET =
            "com/fs/starfarer/loading/G.class";
    private static final String FORMATTER =
            "org/fossic/starsector/optimization/CsvErrorFormatter";

    @Test
    void patchesAndVerifiesTheRealCsvParser()
            throws IOException, ClassNotFoundException {
        ClassNode parser = load();

        new CsvLazyErrorFormattingPatch().applyAndVerify(
                parser,
                new PatchContext(JarWorkspace.OBF_JAR, TARGET))
                .requireSuccess();

        assertEquals(1, AsmUtil.countMethodCall(
                parser,
                FORMATTER,
                "formatLastRow",
                "(Ljava/lang/Object;)Ljava/lang/String;"));
        assertEquals(0, AsmUtil.countMethodCall(
                parser,
                "org/json/JSONObject",
                "toString",
                "(I)Ljava/lang/String;"));
        GameDataPatchVerifier.roundTrip(parser);
        GameDataPatchVerifier.verifyWithJvm(parser);
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
