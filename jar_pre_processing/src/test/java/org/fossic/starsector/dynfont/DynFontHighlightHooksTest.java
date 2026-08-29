package org.fossic.starsector.dynfont;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DynFontHighlightHooksTest {
    @Test
    void preservesSpaceWildcardAndRegexFlagsButQuotesTooltipText() {
        Pattern pattern = DynFontHighlightHooks.compileFallback(
                "(?is)+20%.DAMAGE.(frigate)[x]$\\tail");

        assertTrue(pattern.matcher(
                "prefix +20%\ndamage (frigate)[x]$\\tail suffix").find());
        assertFalse(pattern.matcher(
                "prefix 220%\ndamage (frigate)[x]$\\tail suffix").find());
    }

    @Test
    void safelyHandlesEveryRegexMetacharacterAndQuotedTerminator() {
        Pattern pattern = DynFontHighlightHooks.compileFallback(
                "(?is)[](){}*+?$^|\\E.value");

        assertTrue(pattern.matcher("[](){}*+?$^|\\E value").find());
    }

    @Test
    void malformedOrMissingPrefixStillCannotCrash() {
        Pattern withoutPrefix = DynFontHighlightHooks.compileFallback("+20%.damage");
        Pattern nullInput = DynFontHighlightHooks.compileFallback(null);

        assertTrue(withoutPrefix.matcher("+20% damage").find());
        assertFalse(nullInput.matcher("anything").find());
    }

    @Test
    void leavesNullAndCompleteHighlightColorArraysUntouched() {
        Color first = new Color(10, 20, 30, 40);
        Color second = new Color(50, 60, 70, 80);
        Color[] complete = {first, second};

        assertNull(DynFontHighlightHooks.normalizeHighlightColors(null, Color.YELLOW));
        assertSame(complete,
                DynFontHighlightHooks.normalizeHighlightColors(complete, Color.YELLOW));
        assertArrayEquals(new Color[]{first, second}, complete);
    }

    @Test
    void replacesNullHighlightColorsWithoutMutatingCallerArray() {
        Color first = new Color(10, 20, 30, 40);
        Color fallback = new Color(90, 100, 110, 120);
        Color[] source = {first, null, null};

        Color[] normalized =
                DynFontHighlightHooks.normalizeHighlightColors(source, fallback);

        assertNotSame(source, normalized);
        assertArrayEquals(new Color[]{first, fallback, fallback}, normalized);
        assertArrayEquals(new Color[]{first, null, null}, source);
        assertSame(first, normalized[0]);
        assertSame(fallback, normalized[1]);
        assertSame(fallback, normalized[2]);
    }

    @Test
    void usesRendererConstructionDefaultWhenFallbackColorIsNull() {
        Color[] normalized =
                DynFontHighlightHooks.normalizeHighlightColors(new Color[]{null}, null);

        assertArrayEquals(new Color[]{Color.WHITE}, normalized);
    }
}
