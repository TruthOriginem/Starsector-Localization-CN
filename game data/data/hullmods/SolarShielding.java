package data.hullmods;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;

public class SolarShielding extends BaseLogisticsHullMod {

	public static float CORONA_EFFECT_MULT = 0.25f;
	public static float ENERGY_DAMAGE_MULT = 0.9f;
	
	public static float SMOD_CORONA_EFFECT_MULT = 0f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getEnergyDamageTakenMult().modifyMult(id, ENERGY_DAMAGE_MULT);
		stats.getEnergyShieldDamageTakenMult().modifyMult(id, ENERGY_DAMAGE_MULT);
		
		boolean sMod = isSMod(stats);
		float mult = CORONA_EFFECT_MULT;
		if (sMod) mult = SMOD_CORONA_EFFECT_MULT;
		stats.getDynamic().getStat(Stats.CORONA_EFFECT_MULT).modifyMult(id, mult);
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round((1f - SMOD_CORONA_EFFECT_MULT) * 100f) + "%";
		return null;
	}
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round((1f - CORONA_EFFECT_MULT) * 100f) + "%";
		if (index == 1) return "" + (int) Math.round((1f - ENERGY_DAMAGE_MULT) * 100f) + "%";
		return null;
	}


}
