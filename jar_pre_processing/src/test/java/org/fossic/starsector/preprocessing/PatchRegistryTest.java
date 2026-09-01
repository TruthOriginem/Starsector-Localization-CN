package org.fossic.starsector.preprocessing;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PatchRegistryTest {
    private static final List<String> RELEASE_PATCH_IDS = List.of(
            "faction-hostility-no-manual",
            "ship-info-separator",
            "combat-deployment-font",
            "combat-target-info-width",
            "combat-player-status-value-width",
            "combat-command-ship-info-value-width",
            "combat-hud-counter-width",
            "fleet-card-cr-text-width",
            "submarket-title-width",
            "campaign-date-width",
            "save-date-locale",
            "planet-list-column-width",
            "star-system-map-font",
            "intel-put-first-tag-id",
            "window-decoration-physical-resolution",
            "terrain-status-bar-visible-separator",
            "top-message-highlight-after-layout",
            "campaign-entity-tooltip-highlight-after-layout",
            "tow-cable-tooltip-width",
            "codex-special-weapon-type-filter",
            "global-ime-focus-hook",
            "textfield-ime-hook",
            "resource-stream-dynfont-hook",
            "new-game-seed-field-width",
            "bitmap-font-logical-nominal",
            "headless-console-log-detach",
            "font-glyph-bulk-array-growth",
            "font-definition-low-allocation-parser",
            "font-definition-cursor-parser",
            "resource-leaf-remove-global-monitor",
            "resource-lookup-short-monitor",
            "resource-loader-partial-stream-safety",
            "fast-png-image-decode",
            "renderer-highlight-safe-regex",
            "renderer-dynfont-exact-proxy",
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
            "persistent-cache-cleanup-startup");

    @Test
    void defaultSelectionPreservesTheExactReleasePatchOrder() {
        List<String> actual = ids(PatchRegistry.patches());

        assertEquals(RELEASE_PATCH_IDS, actual);
        assertEquals(actual.size(), new HashSet<>(actual).size());
    }

    @Test
    void everyPatchOwnsExactlyOneKnownGroup() {
        List<JarPatch> patches = PatchRegistry.patches(
                PatchSelection.fromOptions("all", List.of(), true));
        Map<PatchGroup, Integer> counts = new EnumMap<>(PatchGroup.class);
        for (JarPatch patch : patches) {
            counts.merge(patch.group(), 1, Integer::sum);
        }

        assertEquals(64, patches.size());
        assertEquals(15, counts.get(PatchGroup.LOCALIZATION));
        assertEquals(2, counts.get(PatchGroup.IME));
        assertEquals(10, counts.get(PatchGroup.DYNFONT));
        assertEquals(2, counts.get(PatchGroup.RESOURCE_LOCKS));
        assertEquals(3, counts.get(PatchGroup.RESOURCE_STREAM_SAFETY));
        assertEquals(2, counts.get(PatchGroup.PCM_BULK_READ));
        assertEquals(1, counts.get(PatchGroup.CACHE_MAINTENANCE));
        assertEquals(7, counts.get(PatchGroup.PROFILING));
        for (PatchGroup group : PatchGroup.values()) {
            assertTrue(counts.getOrDefault(group, 0) > 0, group.id());
        }
    }

    @Test
    void explicitProfilingAppendsAllProfilePatchesInCatalogOrder() {
        List<String> profileIds = ids(PatchRegistry.patches(
                PatchSelection.fromOptions("none", List.of(), true)))
                .stream()
                .filter(id -> id.startsWith("startup-profile-"))
                .toList();

        assertEquals(List.of(
                "startup-profile-combat-main",
                "startup-profile-resource-loader",
                "startup-profile-script-worker",
                "startup-profile-title-screen",
                "startup-profile-first-title-frame",
                "startup-profile-app-state-init",
                "startup-profile-codex"), profileIds);
    }

    @Test
    void baselineGroupsFilterAllOfTheirOwnPatches() {
        List<String> noLocalization = ids(PatchRegistry.patches(
                PatchSelection.fromOptions(
                        "none", List.of("localization"), false)));
        List<String> noIme = ids(PatchRegistry.patches(
                PatchSelection.fromOptions(
                        "none", List.of("ime"), false)));
        List<String> noDynfont = ids(PatchRegistry.patches(
                PatchSelection.fromOptions(
                        "none", List.of("dynfont"), false)));

        assertFalse(noLocalization.contains("tow-cable-tooltip-width"));
        assertFalse(noLocalization.contains("combat-target-info-width"));
        assertFalse(noIme.contains("global-ime-focus-hook"));
        assertFalse(noIme.contains("textfield-ime-hook"));
        assertFalse(noDynfont.contains("resource-stream-dynfont-hook"));
        assertFalse(noDynfont.contains("renderer-dynfont-exact-proxy"));
        assertTrue(noLocalization.contains("global-ime-focus-hook"));
        assertTrue(noIme.contains("tow-cable-tooltip-width"));
        assertTrue(noDynfont.contains("global-ime-focus-hook"));
    }

    @Test
    void multiPatchOptimizationGroupsFilterAtomically() {
        PatchSelection selection = PatchSelection.fromOptions(
                "resource-locks,resource-stream-safety,"
                        + "pcm-buffer,pcm-bulk-read",
                List.of(), false);
        List<String> actual = ids(PatchRegistry.patches(selection));

        assertTrue(actual.contains("resource-leaf-remove-global-monitor"));
        assertTrue(actual.contains("resource-lookup-short-monitor"));
        assertTrue(actual.contains("resource-loader-partial-stream-safety"));
        assertTrue(actual.contains("loading-utils-resource-stream-safety"));
        assertTrue(actual.contains("graphics-resource-stream-safety"));
        assertTrue(actual.contains("ogg-pcm-decoder-access"));
        assertTrue(actual.contains("ogg-pcm-bulk-preload-read"));
        assertFalse(actual.contains("fast-png-image-decode"));
    }

    @Test
    void cacheMaintenanceIsANormalExplicitGroup() {
        PatchSelection selection = PatchSelection.fromOptions(
                "fast-png,texture-pipeline,texture-cache,cache-maintenance",
                List.of(), false);
        List<String> actual = ids(PatchRegistry.patches(selection));

        assertTrue(actual.contains("texture-conversion-content-cache"));
        assertEquals(1, actual.stream()
                .filter("persistent-cache-cleanup-startup"::equals)
                .count());

        List<String> independent = ids(PatchRegistry.patches(
                PatchSelection.fromOptions("fast-text", List.of(), false)));
        assertFalse(independent.contains("persistent-cache-cleanup-startup"));
    }

    private static List<String> ids(List<JarPatch> patches) {
        return patches.stream().map(JarPatch::id).toList();
    }
}
