package org.fossic.starsector.preprocessing;

import org.fossic.starsector.preprocessing.patches.CampaignDateWidthPatch;
import org.fossic.starsector.preprocessing.patches.CombatDeploymentFontPatch;
import org.fossic.starsector.preprocessing.patches.FactionHostilityNoManualPatch;
import org.fossic.starsector.preprocessing.patches.PlanetListColumnWidthPatch;
import org.fossic.starsector.preprocessing.patches.SaveDateLocalePatch;
import org.fossic.starsector.preprocessing.patches.ShipInfoSeparatorPatch;
import org.fossic.starsector.preprocessing.patches.StarSystemMapFontPatch;

import java.util.List;

public final class PatchRegistry {
    private PatchRegistry() {
    }

    public static List<JarPatch> patches() {
        return List.of(
                new FactionHostilityNoManualPatch(),
                new ShipInfoSeparatorPatch(),
                new CombatDeploymentFontPatch(),
                new CampaignDateWidthPatch(),
                new SaveDateLocalePatch(),
                new PlanetListColumnWidthPatch(),
                new StarSystemMapFontPatch()
        );
    }
}
