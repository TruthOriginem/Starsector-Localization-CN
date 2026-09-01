package org.fossic.starsector.preprocessing;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PatchSelectionTest {
    @Test
    void defaultsEnableEveryBaselineAndOptimizationButNotProfiling() {
        PatchSelection selection = PatchSelection.defaults();

        for (PatchGroup group : PatchGroup.values()) {
            assertEquals(group.kind() != PatchGroupKind.PROFILING,
                    selection.enabled(group), group.id());
        }
        assertEquals("all", selection.requestedOptimizationSpec());
        assertFalse(selection.requestedProfiling());
        assertEquals(List.of(), selection.requestedDisabledGroupIds());
    }

    @Test
    void noneEnablesOnlyTheThreeBaselineGroups() {
        PatchSelection selection = PatchSelection.fromOptions(
                "none", List.of(), false);

        assertEquals(List.of("localization", "ime", "dynfont"),
                selection.enabledGroupIds());
        assertEquals(List.of(), selection.enabledOptimizationIds());
    }

    @Test
    void profilingIsExplicitAndIndependentFromOptimizations() {
        PatchSelection selection = PatchSelection.fromOptions(
                "none", List.of(), true);

        assertEquals(List.of(
                        "localization", "ime", "dynfont", "profiling"),
                selection.enabledGroupIds());
    }

    @Test
    void independentOptimizationDoesNotEnableUnrequestedGroups() {
        PatchSelection selection = PatchSelection.fromOptions(
                "fast-text", List.of(), false);

        assertTrue(selection.enabled(PatchGroup.FAST_TEXT));
        assertFalse(selection.enabled(PatchGroup.FAST_PNG));
        assertFalse(selection.enabled(PatchGroup.CACHE_MAINTENANCE));
    }

    @Test
    void completeDependencyChainsAreAcceptedWithoutExpansion() {
        assertEnabledOptimizations(
                "font-line-parser,font-token-cursor",
                "font-line-parser", "font-token-cursor");
        assertEnabledOptimizations(
                "fast-png,texture-pipeline,texture-cache,cache-maintenance",
                "fast-png", "texture-cache", "texture-pipeline",
                "cache-maintenance");
        assertEnabledOptimizations(
                "pcm-buffer,pcm-bulk-read,pcm-cache,cache-maintenance",
                "pcm-buffer", "pcm-bulk-read", "pcm-cache",
                "cache-maintenance");
        assertEnabledOptimizations(
                "janino-cu-dedup,janino-source-index,"
                        + "janino-bytecode-cache,cache-maintenance",
                "janino-cu-dedup", "janino-source-index",
                "janino-bytecode-cache", "cache-maintenance");
    }

    @Test
    void missingDependenciesFailInsteadOfBeingEnabledImplicitly() {
        assertDependencyFailure("font-token-cursor",
                "font-token-cursor -> font-line-parser：未启用");
        assertDependencyFailure("parallel-spec-parse",
                "parallel-spec-parse -> resource-locks：未启用");
        assertDependencyFailure("texture-cache",
                "texture-cache -> fast-png：未启用",
                "texture-cache -> texture-pipeline：未启用",
                "texture-cache -> cache-maintenance：未启用");
        assertDependencyFailure("pcm-bulk-read",
                "pcm-bulk-read -> pcm-buffer：未启用");
        assertDependencyFailure("pcm-cache,pcm-bulk-read",
                "pcm-bulk-read -> pcm-buffer：未启用",
                "pcm-cache -> pcm-bulk-read -> pcm-buffer：未启用",
                "pcm-cache -> cache-maintenance：未启用");
        assertDependencyFailure("preload-path-dedup",
                "preload-path-dedup -> preload-coordination：未启用");
        assertDependencyFailure("parallel-image-preload",
                "parallel-image-preload -> preload-coordination：未启用");
        assertDependencyFailure("janino-source-index",
                "janino-source-index -> janino-cu-dedup：未启用");
        assertDependencyFailure(
                "janino-bytecode-cache,janino-source-index",
                "janino-source-index -> janino-cu-dedup：未启用",
                "janino-bytecode-cache -> janino-source-index"
                        + " -> janino-cu-dedup：未启用",
                "janino-bytecode-cache -> cache-maintenance：未启用");
    }

    @Test
    void disablingDependenciesFailsInsteadOfDisablingDependents() {
        assertDisabledDependencyFailure("font-line-parser",
                "font-token-cursor -> font-line-parser：被显式禁用");
        assertDisabledDependencyFailure("resource-locks",
                "parallel-spec-parse -> resource-locks：被显式禁用");
        assertDisabledDependencyFailure("preload-coordination",
                "preload-path-dedup -> preload-coordination：被显式禁用",
                "parallel-image-preload -> preload-coordination：被显式禁用");
        assertDisabledDependencyFailure("janino-source-index",
                "janino-bytecode-cache -> janino-source-index：被显式禁用");
    }

    @Test
    void disablingCacheMaintenanceReportsEveryStillEnabledDependent() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions(
                        "all", List.of("cache-maintenance"), false));

        assertMessageContains(error,
                "texture-cache -> cache-maintenance：被显式禁用",
                "pcm-cache -> cache-maintenance：被显式禁用",
                "janino-bytecode-cache -> cache-maintenance：被显式禁用");
    }

    @Test
    void explicitlyDisablingTopLevelGroupsNeverRecurses() {
        PatchSelection selection = PatchSelection.fromOptions(
                "all",
                List.of("texture-cache", "pcm-cache",
                        "janino-bytecode-cache", "cache-maintenance"),
                false);

        assertFalse(selection.enabled(PatchGroup.TEXTURE_CACHE));
        assertFalse(selection.enabled(PatchGroup.PCM_CACHE));
        assertFalse(selection.enabled(PatchGroup.JANINO_BYTECODE_CACHE));
        assertFalse(selection.enabled(PatchGroup.CACHE_MAINTENANCE));
        assertTrue(selection.enabled(PatchGroup.FAST_PNG));
        assertTrue(selection.enabled(PatchGroup.PCM_BULK_READ));
        assertTrue(selection.enabled(PatchGroup.JANINO_SOURCE_INDEX));
    }

    @Test
    void baselineGroupsCanBeExplicitlyDisabledWithoutAffectingOthers() {
        PatchSelection selection = PatchSelection.fromOptions(
                "none", List.of("localization", "ime", "dynfont"), false);

        assertEquals(List.of(), selection.enabledGroupIds());
        assertEquals(List.of("localization", "ime", "dynfont"),
                selection.requestedDisabledGroupIds());
    }

    @Test
    void rejectsDisablingAGroupThatWasNotRequested() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions(
                        "fast-text", List.of("texture-cache"), false));

        assertTrue(error.getMessage().contains(
                "texture-cache' 未处于请求启用集合中"));
    }

    @Test
    void aggregatesIndependentSyntaxAndPropertyErrors() {
        String oldOptimizations = System.getProperty(
                PatchSelection.OPTIMIZATIONS_PROPERTY);
        String oldDisabled = System.getProperty(
                PatchSelection.DISABLED_GROUPS_PROPERTY);
        String oldProfiling = System.getProperty(
                PatchSelection.PROFILING_PROPERTY);
        try {
            System.setProperty(PatchSelection.OPTIMIZATIONS_PROPERTY,
                    "all,missing-optimization");
            System.setProperty(PatchSelection.DISABLED_GROUPS_PROPERTY,
                    "missing-disabled,ime,ime");
            System.setProperty(PatchSelection.PROFILING_PROPERTY, "yes");

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    PatchSelection::fromSystemProperties);

            assertMessageContains(error,
                    "必须为 true 或 false",
                    "all/none 不能与具体优化组混用",
                    "missing-optimization",
                    "missing-disabled",
                    "重复禁用 patch 组: ime");
        } finally {
            restoreProperty(PatchSelection.OPTIMIZATIONS_PROPERTY,
                    oldOptimizations);
            restoreProperty(PatchSelection.DISABLED_GROUPS_PROPERTY,
                    oldDisabled);
            restoreProperty(PatchSelection.PROFILING_PROPERTY, oldProfiling);
        }
    }

    @Test
    void reportsRequestedAndEnabledConfigurationInStableEnumOrder() {
        PatchSelection selection = PatchSelection.fromOptions(
                "pcm-buffer,pcm-bulk-read,fast-text",
                List.of("ime"), false);

        assertEquals("pcm-buffer,pcm-bulk-read,fast-text",
                selection.requestedOptimizationSpec());
        assertEquals(List.of("ime"), selection.requestedDisabledGroupIds());
        assertEquals(List.of("fast-text", "pcm-buffer", "pcm-bulk-read"),
                selection.enabledOptimizationIds());
        assertEquals(List.of(
                        "localization", "dynfont", "fast-text",
                        "pcm-buffer", "pcm-bulk-read"),
                selection.enabledGroupIds());
    }

    @Test
    void groupIdsAndDependencyGraphAreValid() {
        assertDoesNotThrow(PatchGroup::validateDefinitions);
        assertEquals(PatchGroup.values().length,
                new HashSet<>(PatchGroup.allIds()).size());
    }

    private static void assertEnabledOptimizations(
            String spec, String... expected) {
        PatchSelection selection = PatchSelection.fromOptions(
                spec, List.of(), false);
        assertEquals(List.of(expected), selection.enabledOptimizationIds());
    }

    private static void assertDependencyFailure(
            String spec, String... expectedFragments) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions(spec, List.of(), false));
        assertMessageContains(error, expectedFragments);
    }

    private static void assertDisabledDependencyFailure(
            String disabled, String... expectedFragments) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions(
                        "all", List.of(disabled), false));
        assertMessageContains(error, expectedFragments);
    }

    private static void assertMessageContains(
            Exception error, String... expectedFragments) {
        for (String fragment : expectedFragments) {
            assertTrue(error.getMessage().contains(fragment),
                    () -> "missing fragment '" + fragment
                            + "' in:\n" + error.getMessage());
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
