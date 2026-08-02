package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TextureConversionCacheTest {
    @TempDir
    Path cacheDirectory;

    @BeforeEach
    void enableSmallTestEntries() {
        System.setProperty(
                TextureConversionCache.DIRECTORY_PROPERTY,
                cacheDirectory.toString());
        System.setProperty(
                TextureConversionCache.MINIMUM_BYTES_PROPERTY, "0");
        System.clearProperty(TextureConversionCache.DISABLE_PROPERTY);
        TextureConversionCache.resetForTests();
    }

    @AfterEach
    void resetProperties() {
        System.clearProperty(TextureConversionCache.DIRECTORY_PROPERTY);
        System.clearProperty(TextureConversionCache.MINIMUM_BYTES_PROPERTY);
        System.clearProperty(TextureConversionCache.MAXIMUM_BYTES_PROPERTY);
        System.clearProperty(TextureConversionCache.DISABLE_PROPERTY);
        TextureConversionCache.resetForTests();
    }

    @Test
    void roundTripsPixelsDimensionsAlphaAndOriginalColorStatistics() {
        BufferedImage image = patternedImage(3, 2, true);
        byte[] encodedSource = {1, 2, 3, 4, 5};
        String sourceHash = TextureConversionCache.sourceHash(encodedSource);
        TexturePixelConverter.Result converted =
                convertResult(image);

        assertTrue(TextureConversionCache.store(
                sourceHash, image.getWidth(), image.getHeight(),
                image.getColorModel().hasAlpha(), converted));
        TextureConversionCache.CachedTexture cached =
                TextureConversionCache.load(sourceHash);

        assertNotNull(cached);
        assertEquals(3, cached.imageWidth());
        assertEquals(2, cached.imageHeight());
        assertTrue(cached.hasAlpha());
        assertEquals(converted.paddedWidth(), cached.paddedWidth());
        assertEquals(converted.paddedHeight(), cached.paddedHeight());
        assertEquals(converted.averageColor(), cached.averageColor());
        assertEquals(converted.brightColor(), cached.brightColor());
        assertEquals(converted.medianColor(), cached.medianColor());
        assertArrayEquals(bytes(converted.buffer()), cached.pixelBytes());
        assertTrue(TextureCacheDiagnostics.json().contains("\"hits\":1"));
        assertTrue(TextureCacheDiagnostics.json().contains("\"stores\":1"));
        assertEquals(List.of("textures"),
                PersistentCacheMaintenance.registeredNamespacesForTests()
                        .stream().sorted().toList());
    }

    @Test
    void contentHashInvalidatesEvenWhenPhysicalMetadataIsUnchanged(
            @TempDir Path sourceDirectory) throws IOException {
        Path source = sourceDirectory.resolve("same-metadata.png");
        FileTime fixedTime = FileTime.fromMillis(1_700_000_000_000L);
        byte[] first = {10, 20, 30, 40};
        byte[] second = {40, 30, 20, 10};
        Files.write(source, first);
        Files.setLastModifiedTime(source, fixedTime);
        String firstHash = TextureConversionCache.sourceHash(
                Files.readAllBytes(source));
        Files.write(source, second);
        Files.setLastModifiedTime(source, fixedTime);
        String secondHash = TextureConversionCache.sourceHash(
                Files.readAllBytes(source));

        assertEquals(first.length, Files.size(source));
        assertEquals(fixedTime, Files.getLastModifiedTime(source));
        assertNotEquals(firstHash, secondHash);
    }

    @Test
    void corruptedPayloadIsDeletedAndBecomesAnOrdinaryMiss()
            throws IOException {
        BufferedImage image = patternedImage(4, 4, false);
        String sourceHash = TextureConversionCache.sourceHash(
                new byte[]{9, 8, 7, 6});
        assertTrue(TextureConversionCache.store(
                sourceHash, image.getWidth(), image.getHeight(), false,
                convertResult(image)));
        Path cacheFile = onlyCacheFile();
        Files.write(cacheFile, new byte[]{0, 1, 2, 3});

        assertNull(TextureConversionCache.load(sourceHash));
        assertFalse(Files.exists(cacheFile));
    }

    @Test
    void disabledCacheDoesNotReadOrWriteFiles() throws IOException {
        System.setProperty(TextureConversionCache.DISABLE_PROPERTY, "true");
        TextureConversionCache.resetForTests();
        BufferedImage image = patternedImage(2, 2, false);
        String sourceHash = TextureConversionCache.sourceHash(
                new byte[]{1, 1, 2, 3});

        assertFalse(TextureConversionCache.store(
                sourceHash, 2, 2, false,
                convertResult(image)));
        assertNull(TextureConversionCache.load(sourceHash));
        assertTrue(cacheFiles().isEmpty());
        assertTrue(PersistentCacheMaintenance
                .registeredNamespacesForTests().isEmpty());
    }

    @Test
    void invalidConfiguredDirectoryIsAnOrdinaryMissAndStoreSkip() {
        System.setProperty(
                TextureConversionCache.DIRECTORY_PROPERTY, "\0");
        TextureConversionCache.resetForTests();
        BufferedImage image = patternedImage(2, 2, false);
        String sourceHash = TextureConversionCache.sourceHash(
                new byte[]{7, 7, 7});

        assertNull(TextureConversionCache.load(sourceHash));
        assertFalse(TextureConversionCache.store(
                sourceHash, 2, 2, false,
                convertResult(image)));
    }

    @Test
    void oversizeTextureIsRejectedBeforeAllocatingSnapshot() {
        assertTrue(TextureConversionCache.isCacheablePayload(
                2048, 2048, true, 2048 * 2048 * 4));
        assertFalse(TextureConversionCache.isCacheablePayload(
                16_384, 16_384, true, Integer.MAX_VALUE));
        assertFalse(TextureConversionCache.isCacheablePayload(
                Integer.MAX_VALUE, Integer.MAX_VALUE, true,
                Integer.MAX_VALUE));
    }

    @Test
    void corruptedCompressedEnvelopeIsRejectedBeforeDecompression() {
        int rawLength = 512 * 1024 * 1024;
        assertTrue(TextureConversionCache.validCompressedEnvelopeForTests(
                124L, rawLength, 1));
        assertFalse(TextureConversionCache.zstdFrameMatchesForTests(
                new byte[]{1}, rawLength));

        byte[] valid = IsolatedZstdCodec.compress(new byte[32], 1);
        assertTrue(TextureConversionCache.zstdFrameMatchesForTests(
                valid, 32));
        assertFalse(TextureConversionCache.zstdFrameMatchesForTests(
                valid, 64));
        assertFalse(TextureConversionCache.validCompressedEnvelopeForTests(
                123L + Integer.MAX_VALUE, 12, Integer.MAX_VALUE));
    }

    @Test
    void temporaryNamesAreUniqueEvenForTheSameThread() {
        Path target = cacheDirectory.resolve("entry.sstexc.zst");

        Path first = TextureConversionCache.temporaryFile(target);
        Path second = TextureConversionCache.temporaryFile(target);

        assertNotEquals(first, second);
        assertTrue(first.getFileName().toString().endsWith(".tmp"));
        assertTrue(second.getFileName().toString().endsWith(".tmp"));
    }

    @Test
    void implementationGenerationParticipatesInSourceHash() {
        byte[] encoded = {1, 2, 3};

        assertNotEquals(
                TextureConversionCache.sourceHashForIdentity(
                        encoded, "texture-v1"),
                TextureConversionCache.sourceHashForIdentity(
                        encoded, "texture-v2"));
    }

    @Test
    void symbolicLinkShardCannotDeleteAnExternalFile(
            @TempDir Path externalDirectory) throws IOException {
        String sourceHash = TextureConversionCache.sourceHash(
                new byte[]{4, 2, 4, 2});
        Files.createDirectories(cacheDirectory);
        Path externalFile = externalDirectory.resolve(
                sourceHash + ".sstexc.zst");
        Files.write(externalFile, new byte[]{0, 1, 2, 3});
        try {
            Files.createSymbolicLink(
                    cacheDirectory.resolve(sourceHash.substring(0, 2)),
                    externalDirectory);
        } catch (IOException | UnsupportedOperationException failure) {
            assumeTrue(false, "symbolic links unavailable: " + failure);
        }

        assertNull(TextureConversionCache.load(sourceHash));
        assertTrue(Files.exists(externalFile));
    }

    @Test
    void ordinaryConverterRemainsCacheFreeForGroupDisabledBuilds()
            throws IOException {
        BufferedImage image = patternedImage(4, 4, false);
        TextureSourceTracker.track(
                image,
                TextureConversionCache.sourceHash(new byte[]{5, 4, 3, 2}));

        convertResult(image);

        assertTrue(cacheFiles().isEmpty());
    }

    @Test
    void sourceHashesUseImageIdentityEvenWhenAModSubclassOverridesEquality() {
        BufferedImage first = new EqualLookingImage();
        BufferedImage second = new EqualLookingImage();
        TextureSourceTracker.track(first, "first");
        TextureSourceTracker.track(second, "second");

        assertEquals("first", TextureSourceTracker.takeSourceHash(first));
        assertEquals("second", TextureSourceTracker.takeSourceHash(second));
    }

    @Test
    void trackedCachedImageRestoresDirectBufferWithoutMaterializingSource() {
        BufferedImage image = patternedImage(5, 3, true);
        byte[] sourceBytes = {3, 1, 4, 1, 5, 9};
        String sourceHash = TextureConversionCache.sourceHash(sourceBytes);
        TexturePixelConverter.Result expected =
                convertResult(image);
        assertTrue(TextureConversionCache.store(
                sourceHash, image.getWidth(), image.getHeight(), true,
                expected));
        TextureConversionCache.CachedTexture cached =
                TextureConversionCache.load(sourceHash);
        BufferedImage placeholder = TextureSourceTracker.cachedImage(
                sourceBytes, cached, bytes -> {
                    throw new AssertionError("cache hit must not decode");
                });

        TexturePixelConverter.Result actual =
                convertCachedResult(placeholder);

        assertEquals(image.getWidth(), placeholder.getWidth());
        assertEquals(image.getHeight(), placeholder.getHeight());
        assertEquals(expected.averageColor(), actual.averageColor());
        assertEquals(expected.brightColor(), actual.brightColor());
        assertEquals(expected.medianColor(), actual.medianColor());
        assertArrayEquals(bytes(expected.buffer()), bytes(actual.buffer()));
        assertTrue(actual.buffer().isDirect());
    }

    @Test
    void imageProcessorInvalidationForcesMaterializationAndReconversion()
            throws IOException {
        BufferedImage image = patternedImage(2, 2, false);
        byte[] sourceBytes = png(image);
        String sourceHash = TextureConversionCache.sourceHash(sourceBytes);
        TexturePixelConverter.Result original =
                convertResult(image);
        byte[] originalBytes = bytes(original.buffer());
        assertTrue(TextureConversionCache.store(
                sourceHash, 2, 2, false, original));
        BufferedImage placeholder = TextureSourceTracker.cachedImage(
                sourceBytes, TextureConversionCache.load(sourceHash),
                bytes -> javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(bytes)));

        TextureSourceTracker.invalidate(placeholder);
        placeholder.setRGB(0, 0, 0xFFFF0000);
        TexturePixelConverter.Result changed =
                convertCachedResult(placeholder);

        assertNotEquals(
                original.averageColor(), changed.averageColor());
        assertFalse(java.util.Arrays.equals(
                originalBytes, bytes(changed.buffer())));
    }

    @Test
    void processorBoundaryReceivesTheRealDecodedImage() {
        BufferedImage decoded = patternedImage(3, 2, true);
        byte[] encoded = {3, 3, 8};
        TextureConversionCache.CachedTexture cached =
                new TextureConversionCache.CachedTexture(
                        3, 2, true, 4, 2,
                        java.awt.Color.BLACK,
                        java.awt.Color.BLACK,
                        java.awt.Color.BLACK,
                        new byte[4 * 2 * 4]);
        BufferedImage placeholder = TextureSourceTracker.cachedImage(
                encoded, cached, ignored -> decoded);

        BufferedImage processorInput =
                TextureSourceTracker.prepareForProcessor(placeholder);

        assertSame(decoded, processorInput);
        assertEquals(BufferedImage.class, processorInput.getClass());
        assertNull(TextureSourceTracker.cachedTexture(placeholder));
    }

    private Path onlyCacheFile() throws IOException {
        List<Path> files = cacheFiles();
        assertEquals(1, files.size());
        return files.get(0);
    }

    private List<Path> cacheFiles() throws IOException {
        Path root = Path.of(System.getProperty(
                TextureConversionCache.DIRECTORY_PROPERTY));
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private static BufferedImage patternedImage(
            int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(
                width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB
                        : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = alpha && (x + y) % 3 == 0 ? 0 : 255;
                int red = 20 + x * 31 + y * 7;
                int green = 30 + x * 11 + y * 23;
                int blue = 40 + x * 17 + y * 13;
                image.setRGB(x, y,
                        a << 24 | red << 16 | green << 8 | blue);
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.capacity()];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = buffer.get(index);
        }
        return bytes;
    }

    private static TexturePixelConverter.Result convertResult(
            BufferedImage image) {
        return TexturePixelConverter.convert(image);
    }

    private static TexturePixelConverter.Result convertCachedResult(
            BufferedImage image) {
        return TexturePixelConverter.convertCached(image, null);
    }

    private static final class EqualLookingImage extends BufferedImage {
        private EqualLookingImage() {
            super(1, 1, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualLookingImage;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
