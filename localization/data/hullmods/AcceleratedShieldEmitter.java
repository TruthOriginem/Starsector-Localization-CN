package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class AcceleratedShieldEmitter extends BaseHullMod {

	public static float SHIELD_BONUS_TURN = 100f;
	public static float SHIELD_BONUS_UNFOLD = 100f;
	
	public static float SMOD_BONUS = 100f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		boolean sMod = isSMod(stats);
		stats.getShieldTurnRateMult().modifyPercent(id, SHIELD_BONUS_TURN + (sMod ? SMOD_BONUS : 0));
		stats.getShieldUnfoldRateMult().modifyPercent(id, SHIELD_BONUS_UNFOLD + (sMod ? SMOD_BONUS : 0));
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SHIELD_BONUS_TURN + "%";
		if (index == 1) return "" + (int) SHIELD_BONUS_UNFOLD + "%";
		return null;
	}

	public boolean isApplicableToShip(ShipAPI ship) {
		return ship != null && ship.getShield() != null;
	}
	
	public String getUnapplicableReason(ShipAPI ship) {
		return "该舰没有护盾";
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) Math.round(SMOD_BONUS) + "%";
		return null;
	}
	
//	public void addSModEffectSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship,
//									 float width, boolean isForModSpec, boolean isForBuildInList) {
//		float opad = 10f;
//		tooltip.addPara("Increases the shield's turn rate and raise rate by an additional %s.", opad,
//				Misc.getHighlightColor(), "" + (int) Math.round(SMOD_BONUS) + "%");
//	}
}


