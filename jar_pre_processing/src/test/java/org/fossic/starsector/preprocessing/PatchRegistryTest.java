package org.fossic.starsector.preprocessing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PatchRegistryTest {
    @Test
    void defaultSelectionPreservesReleasePatchOrderWithoutProfiling() {
        List<String> actual = PatchRegistry.patches(PatchSelection.defaults())
                .stream()
                .map(JarPatch::id)
                .toList();

        assertEquals(List.of(
                "faction-hostility-no-manual",
                "ship-info-separator",
                "combat-deployment-font",
                "combat-target-info-width",
                "campaign-date-width",
                "save-date-locale",
                "planet-list-column-width",
                "star-system-map-font",
                "intel-put-first-tag-id",
                "window-decoration-physical-resolution",
                "textfield-ime-hook",
                "resource-stream-dynfont-hook",
                "headless-console-log-detach",
                "font-glyph-bulk-array-growth",
                "font-definition-low-allocation-parser",
                "font-definition-cursor-parser",
                "resource-leaf-remove-global-monitor",
                "resource-lookup-short-monitor",
                "resource-loader-partial-stream-safety",
                "fast-png-image-decode",
                "renderer-dynfont-hd-swap",
                "loading-utils-fast-text-reader",
                "csv-lazy-error-row-formatting",
                "csv-linear-merge",
                "parallel-spec-json-parse",
                "loading-utils-resource-stream-safety",
                "rules-linear-duplicate-id-check",
                "texture-row-pixel-conversion",
                "texture-conversion-content-cache",
                "decoded-pcm-fixed-chunk-accumulator",
                "ogg-pcm-decoder-access",
                "ogg-pcm-bulk-preload-read",
                "decoded-pcm-content-cache",
                "sound-decode-worker-count",
                "resource-stable-priority-partition",
                "preload-result-coordination",
                "preload-image-path-dedup",
                "parallel-image-preload",
                "graphics-resource-stream-safety",
                "janino-compilation-unit-dedup",
                "janino-source-index",
                "janino-bytecode-cache",
                "persistent-cache-cleanup-startup"), actual);
        assertEquals(actual.size(), new HashSet<>(actual).size());
    }

    @Test
    void explicitProfilingSelectionAddsAllProfilePatches() {
        List<String> ids = PatchRegistry.patches(
                PatchSelection.fromOptions("none", List.of(), true))
                .stream()
                .map(JarPatch::id)
                .filter(id -> id.startsWith("startup-profile-"))
                .toList();

        assertEquals(List.of(
                "startup-profile-combat-main",
                "startup-profile-resource-loader",
                "startup-profile-script-worker",
                "startup-profile-title-screen",
                "startup-profile-first-title-frame",
                "startup-profile-app-state-init",
                "startup-profile-codex"), ids);
    }

    @Test
    void filteringUsesGroupsRatherThanIndividualPatchNames() {
        PatchSelection selection = PatchSelection.fromOptions(
                "resource-locks,texture-pipeline", List.of(), false);
        List<String> ids = PatchRegistry.patches(selection).stream()
                .map(JarPatch::id)
                .toList();

        assertTrue(ids.contains("resource-leaf-remove-global-monitor"));
        assertTrue(ids.contains("resource-lookup-short-monitor"));
        assertTrue(ids.contains("texture-row-pixel-conversion"));
        assertFalse(ids.contains("texture-upload-lifetime-cleanup"));
        assertFalse(ids.contains("texture-reusable-staging-cleanup"));
        assertTrue(ids.contains("textfield-ime-hook"));
        assertFalse(ids.contains("loading-utils-fast-text-reader"));
        assertFalse(ids.contains("startup-profile-combat-main"));
    }

    @Test
    void fastPngCanBeSelectedWithoutTheTextureConverter() {
        PatchSelection selection = PatchSelection.fromOptions(
                "fast-png", List.of(), false);
        List<String> ids = PatchRegistry.patches(selection).stream()
                .map(JarPatch::id)
                .toList();

        assertTrue(ids.contains("fast-png-image-decode"));
        assertFalse(ids.contains("texture-row-pixel-conversion"));
        assertFalse(ids.contains("texture-upload-lifetime-cleanup"));
        assertFalse(ids.contains("parallel-image-preload"));
    }

    @Test
    void texturePipelineDoesNotReplaceTheOriginalBufferLifetime() {
        List<String> pipeline = PatchRegistry.patches(
                PatchSelection.fromOptions(
                        "texture-pipeline", List.of(), false))
                .stream()
                .map(JarPatch::id)
                .toList();
        assertTrue(pipeline.contains("texture-row-pixel-conversion"));
        assertFalse(pipeline.contains("texture-upload-lifetime-cleanup"));
        assertFalse(pipeline.contains("texture-reusable-staging-cleanup"));
        assertFalse(pipeline.contains("texture-conversion-content-cache"));

        List<String> cache = PatchRegistry.patches(
                PatchSelection.fromOptions(
                        "texture-cache", List.of(), false))
                .stream()
                .map(JarPatch::id)
                .toList();
        assertTrue(cache.indexOf("fast-png-image-decode")
                < cache.indexOf("texture-row-pixel-conversion"));
        assertTrue(cache.indexOf("texture-row-pixel-conversion")
                < cache.indexOf("texture-conversion-content-cache"));
    }

    @Test
    void resourceStreamSafetyCanBeSelectedWithoutOtherOptimizations() {
        PatchSelection selection = PatchSelection.fromOptions(
                "resource-stream-safety", List.of(), false);
        List<String> ids = PatchRegistry.patches(selection).stream()
                .map(JarPatch::id)
                .toList();

        assertTrue(ids.contains(
                "loading-utils-resource-stream-safety"));
        assertTrue(ids.contains(
                "resource-loader-partial-stream-safety"));
        assertTrue(ids.contains("graphics-resource-stream-safety"));
        assertFalse(ids.contains("fast-png-image-decode"));
        assertFalse(ids.contains("parallel-spec-json-parse"));
        assertFalse(ids.contains("parallel-image-preload"));
    }

    @Test
    void cleanupHookFollowsEachPersistentCacheWithoutCouplingThem() {
        assertIndependentCacheSelection(
                "texture-cache",
                "texture-conversion-content-cache",
                "decoded-pcm-content-cache",
                "janino-bytecode-cache");
        assertIndependentCacheSelection(
                "pcm-cache",
                "decoded-pcm-content-cache",
                "texture-conversion-content-cache",
                "janino-bytecode-cache");
        assertIndependentCacheSelection(
                "janino-bytecode-cache",
                "janino-bytecode-cache",
                "texture-conversion-content-cache",
                "decoded-pcm-content-cache");

        List<String> withoutCaches = PatchRegistry.patches(
                PatchSelection.fromOptions(
                        "fast-text", List.of(), false))
                .stream()
                .map(JarPatch::id)
                .toList();
        assertFalse(withoutCaches.contains(
                "persistent-cache-cleanup-startup"));
    }

    private static void assertIndependentCacheSelection(
            String selectionId,
            String expectedCachePatch,
            String firstOtherCachePatch,
            String secondOtherCachePatch) {
        List<String> ids = PatchRegistry.patches(
                PatchSelection.fromOptions(
                        selectionId, List.of(), false))
                .stream()
                .map(JarPatch::id)
                .toList();

        assertTrue(ids.contains(expectedCachePatch));
        assertFalse(ids.contains(firstOtherCachePatch));
        assertFalse(ids.contains(secondOtherCachePatch));
        assertEquals(1, ids.stream()
                .filter("persistent-cache-cleanup-startup"::equals)
                .count());
    }
}
