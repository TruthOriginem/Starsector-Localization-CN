package org.fossic.starsector.preprocessing;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PatchRegistryTest {
    private static final List<String> EXPECTED_PATCH_IDS = List.of(
            "faction-hostility-no-manual",
            "ship-info-separator",
            "combat-deployment-font",
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
            "codex-special-weapon-type-filter");

    @Test
    void defaultCatalogRetainsItsExactPatchOrderAndUniqueIds() {
        List<JarPatch> patches = PatchRegistry.patches();
        List<String> ids = patches.stream().map(JarPatch::id).toList();

        assertEquals(EXPECTED_PATCH_IDS, ids);
        assertEquals(ids.size(), new HashSet<>(ids).size());
    }

    @Test
    void everyMasterPatchRegistersItselfAsLocalization() {
        assertTrue(PatchRegistry.patches().stream()
                .allMatch(patch -> patch.group() == PatchGroup.LOCALIZATION));
    }

    @Test
    void registryFiltersTheCatalogByPatchDeclaredGroup() {
        PatchSelection selection = PatchSelection.fromOptions(
                "none", List.of("localization"), false);

        assertEquals(List.of(), PatchRegistry.patches(selection));
    }
}
