package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class TwlPngDecoderTest {
    @Test
    void decodesRgbWithoutAddingAnAlphaBand() throws Exception {
        assertMatchesImageIo(png(BufferedImage.TYPE_3BYTE_BGR), false);
    }

    @Test
    void decodesRgbaAndPreservesEveryAlphaSample() throws Exception {
        assertMatchesImageIo(png(BufferedImage.TYPE_INT_ARGB), true);
    }

    @Test
    void rejectsMalformedPngSoTheCallerCanFallBack() {
        assertThrows(
                IOException.class,
                () -> TwlPngDecoder.decode(new byte[]{1, 2, 3, 4}));
    }

    private static void assertMatchesImageIo(
            byte[] encoded, boolean expectedAlpha) throws Exception {
        BufferedImage expected = ImageIO.read(
                new ByteArrayInputStream(encoded));
        BufferedImage actual = TwlPngDecoder.decode(encoded);

        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertEquals(
                expected.getColorModel().hasAlpha(),
                actual.getColorModel().hasAlpha());
        if (expectedAlpha) {
            assertTrue(actual.getColorModel().hasAlpha());
        } else {
            assertFalse(actual.getColorModel().hasAlpha());
        }
        assertArrayEquals(
                expected.getRaster().getPixels(
                        0, 0, expected.getWidth(), expected.getHeight(),
                        (int[]) null),
                actual.getRaster().getPixels(
                        0, 0, actual.getWidth(), actual.getHeight(),
                        (int[]) null));
    }

    private static byte[] png(int type) throws IOException {
        BufferedImage image = new BufferedImage(5, 3, type);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = type == BufferedImage.TYPE_INT_ARGB
                        ? (x * 47 + y * 83) & 0xFF
                        : 0xFF;
                int red = (x * 31 + y * 7) & 0xFF;
                int green = (x * 13 + y * 29) & 0xFF;
                int blue = (x * 3 + y * 101) & 0xFF;
                image.setRGB(
                        x, y,
                        alpha << 24 | red << 16 | green << 8 | blue);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
