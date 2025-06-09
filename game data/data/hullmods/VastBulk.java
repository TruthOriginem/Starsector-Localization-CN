package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class VastBulk extends BaseHullMod {

	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getHullDamageTakenMult().modifyMult(id, 0f);
		stats.getEngineDamageTakenMult().modifyMult(id, 0f);
		
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		return null;
	}
}
