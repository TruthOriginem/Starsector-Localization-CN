package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.hullmods.DefectiveManufactory;

public class ConvertedCargoBay extends BaseHullMod {

	//public static final int CREW_REQ = 40;
	public static int REFIT_TIME_PLUS = 50;
	
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		//stats.getFighterRefitTimeMult().modifyPercent(id, REFIT_TIME_PLUS);
		stats.getNumFighterBays().modifyFlat(id, 2f);
	}
	
	public void applyEffectsToFighterSpawnedByShip(ShipAPI fighter, ShipAPI ship, String id) {
		new DefectiveManufactory().applyEffectsToFighterSpawnedByShip(fighter, ship, id);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
		//if (index == 2) return "" + CREW_REQ;
		return new DefectiveManufactory().getDescriptionParam(index, hullSize, ship);
	}
//	public String getDescriptionParam(int index, HullSize hullSize) {
//		if (index == 0) return "" + REFIT_TIME_PLUS + "%";
//		return null;
//	}
	

	public boolean isApplicableToShip(ShipAPI ship) {
		return true;
	}
	
	public String getUnapplicableReason(ShipAPI ship) {
		return null;
	}
}



