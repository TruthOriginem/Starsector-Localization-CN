package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.junit.jupiter.api.Test;

final class PcmDecoderAccessPatchTest {
    private static final String TARGET = "sound/F.class";
    private static final String ACCESS =
            "org/fossic/starsector/optimization/PcmDecoderAccess";

    @Test
    void patchesAndVerifiesTheRealPcmDecoder()
            throws IOException, ClassNotFoundException {
        ClassNode decoder = load(TARGET);

        new PcmDecoderAccessPatch().applyAndVerify(
                decoder,
                new PatchContext(JarWorkspace.SOUND_OBF_JAR, TARGET))
                .requireSuccess();

        assertEquals(1, decoder.interfaces.stream()
                .filter(ACCESS::equals)
                .count());
        assertEquals(4, decoder.methods.stream()
                .filter(method -> (method.name.equals("pcmBuffer")
                        && method.desc.equals("()Ljava/nio/ByteBuffer;"))
                        || (method.name.equals("pcmReadPosition")
                                && (method.desc.equals("()I")
                                        || method.desc.equals("(I)V")))
                        || (method.name.equals("decodeNextPcmBlock")
                                && method.desc.equals("()V")))
                .count());
        GameDataPatchVerifier.roundTrip(decoder);
        GameDataPatchVerifier.verifyWithJvm(decoder);
    }

    static ClassNode load(String entryName) throws IOException {
        Path jar = Path.of("..", "game data", "fs.sound_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(entryName)))
                    .accept(node, 0);
            return node;
        }
    }
}
