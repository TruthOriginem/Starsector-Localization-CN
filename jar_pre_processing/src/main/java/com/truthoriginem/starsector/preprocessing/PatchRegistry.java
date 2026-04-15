package com.truthoriginem.starsector.preprocessing;

import com.truthoriginem.starsector.preprocessing.patches.CampaignDateWidthPatch;
import com.truthoriginem.starsector.preprocessing.patches.CombatDeploymentFontPatch;
import com.truthoriginem.starsector.preprocessing.patches.FactionHostilityNoManualPatch;
import com.truthoriginem.starsector.preprocessing.patches.PlanetListColumnWidthPatch;
import com.truthoriginem.starsector.preprocessing.patches.SaveDateLocalePatch;
import com.truthoriginem.starsector.preprocessing.patches.ShipInfoSeparatorPatch;

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
                new PlanetListColumnWidthPatch()
        );
    }
}
