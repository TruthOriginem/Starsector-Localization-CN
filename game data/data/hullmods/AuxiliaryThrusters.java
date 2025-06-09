package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

public class AuxiliaryThrusters extends BaseHullMod {

	public static float MANEUVER_BONUS = 50f;
	public static float SMOD_SPEED_BONUS = 10f;
	public static float SMOD_TURN_MULT = 2f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getAcceleration().modifyPercent(id, MANEUVER_BONUS * 2f);
		stats.getDeceleration().modifyPercent(id, MANEUVER_BONUS);
		stats.getTurnAcceleration().modifyPercent(id, MANEUVER_BONUS * 2f);
		stats.getMaxTurnRate().modifyPercent(id, MANEUVER_BONUS);
		
		
		boolean sMod = isSMod(stats);
		if (sMod) {
			stats.getDynamic().getStat(Stats.ZERO_FLUX_BOOST_TURN_RATE_BONUS_MULT).modifyMult(id, SMOD_TURN_MULT);
			stats.getZeroFluxSpeedBoost().modifyFlat(id, SMOD_SPEED_BONUS);
		}
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) MANEUVER_BONUS + "%";
		return null;
	}

	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SMOD_SPEED_BONUS + "";
		if (index == 1) return "doubles";
		return null;
	}
}
