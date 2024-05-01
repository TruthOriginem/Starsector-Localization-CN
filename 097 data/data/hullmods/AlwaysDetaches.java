package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

public class AlwaysDetaches extends BaseHullMod {

	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getDynamic().getStat(Stats.MODULE_DETACH_CHANCE_MULT).modifyFlat(id, 100f);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		return null;
	}


}
