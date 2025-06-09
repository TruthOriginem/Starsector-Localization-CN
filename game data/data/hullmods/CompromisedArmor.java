package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.hullmods.CompromisedStructure;

public class CompromisedArmor extends BaseHullMod {
	public static final float ARMOR_PENALTY_MULT = 0.8f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		float effect = stats.getDynamic().getValue(Stats.DMOD_EFFECT_MULT);
		float mult = ARMOR_PENALTY_MULT + (1f - ARMOR_PENALTY_MULT) * (1f - effect);
		
		stats.getArmorBonus().modifyMult(id, mult);
		CompromisedStructure.modifyCost(hullSize, stats, id);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
		float effect = 1f;
		if (ship != null) effect = ship.getMutableStats().getDynamic().getValue(Stats.DMOD_EFFECT_MULT);
		float mult = ARMOR_PENALTY_MULT + (1f - ARMOR_PENALTY_MULT) * (1f - effect);
		
		if (index == 0) return "" + (int) Math.round((1f - mult) * 100f) + "%";
		if (index >= 1) return CompromisedStructure.getCostDescParam(index, 1);
		return null;
	}
}
