package org.fossic.starsector.optimization;

import java.util.Objects;

/** PNG 快速解码器返回的非预乘 RGB/RGBA 8-bit 像素。 */
public record PngDecodedImage(
        int width, int height, int channels, byte[] pixels) {
    public PngDecodedImage {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "width and height must be positive");
        }
        if (channels != 3 && channels != 4) {
            throw new IllegalArgumentException(
                    "channels must be RGB or RGBA: " + channels);
        }
        Objects.requireNonNull(pixels, "pixels");
        int expected = Math.multiplyExact(
                Math.multiplyExact(width, height), channels);
        if (pixels.length != expected) {
            throw new IllegalArgumentException(
                    "pixel byte count mismatch: expected=" + expected
                            + ", actual=" + pixels.length);
        }
    }
}
