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

final class FontDefinitionCursorPatchTest {
    private static final String TARGET = "com/fs/graphics/A/D.class";
    private static final String OLD_HELPER =
            "org/fossic/starsector/optimization/FontDefinitionParser";
    private static final String CURSOR =
            "org/fossic/starsector/optimization/FontDefinitionCursor";

    @Test
    void replacesTheIntermediateTokenArrayAfterTheLineParserPatch()
            throws IOException {
        ClassNode fontLoader = load();
        PatchContext context = new PatchContext(
                JarWorkspace.COMMON_OBF_JAR, TARGET);
        new FontDefinitionParserPatch().applyAndVerify(
                fontLoader, context);

        new FontDefinitionCursorPatch().applyAndVerify(
                fontLoader, context);

        assertEquals(1, fontLoader.fields.stream()
                .filter(field -> "[Ljava/lang/String;".equals(field.desc))
                .count());
        assertEquals(1, fontLoader.fields.stream()
                .filter(field -> ("L" + CURSOR + ";")
                        .equals(field.desc))
                .count());
        assertEquals(0, AsmUtil.countMethodCall(
                fontLoader,
                OLD_HELPER,
                "tokenizeAfterKeyword",
                "(Ljava/lang/String;)[Ljava/lang/String;"));
        assertEquals(1, AsmUtil.countMethodCall(
                fontLoader,
                CURSOR,
                "reset",
                "(L" + CURSOR + ";Ljava/lang/String;)L"
                        + CURSOR + ";"));
        assertEquals(1, AsmUtil.countMethodCall(
                fontLoader, CURSOR, "nextInt", "()I"));
        assertEquals(1, AsmUtil.countMethodCall(
                fontLoader,
                CURSOR,
                "nextString",
                "(Z)Ljava/lang/String;"));
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
