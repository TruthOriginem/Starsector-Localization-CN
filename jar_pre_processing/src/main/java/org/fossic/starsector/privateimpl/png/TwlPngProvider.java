package org.fossic.starsector.privateimpl.png;

import de.matthiasmann.twl.utils.PNGDecoder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.fossic.starsector.optimization.PngDecodedImage;
import org.fossic.starsector.optimization.PrivatePngCodec;

/** 只由 PrivateDependencyClassLoader 定义，第三方类型不会越过 SPI 边界。 */
public final class TwlPngProvider implements PrivatePngCodec {
    @Override
    public PngDecodedImage decode(byte[] encoded) throws IOException {
        PNGDecoder decoder = new PNGDecoder(new ByteArrayInputStream(encoded));
        boolean alpha = decoder.hasAlpha();
        int channels = alpha ? 4 : 3;
        int stride;
        int byteCount;
        try {
            stride = Math.multiplyExact(decoder.getWidth(), channels);
            byteCount = Math.multiplyExact(stride, decoder.getHeight());
        } catch (ArithmeticException tooLarge) {
            throw new IOException("decoded PNG is too large", tooLarge);
        }
        byte[] pixels = new byte[byteCount];
        decoder.decode(
                ByteBuffer.wrap(pixels),
                stride,
                alpha ? PNGDecoder.Format.RGBA : PNGDecoder.Format.RGB);
        return new PngDecodedImage(
                decoder.getWidth(), decoder.getHeight(), channels, pixels);
    }
}
