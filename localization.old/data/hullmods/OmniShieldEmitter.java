package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class OmniShieldEmitter extends BaseHullMod {

	public static float ARC_PENALTY = 30f;
	//public static float SHIELD_UPKEEP_BONUS = 25f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		boolean sMod = isSMod(stats);
		if (!sMod) {
			stats.getShieldArcBonus().modifyMult(id, 1f - ARC_PENALTY * 0.01f);
		}
		//stats.getShieldUpkeepMult().modifyMult(id, 1f - SHIELD_UPKEEP_BONUS * 0.01f);
	}
	
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		ShieldAPI shield = ship.getShield();
		if (shield != null) {
			shield.setType(ShieldType.OMNI);
		}
	}

	
	public String getDescriptionParam(int index, HullSize hullSize) {
		//if (index == 0) return "" + (int) ARC_PENALTY;
		if (index == 0) return "" + (int) ARC_PENALTY + "%";
		//if (index == 1) return "" + (int) SHIELD_UPKEEP_BONUS + "%";
		return null;
	}

	public boolean isApplicableToShip(ShipAPI ship) {
		if (ship.getVariant().getHullSpec().getShieldType() == ShieldType.OMNI && 
				!ship.getVariant().hasHullMod("frontemitter")) return false;
		if (ship.getVariant().getHullMods().contains("frontemitter")) return false;
		if (ship.getVariant().hasHullMod("adaptiveshields") && ship.getShield() != null) return true;
		return ship != null && ship.getShield() != null && ship.getShield().getType() == ShieldType.FRONT;
	}
	
	public String getUnapplicableReason(ShipAPI ship) {
		if (ship == null || ship.getShield() == null) return "该舰没有护盾";
		
		if (ship.getShield().getType() == ShieldType.OMNI) { 
			return "该舰已经拥有全角护盾";
		}
		
		if (ship.getVariant().getHullMods().contains("frontemitter")) {
			return "不兼容于 固化护盾转化器";
		}
		
		return null;
	}
	
}
