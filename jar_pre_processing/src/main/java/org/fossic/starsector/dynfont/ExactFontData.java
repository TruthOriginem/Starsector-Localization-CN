package org.fossic.starsector.dynfont;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/** 不依赖游戏类或 OpenGL 的精确动态字体数据与测量核心。 */
final class ExactFontData {
    private static final byte[] MAGIC = {'S', 'S', 'D', 'F', 'O', 'N', 'T', 0};
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 48;
    private static final int GLYPH_BYTES = 32;
    private static final int KERNING_BYTES = 12;
    private static final int MAX_RECORDS = 1_000_000;

    record Glyph(int codepoint, int x, int y, int width, int height,
                 float bearingX, float bearingY, float advance) {
    }

    private final double atlasScreenScale;
    private final float baseNominal;
    private final float lineHeightPhysical;
    private final float baselinePhysical;
    private final int atlasWidth;
    private final int atlasHeight;
    private final Map<Integer, Glyph> glyphs;
    private final Map<Long, Float> kernings;
    private final Glyph fallback;

    private ExactFontData(double atlasScreenScale, float baseNominal,
                          float lineHeightPhysical, float baselinePhysical,
                          int atlasWidth, int atlasHeight,
                          Map<Integer, Glyph> glyphs, Map<Long, Float> kernings)
            throws IOException {
        this.atlasScreenScale = atlasScreenScale;
        this.baseNominal = baseNominal;
        this.lineHeightPhysical = lineHeightPhysical;
        this.baselinePhysical = baselinePhysical;
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
        this.glyphs = Map.copyOf(glyphs);
        this.kernings = Map.copyOf(kernings);
        fallback = glyphs.get((int) '?');
        if (fallback == null) {
            throw new IOException("dfnt 缺少必需的 ? fallback glyph");
        }
    }

    static ExactFontData parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < HEADER_BYTES) {
            throw new IOException("dfnt header truncated");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (byte expected : MAGIC) {
            if (in.get() != expected) {
                throw new IOException("dfnt magic mismatch");
            }
        }
        int version = in.getInt();
        if (version != VERSION) {
            throw new IOException("unsupported dfnt version: " + version);
        }
        double atlasScale = in.getDouble();
        float nominal = in.getFloat();
        float lineHeight = in.getFloat();
        float baseline = in.getFloat();
        int atlasWidth = in.getInt();
        int atlasHeight = in.getInt();
        int glyphCount = in.getInt();
        int kerningCount = in.getInt();
        if (!Double.isFinite(atlasScale) || atlasScale < 1.0
                || !Float.isFinite(nominal) || nominal <= 0
                || !Float.isFinite(lineHeight) || lineHeight <= 0
                || !Float.isFinite(baseline)
                || atlasWidth <= 0 || atlasHeight <= 0
                || glyphCount <= 0 || glyphCount > MAX_RECORDS
                || kerningCount < 0 || kerningCount > MAX_RECORDS) {
            throw new IOException("invalid dfnt header values");
        }
        long expectedLength = HEADER_BYTES
                + (long) glyphCount * GLYPH_BYTES
                + (long) kerningCount * KERNING_BYTES;
        if (expectedLength != bytes.length) {
            throw new IOException("dfnt length mismatch");
        }

        Map<Integer, Glyph> glyphs = new HashMap<>(glyphCount * 4 / 3 + 1);
        for (int i = 0; i < glyphCount; i++) {
            int codepoint = in.getInt();
            int x = in.getInt();
            int y = in.getInt();
            int width = in.getInt();
            int height = in.getInt();
            float bearingX = in.getFloat();
            float bearingY = in.getFloat();
            float advance = in.getFloat();
            if (!Character.isValidCodePoint(codepoint)
                    || x < 0 || y < 0 || width < 0 || height < 0
                    || (long) x + width > atlasWidth || (long) y + height > atlasHeight
                    || !Float.isFinite(bearingX) || !Float.isFinite(bearingY)
                    || !Float.isFinite(advance) || advance < 0) {
                throw new IOException("invalid dfnt glyph record: " + codepoint);
            }
            Glyph previous = glyphs.put(codepoint,
                    new Glyph(codepoint, x, y, width, height,
                            bearingX, bearingY, advance));
            if (previous != null) {
                throw new IOException("duplicate dfnt glyph: " + codepoint);
            }
        }

        Map<Long, Float> kernings = new HashMap<>(kerningCount * 4 / 3 + 1);
        for (int i = 0; i < kerningCount; i++) {
            int first = in.getInt();
            int second = in.getInt();
            float amount = in.getFloat();
            if (!Character.isValidCodePoint(first) || !Character.isValidCodePoint(second)
                    || !Float.isFinite(amount)) {
                throw new IOException("invalid dfnt kerning record");
            }
            long key = pair(first, second);
            if (kernings.put(key, amount) != null) {
                throw new IOException("duplicate dfnt kerning pair");
            }
        }
        return new ExactFontData(atlasScale, nominal, lineHeight, baseline,
                atlasWidth, atlasHeight, glyphs, kernings);
    }

    double atlasScreenScale() {
        return atlasScreenScale;
    }

    float baseNominal() {
        return baseNominal;
    }

    float lineHeightPhysical() {
        return lineHeightPhysical;
    }

    float baselinePhysical() {
        return baselinePhysical;
    }

    int atlasWidth() {
        return atlasWidth;
    }

    int atlasHeight() {
        return atlasHeight;
    }

    Glyph glyph(int codepoint) {
        return glyphs.getOrDefault(codepoint, fallback);
    }

    float measureWidth(String text, float requestedSize) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float factor = requestedFactor(requestedSize);
        float current = 0f;
        float widest = 0f;
        int previous = -1;
        for (int offset = 0; offset < text.length();) {
            int codepoint = text.codePointAt(offset);
            offset += Character.charCount(codepoint);
            if (codepoint == '\n') {
                widest = Math.max(widest, current);
                current = 0f;
                previous = -1;
                continue;
            }
            Glyph glyph = glyph(codepoint);
            if (previous >= 0) {
                current += kernings.getOrDefault(pair(previous, glyph.codepoint()), 0f);
            }
            current += glyph.advance();
            previous = glyph.codepoint();
        }
        return Math.max(widest, current) * factor;
    }

    float measureHeight(String text, float requestedSize) {
        int lines = 1;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
        }
        return lines * lineHeightPhysical * requestedFactor(requestedSize);
    }

    private float requestedFactor(float requestedSize) {
        if (!Float.isFinite(requestedSize) || requestedSize == 0f) {
            return 0f;
        }
        return Math.abs(requestedSize) / baseNominal / (float) atlasScreenScale;
    }

    private static long pair(int first, int second) {
        return ((long) first << 32) ^ (second & 0xffffffffL);
    }
}
