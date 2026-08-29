package org.fossic.starsector.dynfont;

import java.awt.Color;
import java.util.regex.Pattern;

/** Compatibility helpers for the renderer's translated-text highlight paths. */
public final class DynFontHighlightHooks {
    private static final String ORIGINAL_FLAGS = "(?is)";
    private static final Pattern NEVER_MATCH = Pattern.compile("(?!)");

    private DynFontHighlightHooks() {
    }

    /**
     * Compiles the renderer's prepared fallback expression without treating tooltip text as regex.
     *
     * <p>The original renderer replaces ASCII spaces with {@code .}, prepends {@code (?is)}, and
     * then compiles the whole UI string as a regular expression. Keep those wildcard separators
     * and flags, but quote every other character so text such as {@code +20%} cannot throw a
     * {@link java.util.regex.PatternSyntaxException}.</p>
     */
    public static Pattern compileFallback(String prepared) {
        if (prepared == null) return NEVER_MATCH;

        String body = prepared.startsWith(ORIGINAL_FLAGS)
                ? prepared.substring(ORIGINAL_FLAGS.length())
                : prepared;
        StringBuilder safe = new StringBuilder(body.length() + 16);
        int literalStart = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != '.') continue;
            appendQuoted(safe, body, literalStart, i);
            safe.append('.');
            literalStart = i + 1;
        }
        appendQuoted(safe, body, literalStart, body.length());
        return Pattern.compile(safe.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    /**
     * Treats a null per-range color as "use the renderer's default highlight color".
     *
     * <p>The renderer already falls back to its default when a per-range color is absent, but
     * dereferences an explicitly null array entry while recalculating alpha. Preserve complete
     * arrays by identity and copy only malformed arrays, so normal rendering and caller-owned
     * arrays remain untouched.</p>
     */
    public static Color[] normalizeHighlightColors(Color[] colors, Color defaultColor) {
        if (colors == null) return null;

        int firstNull = -1;
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] == null) {
                firstNull = i;
                break;
            }
        }
        if (firstNull < 0) return colors;

        Color fallback = defaultColor != null ? defaultColor : Color.WHITE;
        Color[] normalized = colors.clone();
        for (int i = firstNull; i < normalized.length; i++) {
            if (normalized[i] == null) normalized[i] = fallback;
        }
        return normalized;
    }

    private static void appendQuoted(StringBuilder target, String source, int start, int end) {
        if (start < end) target.append(Pattern.quote(source.substring(start, end)));
    }
}
