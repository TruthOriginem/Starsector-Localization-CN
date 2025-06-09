package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

@SuppressWarnings("unchecked")
public class FluxDistributor extends BaseHullMod {

	public static float SMOD_DISSIPATION = 5f;
	
	private static Map mag = new HashMap();
	static {
		mag.put(HullSize.FRIGATE, 30f);
		mag.put(HullSize.DESTROYER, 60f);
		mag.put(HullSize.CRUISER, 90f);
		mag.put(HullSize.CAPITAL_SHIP, 150f);
	}
	
	public static Map magBonus = new HashMap();
	static {
		magBonus.put(HullSize.FRIGATE, 10f);
		magBonus.put(HullSize.DESTROYER, 20f);
		magBonus.put(HullSize.CRUISER, 30f);
		magBonus.put(HullSize.CAPITAL_SHIP, 50f);
	}
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		boolean sMod = isSMod(stats);
		float dissipation = (Float) mag.get(hullSize);
		if (sMod) dissipation += (Float) magBonus.get(hullSize);
		stats.getFluxDissipation().modifyFlat(id, dissipation);
		
//		if (sMod) {
//			stats.getHardFluxDissipationFraction().modifyFlat(id, SMOD_DISSIPATION / 100f);
//		}
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		//if (index == 0) return "" + (int) SMOD_DISSIPATION + "%";
		if (index == 0) return "" + ((Float) magBonus.get(HullSize.FRIGATE)).intValue();
		if (index == 1) return "" + ((Float) magBonus.get(HullSize.DESTROYER)).intValue();
		if (index == 2) return "" + ((Float) magBonus.get(HullSize.CRUISER)).intValue();
		if (index == 3) return "" + ((Float) magBonus.get(HullSize.CAPITAL_SHIP)).intValue();
		return null;
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + ((Float) mag.get(HullSize.FRIGATE)).intValue();
		if (index == 1) return "" + ((Float) mag.get(HullSize.DESTROYER)).intValue();
		if (index == 2) return "" + ((Float) mag.get(HullSize.CRUISER)).intValue();
		if (index == 3) return "" + ((Float) mag.get(HullSize.CAPITAL_SHIP)).intValue();
		return null;
	}


}
