package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

@SuppressWarnings("unchecked")
public class CivGrade extends BaseHullMod {

	private static final float PROFILE_INCREASE = 100f;
	private static final float STRENGTH_DECREASE = 50f;
	
	private static final int PD_OP = 5;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getSensorProfile().modifyPercent(id, PROFILE_INCREASE);
		stats.getSensorStrength().modifyMult(id, 1f - STRENGTH_DECREASE * 0.01f);
		
//		stats.getDynamic().getMod(Stats.SMALL_PD_MOD).modifyFlat(id, -PD_OP);
//		stats.getDynamic().getMod(Stats.MEDIUM_PD_MOD).modifyFlat(id, -PD_OP);
//		stats.getDynamic().getMod(Stats.LARGE_PD_MOD).modifyFlat(id, -PD_OP);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) PROFILE_INCREASE + "%";
		if (index == 1) return "" + (int) STRENGTH_DECREASE + "%";
//		if (index == 2) return "" + (int) PD_OP;
		return null;
	}

	@Override
	public boolean affectsOPCosts() {
		return false;
	}


}
