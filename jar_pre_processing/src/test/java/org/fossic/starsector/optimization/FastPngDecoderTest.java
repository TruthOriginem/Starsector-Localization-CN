package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FastPngDecoderTest {
    @Test
    void eligibleRgbUsesFastBackendWithoutTakingStreamOwnership()
            throws Exception {
        byte[] png = png(BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(png));
        AtomicInteger backendCalls = new AtomicInteger();
        TrackingInputStream input = new TrackingInputStream(png);

        BufferedImage actual = FastPngDecoder.decode(input, bytes -> {
            backendCalls.incrementAndGet();
            assertArrayEquals(png, bytes);
            return expected;
        });

        assertSame(expected, actual);
        assertEquals(1, backendCalls.get());
        assertFalse(input.closed);
        assertTrue(FastPngDecoder.isEligibleForTesting(png));
    }

    @Test
    void eligibleRgbaKeepsAlphaAndFallsBackAfterFastBackendFailure()
            throws Exception {
        byte[] png = png(BufferedImage.TYPE_INT_ARGB);
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(png));
        AtomicInteger backendCalls = new AtomicInteger();

        BufferedImage actual = FastPngDecoder.decode(
                new ByteArrayInputStream(png), bytes -> {
                    backendCalls.incrementAndGet();
                    throw new IOException("fast backend failure");
                });

        assertEquals(1, backendCalls.get());
        assertImagesEqual(expected, actual);
        assertTrue(actual.getColorModel().hasAlpha());
        assertTrue(FastPngDecoder.isEligibleForTesting(png));
    }

    @Test
    void uncheckedFastBackendFailureAlsoFallsBackToImageIo()
            throws Exception {
        byte[] png = png(BufferedImage.TYPE_INT_ARGB);
        BufferedImage expected = ImageIO.read(
                new ByteArrayInputStream(png));

        BufferedImage actual = FastPngDecoder.decode(
                new ByteArrayInputStream(png), bytes -> {
                    throw new IllegalArgumentException(
                            "malformed fast-backend input");
                });

        assertImagesEqual(expected, actual);
    }

    @Test
    void linkageFailuresFallBackWithoutTakingStreamOwnership()
            throws Exception {
        byte[] png = png(BufferedImage.TYPE_INT_ARGB);
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(png));
        LinkageError[] failures = {
            new NoClassDefFoundError("optional PNG backend missing"),
            new ExceptionInInitializerError("optional PNG backend failed")
        };
        for (LinkageError failure : failures) {
            TrackingInputStream input = new TrackingInputStream(png);

            BufferedImage actual = FastPngDecoder.decode(
                    input, bytes -> {
                        throw failure;
                    });

            assertImagesEqual(expected, actual);
            assertFalse(input.closed);
        }
    }

    @Test
    void trackedNonPngDoesNotConsumeUnrelatedTrailingBytes()
            throws Exception {
        byte[] jpeg = jpeg();
        byte[] source = Arrays.copyOf(jpeg, jpeg.length + 1024 * 1024);
        CountingInputStream input = new CountingInputStream(source);

        BufferedImage actual = FastPngDecoder.decodeTracked(
                "graphics/test.jpg", input, bytes -> {
                    throw new AssertionError("PNG backend must not run");
                });

        assertNotNull(actual);
        assertTrue(input.bytesRead() < source.length / 2,
                "non-PNG path should remain streaming");
    }

    @Test
    void oversizedPngBypassesByteArrayCacheAndFastBackend()
            throws Exception {
        byte[] png = png(BufferedImage.TYPE_INT_ARGB);
        System.setProperty(
                FastPngDecoder.MAXIMUM_ENCODED_BYTES_PROPERTY, "32");
        try {
            BufferedImage actual = FastPngDecoder.decodeTracked(
                    "graphics/test.png",
                    new ByteArrayInputStream(png),
                    bytes -> {
                        throw new AssertionError(
                                "oversized PNG must use streaming ImageIO");
                    });

            assertImagesEqual(
                    ImageIO.read(new ByteArrayInputStream(png)), actual);
        } finally {
            System.clearProperty(
                    FastPngDecoder.MAXIMUM_ENCODED_BYTES_PROPERTY);
        }
    }

    @Test
    void colorManagedPngAlwaysUsesImageIoFallback() throws Exception {
        byte[] source = png(BufferedImage.TYPE_3BYTE_BGR);
        byte[] profiled = insertChunk(
                source, "gAMA", new byte[]{0, 0, (byte) 0xB1, (byte) 0x8F});
        AtomicInteger backendCalls = new AtomicInteger();

        BufferedImage actual = FastPngDecoder.decode(
                new ByteArrayInputStream(profiled), bytes -> {
                    backendCalls.incrementAndGet();
                    throw new AssertionError("fast backend must not run");
                });

        assertEquals(0, backendCalls.get());
        assertImagesEqual(
                ImageIO.read(new ByteArrayInputStream(profiled)), actual);
        assertFalse(FastPngDecoder.isEligibleForTesting(profiled));
    }

    @Test
    void indexedGrayAndNonPngInputsStayOnImageIo() throws Exception {
        byte[][] inputs = {
            png(BufferedImage.TYPE_BYTE_INDEXED),
            png(BufferedImage.TYPE_BYTE_GRAY),
            jpeg()
        };
        for (byte[] input : inputs) {
            AtomicInteger backendCalls = new AtomicInteger();

            BufferedImage actual = FastPngDecoder.decode(
                    new ByteArrayInputStream(input), bytes -> {
                        backendCalls.incrementAndGet();
                        throw new AssertionError("fast backend must not run");
                    });

            assertEquals(0, backendCalls.get());
            assertImagesEqual(
                    ImageIO.read(new ByteArrayInputStream(input)), actual);
            assertFalse(FastPngDecoder.isEligibleForTesting(input));
        }
    }

    @Test
    void fastRgbSamplesRemainOpaqueWithoutSyntheticAlphaBand() {
        PngDecodedImage decoded = new PngDecodedImage(
                2, 1, 3,
                new byte[]{10, 20, 30, 40, 50, 60});

        BufferedImage image = TwlPngDecoder.toBufferedImage(
                decoded);

        assertFalse(image.getColorModel().hasAlpha());
        assertArrayEquals(
                new int[]{10, 20, 30, 40, 50, 60},
                image.getRaster().getPixels(0, 0, 2, 1, (int[]) null));
        assertEquals(0xFF0A141E, image.getRGB(0, 0));
        assertEquals(0xFF28323C, image.getRGB(1, 0));
    }

    @Test
    void fastRgbaSamplesPreserveEveryAlphaValue() {
        PngDecodedImage decoded = new PngDecodedImage(
                2, 1, 4,
                new byte[]{10, 20, 30, 0, 40, 50, 60, (byte) 255});

        BufferedImage image = TwlPngDecoder.toBufferedImage(
                decoded);

        assertTrue(image.getColorModel().hasAlpha());
        assertArrayEquals(
                new int[]{10, 20, 30, 0, 40, 50, 60, 255},
                image.getRaster().getPixels(0, 0, 2, 1, (int[]) null));
        assertEquals(0x000A141E, image.getRGB(0, 0));
        assertEquals(0xFF28323C, image.getRGB(1, 0));
    }

    @Test
    void trackedDecodeUsesPersistedConversionBeforeCallingBackend(
            @TempDir Path cacheDirectory) throws Exception {
        System.setProperty(
                TextureConversionCache.DIRECTORY_PROPERTY,
                cacheDirectory.toString());
        System.setProperty(
                TextureConversionCache.MINIMUM_BYTES_PROPERTY, "0");
        System.clearProperty(TextureConversionCache.DISABLE_PROPERTY);
        TextureConversionCache.resetForTests();
        try {
            byte[] png = png(BufferedImage.TYPE_INT_ARGB);
            AtomicInteger backendCalls = new AtomicInteger();
            BufferedImage first = FastPngDecoder.decodeTracked(
                    "graphics/test.png",
                    new ByteArrayInputStream(png),
                    bytes -> {
                        backendCalls.incrementAndGet();
                        return ImageIO.read(new ByteArrayInputStream(bytes));
                    });
            TexturePixelConverter.Result expected =
                    TexturePixelConverter.convertCached(first, null);

            BufferedImage second = FastPngDecoder.decodeTracked(
                    "graphics/test.png",
                    new ByteArrayInputStream(png),
                    bytes -> {
                        throw new AssertionError(
                                "persisted conversion must skip decoder");
                    });
            TexturePixelConverter.Result actual =
                    TexturePixelConverter.convertCached(second, null);

            assertEquals(1, backendCalls.get());
            assertArrayEquals(
                    bufferBytes(expected.buffer()),
                    bufferBytes(actual.buffer()));
            assertEquals(expected.averageColor(), actual.averageColor());
        } finally {
            System.clearProperty(TextureConversionCache.DIRECTORY_PROPERTY);
            System.clearProperty(
                    TextureConversionCache.MINIMUM_BYTES_PROPERTY);
            System.clearProperty(TextureConversionCache.DISABLE_PROPERTY);
            TextureConversionCache.resetForTests();
        }
    }

    private static byte[] png(int type) throws IOException {
        BufferedImage image = new BufferedImage(3, 2, type);
        image.setRGB(0, 0, 0x00112233);
        image.setRGB(1, 0, 0x80445566);
        image.setRGB(2, 0, 0xFF778899);
        image.setRGB(0, 1, 0xFF102030);
        image.setRGB(1, 1, 0x7F405060);
        image.setRGB(2, 1, 0x00708090);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static byte[] jpeg() throws IOException {
        BufferedImage image = new BufferedImage(
                2, 2, BufferedImage.TYPE_3BYTE_BGR);
        image.setRGB(0, 0, 0xFF102030);
        image.setRGB(1, 0, 0xFF405060);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", output));
        return output.toByteArray();
    }

    private static byte[] insertChunk(
            byte[] png, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                png.length + data.length + 12);
        output.write(png, 0, 33);
        writeInt(output, data.length);
        output.write(typeBytes);
        output.write(data);
        writeInt(output, (int) crc.getValue());
        output.write(png, 33, png.length - 33);
        return output.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }

    private static void assertImagesEqual(
            BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertEquals(
                expected.getColorModel().hasAlpha(),
                actual.getColorModel().hasAlpha());
        int[] expectedPixels = expected.getRGB(
                0, 0, expected.getWidth(), expected.getHeight(),
                null, 0, expected.getWidth());
        int[] actualPixels = actual.getRGB(
                0, 0, actual.getWidth(), actual.getHeight(),
                null, 0, actual.getWidth());
        assertTrue(Arrays.equals(expectedPixels, actualPixels));
    }

    private static byte[] bufferBytes(java.nio.ByteBuffer buffer) {
        byte[] result = new byte[buffer.capacity()];
        for (int index = 0; index < result.length; index++) {
            result[index] = buffer.get(index);
        }
        return result;
    }

    private static final class TrackingInputStream extends FilterInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(new ByteArrayInputStream(bytes));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class CountingInputStream
            extends ByteArrayInputStream {
        private int bytesRead;

        private CountingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public synchronized int read(
                byte[] bytes, int offset, int length) {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                bytesRead += read;
            }
            return read;
        }

        private int bytesRead() {
            return bytesRead;
        }
    }
}
