package data.hullmods;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.util.Misc;

public class SafetyOverrides extends BaseHullMod {

	private static Map speed = new HashMap();
	static {
		speed.put(HullSize.FRIGATE, 50f);
		speed.put(HullSize.DESTROYER, 30f);
		speed.put(HullSize.CRUISER, 20f);
		speed.put(HullSize.CAPITAL_SHIP, 10f);
	}
	
//	private static Map flux = new HashMap();
//	static {
//		flux.put(HullSize.FRIGATE, 100f);
//		flux.put(HullSize.DESTROYER, 150f);
//		flux.put(HullSize.CRUISER, 200f);
//		flux.put(HullSize.CAPITAL_SHIP, 300f);
//	}
	
	//private static final float PEAK_MULT = 0.3333f;
	private static final float PEAK_MULT = 0.33f;
	//private static final float CR_DEG_MULT = 2f;
	//private static final float OVERLOAD_DUR = 50f;
	private static final float FLUX_DISSIPATION_MULT = 2f;
	//private static final float FLUX_CAPACITY_MULT = 1f;
	
	private static final float RANGE_THRESHOLD = 450f;
	private static final float RANGE_MULT = 0.25f;
	
	//private static final float RECOIL_MULT = 2f;
	//private static final float MALFUNCTION_PROB = 0.05f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getMaxSpeed().modifyFlat(id, (Float) speed.get(hullSize));
		stats.getAcceleration().modifyFlat(id, (Float) speed.get(hullSize) * 2f);
		stats.getDeceleration().modifyFlat(id, (Float) speed.get(hullSize) * 2f);
		stats.getZeroFluxMinimumFluxLevel().modifyFlat(id, 2f); // set to two, meaning boost is always on 
		
		stats.getFluxDissipation().modifyMult(id, FLUX_DISSIPATION_MULT);
		
		stats.getPeakCRDuration().modifyMult(id, PEAK_MULT);
		//stats.getCRLossPerSecondPercent().modifyMult(id, CR_DEG_MULT);
//		stats.getWeaponMalfunctionChance().modifyFlat(id, MALFUNCTION_PROB);
//		stats.getEngineMalfunctionChance().modifyFlat(id, MALFUNCTION_PROB);
		
		//stats.getOverloadTimeMod().modifyPercent(id, OVERLOAD_DUR);
		stats.getVentRateMult().modifyMult(id, 0f);
		
		stats.getWeaponRangeThreshold().modifyFlat(id, RANGE_THRESHOLD);
		stats.getWeaponRangeMultPastThreshold().modifyMult(id, RANGE_MULT);
		
//		stats.getMaxRecoilMult().modifyMult(id, 1.5f);
//		stats.getRecoilPerShotMult().modifyMult(id, RECOIL_MULT);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + ((Float) speed.get(HullSize.FRIGATE)).intValue();
		if (index == 1) return "" + ((Float) speed.get(HullSize.DESTROYER)).intValue();
		if (index == 2) return "" + ((Float) speed.get(HullSize.CRUISER)).intValue();
		//if (index == 3) return "" + ((Float) speed.get(HullSize.)).intValue();
		if (index == 3) return Misc.getRoundedValue(FLUX_DISSIPATION_MULT);
		//if (index == 4) return Strings.X + Misc.getRoundedValue(PEAK_MULT);
		if (index == 4) return "3";
		//if (index == 3) return Misc.getRoundedValue(CR_DEG_MULT);
		if (index == 5) return Misc.getRoundedValue(RANGE_THRESHOLD);
//		if (index == 4) return Misc.getRoundedValue(RECOIL_MULT);
		//if (index == 3) return (int)OVERLOAD_DUR + "%";
		
//		if (index == 0) return "" + ((Float) speed.get(hullSize)).intValue();
//		if (index == 1) return "" + (int)((FLUX_DISSIPATION_MULT - 1f) * 100f) + "%";
//		if (index == 2) return "" + (int)((1f - PEAK_MULT) * 100f) + "%";
		
//		if (index == 0) return "" + ((Float) speed.get(HullSize.FRIGATE)).intValue();
//		if (index == 1) return "" + ((Float) speed.get(HullSize.DESTROYER)).intValue();
//		if (index == 2) return "" + ((Float) speed.get(HullSize.CRUISER)).intValue();
//		if (index == 3) return "" + ((Float) speed.get(HullSize.CAPITAL_SHIP)).intValue();
//		
//		if (index == 4) return "" + (int)((FLUX_DISSIPATION_MULT - 1f) * 100f);
//		if (index == 5) return "" + (int)((1f - PEAK_MULT) * 100f);
		
		return null;
	}

	@Override
	public boolean isApplicableToShip(ShipAPI ship) {
//		return !ship.getVariant().getHullMods().contains("unstable_injector") &&
//			   !ship.getVariant().getHullMods().contains("augmented_engines");
		if (ship.getVariant().getHullSize() == HullSize.CAPITAL_SHIP) return false;
		if (ship.getVariant().hasHullMod(HullMods.CIVGRADE) && !ship.getVariant().hasHullMod(HullMods.MILITARIZED_SUBSYSTEMS)) return false;
		if (ship.getVariant().hasHullMod(HullMods.FLUX_SHUNT)) return false;
		
		
		return true;
	}
	
	public String getUnapplicableReason(ShipAPI ship) {
		if (ship.getVariant().getHullSize() == HullSize.CAPITAL_SHIP) {
			return "不能被安装在主力舰上";
		}
		if (ship.getVariant().hasHullMod(HullMods.CIVGRADE) && !ship.getVariant().hasHullMod(HullMods.MILITARIZED_SUBSYSTEMS)) {
			return "不能被安装在民用舰上";
		}
		if (ship.getVariant().hasHullMod(HullMods.FLUX_SHUNT)) {
			return "不兼容于 磁通分流";
		}
		
		return null;
	}
	

	private Color color = new Color(255,100,255,255);
	@Override
	public void advanceInCombat(ShipAPI ship, float amount) {
		//ship.getFluxTracker().setHardFlux(ship.getFluxTracker().getCurrFlux());
//		if (ship.getEngineController().isAccelerating() || 
//				ship.getEngineController().isAcceleratingBackwards() ||
//				ship.getEngineController().isDecelerating() ||
//				ship.getEngineController().isTurningLeft() ||
//				ship.getEngineController().isTurningRight() ||
//				ship.getEngineController().isStrafingLeft() ||
//				ship.getEngineController().isStrafingRight()) {
			ship.getEngineController().fadeToOtherColor(this, color, null, 1f, 0.4f);
			ship.getEngineController().extendFlame(this, 0.25f, 0.25f, 0.25f);
//		}
	}

	

}
