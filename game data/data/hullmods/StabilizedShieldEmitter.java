package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class StabilizedShieldEmitter extends BaseHullMod {

	public static float SHIELD_UPKEEP_BONUS = 50f;
	public static float SMOD_SOFT_FLUX_CONVERSION = 0.1f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getShieldUpkeepMult().modifyMult(id, 1f - SHIELD_UPKEEP_BONUS * 0.01f);
		boolean sMod = isSMod(stats);
		if (sMod) {
			stats.getShieldSoftFluxConversion().modifyFlat(id, SMOD_SOFT_FLUX_CONVERSION);
		}
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SHIELD_UPKEEP_BONUS + "%";
		return null;
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round(SMOD_SOFT_FLUX_CONVERSION * 100f) + "%";
		return null;
	}

	public boolean isApplicableToShip(ShipAPI ship) {
		return ship != null && ship.getShield() != null;
	}
	
	public String getUnapplicableReason(ShipAPI ship) {
		return "Ship has no shields";
	}
	
//	public void addSModEffectSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship,
//									 float width, boolean isForModSpec, boolean isForBuildInList) {
//		float opad = 10f;
//		tooltip.addPara("Converts %s of the hard flux damage taken by shields to soft flux.", opad,
//				Misc.getHighlightColor(), "" + (int) Math.round(SMOD_SOFT_FLUX_CONVERSION * 100f) + "%");
//	}
}



