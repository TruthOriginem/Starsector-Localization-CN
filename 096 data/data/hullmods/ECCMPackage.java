package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

public class ECCMPackage extends BaseHullMod {

	// original values from MissileSpecialization
//	public static final float MISSILE_SPEC_SPEED_BONUS = 25f;
//	public static final float MISSILE_SPEC_RANGE_MULT = 0.8f;
//	public static final float MISSILE_SPEC_ACCEL_BONUS = 50f;
//	public static final float MISSILE_TURN_RATE_BONUS = 50f;
//	public static final float MISSILE_TURN_ACCEL_BONUS = 100f;
	// original values from ECCM
//	stats.getMissileMaxSpeedBonus().modifyPercent(id, 10f);
//	stats.getMissileAccelerationBonus().modifyPercent(id, 100f);
//	stats.getMissileMaxTurnRateBonus().modifyPercent(id, 10f);
//	stats.getMissileTurnAccelerationBonus().modifyPercent(id, 50f);
	
	
	public static float MISSILE_SPEED_BONUS = 25f;
	public static float MISSILE_RANGE_MULT = 0.8f;
	public static float MISSILE_ACCEL_BONUS = 150f;
	public static float MISSILE_RATE_BONUS = 50f;
	public static float MISSILE_TURN_ACCEL_BONUS = 150f;
	
	public static float EW_PENALTY_MULT = 0.5f;
	public static float EW_PENALTY_REDUCTION = 5f;
	//public static float MAX_EW_PENALTY_MOD = 5f;
	
	public static float ECCM_CHANCE = 0.5f;
	public static float GUIDANCE_IMPROVEMENT = 1f;
	
	public static float SMOD_ECCM_CHANCE = 1f;
	public static float SMOD_EW = 0f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		boolean sMod = isSMod(stats);
		stats.getEccmChance().modifyFlat(id, sMod ? SMOD_ECCM_CHANCE : ECCM_CHANCE);
		stats.getMissileGuidance().modifyFlat(id, GUIDANCE_IMPROVEMENT);
		
//		stats.getMissileMaxSpeedBonus().modifyPercent(id, 10f);
//		stats.getMissileAccelerationBonus().modifyPercent(id, 100f);
//		stats.getMissileMaxTurnRateBonus().modifyPercent(id, 10f);
//		stats.getMissileTurnAccelerationBonus().modifyPercent(id, 50f);
		
		stats.getMissileMaxSpeedBonus().modifyPercent(id, MISSILE_SPEED_BONUS);
		stats.getMissileWeaponRangeBonus().modifyMult(id, MISSILE_RANGE_MULT);
		stats.getMissileAccelerationBonus().modifyPercent(id, MISSILE_ACCEL_BONUS);
		stats.getMissileMaxTurnRateBonus().modifyPercent(id, MISSILE_RATE_BONUS);
		stats.getMissileTurnAccelerationBonus().modifyPercent(id, MISSILE_TURN_ACCEL_BONUS);
		
		
//		if (sMod) {
//			stats.getDynamic().getStat(Stats.ELECTRONIC_WARFARE_PENALTY_MULT).modifyMult(id, SMOD_EW);
//		} else {
			//stats.getDynamic().getStat(Stats.ELECTRONIC_WARFARE_PENALTY_MULT).modifyMult(id, EW_PENALTY_MULT);
			stats.getDynamic().getMod(Stats.ELECTRONIC_WARFARE_PENALTY_MOD).modifyMult(id, EW_PENALTY_MULT);
//		}
		//stats.getDynamic().getMod(Stats.ELECTRONIC_WARFARE_PENALTY_MOD).modifyFlat(id, -EW_PENALTY_REDUCTION);
		
		//stats.getDynamic().getMod(Stats.ELECTRONIC_WARFARE_PENALTY_MAX_FOR_SHIP_MOD).modifyFlat(id, -MAX_EW_PENALTY_MOD);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) (ECCM_CHANCE * 100f) + "%";
		if (index == 1) return "" + (int) (MISSILE_SPEED_BONUS) + "%";
		if (index == 2) return "" + (int) (MISSILE_RATE_BONUS) + "%";
		if (index == 3) return "" + (int) ((1f - EW_PENALTY_MULT) * 100f) + "%";
		//if (index == 3) return "" + (int) EW_PENALTY_REDUCTION + "";
		return null;
	}


}



