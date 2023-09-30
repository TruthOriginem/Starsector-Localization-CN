package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class DedicatedTargetingCore extends BaseHullMod {

	private static Map mag = new HashMap();
	static {
		mag.put(HullSize.FIGHTER, 0f);
		mag.put(HullSize.FRIGATE, 0f);
		mag.put(HullSize.DESTROYER, 0f);
		mag.put(HullSize.CRUISER, 35f);
		mag.put(HullSize.CAPITAL_SHIP, 50f);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + ((Float) mag.get(HullSize.CRUISER)).intValue() + "%";
		if (index == 1) return "" + ((Float) mag.get(HullSize.CAPITAL_SHIP)).intValue() + "%";
		return null;
	}
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getBallisticWeaponRangeBonus().modifyPercent(id, (Float) mag.get(hullSize));
		stats.getEnergyWeaponRangeBonus().modifyPercent(id, (Float) mag.get(hullSize));
	}

	@Override
	public boolean isApplicableToShip(ShipAPI ship) {
		return (ship.getHullSize() == HullSize.CAPITAL_SHIP || ship.getHullSize() == HullSize.CRUISER) &&
				!ship.getVariant().getHullMods().contains("targetingunit") &&
				!ship.getVariant().getHullMods().contains("advancedcore");
	}
	
	
	public String getUnapplicableReason(ShipAPI ship) {
		if (ship != null && ship.getHullSize() != HullSize.CAPITAL_SHIP && ship.getHullSize() != HullSize.CRUISER) {
			return "Can only be installed on cruisers and capital ships";
		}
		if (ship.getVariant().getHullMods().contains("targetingunit")) {
			return "Incompatible with Integrated Targeting Unit";
		}
		if (ship.getVariant().getHullMods().contains("advancedcore")) {
			return "Incompatible with Advanced Targeting Core";
		}
		
		return null;
	}
	
}
