package org.fossic.starsector.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class GlyphArrayGrowthTest {
    @BeforeEach
    void resetDiagnostics() {
        GlyphArrayGrowth.resetForTests();
    }

    @Test
    void returnsTheSameArrayWhenTheGlyphAlreadyFits() {
        Marker[] original = new Marker[256];

        Object[] result = GlyphArrayGrowth.ensureCapacity(
                original, 255);

        assertSame(original, result);
        assertEquals(
                "{\"growths\":0,\"copiedElements\":0}",
                GlyphArrayGrowthDiagnostics.json());
    }

    @Test
    void growsToExactlyGlyphIdPlusOneHundredAndPreservesType() {
        Marker[] original = new Marker[256];
        original[0] = new Marker();
        original[255] = new Marker();

        Object[] result = GlyphArrayGrowth.ensureCapacity(
                original, 256);

        assertEquals(Marker[].class, result.getClass());
        assertEquals(356, result.length);
        assertSame(original[0], result[0]);
        assertSame(original[255], result[255]);
        assertNull(result[256]);
        assertEquals(
                "{\"growths\":1,\"copiedElements\":256}",
                GlyphArrayGrowthDiagnostics.json());
    }

    @Test
    void sparseGlyphUsesTheSameExactLengthRuleAsTheGame() {
        Marker[] original = new Marker[4];

        Object[] result = GlyphArrayGrowth.ensureCapacity(
                original, 65_535);

        assertEquals(65_635, result.length);
        assertEquals(
                "{\"growths\":1,\"copiedElements\":4}",
                GlyphArrayGrowthDiagnostics.json());
    }

    @Test
    void negativeGlyphRemainsForTheOriginalArrayStoreToReject() {
        Marker[] original = new Marker[4];

        assertSame(original, GlyphArrayGrowth.ensureCapacity(
                original, -1));
    }

    private static final class Marker {
    }
}
