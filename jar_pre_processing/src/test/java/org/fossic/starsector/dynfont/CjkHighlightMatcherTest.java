package org.fossic.starsector.dynfont;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

final class CjkHighlightMatcherTest {
    private static long range(int start, int end) {
        return ((long) start << 32) | end;
    }

    @Test
    void everyChineseSplitIncludesTheInsertedLf() {
        String word = "核心易损";
        for (int split = 1; split < word.length(); split++) {
            String display = word.substring(0, split) + "\n" + word.substring(split);
            assertEquals(
                    range(0, 5),
                    CjkHighlightMatcher.findBulk(display.replace('\n', ' '), word, 0, display));
            assertEquals(range(0, 5), CjkHighlightMatcher.selectSingle(-1, display, word, false));
        }
    }

    @Test
    void boundariesAcceptHanAndPunctuationOnEitherSideOnly() {
        String punctuation = "，。！？；：、（）［］｛｝【】〔〕〖〗〘〙〚〛《》〈〉「」『』﹁﹂﹃﹄“”‘’〝〞〟…—–﹏·";
        punctuation
                .codePoints()
                .forEach(cp -> assertTrue(CjkHighlightMatcher.isCjk(cp), Integer.toHexString(cp)));
        for (int cp : new int[] {'A', '0', '%', 0xff21, 0xff10, 0xffe5, 0x1f600})
            assertFalse(CjkHighlightMatcher.isCjk(cp));
        assertTrue(CjkHighlightMatcher.cjkBoundary("𠀀EMP", 2));
        assertTrue(CjkHighlightMatcher.cjkBoundary("EMP𠀀", 3));
        assertTrue(CjkHighlightMatcher.cjkBoundary("A核", 1));
        assertTrue(CjkHighlightMatcher.cjkBoundary("核A", 1));
        assertFalse(CjkHighlightMatcher.cjkBoundary("AB", 1));
    }

    @Test
    void orderedSearchFindsEarlierWrappedCandidateAndRespectsFromIndex() {
        String display = "核心易\n损核心易损";
        assertEquals(
                range(0, 5),
                CjkHighlightMatcher.findBulk(display.replace('\n', ' '), "核心易损", 0, display));
        assertEquals(
                range(5, 9),
                CjkHighlightMatcher.findBulk(display.replace('\n', ' '), "核心易损", 5, display));
        assertEquals(
                -1, CjkHighlightMatcher.findBulk(display.replace('\n', ' '), "核心易损", 9, display));
    }

    @Test
    void onlySkipsSingleInternalLfAndNeverRealSpacesOrOtherWhitespace() {
        for (String text : List.of("核\n\n心", "核\r\n心", "核\t心", "核 心", "核\n 心", "核\nX心"))
            assertEquals(-1, CjkHighlightMatcher.selectSingle(-1, text, "核心", false), text);
        assertEquals(-1, CjkHighlightMatcher.selectSingle(-1, "E\nMP伤害", "EMP伤害", false));
        assertEquals(range(0, 6), CjkHighlightMatcher.selectSingle(-1, "EMP\n伤害", "EMP伤害", false));
        assertEquals(range(0, 5), CjkHighlightMatcher.selectSingle(-1, "核\n心\n损", "核心损", false));
        assertEquals(range(0, 5), CjkHighlightMatcher.findBulk("核心 易损", "核心 易损", 0, "核心\n易损"));
    }

    @Test
    void noMatchAndEmptyInputsKeepSentinelsAndEmptyExactSemantics() {
        assertEquals(-1, CjkHighlightMatcher.selectSingle(-1, "", "核心", false));
        assertEquals(range(0, 0), CjkHighlightMatcher.selectSingle(0, "", "", false));
        assertEquals(-1, CjkHighlightMatcher.findBulk("abc", "核心", 0, "abc"));
    }
}
