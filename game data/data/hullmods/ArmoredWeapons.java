package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class ArmoredWeapons extends BaseHullMod {

	public static float RECOIL_BONUS = 25f;
	public static float HEALTH_BONUS = 100f;
	public static float ARMOR_BONUS = 10f;
	public static float TURN_PENALTY = 25f;
	
	public static float SMOD_BONUS = 10f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getArmorBonus().modifyPercent(id, ARMOR_BONUS);
		stats.getWeaponHealthBonus().modifyPercent(id, HEALTH_BONUS);
		stats.getWeaponTurnRateBonus().modifyMult(id, 1f - TURN_PENALTY * 0.01f);
		
		
		stats.getMaxRecoilMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
		stats.getRecoilPerShotMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
		// slower recoil recovery, also, to match the reduced recoil-per-shot
		// overall effect is same as without skill but halved in every respect
		stats.getRecoilDecayMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
		
		boolean sMod = isSMod(stats);
		if (sMod) {
			stats.getBallisticRoFMult().modifyMult(id, 1f + SMOD_BONUS * 0.01f);
			stats.getEnergyRoFMult().modifyMult(id, 1f + SMOD_BONUS * 0.01f);
		}
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) HEALTH_BONUS + "%";
		if (index == 1) return "" + (int) RECOIL_BONUS + "%";
		if (index == 2) return "" + (int) TURN_PENALTY + "%";
		if (index == 3) return "" + (int) ARMOR_BONUS + "%";
		return null;
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SMOD_BONUS + "%";
		return null;
	}
	

}
