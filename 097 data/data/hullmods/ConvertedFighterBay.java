package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class ConvertedFighterBay extends BaseHullMod {

	public static int CREW_REQ_PER_BAY = 20;
	public static int MAX_CREW = 80;
	public static int CARGO_PER_BAY = 50;
	
	public static float SMOD_MAINT_PER_BAY = 15f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		int bays = (int) Math.round(stats.getNumFighterBays().getBaseValue());
		stats.getNumFighterBays().modifyFlat(id, -bays);

		int crewReduction = CREW_REQ_PER_BAY * bays;
		if (crewReduction > MAX_CREW) crewReduction = MAX_CREW;
		int cargo = CARGO_PER_BAY * bays;
		
//		ShipVariantAPI v = stats.getVariant();
//		if (v != null) {
//			
//		}
		
		stats.getMinCrewMod().modifyPercent(id, -crewReduction);
		stats.getCargoMod().modifyFlat(id, cargo);
		
		boolean sMod = isSMod(stats);
		if (sMod && bays > 0) {
			float bonus = bays * (SMOD_MAINT_PER_BAY * 0.01f);
			if (bonus > 1f) bonus = 1f;
			stats.getSuppliesPerMonth().modifyMult(id, 1f - bonus);
		}
		
	}
	
	public boolean isApplicableToShip(ShipAPI ship) {
		int builtIn = ship.getHullSpec().getBuiltInWings().size();
		int bays = (int) Math.round(ship.getMutableStats().getNumFighterBays().getBaseValue());
		if (builtIn <= 0 || bays > builtIn) return false;
		return true;
	}
	
	public String getUnapplicableReason(ShipAPI ship) {
		return "Requires built-in fighter wings only";
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
		if (index == 0) return "" + (int) SMOD_MAINT_PER_BAY + "%";
		return null;
	}
		
	public String getDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
		if (index == 0) return "" + CARGO_PER_BAY;
		if (index == 1) return "" + CREW_REQ_PER_BAY + "%";
		if (index == 2) return "" + MAX_CREW + "%";
		return null;
	}
	
}



