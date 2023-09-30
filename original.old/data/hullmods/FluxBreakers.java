package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class FluxBreakers extends BaseHullMod {

	public static final float FLUX_RESISTANCE = 50f;
	//public static final float DISSIPATION_BONUS = 10f;
	public static final float VENT_RATE_BONUS = 25f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getEmpDamageTakenMult().modifyMult(id, 1f - FLUX_RESISTANCE * 0.01f);
		//stats.getFluxDissipation().modifyPercent(id,DISSIPATION_BONUS);
		stats.getVentRateMult().modifyPercent(id, VENT_RATE_BONUS);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) FLUX_RESISTANCE + "%";
		if (index == 1) return "" + (int) VENT_RATE_BONUS + "%";
		return null;
	}


}
