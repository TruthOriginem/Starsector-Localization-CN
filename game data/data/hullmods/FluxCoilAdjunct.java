package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

@SuppressWarnings("unchecked")
public class FluxCoilAdjunct extends BaseHullMod {

	public static Map mag = new HashMap();
	static {
		mag.put(HullSize.FRIGATE, 600f);
		mag.put(HullSize.DESTROYER, 1200f);
		mag.put(HullSize.CRUISER, 1800f);
		mag.put(HullSize.CAPITAL_SHIP, 3000f);
	}
	
	public static Map magBonus = new HashMap();
	static {
		magBonus.put(HullSize.FRIGATE, 200f);
		magBonus.put(HullSize.DESTROYER, 400f);
		magBonus.put(HullSize.CRUISER, 600f);
		magBonus.put(HullSize.CAPITAL_SHIP, 1000f);
	}
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		boolean sMod = isSMod(stats);
		float cap = (Float) mag.get(hullSize);
		if (sMod) {
			cap += (Float) magBonus.get(hullSize);
		}
		stats.getFluxCapacity().modifyFlat(id, cap);
	}
	
	public String getSModDescriptionParam(int index, HullSize hullSize) {
//		float cap = (Float) mag.get(hullSize) * Misc.FLUX_PER_CAPACITOR;
//		if (index == 0) return "" + (int) cap;
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

	
//	@Override
//	public void advanceInCombat(ShipAPI ship, float amount) {
//		boolean sMod = isSMod(ship);
//		if (sMod) {
//			float cap = (Float) mag.get(ship.getHullSize()); // * Misc.FLUX_PER_CAPACITOR;
//			
//			String id = "fca_sModEffect";
//			float capLevel = cap / Math.max(1f, ship.getMaxFlux());
//			if (capLevel < 0) {
//				ship.getMutableStats().getZeroFluxMinimumFluxLevel().unmodifyFlat(id);
//			} else {
//				ship.getMutableStats().getZeroFluxMinimumFluxLevel().modifyFlat(id, capLevel);
//			}
//		}
//	}

	
}










