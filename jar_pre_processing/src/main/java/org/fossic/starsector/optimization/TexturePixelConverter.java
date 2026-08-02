package org.fossic.starsector.optimization;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 把游戏纹理图像转换为 OpenGL 上传缓冲，并计算原版使用的三种代表色。
 *
 * <p>原版对每个像素调用一次 {@link Raster#getPixel(int, int, int[])}，在常见的三通道和
 * 四通道栅格上会产生大量虚方法调用。标准 {@link BufferedImage} 直接读取 backing raster，
 * 避免 {@link BufferedImage#getData()} 创建整图副本；mod 提供的子类仍调用 {@code getData()}，
 * 保留其自定义快照语义。随后一次读取一整行，在 Java 数组中完成通道转换。非标准通道数仍
 * 回退到原版逐像素路径，保留灰度和索引色图像原有的特殊语义。
 */
public final class TexturePixelConverter {
    private static final int LARGE_TEXTURE_SIZE = 2048;

    private TexturePixelConverter() {
    }

    public static Result convert(BufferedImage image) {
        return convert(image, null);
    }

    public static Result convert(
            BufferedImage image, ByteBuffer cachedOpaqueLargeTextureBuffer) {
        return convertUncached(image, cachedOpaqueLargeTextureBuffer);
    }

    /** 仅由 texture-cache 功能组的 ASM bridge 调用。 */
    public static Result convertCached(
            BufferedImage image, ByteBuffer cachedOpaqueLargeTextureBuffer) {
        TextureConversionCache.CachedTexture cached =
                TextureSourceTracker.cachedTexture(image);
        if (cached != null) {
            return restoreCached(cached, cachedOpaqueLargeTextureBuffer);
        }

        BufferedImage conversionImage =
                TextureSourceTracker.imageForConversion(image);
        Result converted = convertUncached(
                conversionImage, cachedOpaqueLargeTextureBuffer);
        String sourceHash = TextureSourceTracker.takeSourceHash(image);
        if (sourceHash != null) {
            TextureConversionCache.store(
                    sourceHash,
                    conversionImage.getWidth(),
                    conversionImage.getHeight(),
                    conversionImage.getColorModel().hasAlpha(),
                    converted);
        }
        return converted;
    }

    private static Result convertUncached(
            BufferedImage image, ByteBuffer cachedOpaqueLargeTextureBuffer) {
        int width = image.getWidth();
        int height = image.getHeight();
        int paddedWidth = powerOfTwoAtLeastTwo(width);
        int paddedHeight = powerOfTwoAtLeastTwo(height);
        boolean standardImage = image.getClass() == BufferedImage.class
                && image.getType() != BufferedImage.TYPE_CUSTOM;
        Raster raster = standardImage ? image.getRaster() : image.getData();
        boolean hasAlpha = image.getColorModel().hasAlpha();
        int components = hasAlpha ? 4 : 3;
        ByteBuffer output = outputBuffer(
                width,
                height,
                paddedWidth,
                paddedHeight,
                components,
                hasAlpha,
                cachedOpaqueLargeTextureBuffer);
        ByteBuffer resultingLargeTextureBuffer =
                isReusableOpaqueLargeTexture(width, height, hasAlpha)
                        ? output
                        : cachedOpaqueLargeTextureBuffer;
        Statistics statistics = new Statistics();

        if (standardImage && raster.getNumBands() == components) {
            convertRows(
                    raster, width, height, paddedWidth, components,
                    hasAlpha, output, statistics);
        } else {
            convertPixels(
                    raster, width, height, paddedWidth, components,
                    hasAlpha, output, statistics);
        }
        output.position(0);
        output.limit(output.capacity());

        return new Result(
                output,
                paddedWidth,
                paddedHeight,
                statistics.averageColor(),
                statistics.brightColor(),
                statistics.medianColor(),
                resultingLargeTextureBuffer);
    }

    private static Result restoreCached(
            TextureConversionCache.CachedTexture cached,
            ByteBuffer cachedOpaqueLargeTextureBuffer) {
        int components = cached.hasAlpha() ? 4 : 3;
        ByteBuffer output = outputBuffer(
                cached.imageWidth(),
                cached.imageHeight(),
                cached.paddedWidth(),
                cached.paddedHeight(),
                components,
                cached.hasAlpha(),
                cachedOpaqueLargeTextureBuffer);
        if (output.capacity() != cached.pixelBytes().length) {
            throw new IllegalStateException(
                    "cached texture buffer capacity mismatch");
        }
        output.position(0);
        output.put(cached.pixelBytes());
        output.position(0);
        output.limit(output.capacity());
        ByteBuffer resultingLargeTextureBuffer =
                isReusableOpaqueLargeTexture(
                        cached.imageWidth(),
                        cached.imageHeight(),
                        cached.hasAlpha())
                        ? output
                        : cachedOpaqueLargeTextureBuffer;
        return new Result(
                output,
                cached.paddedWidth(),
                cached.paddedHeight(),
                cached.averageColor(),
                cached.brightColor(),
                cached.medianColor(),
                resultingLargeTextureBuffer);
    }

    private static void convertRows(
            Raster raster,
            int width,
            int height,
            int paddedWidth,
            int components,
            boolean hasAlpha,
            ByteBuffer output,
            Statistics statistics) {
        int[] samples = new int[width * components];
        byte[] encoded = new byte[width * components];
        for (int outputY = 0; outputY < height; outputY++) {
            int sourceY = height - outputY - 1;
            raster.getPixels(0, sourceY, width, 1, samples);
            encodeRow(
                    samples, encoded, width, components, hasAlpha, statistics);
            output.position(outputY * paddedWidth * components);
            output.put(encoded, 0, encoded.length);
        }
    }

    private static void convertPixels(
            Raster raster,
            int width,
            int height,
            int paddedWidth,
            int components,
            boolean hasAlpha,
            ByteBuffer output,
            Statistics statistics) {
        int[] pixel = new int[components];
        byte[] encoded = new byte[width * components];
        for (int outputY = 0; outputY < height; outputY++) {
            int sourceY = height - outputY - 1;
            for (int x = 0; x < width; x++) {
                raster.getPixel(x, sourceY, pixel);
                encodePixel(
                        pixel, 0, encoded, x * components,
                        hasAlpha, statistics);
            }
            output.position(outputY * paddedWidth * components);
            output.put(encoded, 0, encoded.length);
        }
    }

    private static void encodeRow(
            int[] samples,
            byte[] encoded,
            int width,
            int components,
            boolean hasAlpha,
            Statistics statistics) {
        for (int x = 0; x < width; x++) {
            int offset = x * components;
            encodePixel(
                    samples, offset, encoded, offset, hasAlpha, statistics);
        }
    }

    private static void encodePixel(
            int[] samples,
            int sampleOffset,
            byte[] encoded,
            int outputOffset,
            boolean hasAlpha,
            Statistics statistics) {
        if (hasAlpha && samples[sampleOffset + 3] == 0) {
            encoded[outputOffset] = 0;
            encoded[outputOffset + 1] = 0;
            encoded[outputOffset + 2] = 0;
            encoded[outputOffset + 3] = 0;
            return;
        }

        int red = samples[sampleOffset];
        int green = samples[sampleOffset + 1];
        int blue = samples[sampleOffset + 2];
        encoded[outputOffset] = (byte) red;
        encoded[outputOffset + 1] = (byte) green;
        encoded[outputOffset + 2] = (byte) blue;
        if (hasAlpha) {
            encoded[outputOffset + 3] = (byte) samples[sampleOffset + 3];
            statistics.addWithHistogram(red, green, blue);
        } else {
            statistics.addOpaque(red, green, blue);
        }
    }

    private static ByteBuffer outputBuffer(
            int width,
            int height,
            int paddedWidth,
            int paddedHeight,
            int components,
            boolean hasAlpha,
            ByteBuffer cachedOpaqueLargeTextureBuffer) {
        int capacity = paddedWidth * paddedHeight * components;
        ByteBuffer result;
        if (isReusableOpaqueLargeTexture(width, height, hasAlpha)
                && cachedOpaqueLargeTextureBuffer != null) {
            result = cachedOpaqueLargeTextureBuffer;
        } else {
            result = allocateDirect(capacity);
        }
        result.position(0);
        result.limit(result.capacity());
        return result;
    }

    private static boolean isReusableOpaqueLargeTexture(
            int width, int height, boolean hasAlpha) {
        return !hasAlpha
                && width == LARGE_TEXTURE_SIZE
                && height == LARGE_TEXTURE_SIZE;
    }

    private static ByteBuffer allocateDirect(int capacity) {
        return ByteBuffer.allocateDirect(capacity)
                .order(ByteOrder.nativeOrder());
    }

    private static int powerOfTwoAtLeastTwo(int value) {
        int result = 2;
        while (result < value) {
            result *= 2;
        }
        return result;
    }

    private static final class Statistics {
        private final float[] redHistogram = new float[256];
        private final float[] greenHistogram = new float[256];
        private final float[] blueHistogram = new float[256];
        private float redSum;
        private float greenSum;
        private float blueSum;
        private float count;

        private void addWithHistogram(int red, int green, int blue) {
            add(red, green, blue);
            redHistogram[red]++;
            greenHistogram[green]++;
            blueHistogram[blue]++;
        }

        private void addOpaque(int red, int green, int blue) {
            add(red, green, blue);
            if (red < 256 && green < 256 && blue < 256) {
                redHistogram[red]++;
                greenHistogram[green]++;
                blueHistogram[blue]++;
            }
        }

        private void add(int red, int green, int blue) {
            redSum += red;
            greenSum += green;
            blueSum += blue;
            count++;
        }

        private Color averageColor() {
            if (count <= 0) {
                return Color.white;
            }
            return color(
                    (int) (redSum / count),
                    (int) (greenSum / count),
                    (int) (blueSum / count));
        }

        private Color brightColor() {
            if (count <= 0) {
                return Color.white;
            }
            float brightCount = count * 0.5f;
            return color(
                    (int) brightestAverage(redHistogram, brightCount),
                    (int) brightestAverage(greenHistogram, brightCount),
                    (int) brightestAverage(blueHistogram, brightCount));
        }

        private Color medianColor() {
            if (count <= 0) {
                return Color.white;
            }
            return color(
                    (int) lowerMedian(redHistogram, count),
                    (int) lowerMedian(greenHistogram, count),
                    (int) brightestAverage(blueHistogram, count));
        }
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

    public static final class Result {
        private final ByteBuffer buffer;
        private final int paddedWidth;
        private final int paddedHeight;
        private final Color averageColor;
        private final Color brightColor;
        private final Color medianColor;
        private final ByteBuffer cachedOpaqueLargeTextureBuffer;

        private Result(
                ByteBuffer buffer,
                int paddedWidth,
                int paddedHeight,
                Color averageColor,
                Color brightColor,
                Color medianColor,
                ByteBuffer cachedOpaqueLargeTextureBuffer) {
            this.buffer = buffer;
            this.paddedWidth = paddedWidth;
            this.paddedHeight = paddedHeight;
            this.averageColor = averageColor;
            this.brightColor = brightColor;
            this.medianColor = medianColor;
            this.cachedOpaqueLargeTextureBuffer =
                    cachedOpaqueLargeTextureBuffer;
        }

        public ByteBuffer buffer() {
            return buffer;
        }

        public int paddedWidth() {
            return paddedWidth;
        }

        public int paddedHeight() {
            return paddedHeight;
        }

        public Color averageColor() {
            return averageColor;
        }

        public Color brightColor() {
            return brightColor;
        }

        public Color medianColor() {
            return medianColor;
        }

        public ByteBuffer cachedOpaqueLargeTextureBuffer() {
            return cachedOpaqueLargeTextureBuffer;
        }
    }
}
