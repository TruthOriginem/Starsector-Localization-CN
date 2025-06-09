package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class FrontShieldEmitter extends BaseHullMod {

	public static float ARC_BONUS = 100f;
	//public static float UPKEEP_BONUS = 50f;
	
	public static float SMOD_SHIELD_BONUS = 5f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getShieldArcBonus().modifyPercent(id, ARC_BONUS);
		//stats.getShieldUpkeepMult().modifyMult(id, 1f - UPKEEP_BONUS * 0.01f);
		
		boolean sMod = isSMod(stats);
		if (sMod) {
			stats.getShieldDamageTakenMult().modifyMult(id, 1f - SMOD_SHIELD_BONUS * 0.01f);
		}
	}
	
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		ShieldAPI shield = ship.getShield();
		if (shield != null) {
			shield.setType(ShieldType.FRONT);
		}
	}

	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) ARC_BONUS + "%";
		//if (index == 1) return "" + (int) UPKEEP_BONUS + "%";
		return null;
	}

	public String getSModDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) SMOD_SHIELD_BONUS + "%";
		return null;
	}

	public boolean isApplicableToShip(ShipAPI ship) {
		if (ship.getVariant().hasHullMod("frontemitter") && ship.getShield() != null) return true;
		return ship != null && ship.getShield() != null && ship.getShield().getType() == ShieldType.OMNI &&
						!ship.getVariant().getHullMods().contains("adaptiveshields");

	}
	
	public String getUnapplicableReason(ShipAPI ship) {
		if (ship == null || ship.getShield() == null) {
			return "该舰没有护盾";
		}
		
		if (ship.getShield().getType() == ShieldType.FRONT) {
			return "该舰已经拥有定向护盾";
		}
		
		if (ship.getVariant().getHullMods().contains("adaptiveshields")) {
			return "不兼容于 全角护盾发生器";
		}
		
		return null;
	}
	
	
}




