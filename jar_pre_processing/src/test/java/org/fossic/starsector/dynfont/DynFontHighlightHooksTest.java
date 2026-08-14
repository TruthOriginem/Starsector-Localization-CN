package org.fossic.starsector.dynfont;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
