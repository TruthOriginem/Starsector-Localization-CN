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

final class DecodedPcmCachePatchTest {
    private static final String HELPER =
            "org/fossic/starsector/optimization/DecodedPcmCache";

    @Test
    void wrapsTheRealPatchedOggDecoderAndPreservesOriginalBody()
            throws IOException {
        ClassNode decoder = load();
        PatchContext context = new PatchContext(
                JarWorkspace.SOUND_OBF_JAR, "sound/O0oO.class");
        new DecodedPcmBufferPatch().applyAndVerify(decoder, context);
        new DecodedPcmBulkReadPatch().applyAndVerify(decoder, context);

        new DecodedPcmCachePatch().applyAndVerify(decoder, context);

        assertEquals(1, decoder.methods.stream()
                .filter(method -> "super".equals(method.name))
                .filter(method -> "(Ljava/io/InputStream;)Lsound/G;"
                        .equals(method.desc))
                .count());
        assertEquals(1, decoder.methods.stream()
                .filter(method -> "starsector$decodePcmUncached"
                        .equals(method.name))
                .filter(method -> "(Ljava/io/InputStream;)Lsound/G;"
                        .equals(method.desc))
                .count());
        assertEquals(1, AsmUtil.countMethodCall(
                decoder,
                HELPER,
                "decode",
                "(Ljava/lang/Object;Ljava/io/InputStream;)"
                        + "Ljava/lang/Object;"));
    }

    private static ClassNode load() throws IOException {
        Path jar = Path.of("..", "game data", "fs.sound_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(
                    "sound/O0oO.class"))).accept(node, 0);
            return node;
        }
    }
}
