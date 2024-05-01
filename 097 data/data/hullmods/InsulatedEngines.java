package data.hullmods;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;

public class InsulatedEngines extends BaseLogisticsHullMod {

	public static float PROFILE_MULT = 0.5f;
	public static float HEALTH_BONUS = 100f;
	public static float HULL_BONUS = 10f;
	
	public static float SMOD_PROFILE_MULT = 0.1f;
	public static float SMOD_ENGINE_HEALTH = 100f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		boolean sMod = isSMod(stats);
		
		stats.getEngineHealthBonus().modifyPercent(id, HEALTH_BONUS + (sMod ? SMOD_ENGINE_HEALTH : 0));
		stats.getHullBonus().modifyPercent(id, HULL_BONUS);
		
		if (sMod) {
			stats.getSensorProfile().modifyMult(id, SMOD_PROFILE_MULT);
		} else {
			stats.getSensorProfile().modifyMult(id, PROFILE_MULT);
		}
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SMOD_ENGINE_HEALTH + "%";
		if (index == 1) return "" + (int) Math.round((1f - SMOD_PROFILE_MULT) * 100f) + "%";
		return null;
	}
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) HEALTH_BONUS + "%";
		if (index == 1) return "" + (int) HULL_BONUS + "%";
		if (index == 2) return "" + (int) Math.round((1f - PROFILE_MULT) * 100f) + "%";
		return null;
	}


}
