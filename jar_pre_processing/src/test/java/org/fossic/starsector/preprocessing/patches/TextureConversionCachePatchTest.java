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

final class TextureConversionCachePatchTest {
    private static final String FAST_PNG =
            "org/fossic/starsector/optimization/FastPngDecoder";
    private static final String TRACKER =
            "org/fossic/starsector/optimization/TextureSourceTracker";

    @Test
    void bridgesBothRealImageReadersAndBothProcessorBoundaries()
            throws IOException {
        ClassNode preloader = load("com/fs/graphics/L.class");
        ClassNode textureLoader = load(
                "com/fs/graphics/TextureLoader.class");
        FastPngDecoderPatch fastPng = new FastPngDecoderPatch();
        fastPng.applyAndVerify(preloader, context("com/fs/graphics/L.class"));
        fastPng.applyAndVerify(
                textureLoader,
                context("com/fs/graphics/TextureLoader.class"));
        new TexturePixelConversionPatch().applyAndVerify(
                textureLoader,
                context("com/fs/graphics/TextureLoader.class"));

        TextureConversionCachePatch patch =
                new TextureConversionCachePatch();
        patch.applyAndVerify(preloader, context("com/fs/graphics/L.class"));
        patch.applyAndVerify(
                textureLoader,
                context("com/fs/graphics/TextureLoader.class"));

        String trackedDecode =
                "(Ljava/lang/String;Ljava/io/InputStream;)"
                        + "Ljava/awt/image/BufferedImage;";
        assertEquals(1, AsmUtil.countMethodCall(
                preloader, FAST_PNG, "decodeTracked", trackedDecode));
        assertEquals(1, AsmUtil.countMethodCall(
                textureLoader, FAST_PNG, "decodeTracked", trackedDecode));
        assertEquals(2, AsmUtil.countMethodCall(
                textureLoader, TRACKER, "prepareForProcessor",
                "(Ljava/awt/image/BufferedImage;)"
                        + "Ljava/awt/image/BufferedImage;"));
        assertEquals(0, AsmUtil.countMethodCall(
                textureLoader, TRACKER, "invalidate",
                "(Ljava/awt/image/BufferedImage;)V"));
        assertEquals(2, AsmUtil.countMethodCall(
                textureLoader, TRACKER, "hasAlpha",
                "(Ljava/awt/image/BufferedImage;)Z"));
        assertEquals(1, AsmUtil.countMethodCall(
                textureLoader,
                "org/fossic/starsector/optimization/TexturePixelConverter",
                "convertCached",
                "(Ljava/awt/image/BufferedImage;Ljava/nio/ByteBuffer;)"
                        + "Lorg/fossic/starsector/optimization/"
                        + "TexturePixelConverter$Result;"));
    }

    private static PatchContext context(String classPath) {
        return new PatchContext(JarWorkspace.COMMON_OBF_JAR, classPath);
    }

    private static ClassNode load(String entryName) throws IOException {
        Path jar = Path.of("..", "game data", "fs.common_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(entryName)))
                    .accept(node, 0);
            return node;
        }
    }
}
