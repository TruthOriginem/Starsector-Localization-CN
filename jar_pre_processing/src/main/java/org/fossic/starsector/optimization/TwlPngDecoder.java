package org.fossic.starsector.optimization;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;

/**
 * 把受白名单保护的 PNG 直接解码为游戏纹理转换可批量读取的 RGB/RGBA raster。
 *
 * <p>PNGDecoder 使用 JDK 自带的 native zlib，并在 Java 中完成逐行反滤波；输出直接写入
 * 最终 {@code DataBufferByte} 的 backing array，不经过 JNI 或第二份像素复制。
 */
public final class TwlPngDecoder {
    private static final ComponentColorModel RGB_MODEL = colorModel(false);
    private static final ComponentColorModel RGBA_MODEL = colorModel(true);

    private TwlPngDecoder() {
    }

    public static BufferedImage decode(byte[] encoded) throws IOException {
        return toBufferedImage(Holder.CODEC.decode(encoded));
    }

    static BufferedImage toBufferedImage(PngDecodedImage decoded) {
        boolean alpha = decoded.channels() == 4;
        int[] offsets = alpha
                ? new int[]{0, 1, 2, 3}
                : new int[]{0, 1, 2};
        DataBufferByte data = new DataBufferByte(
                decoded.pixels(), decoded.pixels().length);
        WritableRaster raster = Raster.createInterleavedRaster(
                data,
                decoded.width(),
                decoded.height(),
                decoded.width() * decoded.channels(),
                decoded.channels(),
                offsets,
                null);
        return new BufferedImage(
                alpha ? RGBA_MODEL : RGB_MODEL,
                raster,
                false,
                null);
    }

    private static ComponentColorModel colorModel(boolean alpha) {
        return new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                alpha
                        ? new int[]{8, 8, 8, 8}
                        : new int[]{8, 8, 8},
                alpha,
                false,
                alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE);
    }

    private static final class Holder {
        private static final PrivatePngCodec CODEC =
                PrivateDependencyClassLoader.loadProvider(
                        "META-INF/starsector-optimization/private/png/",
                        "org.fossic.starsector.privateimpl.png.TwlPngProvider",
                        PrivatePngCodec.class,
                        new PrivateDependencyClassLoader.OwnedPackage(
                                "org.fossic.starsector.privateimpl.png.",
                                "org.fossic.starsector.privateimpl.png."
                                        + "TwlPngProvider"),
                        new PrivateDependencyClassLoader.OwnedPackage(
                                "de.matthiasmann.twl.utils.",
                                "de.matthiasmann.twl.utils.PNGDecoder"));
    }
}
