package org.fossic.starsector.dynfont;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ExactFontDataTest {
    @Test
    void parsesPreciseMetricsAndMeasuresWithRequestedSize() throws Exception {
        ExactFontData font = ExactFontData.parse(validFont());

        assertEquals(1.95, font.atlasScreenScale(), 0.000001);
        assertEquals(20f, font.baseNominal());
        assertEquals(31.25f, font.lineHeightPhysical());
        assertEquals(14f, font.measureWidth("AV", 20f), 0.00001f);
        assertEquals(28f, font.measureWidth("AV", 40f), 0.00001f);
        assertEquals(31.25f / 1.95f * 2f,
                font.measureHeight("A\nV", 20f), 0.00001f);
    }

    @Test
    void usesQuestionMarkForMissingGlyphWithoutMixingOriginalMetrics() throws Exception {
        ExactFontData font = ExactFontData.parse(validFont());
        assertEquals(4f, font.measureWidth("中", 20f), 0.00001f);
    }

    @Test
    void rejectsCorruptOrUnsafeInput() throws Exception {
        byte[] badMagic = validFont();
        badMagic[0] = 'X';
        assertThrows(IOException.class, () -> ExactFontData.parse(badMagic));

        byte[] truncated = validFont();
        assertThrows(IOException.class,
                () -> ExactFontData.parse(java.util.Arrays.copyOf(truncated, 20)));

        byte[] duplicate = validFont();
        ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN).putInt(112, 'A');
        assertThrows(IOException.class, () -> ExactFontData.parse(duplicate));

        byte[] outsideAtlas = validFont();
        ByteBuffer.wrap(outsideAtlas).order(ByteOrder.LITTLE_ENDIAN).putInt(52, 63);
        assertThrows(IOException.class, () -> ExactFontData.parse(outsideAtlas));
    }

    private static byte[] validFont() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream ignored = new DataOutputStream(bytes)) {
            ByteBuffer b = ByteBuffer.allocate(48 + 3 * 32 + 12)
                    .order(ByteOrder.LITTLE_ENDIAN);
            b.put(new byte[]{'S', 'S', 'D', 'F', 'O', 'N', 'T', 0});
            b.putInt(1);
            b.putDouble(1.95);
            b.putFloat(20f);
            b.putFloat(31.25f);
            b.putFloat(27.5f);
            b.putInt(64).putInt(128);
            b.putInt(3).putInt(1);
            glyph(b, '?', 1, 2, 3, 4, 0.25f, 1.5f, 7.8f);
            glyph(b, 'A', 5, 6, 7, 8, 0.5f, 2f, 15.6f);
            glyph(b, 'V', 13, 14, 9, 10, 0.75f, 2.25f, 13.65f);
            b.putInt('A').putInt('V').putFloat(-1.95f);
            return b.array();
        }
    }

    private static void glyph(ByteBuffer b, int id, int x, int y, int w, int h,
                              float bearingX, float bearingY, float advance) {
        b.putInt(id).putInt(x).putInt(y).putInt(w).putInt(h);
        b.putFloat(bearingX).putFloat(bearingY).putFloat(advance);
    }
}
