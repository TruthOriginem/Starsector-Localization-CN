package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/**
 * Unused, replaced by MilitarizedSubsystems
 * @author Alex Mosolov
 *
 * Copyright 2018 Fractal Softworks, LLC
 */
public class Militarized extends BaseHullMod {

	/* Militarized Auxiliary 
	 * Basically take a civilian hull, bring it up to military standards. (More or less.)
	 * - military sensor profile/detection (handled in skin by removing civ hull)
	 * - more OP (handled in skin)
	 * - slightly better flux handling
	 * - slightly better armour
	 * - slightly better speed/maneuver
	 * - 
	 */
	private static final float CAPACITY_MULT = 1.1f;
	private static final float DISSIPATION_MULT = 1.1f;
	private static final float HANDLING_MULT = 1.1f;
	
	private static final int BURN_LEVEL_BONUS = 1;
	
	
	private static Map mag = new HashMap();
	static {
		mag.put(HullSize.FRIGATE, 25f);
		mag.put(HullSize.DESTROYER, 30f);
		mag.put(HullSize.CRUISER, 35f);
		mag.put(HullSize.CAPITAL_SHIP, 45f);
	}
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		
		// Slightly better armour
		stats.getArmorBonus().modifyPercent(id, (Float) mag.get(hullSize));
		
		// 10% better flux stats
		stats.getFluxCapacity().modifyMult(id, CAPACITY_MULT);
		stats.getFluxDissipation().modifyMult(id, DISSIPATION_MULT);
		
		// 10% better handling all around!
		stats.getMaxSpeed().modifyMult(id, HANDLING_MULT);
		stats.getAcceleration().modifyMult(id, HANDLING_MULT);
		stats.getDeceleration().modifyMult(id, HANDLING_MULT);
		stats.getMaxTurnRate().modifyMult(id, HANDLING_MULT);
		stats. getTurnAcceleration().modifyMult(id, HANDLING_MULT);
		
		stats.getMaxBurnLevel().modifyFlat(id, BURN_LEVEL_BONUS);
		
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + ((Float) mag.get(HullSize.FRIGATE)).intValue() + "%";
		if (index == 1) return "" + ((Float) mag.get(HullSize.DESTROYER)).intValue() + "%";
		if (index == 2) return "" + ((Float) mag.get(HullSize.CRUISER)).intValue() + "%";
		if (index == 3) return "" + ((Float) mag.get(HullSize.CAPITAL_SHIP)).intValue() + "%";
		if (index == 4) return "" + (int) ((HANDLING_MULT - 1f) * 100f) + "%";
		if (index == 5) return "" + (int) ((CAPACITY_MULT - 1f) * 100f) + "%";
		if (index == 6) return "" + (int) ((DISSIPATION_MULT - 1f) * 100f) + "%";
		if (index == 7) return "" + BURN_LEVEL_BONUS;
		return null;
		//if (index == 0) return "" + ((Float) mag.get(hullSize)).intValue();
		//return null;
	}


}
