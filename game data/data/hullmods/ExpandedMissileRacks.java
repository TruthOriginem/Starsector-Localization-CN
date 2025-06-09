package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class ExpandedMissileRacks extends BaseHullMod {

	public static float AMMO_BONUS = 100f;
	public static float SMOD_ROF_MULT = 0.8f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getMissileAmmoBonus().modifyPercent(id, AMMO_BONUS);
		
		boolean sMod = isSMod(stats);
		if (sMod) {
			stats.getMissileRoFMult().modifyMult(id, SMOD_ROF_MULT);
		}
		//stats.getMissileWeaponDamageMult().modifyPercent(id, 1000f);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) AMMO_BONUS + "%";
		return null;
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round((1f - SMOD_ROF_MULT) * 100f) + "%";
		return null;
	}

	@Override
	public boolean isSModEffectAPenalty() {
		return true;
	}

//	public void addSModEffectSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship,
//									 float width, boolean isForModSpec, boolean isForBuildInList) {
//		float opad = 10f;
//		tooltip.addPara("Reduces the rate of fire of missile weapons by %s.", opad,
//				Misc.getHighlightColor(), "" + (int) Math.round((1f - SMOD_ROF_MULT) * 100f) + "%");
//	}
}


