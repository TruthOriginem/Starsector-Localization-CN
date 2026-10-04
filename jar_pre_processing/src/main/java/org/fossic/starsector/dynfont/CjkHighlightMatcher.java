package org.fossic.starsector.dynfont;

/** Stateless searches in the renderer's final UTF-16 text; ranges include inserted line feeds. */
public final class CjkHighlightMatcher {
    private CjkHighlightMatcher() {}

    /** Upper 32 bits: start; lower 32 bits: exclusive end; -1 means no match. */
    private static long range(int start, int end) {
        return ((long) start << 32) | (end & 0xffffffffL);
    }

    public static long findBulk(String normalized, String target, int from, String displayed) {
        int exact = normalized.indexOf(target, from);
        long fallback =
                wrapped(
                        displayed,
                        target,
                        Math.max(0, from),
                        exact < 0 ? displayed.length() : exact,
                        false,
                        true);
        return fallback != -1L ? fallback : exact < 0 ? -1L : range(exact, exact + target.length());
    }

    public static long selectSingle(int exact, String displayed, String target, boolean last) {
        long fallback =
                wrapped(
                        displayed,
                        target,
                        last && exact >= 0 ? exact + 1 : 0,
                        !last && exact >= 0 ? exact : displayed.length(),
                        last,
                        false);
        return fallback != -1L ? fallback : exact < 0 ? -1L : range(exact, exact + target.length());
    }

    private static long wrapped(
            String displayed,
            String target,
            int from,
            int limit,
            boolean last,
            boolean normalizeLf) {
        if (target.isEmpty() || displayed.indexOf('\n', from) < 0 || !hasCjk(target)) return -1L;
        long found = -1L;
        for (int start = from; start < limit; start++) {
            char first = displayed.charAt(start);
            if ((normalizeLf && first == '\n' ? ' ' : first) != target.charAt(0)) continue;
            int p = start, t = 0;
            boolean skipped = false;
            while (p < displayed.length() && t < target.length()) {
                char ch = displayed.charAt(p);
                if ((normalizeLf && ch == '\n' ? ' ' : ch) == target.charAt(t)) {
                    p++;
                    t++;
                } else if (ch == '\n'
                        && t > 0
                        && p + 1 < displayed.length()
                        && displayed.charAt(p + 1) != '\n'
                        && (isCjk(target.codePointBefore(t)) || isCjk(target.codePointAt(t)))) {
                    p++;
                    skipped = true;
                    // Only one inserted LF at this target position may be skipped.
                    if (displayed.charAt(p) != target.charAt(t)) break;
                } else break;
            }
            if (skipped && t == target.length()) {
                found = range(start, p);
                if (!last) return found;
            }
        }
        return found;
    }

    /** The original ASCII/whitespace and numeric guards still execute independently. */
    public static boolean cjkBoundary(String displayed, int boundary) {
        return boundary > 0
                && boundary < displayed.length()
                && (isCjk(displayed.codePointBefore(boundary))
                        || isCjk(displayed.codePointAt(boundary)));
    }

    private static boolean hasCjk(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    static boolean isCjk(int cp) {
        if (cp < 0x80) return false;
        if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true;
        if (cp == 0x00b7
                || cp == 0x2013
                || cp == 0x2014
                || cp == 0x2026
                || cp >= 0x2018 && cp <= 0x201f) return true;
        boolean block =
                cp >= 0x3000 && cp <= 0x303f
                        || cp >= 0xff00 && cp <= 0xff65
                        || cp >= 0xfe10 && cp <= 0xfe1f
                        || cp >= 0xfe30 && cp <= 0xfe6f;
        if (!block) return false;
        int type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }
}
