package org.fossic.starsector.optimization;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * 只为已证明与 ImageIO 像素语义等价的 PNG 子集启用快速解码。
 *
 * <p>白名单限于 8-bit、非隔行、无色彩管理的 RGB/RGBA。其他图片和任何快速解码异常都走
 * ImageIO；本类不关闭调用方传入的流。
 */
public final class FastPngDecoder {
    public static final String MAXIMUM_ENCODED_BYTES_PROPERTY =
            "starsector.optimization.pngMaximumEncodedBytes";
    private static final int DEFAULT_MAXIMUM_ENCODED_BYTES =
            64 * 1024 * 1024;
    private static final int ABSOLUTE_MAXIMUM_ENCODED_BYTES =
            512 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4E, 0x47,
        0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final Set<String> PIXEL_AFFECTING_CHUNKS = Set.of(
            "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "tRNS",
            "PLTE", "CgBI", "acTL", "fcTL", "fdAT");

    private FastPngDecoder() {
    }

    public static BufferedImage decode(InputStream input) throws IOException {
        return decode(input, TwlPngDecoder::decode);
    }

    /**
     * 纹理缓存组使用的路径感知入口。路径只用于保持 hook 语义；缓存身份完全来自编码内容。
     */
    public static BufferedImage decodeTracked(
            String path, InputStream input) throws IOException {
        return decodeTracked(path, input, TwlPngDecoder::decode);
    }

    static BufferedImage decodeTracked(
            String path, InputStream input, PngBackend backend)
            throws IOException {
        if (!TextureConversionCache.isEnabled()) {
            return decode(input, backend);
        }
        if (input == null) {
            return null;
        }
        BufferedInputStream buffered = input instanceof BufferedInputStream bis
                ? bis
                : new BufferedInputStream(input);
        if (!hasPngSignature(buffered)) {
            return ImageIO.read(buffered);
        }
        EncodedRead read = readBounded(buffered);
        if (!read.complete()) {
            return ImageIO.read(new SequenceInputStream(
                    new ByteArrayInputStream(read.bytes()), buffered));
        }
        byte[] encoded = read.bytes();
        String sourceHash = TextureConversionCache.sourceHash(encoded);
        TextureConversionCache.CachedTexture cached =
                TextureConversionCache.load(sourceHash);
        if (cached != null) {
            return TextureSourceTracker.cachedImage(
                    encoded,
                    cached,
                    bytes -> decodeEncoded(bytes, backend));
        }

        BufferedImage decoded = decodeEncoded(encoded, backend);
        TextureSourceTracker.track(decoded, sourceHash);
        return decoded;
    }

    static BufferedImage decode(InputStream input, PngBackend backend)
            throws IOException {
        if (input == null) {
            return null;
        }
        BufferedInputStream buffered = input instanceof BufferedInputStream bis
                ? bis
                : new BufferedInputStream(input);
        if (!hasPngSignature(buffered)) {
            return ImageIO.read(buffered);
        }
        EncodedRead read = readBounded(buffered);
        if (!read.complete()) {
            return ImageIO.read(new SequenceInputStream(
                    new ByteArrayInputStream(read.bytes()), buffered));
        }
        return decodeEncoded(read.bytes(), backend);
    }

    private static boolean hasPngSignature(BufferedInputStream buffered)
            throws IOException {
        buffered.mark(PNG_SIGNATURE.length);
        byte[] signature = buffered.readNBytes(PNG_SIGNATURE.length);
        buffered.reset();
        return Arrays.equals(signature, PNG_SIGNATURE);
    }

    private static EncodedRead readBounded(BufferedInputStream buffered)
            throws IOException {
        int maximum = Math.max(
                PNG_SIGNATURE.length,
                Integer.getInteger(
                        MAXIMUM_ENCODED_BYTES_PROPERTY,
                        DEFAULT_MAXIMUM_ENCODED_BYTES));
        maximum = Math.min(ABSOLUTE_MAXIMUM_ENCODED_BYTES, maximum);
        byte[] bytes = buffered.readNBytes(maximum);
        return new EncodedRead(bytes, bytes.length < maximum);
    }

    private static BufferedImage decodeEncoded(
            byte[] encoded, PngBackend backend) throws IOException {
        if (!isEligible(encoded)) {
            return ImageIO.read(new ByteArrayInputStream(encoded));
        }
        try {
            return backend.decode(encoded);
        } catch (IOException | RuntimeException | LinkageError failure) {
            return ImageIO.read(new ByteArrayInputStream(encoded));
        }
    }

    static boolean isEligibleForTesting(byte[] encoded) {
        return isEligible(encoded);
    }

    private static boolean isEligible(byte[] encoded) {
        if (encoded.length < 33
                || !matches(encoded, 0, PNG_SIGNATURE)
                || readInt(encoded, 8) != 13
                || !matchesAscii(encoded, 12, "IHDR")) {
            return false;
        }
        int width = readInt(encoded, 16);
        int height = readInt(encoded, 20);
        int bitDepth = encoded[24] & 0xFF;
        int colorType = encoded[25] & 0xFF;
        int compression = encoded[26] & 0xFF;
        int filter = encoded[27] & 0xFF;
        int interlace = encoded[28] & 0xFF;
        if (width <= 0
                || height <= 0
                || bitDepth != 8
                || (colorType != 2 && colorType != 6)
                || compression != 0
                || filter != 0
                || interlace != 0) {
            return false;
        }

        boolean sawImageData = false;
        boolean sawEnd = false;
        int offset = 8;
        while (offset <= encoded.length - 12) {
            int length = readInt(encoded, offset);
            if (length < 0 || length > encoded.length - offset - 12) {
                return false;
            }
            String type = ascii(encoded, offset + 4);
            if (PIXEL_AFFECTING_CHUNKS.contains(type)) {
                return false;
            }
            if (isUnknownCritical(type)) {
                return false;
            }
            if ("IDAT".equals(type)) {
                sawImageData = true;
            } else if ("IEND".equals(type)) {
                sawEnd = true;
                break;
            }
            offset += length + 12;
        }
        return sawImageData && sawEnd;
    }

    private static boolean isUnknownCritical(String type) {
        if (type.length() != 4 || !Character.isUpperCase(type.charAt(0))) {
            return false;
        }
        return !"IHDR".equals(type)
                && !"IDAT".equals(type)
                && !"IEND".equals(type);
    }

    private static boolean matches(byte[] source, int offset, byte[] expected) {
        if (offset < 0 || offset > source.length - expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (source[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAscii(
            byte[] source, int offset, String expected) {
        if (offset < 0 || offset > source.length - expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if ((source[offset + index] & 0xFF) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] source, int offset) {
        if (offset < 0 || offset > source.length - 4) {
            return "";
        }
        return new String(new char[]{
            (char) (source[offset] & 0xFF),
            (char) (source[offset + 1] & 0xFF),
            (char) (source[offset + 2] & 0xFF),
            (char) (source[offset + 3] & 0xFF)
        });
    }

    private static int readInt(byte[] source, int offset) {
        if (offset < 0 || offset > source.length - Integer.BYTES) {
            return -1;
        }
        return (source[offset] & 0xFF) << 24
                | (source[offset + 1] & 0xFF) << 16
                | (source[offset + 2] & 0xFF) << 8
                | source[offset + 3] & 0xFF;
    }

    @FunctionalInterface
    interface PngBackend {
        BufferedImage decode(byte[] encoded) throws IOException;
    }

    private record EncodedRead(byte[] bytes, boolean complete) {
    }
}
