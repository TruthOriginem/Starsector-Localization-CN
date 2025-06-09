package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

public class HeavyArmor extends BaseHullMod {

	public static float MANEUVER_PENALTY = 10f;
	
	public static float SMOD_MANEUVER_PENALTY = 25f;
	
	
	private static Map mag = new HashMap();
	static {
//		mag.put(HullSize.FRIGATE, 100f);
//		mag.put(HullSize.DESTROYER, 200f);
//		mag.put(HullSize.CRUISER, 300f);
//		mag.put(HullSize.CAPITAL_SHIP, 400f);
		mag.put(HullSize.FRIGATE, 150f);
		mag.put(HullSize.DESTROYER, 300f);
		mag.put(HullSize.CRUISER, 400f);
		mag.put(HullSize.CAPITAL_SHIP, 500f);
	}
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		
		stats.getArmorBonus().modifyFlat(id, (Float) mag.get(hullSize));
		
//		stats.getAcceleration().modifyMult(id, 1f - MANEUVER_PENALTY * 0.01f);
//		stats.getDeceleration().modifyMult(id, 1f - MANEUVER_PENALTY * 0.01f);
//		stats.getTurnAcceleration().modifyMult(id, 1f - MANEUVER_PENALTY * 0.01f);
//		stats.getMaxTurnRate().modifyMult(id, 1f - MANEUVER_PENALTY * 0.01f);
		
		boolean sMod = isSMod(stats);
		if (sMod) {
			stats.getAcceleration().modifyMult(id, 1f - SMOD_MANEUVER_PENALTY * 0.01f);
			stats.getDeceleration().modifyMult(id, 1f - SMOD_MANEUVER_PENALTY * 0.01f);
			stats.getTurnAcceleration().modifyMult(id, 1f - SMOD_MANEUVER_PENALTY * 0.01f);
			stats.getMaxTurnRate().modifyMult(id, 1f - SMOD_MANEUVER_PENALTY * 0.01f);
		}
		
		//stats.getBeamWeaponFluxCostMult().modifyMult(id, 10f);
//		stats.getEnergyAmmoRegenMult().modifyMult(id, 10f);
		
//		stats.getDynamic().getMod(Stats.MAX_LOGISTICS_HULLMODS_MOD).modifyFlat(id, 1);
//		//stats.getCargoMod().modifyFlat(id, -70);
//		stats.getTimeMult().modifyMult(id, 4f);
//		//if (stats.getEntity() != null && stats.getEntity() == Global.getCombatEngine().getPlayerShip()) {
//			Global.getCombatEngine().getTimeMult().modifyMult(id, 1f / 4f);
//		//}
		//stats.getNumFighterBays().modifyFlat(id, -1f);
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + ((Float) mag.get(HullSize.FRIGATE)).intValue();
		if (index == 1) return "" + ((Float) mag.get(HullSize.DESTROYER)).intValue();
		if (index == 2) return "" + ((Float) mag.get(HullSize.CRUISER)).intValue();
		if (index == 3) return "" + ((Float) mag.get(HullSize.CAPITAL_SHIP)).intValue();
		if (index == 4) return "" + (int) MANEUVER_PENALTY + "%";
		return null;
		//if (index == 0) return "" + ((Float) mag.get(hullSize)).intValue();
		//return null;
	}
	
	
	@Override
	public String getSModDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
		if (index == 0) return "" + (int) SMOD_MANEUVER_PENALTY + "%";
		return null;
	}
	
	@Override
	public boolean isSModEffectAPenalty() {
		return true;
	}
	
	

	public boolean isApplicableToShip(ShipAPI ship) {
		//if (ship.getMutableStats().getCargoMod().computeEffective(ship.getHullSpec().getCargo()) < 70) return false;

		return true;
	}


}
