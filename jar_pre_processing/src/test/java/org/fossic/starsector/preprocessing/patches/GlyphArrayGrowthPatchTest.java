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

final class GlyphArrayGrowthPatchTest {
    private static final String TARGET =
            "com/fs/graphics/A/F.class";
    private static final String HELPER =
            "org/fossic/starsector/optimization/GlyphArrayGrowth";

    @Test
    void bridgesTheRealGlyphInsertionMethodOnce() throws IOException {
        ClassNode font = load();

        new GlyphArrayGrowthPatch().applyAndVerify(
                font,
                new PatchContext(
                        JarWorkspace.COMMON_OBF_JAR, TARGET));

        assertEquals(1, AsmUtil.countMethodCall(
                font,
                HELPER,
                "ensureCapacity",
                "([Ljava/lang/Object;I)[Ljava/lang/Object;"));
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
