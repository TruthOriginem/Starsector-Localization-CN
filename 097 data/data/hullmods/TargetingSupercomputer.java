package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class TargetingSupercomputer extends BaseHullMod {

	public static final float RECOIL_BONUS = 50f;
	public static final float PROJ_SPEED_BONUS = 100f;
	
	public static float RANGE_BONUS = 250f;
	public static float PD_MINUS = 190f;
	public static float VISION_BONUS = 2000f;
	public static float AUTOFIRE_AIM = 0.5f;
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)Math.round(RANGE_BONUS) + "%";
		if (index == 1) return "" + (int)Math.round(RANGE_BONUS - PD_MINUS) + "%";
		//if (index == 0) return "" + (int)RANGE_THRESHOLD;
		//if (index == 1) return "" + (int)((RANGE_MULT - 1f) * 100f);
		//if (index == 1) return "" + new Float(VISION_BONUS).intValue();
		return null;
	}
	
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getBallisticWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
		stats.getEnergyWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
		//stats.getBeamWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
		
		stats.getNonBeamPDWeaponRangeBonus().modifyPercent(id, -PD_MINUS);
		stats.getBeamPDWeaponRangeBonus().modifyPercent(id, -PD_MINUS);
		
		
		stats.getSightRadiusMod().modifyFlat(id, VISION_BONUS);
		stats.getAutofireAimAccuracy().modifyFlat(id, AUTOFIRE_AIM);
		
		
		
		
		stats.getMaxRecoilMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
		stats.getRecoilPerShotMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
		//stats.getProjectileSpeedMult().modifyPercent(id, PROJ_SPEED_BONUS);
	}


	
}
