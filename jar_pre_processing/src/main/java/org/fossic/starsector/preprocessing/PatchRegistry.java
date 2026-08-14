package org.fossic.starsector.preprocessing;

import org.fossic.starsector.preprocessing.patches.CampaignDateWidthPatch;
import org.fossic.starsector.preprocessing.patches.CampaignEntityTooltipHighlightLayoutPatch;
import org.fossic.starsector.preprocessing.patches.BitmapFontLogicalNominalPatch;
import org.fossic.starsector.preprocessing.patches.CombatDeploymentFontPatch;
import org.fossic.starsector.preprocessing.patches.CodexWeaponTypeFilterPatch;
import org.fossic.starsector.preprocessing.patches.CombatTargetInfoWidthPatch;
import org.fossic.starsector.preprocessing.patches.FactionHostilityNoManualPatch;
import org.fossic.starsector.preprocessing.patches.IntelPutFirstTagIdPatch;
import org.fossic.starsector.preprocessing.patches.NewGameSeedFieldWidthPatch;
import org.fossic.starsector.preprocessing.patches.PlanetListColumnWidthPatch;
import org.fossic.starsector.preprocessing.patches.RendererHighlightColorNullPatch;
import org.fossic.starsector.preprocessing.patches.RendererDynFontPatch;
import org.fossic.starsector.preprocessing.patches.RendererHighlightRegexPatch;
import org.fossic.starsector.preprocessing.patches.ResourceStreamDynFontPatch;
import org.fossic.starsector.preprocessing.patches.SaveDateLocalePatch;
import org.fossic.starsector.preprocessing.patches.ShipInfoSeparatorPatch;
import org.fossic.starsector.preprocessing.patches.StarSystemMapFontPatch;
import org.fossic.starsector.preprocessing.patches.TerrainStatusBarSeparatorPatch;
import org.fossic.starsector.preprocessing.patches.TextFieldImeHookPatch;
import org.fossic.starsector.preprocessing.patches.TopMessageHighlightLayoutPatch;
import org.fossic.starsector.preprocessing.patches.TowCableTooltipWidthPatch;
import org.fossic.starsector.preprocessing.patches.WindowDecorationPhysicalResolutionPatch;

import java.util.List;
import java.util.function.Supplier;

public final class PatchRegistry {
    private PatchRegistry() {
    }

    public static List<JarPatch> patches() {
        return patches(PatchSelection.defaults());
    }

    public static List<JarPatch> patches(PatchSelection selection) {
        return catalog().stream()
                .map(Supplier::get)
                .filter(patch -> selection.enabled(patch.group()))
                .toList();
    }

    private static List<Supplier<JarPatch>> catalog() {
        return List.of(
                FactionHostilityNoManualPatch::new,
                ShipInfoSeparatorPatch::new,
                CombatDeploymentFontPatch::new,
                CombatTargetInfoWidthPatch::new,
                NewGameSeedFieldWidthPatch::new,
                CampaignDateWidthPatch::new,
                SaveDateLocalePatch::new,
                PlanetListColumnWidthPatch::new,
                StarSystemMapFontPatch::new,
                IntelPutFirstTagIdPatch::new,
                WindowDecorationPhysicalResolutionPatch::new,
                TerrainStatusBarSeparatorPatch::new,
                TopMessageHighlightLayoutPatch::new,
                CampaignEntityTooltipHighlightLayoutPatch::new,
                RendererHighlightColorNullPatch::new,
                TowCableTooltipWidthPatch::new,
                CodexWeaponTypeFilterPatch::new,
                TextFieldImeHookPatch::new,
                ResourceStreamDynFontPatch::new,
                BitmapFontLogicalNominalPatch::new,
                RendererHighlightRegexPatch::new,
                RendererDynFontPatch::new
        );
    }
}
