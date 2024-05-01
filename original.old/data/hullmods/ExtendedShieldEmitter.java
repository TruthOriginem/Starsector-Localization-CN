package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class ExtendedShieldEmitter extends BaseHullMod {

	public static final float SHIELD_ARC_BONUS = 60f;
	public static final float SMOD_ARC_BONUS = 60f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		boolean sMod = isSMod(stats);
		stats.getShieldArcBonus().modifyFlat(id, SHIELD_ARC_BONUS + (sMod ? SMOD_ARC_BONUS : 0));
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SHIELD_ARC_BONUS;
		return null;
	}

	public boolean isApplicableToShip(ShipAPI ship) {
		return ship != null && ship.getShield() != null;
	}
	
	public String getUnapplicableReason(ShipAPI ship) {
		return "Ship has no shields";
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round(SMOD_ARC_BONUS) + "";
		return null;
	}
	
//	public void addSModEffectSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship,
//									 float width, boolean isForModSpec, boolean isForBuildInList) {
//		float opad = 10f;
//		tooltip.addPara("Increases the shield's coverage by an additional %s degrees.", opad,
//				Misc.getHighlightColor(), "" + (int) Math.round(SMOD_ARC_BONUS));
//	}
}



