package org.fossic.starsector.preprocessing.patches;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.fossic.starsector.preprocessing.AsmUtil;
import org.fossic.starsector.preprocessing.JarWorkspace;
import org.fossic.starsector.preprocessing.PatchContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.junit.jupiter.api.Test;

final class GraphicsResourceStreamSafetyPatchTest {
    private static final String PRELOADER = "com/fs/graphics/L.class";
    private static final String TEXTURE_LOADER =
            "com/fs/graphics/TextureLoader.class";
    private static final String HELPER =
            "org/fossic/starsector/optimization/OwnedResourceStreams";
    private static final String FAST_PNG =
            "org/fossic/starsector/optimization/FastPngDecoder";
    private static final String IMAGE_IO = "javax/imageio/ImageIO";
    private static final String IMAGE_DESC =
            "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";
    private static final String TRACKED_DESC =
            "(Ljava/lang/String;Ljava/io/InputStream;)"
                    + "Ljava/awt/image/BufferedImage;";

    @Test
    void safetyOnlyOwnsBothOriginalImageStreamsAndSoundBytes()
            throws IOException {
        ClassNode preloader = load(PRELOADER);
        ClassNode textureLoader = load(TEXTURE_LOADER);
        GraphicsResourceStreamSafetyPatch patch =
                new GraphicsResourceStreamSafetyPatch();

        patch.applyAndVerify(
                preloader,
                context(PRELOADER)).requireSuccess();
        patch.applyAndVerify(
                textureLoader,
                context(TEXTURE_LOADER)).requireSuccess();

        assertEquals(2, count(
                preloader,
                HELPER,
                "readImageAndClose",
                IMAGE_DESC) + count(
                textureLoader,
                HELPER,
                "readImageAndClose",
                IMAGE_DESC));
        assertEquals(1, count(
                preloader,
                HELPER,
                "readAllAndClose",
                "(Ljava/io/InputStream;)[B"));
        assertEquals(0, count(
                preloader, IMAGE_IO, "read", IMAGE_DESC));
        assertEquals(0, count(
                textureLoader, IMAGE_IO, "read", IMAGE_DESC));
        assertEquals(0, count(
                preloader,
                "java/io/BufferedInputStream",
                "close",
                "()V"));
        verifyWithJvm(preloader, textureLoader);
    }

    @Test
    void composesAfterFastDecodeAndTextureCacheTracking()
            throws IOException {
        ClassNode preloader = load(PRELOADER);
        ClassNode textureLoader = load(TEXTURE_LOADER);
        FastPngDecoderPatch fastPng = new FastPngDecoderPatch();
        TextureConversionCachePatch textureCache =
                new TextureConversionCachePatch();

        fastPng.applyAndVerify(
                preloader, context(PRELOADER)).requireSuccess();
        fastPng.applyAndVerify(
                textureLoader, context(TEXTURE_LOADER)).requireSuccess();
        new TexturePixelConversionPatch().applyAndVerify(
                textureLoader, context(TEXTURE_LOADER)).requireSuccess();
        textureCache.applyAndVerify(
                preloader, context(PRELOADER)).requireSuccess();
        textureCache.applyAndVerify(
                textureLoader, context(TEXTURE_LOADER)).requireSuccess();
        new PreloadResultCoordinatorPatch().applyAndVerify(
                preloader, context(PRELOADER)).requireSuccess();
        new PreloadPathDedupPatch().applyAndVerify(
                preloader, context(PRELOADER)).requireSuccess();
        new ParallelImagePreloadPatch().applyAndVerify(
                preloader, context(PRELOADER)).requireSuccess();

        GraphicsResourceStreamSafetyPatch safety =
                new GraphicsResourceStreamSafetyPatch();
        safety.applyAndVerify(
                preloader, context(PRELOADER)).requireSuccess();
        safety.applyAndVerify(
                textureLoader, context(TEXTURE_LOADER)).requireSuccess();

        assertEquals(2, count(
                preloader,
                HELPER,
                "decodeTrackedPngAndClose",
                TRACKED_DESC) + count(
                textureLoader,
                HELPER,
                "decodeTrackedPngAndClose",
                TRACKED_DESC));
        assertEquals(0, count(
                preloader,
                FAST_PNG,
                "decodeTracked",
                TRACKED_DESC));
        assertEquals(0, count(
                textureLoader,
                FAST_PNG,
                "decodeTracked",
                TRACKED_DESC));
        assertEquals(1, count(
                preloader,
                HELPER,
                "readAllAndClose",
                "(Ljava/io/InputStream;)[B"));
        verifyWithJvm(preloader, textureLoader);
    }

    private static int count(
            ClassNode node, String owner, String name, String desc) {
        return AsmUtil.countMethodCall(node, owner, name, desc);
    }

    private static PatchContext context(String target) {
        return new PatchContext(JarWorkspace.COMMON_OBF_JAR, target);
    }

    private static ClassNode load(String target) throws IOException {
        Path jar = Path.of("..", "game data", "fs.common_obf.jar");
        try (ZipFile input = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(input.getInputStream(input.getEntry(target)))
                    .accept(node, 0);
            return node;
        }
    }

    private static void verifyWithJvm(ClassNode... nodes)
            throws IOException {
        Map<String, byte[]> definitions = new HashMap<>();
        for (ClassNode node : nodes) {
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            definitions.put(
                    node.name.replace('/', '.'), writer.toByteArray());
        }
        URL[] gameJars;
        try (Stream<Path> jars = Files.list(
                Path.of("..", "game data"))) {
            gameJars = jars
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".jar"))
                    .map(GraphicsResourceStreamSafetyPatchTest::toUrl)
                    .toArray(URL[]::new);
        }
        try (VerificationLoader loader = new VerificationLoader(
                gameJars,
                GraphicsResourceStreamSafetyPatchTest.class
                        .getClassLoader(),
                definitions)) {
            for (String name : definitions.keySet()) {
                assertDoesNotThrow(() ->
                        Class.forName(name, false, loader));
            }
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class VerificationLoader extends URLClassLoader {
        private final Map<String, byte[]> definitions;

        private VerificationLoader(
                URL[] urls,
                ClassLoader parent,
                Map<String, byte[]> definitions) {
            super(urls, parent);
            this.definitions = new HashMap<>(definitions);
        }

        @Override
        protected Class<?> findClass(String name)
                throws ClassNotFoundException {
            byte[] definition = definitions.remove(name);
            if (definition != null) {
                return defineClass(name, definition, 0, definition.length);
            }
            return super.findClass(name);
        }
    }
}
