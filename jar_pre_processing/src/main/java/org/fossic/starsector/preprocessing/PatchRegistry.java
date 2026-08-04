package org.fossic.starsector.preprocessing;

import org.fossic.starsector.preprocessing.patches.CampaignDateWidthPatch;
import org.fossic.starsector.preprocessing.patches.CombatDeploymentFontPatch;
import org.fossic.starsector.preprocessing.patches.CombatTargetInfoWidthPatch;
import org.fossic.starsector.preprocessing.patches.FactionHostilityNoManualPatch;
import org.fossic.starsector.preprocessing.patches.IntelPutFirstTagIdPatch;
import org.fossic.starsector.preprocessing.patches.PlanetListColumnWidthPatch;
import org.fossic.starsector.preprocessing.patches.RendererDynFontPatch;
import org.fossic.starsector.preprocessing.patches.ResourceStreamDynFontPatch;
import org.fossic.starsector.preprocessing.patches.SaveDateLocalePatch;
import org.fossic.starsector.preprocessing.patches.ShipInfoSeparatorPatch;
import org.fossic.starsector.preprocessing.patches.StarSystemMapFontPatch;
import org.fossic.starsector.preprocessing.patches.TextFieldImeHookPatch;
import org.fossic.starsector.preprocessing.patches.WindowDecorationPhysicalResolutionPatch;

import java.util.List;

public final class PatchRegistry {
    private PatchRegistry() {
    }

    public static List<JarPatch> patches() {
        return List.of(
                new FactionHostilityNoManualPatch(),
                new ShipInfoSeparatorPatch(),
                new CombatDeploymentFontPatch(),
                new CombatTargetInfoWidthPatch(),
                new CampaignDateWidthPatch(),
                new SaveDateLocalePatch(),
                new PlanetListColumnWidthPatch(),
                new StarSystemMapFontPatch(),
                new IntelPutFirstTagIdPatch(),
                new WindowDecorationPhysicalResolutionPatch(),
                new TextFieldImeHookPatch(),
                new ResourceStreamDynFontPatch(),
                new RendererDynFontPatch()
        );
    }
}
