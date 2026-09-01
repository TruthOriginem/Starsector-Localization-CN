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
    void defaultsEnableOnlyTheBaselineGroupOnMaster() {
        PatchSelection selection = PatchSelection.defaults();

        assertEquals("none", selection.requestedOptimizationSpec());
        assertFalse(selection.requestedProfiling());
        assertEquals(List.of(), selection.requestedDisabledGroupIds());
        assertEquals(List.of(), selection.enabledOptimizationIds());
        assertEquals(List.of("localization"), selection.enabledGroupIds());
        assertTrue(selection.enabled(PatchGroup.LOCALIZATION));
    }

    @Test
    void explicitNoneStillKeepsTheBaselineGroup() {
        PatchSelection selection = PatchSelection.fromOptions(
                "none", List.of(), false);

        assertEquals(List.of("localization"), selection.enabledGroupIds());
    }

    @Test
    void explicitlyDisablingTheRequestedBaselineGroupLeavesNoPatchesEnabled() {
        PatchSelection selection = PatchSelection.fromOptions(
                "none", List.of("localization"), false);

        assertFalse(selection.enabled(PatchGroup.LOCALIZATION));
        assertEquals(List.of("localization"),
                selection.requestedDisabledGroupIds());
        assertEquals(List.of(), selection.enabledGroupIds());
    }

    @Test
    void rejectsOptimizationAllWhenThisProductDefinesNoOptimizationGroups() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions("all", List.of(), false));

        assertTrue(error.getMessage().contains("未定义可启用的优化组"));
    }

    @Test
    void rejectsUnknownOptimizationGroup() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions(
                        "missing-optimization", List.of(), false));

        assertTrue(error.getMessage().contains("未知 patch 组"));
    }

    @Test
    void rejectsProfilingWhenThisProductDefinesNoProfilingGroup() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions("none", List.of(), true));

        assertTrue(error.getMessage().contains("未定义 profiling patch 组"));
    }

    @Test
    void rejectsUnknownAndDuplicateDisabledGroups() {
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions(
                        "none", List.of("missing-group"), false));
        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> PatchSelection.fromOptions(
                        "none", List.of("localization", "localization"), false));

        assertTrue(unknown.getMessage().contains("未知 patch 组"));
        assertTrue(duplicate.getMessage().contains("重复禁用 patch 组"));
    }

    @Test
    void systemPropertiesRejectInvalidBoolean() {
        String oldValue = System.getProperty(PatchSelection.PROFILING_PROPERTY);
        try {
            System.setProperty(PatchSelection.PROFILING_PROPERTY, "yes");
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    PatchSelection::fromSystemProperties);
            assertTrue(error.getMessage().contains("必须为 true 或 false"));
        } finally {
            restoreProperty(PatchSelection.PROFILING_PROPERTY, oldValue);
        }
    }

    @Test
    void groupDefinitionsHaveUniqueIdsAndAValidDependencyGraph() {
        assertDoesNotThrow(PatchGroup::validateDefinitions);
        assertEquals(PatchGroup.values().length,
                new HashSet<>(PatchGroup.allIds()).size());
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
