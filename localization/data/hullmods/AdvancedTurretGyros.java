package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class AdvancedTurretGyros extends BaseHullMod {

	public static float TURRET_SPEED_BONUS = 75f;
	
	public static float SMOD_BONUS = 25f;
	public static float SMOD_BONUS_PER_SIZE = 5f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getWeaponTurnRateBonus().modifyPercent(id, TURRET_SPEED_BONUS);
		stats.getBeamWeaponTurnRateBonus().modifyPercent(id, TURRET_SPEED_BONUS);
		
		boolean sMod = isSMod(stats);
		if (sMod) {
			stats.getDamageToMissiles().modifyPercent(id, SMOD_BONUS);
			stats.getDamageToFighters().modifyPercent(id, SMOD_BONUS);
			//stats.getDamageToFrigates().modifyPercent(id, SMOD_BONUS);
			if (hullSize == HullSize.CAPITAL_SHIP) {
				stats.getDamageToFrigates().modifyPercent(id, SMOD_BONUS_PER_SIZE * 3f);
				stats.getDamageToDestroyers().modifyPercent(id, SMOD_BONUS_PER_SIZE * 2f);
				stats.getDamageToCruisers().modifyPercent(id, SMOD_BONUS_PER_SIZE * 1f);
			} else if (hullSize == HullSize.CRUISER) {
				stats.getDamageToFrigates().modifyPercent(id, SMOD_BONUS_PER_SIZE * 2f);
				stats.getDamageToDestroyers().modifyPercent(id, SMOD_BONUS_PER_SIZE * 1f);
			} else if (hullSize == HullSize.DESTROYER) {
				stats.getDamageToFrigates().modifyPercent(id, SMOD_BONUS_PER_SIZE * 1f);
			}
		}
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) TURRET_SPEED_BONUS + "%";
		return null;
	}

	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SMOD_BONUS + "%";
		if (index == 1) return "" + (int) SMOD_BONUS_PER_SIZE + "%";
		return null;
	}
	
}
