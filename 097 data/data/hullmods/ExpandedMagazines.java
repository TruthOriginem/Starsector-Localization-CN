package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class ExpandedMagazines extends BaseHullMod {

	public static float AMMO_BONUS = 50f;
	
	//public static float SMOD_AMMO_BONUS = 50f;
	public static float SMOD_REGEN_BONUS = 50f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		
		stats.getBallisticAmmoBonus().modifyPercent(id, AMMO_BONUS);
		stats.getEnergyAmmoBonus().modifyPercent(id, AMMO_BONUS);
//		stats.getBallisticAmmoBonus().modifyPercent(id, AMMO_BONUS + (sMod ? SMOD_AMMO_BONUS : 0));
//		stats.getEnergyAmmoBonus().modifyPercent(id, AMMO_BONUS + (sMod ? SMOD_AMMO_BONUS : 0));
		
		boolean sMod = isSMod(stats);
		if (sMod) {
			stats.getBallisticAmmoRegenMult().modifyPercent(id, SMOD_REGEN_BONUS);
			stats.getEnergyAmmoRegenMult().modifyPercent(id, SMOD_REGEN_BONUS);
		}
		
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		//if (index == 0) return "" + (int) SMOD_AMMO_BONUS + "%";
		if (index == 0) return "" + (int) SMOD_REGEN_BONUS + "%";
		return null;
	}
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) AMMO_BONUS + "%";
		return null;
	}


}
