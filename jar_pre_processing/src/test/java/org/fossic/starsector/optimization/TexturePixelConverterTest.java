package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class TexturePixelConverterTest {
    @Test
    void convertsRgbWithVerticalFlipPowerOfTwoPaddingAndOriginalColors() {
        BufferedImage image = new BufferedImage(
                3, 2, BufferedImage.TYPE_INT_RGB);
        setPixel(image, 0, 0, 10, 20, 30);
        setPixel(image, 1, 0, 40, 50, 60);
        setPixel(image, 2, 0, 70, 80, 90);
        setPixel(image, 0, 1, 100, 110, 120);
        setPixel(image, 1, 1, 130, 140, 150);
        setPixel(image, 2, 1, 160, 170, 180);

        var result = TexturePixelConverter.convert(image);

        assertEquals(4, result.paddedWidth());
        assertEquals(2, result.paddedHeight());
        assertBuffer(
                result.buffer(),
                100, 110, 120, 130, 140, 150, 160, 170, 180, 0, 0, 0,
                10, 20, 30, 40, 50, 60, 70, 80, 90, 0, 0, 0);
        assertEquals(new Color(85, 95, 105, 255), result.averageColor());
        assertEquals(new Color(130, 140, 150, 255), result.brightColor());
        assertEquals(new Color(70, 80, 105, 255), result.medianColor());
    }

    @Test
    void leavesTransparentPixelsZeroAndExcludesThemFromColorStatistics() {
        BufferedImage image = new BufferedImage(
                2, 2, BufferedImage.TYPE_INT_ARGB);
        setPixel(image, 0, 0, 200, 201, 202, 0);
        setPixel(image, 1, 0, 10, 20, 30, 1);
        setPixel(image, 0, 1, 40, 50, 60, 128);
        setPixel(image, 1, 1, 70, 80, 90, 255);

        var result = TexturePixelConverter.convert(image);

        assertBuffer(
                result.buffer(),
                40, 50, 60, 128, 70, 80, 90, 255,
                0, 0, 0, 0, 10, 20, 30, 1);
        assertEquals(new Color(40, 50, 60, 255), result.averageColor());
        assertEquals(new Color(60, 70, 80, 255), result.brightColor());
        assertEquals(new Color(40, 50, 60, 255), result.medianColor());
    }

    @Test
    void preservesOriginalFallbackSemanticsForOneBandRaster() {
        BufferedImage image = new BufferedImage(
                2, 1, BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setSample(0, 0, 0, 10);
        image.getRaster().setSample(1, 0, 0, 20);

        var result = TexturePixelConverter.convert(image);

        assertBuffer(
                result.buffer(),
                10, 0, 0, 20, 0, 0,
                0, 0, 0, 0, 0, 0);
        assertEquals(new Color(15, 0, 0, 255), result.averageColor());
        assertEquals(new Color(20, 0, 0, 255), result.brightColor());
        assertEquals(new Color(10, 0, 0, 255), result.medianColor());
    }

    @Test
    void keepsOriginalWhiteDefaultsWhenEveryPixelIsTransparent() {
        BufferedImage image = new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_ARGB);
        setPixel(image, 0, 0, 123, 45, 67, 0);

        var result = TexturePixelConverter.convert(image);

        assertEquals(2, result.paddedWidth());
        assertEquals(2, result.paddedHeight());
        assertBuffer(result.buffer(), new int[2 * 2 * 4]);
        assertSame(Color.white, result.averageColor());
        assertSame(Color.white, result.brightColor());
        assertSame(Color.white, result.medianColor());
    }

    @Test
    void matchesTheOriginalPerPixelAlgorithmAcrossImageLayouts() {
        int[] types = {
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR,
            BufferedImage.TYPE_4BYTE_ABGR_PRE,
            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_INT_ARGB,
            BufferedImage.TYPE_INT_ARGB_PRE,
            BufferedImage.TYPE_INT_BGR,
            BufferedImage.TYPE_USHORT_565_RGB,
            BufferedImage.TYPE_USHORT_555_RGB,
            BufferedImage.TYPE_BYTE_GRAY,
            BufferedImage.TYPE_USHORT_GRAY,
            BufferedImage.TYPE_BYTE_BINARY,
            BufferedImage.TYPE_BYTE_INDEXED
        };
        int[][] sizes = {{1, 1}, {3, 5}, {8, 4}};
        Random random = new Random(0x5a17_2026L);

        for (int type : types) {
            for (int[] size : sizes) {
                BufferedImage image =
                        createRandomImage(type, size[0], size[1], random);
                ReferenceResult expected = referenceConvert(image);

                var actual = TexturePixelConverter.convert(image);

                String context = "type=" + type + ", size="
                        + size[0] + "x" + size[1];
                assertEquals(
                        expected.paddedWidth, actual.paddedWidth(), context);
                assertEquals(
                        expected.paddedHeight, actual.paddedHeight(), context);
                assertEquals(
                        expected.averageColor, actual.averageColor(), context);
                assertEquals(
                        expected.brightColor, actual.brightColor(), context);
                assertEquals(
                        expected.medianColor, actual.medianColor(), context);
                assertArrayEquals(
                        expected.bytes, bytes(actual.buffer()), context);
            }
        }
    }

    @Test
    void preservesCustomBufferedImageSubclassPixelSemantics() {
        TrackingBufferedImage image = new TrackingBufferedImage(7, 5);
        Random random = new Random(42);
        fillRandomPixels(image, random);

        var result = TexturePixelConverter.convert(image);

        assertEquals(0, image.trackingRaster.bulkReadCount);
        assertEquals(
                image.getWidth() * image.getHeight(),
                image.trackingRaster.singlePixelReadCount);
    }

    @Test
    void preservesCustomBufferedImageSubclassSnapshotSemantics() {
        CopyDetectingBufferedImage image =
                new CopyDetectingBufferedImage(3, 2);
        Random random = new Random(84);
        fillRandomPixels(image, random);

        var result = TexturePixelConverter.convert(image);

        assertTrue(image.dataCopyRequested);
    }

    @Test
    void observesCustomImageDataBeforeAlphaQuery() {
        ReentrantBufferedImage image = new ReentrantBufferedImage(3, 2);

        var result = TexturePixelConverter.convert(image);

        assertEquals("data", image.observations.get(0));
        assertEquals("alpha", image.observations.get(1));
        assertTrue(result.buffer().isDirect());
    }

    @Test
    void conversionFailureDoesNotAffectTheNextConversion() {
        assertThrows(
                IllegalStateException.class,
                () -> TexturePixelConverter.convert(
                        new FailingPixelBufferedImage(2, 2)));

        var next = TexturePixelConverter.convert(
                new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR));

        assertTrue(next.buffer().isDirect());
    }

    @Test
    void cachedRestoreFailureDoesNotAffectTheNextConversion() {
        TextureConversionCache.CachedTexture malformed =
                new TextureConversionCache.CachedTexture(
                        2,
                        2,
                        false,
                        2,
                        2,
                        Color.BLACK,
                        Color.BLACK,
                        Color.BLACK,
                        new byte[1]);
        BufferedImage placeholder = TextureSourceTracker.cachedImage(
                new byte[]{1},
                malformed,
                ignored -> {
                    throw new AssertionError(
                            "malformed cache must not materialize source");
                });

        assertThrows(
                IllegalStateException.class,
                () -> TexturePixelConverter.convertCached(
                        placeholder, null));

        var next = TexturePixelConverter.convert(
                new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR));

        assertTrue(next.buffer().isDirect());
    }

    @Test
    void preservesTheLoaderOwnedLargeTextureCache() {
        BufferedImage large = new BufferedImage(
                2048, 2048, BufferedImage.TYPE_3BYTE_BGR);

        var first = TexturePixelConverter.convert(large, null);
        var second = TexturePixelConverter.convert(
                large, first.cachedOpaqueLargeTextureBuffer());
        var normal = TexturePixelConverter.convert(
                new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR),
                second.cachedOpaqueLargeTextureBuffer());

        assertSame(first.buffer(), first.cachedOpaqueLargeTextureBuffer());
        assertSame(first.buffer(), second.buffer());
        assertSame(
                first.buffer(), second.cachedOpaqueLargeTextureBuffer());
        assertSame(
                first.buffer(), normal.cachedOpaqueLargeTextureBuffer());
        assertNotSame(first.buffer(), normal.buffer());
    }

    @Test
    void ordinaryTexturesUseFreshZeroedBuffers() {
        BufferedImage image = new BufferedImage(
                3, 3, BufferedImage.TYPE_INT_ARGB);
        var first = TexturePixelConverter.convert(image);
        for (int index = 0; index < first.buffer().capacity(); index++) {
            first.buffer().put(index, (byte) 0x5a);
        }
        var second = TexturePixelConverter.convert(image);

        assertNotSame(first.buffer(), second.buffer());
        assertBuffer(second.buffer(), new int[4 * 4 * 4]);
    }

    private static BufferedImage createRandomImage(
            int type, int width, int height, Random random) {
        BufferedImage image;
        if (type == BufferedImage.TYPE_BYTE_INDEXED) {
            byte[] channel = {(byte) 0, (byte) 255};
            byte[] alpha = {(byte) 0, (byte) 255};
            IndexColorModel colors =
                    new IndexColorModel(1, 2, channel, channel, channel, alpha);
            image = new BufferedImage(
                    width, height, BufferedImage.TYPE_BYTE_INDEXED, colors);
        } else {
            image = new BufferedImage(width, height, type);
        }
        fillRandomPixels(image, random);
        return image;
    }

    private static void fillRandomPixels(BufferedImage image, Random random) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = random.nextInt(4) == 0
                        ? 0
                        : random.nextInt(256);
                int argb = alpha << 24
                        | random.nextInt(256) << 16
                        | random.nextInt(256) << 8
                        | random.nextInt(256);
                image.setRGB(x, y, argb);
            }
        }
    }

    private static void setPixel(
            BufferedImage image, int x, int y, int red, int green, int blue) {
        image.getRaster().setPixel(x, y, new int[] {red, green, blue});
    }

    private static void setPixel(
            BufferedImage image, int x, int y,
            int red, int green, int blue, int alpha) {
        image.getRaster().setPixel(
                x, y, new int[] {red, green, blue, alpha});
    }

    private static void assertBuffer(ByteBuffer actual, int... expected) {
        assertTrue(actual.isDirect());
        assertEquals(ByteOrder.nativeOrder(), actual.order());
        assertEquals(0, actual.position());
        assertEquals(actual.capacity(), actual.limit());
        byte[] expectedBytes = new byte[expected.length];
        for (int i = 0; i < expected.length; i++) {
            expectedBytes[i] = (byte) expected[i];
        }
        assertArrayEquals(expectedBytes, bytes(actual));
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.capacity()];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.get(i);
        }
        return result;
    }

    private static ReferenceResult referenceConvert(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int paddedWidth = powerOfTwoAtLeastTwo(width);
        int paddedHeight = powerOfTwoAtLeastTwo(height);
        boolean alpha = image.getColorModel().hasAlpha();
        int components = alpha ? 4 : 3;
        byte[] output =
                new byte[paddedWidth * paddedHeight * components];
        float[] redHistogram = new float[256];
        float[] greenHistogram = new float[256];
        float[] blueHistogram = new float[256];
        float redSum = 0;
        float greenSum = 0;
        float blueSum = 0;
        float count = 0;
        int[] pixel = new int[components];
        Raster raster = image.getData();

        for (int outputY = 0; outputY < height; outputY++) {
            int sourceY = height - outputY - 1;
            for (int x = 0; x < width; x++) {
                raster.getPixel(x, sourceY, pixel);
                if (alpha && pixel[3] == 0) {
                    continue;
                }
                int target = (outputY * paddedWidth + x) * components;
                output[target] = (byte) pixel[0];
                output[target + 1] = (byte) pixel[1];
                output[target + 2] = (byte) pixel[2];
                if (alpha) {
                    output[target + 3] = (byte) pixel[3];
                }
                redSum += pixel[0];
                greenSum += pixel[1];
                blueSum += pixel[2];
                if (alpha
                        || pixel[0] < 256
                        && pixel[1] < 256
                        && pixel[2] < 256) {
                    redHistogram[pixel[0]]++;
                    greenHistogram[pixel[1]]++;
                    blueHistogram[pixel[2]]++;
                }
                count++;
            }
        }

        Color average = Color.white;
        Color bright = Color.white;
        Color median = Color.white;
        if (count > 0) {
            average = color(
                    (int) (redSum / count),
                    (int) (greenSum / count),
                    (int) (blueSum / count));
            bright = color(
                    (int) brightestAverage(redHistogram, count * 0.5f),
                    (int) brightestAverage(greenHistogram, count * 0.5f),
                    (int) brightestAverage(blueHistogram, count * 0.5f));
            median = color(
                    (int) lowerMedian(redHistogram, count),
                    (int) lowerMedian(greenHistogram, count),
                    (int) brightestAverage(blueHistogram, count));
        }
        return new ReferenceResult(
                output, paddedWidth, paddedHeight, average, bright, median);
    }

    private static int powerOfTwoAtLeastTwo(int value) {
        int result = 2;
        while (result < value) {
            result *= 2;
        }
        return result;
    }

    private static float lowerMedian(float[] histogram, float count) {
        float seen = 0;
        float target = count * 0.5f;
        for (int value = 0; value <= 255; value++) {
            seen += histogram[value];
            if (seen >= target) {
                return value;
            }
        }
        return 0;
    }

    private static float brightestAverage(float[] histogram, float count) {
        float seen = 0;
        float weighted = 0;
        for (int value = 255; value >= 0; value--) {
            float available = histogram[value];
            float included = available;
            if (seen + available >= count) {
                included = count - seen;
            }
            seen += included;
            weighted += value * included;
            if (seen >= count) {
                break;
            }
        }
        return seen > 0 ? weighted / seen : 0;
    }

    private static Color color(int red, int green, int blue) {
        return new Color(clamp(red), clamp(green), clamp(blue), 255);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private record ReferenceResult(
            byte[] bytes,
            int paddedWidth,
            int paddedHeight,
            Color averageColor,
            Color brightColor,
            Color medianColor) {
    }

    private static final class TrackingBufferedImage extends BufferedImage {
        private final TrackingRaster trackingRaster;

        private TrackingBufferedImage(int width, int height) {
            super(width, height, BufferedImage.TYPE_4BYTE_ABGR);
            trackingRaster = new TrackingRaster(super.getRaster());
        }

        @Override
        public Raster getData() {
            return trackingRaster;
        }

        @Override
        public WritableRaster getRaster() {
            return trackingRaster;
        }
    }

    private static final class TrackingRaster extends WritableRaster {
        private final WritableRaster delegate;
        private int bulkReadCount;
        private int singlePixelReadCount;

        private TrackingRaster(WritableRaster delegate) {
            super(
                    delegate.getSampleModel(),
                    delegate.getDataBuffer(),
                    new Point(delegate.getMinX(), delegate.getMinY()));
            this.delegate = delegate;
        }

        @Override
        public int[] getPixel(int x, int y, int[] target) {
            singlePixelReadCount++;
            return delegate.getPixel(x, y, target);
        }

        @Override
        public int[] getPixels(
                int x, int y, int width, int height, int[] target) {
            bulkReadCount++;
            return delegate.getPixels(x, y, width, height, target);
        }
    }

    private static final class CopyDetectingBufferedImage
            extends BufferedImage {
        private boolean dataCopyRequested;

        private CopyDetectingBufferedImage(int width, int height) {
            super(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        }

        @Override
        public Raster getData() {
            dataCopyRequested = true;
            return super.getData();
        }
    }

    private static final class ReentrantBufferedImage extends BufferedImage {
        private final List<String> observations = new ArrayList<>();

        private ReentrantBufferedImage(int width, int height) {
            super(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        }

        @Override
        public Raster getData() {
            observations.add("data");
            return super.getData();
        }

        @Override
        public java.awt.image.ColorModel getColorModel() {
            if (observations != null) {
                observations.add("alpha");
            }
            return super.getColorModel();
        }
    }

    private static final class FailingPixelBufferedImage
            extends BufferedImage {
        private final WritableRaster failingRaster;

        private FailingPixelBufferedImage(int width, int height) {
            super(width, height, BufferedImage.TYPE_4BYTE_ABGR);
            failingRaster = new FailingPixelRaster(super.getRaster());
        }

        @Override
        public Raster getData() {
            return failingRaster;
        }
    }

    private static final class FailingPixelRaster extends WritableRaster {
        private FailingPixelRaster(WritableRaster delegate) {
            super(
                    delegate.getSampleModel(),
                    delegate.getDataBuffer(),
                    new Point(delegate.getMinX(), delegate.getMinY()));
        }

        @Override
        public int[] getPixel(int x, int y, int[] target) {
            throw new IllegalStateException("synthetic pixel failure");
        }
    }
}
