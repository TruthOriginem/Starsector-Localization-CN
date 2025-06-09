package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.hullmods.CompromisedStructure;

public class DegradedDriveField extends BaseHullMod {
	public static final float PROFILE_PERCENT = 50f;
	
	private static Map mag = new HashMap();
	static {
		mag.put(HullSize.FRIGATE, -1f);
		mag.put(HullSize.DESTROYER, -1f);
		mag.put(HullSize.CRUISER, -1f);
		mag.put(HullSize.CAPITAL_SHIP, -1f);
	}
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		float effect = stats.getDynamic().getValue(Stats.DMOD_EFFECT_MULT);
		
		stats.getMaxBurnLevel().modifyFlat(id, (Float) mag.get(hullSize));
		stats.getSensorProfile().modifyPercent(id, PROFILE_PERCENT * effect);
		
		CompromisedStructure.modifyCost(hullSize, stats, id);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
		float effect = 1f;
		if (ship != null) effect = ship.getMutableStats().getDynamic().getValue(Stats.DMOD_EFFECT_MULT);
		
		if (index == 0) return "" + Math.abs(((Float) mag.get(hullSize)).intValue());
		if (index == 1) return "" + (int) Math.round(PROFILE_PERCENT * effect) + "%";
		if (index >= 2) return CompromisedStructure.getCostDescParam(index, 2);
		return null;
	}

	
}







