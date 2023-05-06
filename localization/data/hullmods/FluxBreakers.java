package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class FluxBreakers extends BaseHullMod {

	public static float FLUX_RESISTANCE = 50f;
	public static float VENT_RATE_BONUS = 25f;
	
	public static float SMOD_VENT_BONUS = 10f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		boolean sMod = isSMod(stats);
		stats.getEmpDamageTakenMult().modifyMult(id, 1f - FLUX_RESISTANCE * 0.01f);
		//stats.getFluxDissipation().modifyPercent(id,DISSIPATION_BONUS);
		stats.getVentRateMult().modifyPercent(id, VENT_RATE_BONUS + (sMod ? SMOD_VENT_BONUS : 0));
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SMOD_VENT_BONUS + "%";
		return null;
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) FLUX_RESISTANCE + "%";
		if (index == 1) return "" + (int) VENT_RATE_BONUS + "%";
		return null;
	}


}
