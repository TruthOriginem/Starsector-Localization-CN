package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class NoWeaponFlux extends BaseHullMod {

	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getBallisticWeaponFluxCostMod().modifyMult(id, 0);
		stats.getEnergyWeaponFluxCostMod().modifyMult(id, 0);
		stats.getMissileWeaponFluxCostMod().modifyMult(id, 0);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		return null;
	}


}
