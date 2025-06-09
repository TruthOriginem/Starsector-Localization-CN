package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class AutomatedRepairUnit extends BaseHullMod {

	public static float REPAIR_RATE_BONUS = 50f;
	public static float CR_RECOVERY_BONUS = 50f;
	public static float REPAIR_BONUS = 50f;
	
	public static float SMOD_REPAIR_BONUS = 25f;
	public static float SMOD_OVERLOAD_BONUS = 33f;
	
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		
		boolean sMod = isSMod(stats);
		float bonus = REPAIR_BONUS;
		if (sMod) bonus += SMOD_REPAIR_BONUS;
		stats.getCombatEngineRepairTimeMult().modifyMult(id, 1f - bonus * 0.01f);
		stats.getCombatWeaponRepairTimeMult().modifyMult(id, 1f - bonus * 0.01f);
		
		if (sMod) {
			stats.getOverloadTimeMod().modifyMult(id, 1f - SMOD_OVERLOAD_BONUS * 0.01f);
		}
		
//		stats.getBaseCRRecoveryRatePercentPerDay().modifyPercent(id, CR_RECOVERY_BONUS);
//		stats.getRepairRatePercentPerDay().modifyPercent(id, REPAIR_RATE_BONUS);
//		stats.getSuppliesToRecover().modifyPercent(id, LOGISTICS_PENALTY);
//		stats.getSuppliesPerMonth().modifyPercent(id, LOGISTICS_PENALTY);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) REPAIR_BONUS + "%";
//		if (index == 1) return "" + (int) CR_RECOVERY_BONUS + "%";
		//if (index == 2) return "" + (int) LOGISTICS_PENALTY;
		return null;
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round(100f - REPAIR_BONUS - SMOD_REPAIR_BONUS) + "%";
		if (index == 1) return "" + (int) SMOD_OVERLOAD_BONUS + "%";
		return null;
	}


}


