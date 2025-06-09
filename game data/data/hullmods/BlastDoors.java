package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class BlastDoors extends BaseHullMod {

	public static float HULL_BONUS = 20f;
	public static float CASUALTY_REDUCTION = 60f;
	public static float SMOD_BONUS = 25f;
	//public static final float HULL_DAMAGE_CR_MULT = 0.25f;
	

	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
//		stats.getCargoMod().modifyPercent(id, -50f);
//		stats.getCargoMod().modifyPercent(id + "sfsdfd", +25f);
//		stats.getMaxCrewMod().modifyPercent(id, 100);
		boolean sMod = isSMod(stats);
		
		stats.getHullBonus().modifyPercent(id, HULL_BONUS);
		stats.getCrewLossMult().modifyMult(id, 1f - (CASUALTY_REDUCTION + (sMod ? SMOD_BONUS : 0)) * 0.01f);
		//stats.getDynamic().getStat(Stats.HULL_DAMAGE_CR_LOSS).modifyMult(id, HULL_DAMAGE_CR_MULT);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) HULL_BONUS + "%";
		if (index == 1) return "" + (int) CASUALTY_REDUCTION + "%";
		//if (index == 2) return "" + (int) ((1f - HULL_DAMAGE_CR_MULT) * 100f);
		return null;
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round(CASUALTY_REDUCTION + SMOD_BONUS) + "%";
		return null;
	}
}
