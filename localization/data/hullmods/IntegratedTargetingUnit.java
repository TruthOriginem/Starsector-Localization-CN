package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;

public class IntegratedTargetingUnit extends BaseHullMod {

	public static Map mag = new HashMap();
	static {
		mag.put(HullSize.FIGHTER, 0f);
		mag.put(HullSize.FRIGATE, 10f);
		mag.put(HullSize.DESTROYER, 20f);
		mag.put(HullSize.CRUISER, 40f);
		mag.put(HullSize.CAPITAL_SHIP, 60f);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + ((Float) mag.get(HullSize.FRIGATE)).intValue() + "%";
		if (index == 1) return "" + ((Float) mag.get(HullSize.DESTROYER)).intValue() + "%";
		if (index == 2) return "" + ((Float) mag.get(HullSize.CRUISER)).intValue() + "%";
		if (index == 3) return "" + ((Float) mag.get(HullSize.CAPITAL_SHIP)).intValue() + "%";
		return null;
	}
	
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getBallisticWeaponRangeBonus().modifyPercent(id, (Float) mag.get(hullSize));
		stats.getEnergyWeaponRangeBonus().modifyPercent(id, (Float) mag.get(hullSize));
	}


	@Override
	public boolean isApplicableToShip(ShipAPI ship) {
		return !ship.getVariant().getHullMods().contains("dedicated_targeting_core") &&
				!ship.getVariant().getHullMods().contains(HullMods.DISTRIBUTED_FIRE_CONTROL) &&
				!ship.getVariant().getHullMods().contains("advancedcore");
	}
	
	public String getUnapplicableReason(ShipAPI ship) {
		if (ship.getVariant().getHullMods().contains("dedicated_targeting_core")) {
			return "不兼容于 专注型目标锁定核心";
		}
		if (ship.getVariant().getHullMods().contains("advancedcore")) {
			return "不兼容于 先进目标定位核心";
		}
		if (ship.getVariant().getHullMods().contains(HullMods.DISTRIBUTED_FIRE_CONTROL)) {
			return "不兼容于 分布式火控系统";
		}
		return null;
	}
	
}
