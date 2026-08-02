package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.objectweb.asm.tree.ClassNode;
import org.junit.jupiter.api.Test;

final class DecodedPcmPipelinePatchTest {
    private static final String DECODER_TARGET = "sound/O0oO.class";
    private static final String ACCESS_TARGET = "sound/F.class";
    private static final String ACCUMULATOR =
            "org/fossic/starsector/optimization/DecodedPcmBuffer";
    private static final String ACCESS =
            "org/fossic/starsector/optimization/PcmDecoderAccess";

    @Test
    void composesBufferAccessAndBulkReadOnTheRealSoundClasses()
            throws IOException, ClassNotFoundException {
        ClassNode preloadDecoder =
                PcmDecoderAccessPatchTest.load(DECODER_TARGET);
        ClassNode pcmDecoder =
                PcmDecoderAccessPatchTest.load(ACCESS_TARGET);
        PatchContext preloadContext = new PatchContext(
                JarWorkspace.SOUND_OBF_JAR, DECODER_TARGET);

        new DecodedPcmBufferPatch().applyAndVerify(
                preloadDecoder, preloadContext).requireSuccess();
        new PcmDecoderAccessPatch().applyAndVerify(
                pcmDecoder,
                new PatchContext(
                        JarWorkspace.SOUND_OBF_JAR, ACCESS_TARGET))
                .requireSuccess();
        new DecodedPcmBulkReadPatch().applyAndVerify(
                preloadDecoder, preloadContext).requireSuccess();

        assertEquals(1, AsmUtil.countMethodCall(
                preloadDecoder,
                ACCUMULATOR,
                "readFrom",
                "(L" + ACCESS + ";)I"));
        assertEquals(1, AsmUtil.countMethodCall(
                preloadDecoder,
                ACCUMULATOR,
                "finish",
                "()Ljava/nio/ByteBuffer;"));
        assertEquals(0, AsmUtil.countMethodCall(
                preloadDecoder, "sound/F", "read", "()I"));
        assertEquals(1, pcmDecoder.interfaces.stream()
                .filter(ACCESS::equals)
                .count());
        GameDataPatchVerifier.roundTrip(preloadDecoder);
        GameDataPatchVerifier.roundTrip(pcmDecoder);
        GameDataPatchVerifier.verifyWithJvm(
                List.of(preloadDecoder, pcmDecoder));
    }
}
