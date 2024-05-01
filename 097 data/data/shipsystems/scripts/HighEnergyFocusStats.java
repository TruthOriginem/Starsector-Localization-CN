package data.shipsystems.scripts;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

public class HighEnergyFocusStats extends BaseShipSystemScript {

	public static final float DAMAGE_BONUS_PERCENT = 50f;
	public static final float EXTRA_DAMAGE_TAKEN_PERCENT = 100f;
	
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		
		float bonusPercent = DAMAGE_BONUS_PERCENT * effectLevel;
		stats.getEnergyWeaponDamageMult().modifyPercent(id, bonusPercent);
		//stats.getEnergyWeaponRangeBonus().modifyPercent(id, bonusPercent);
		
		//float damageTakenPercent = EXTRA_DAMAGE_TAKEN_PERCENT * effectLevel;
//		stats.getArmorDamageTakenMult().modifyPercent(id, damageTakenPercent);
//		stats.getHullDamageTakenMult().modifyPercent(id, damageTakenPercent);
//		stats.getShieldDamageTakenMult().modifyPercent(id, damageTakenPercent);
		//stats.getWeaponDamageTakenMult().modifyPercent(id, damageTakenPercent);
		//stats.getEngineDamageTakenMult().modifyPercent(id, damageTakenPercent);
		
		//stats.getBeamWeaponFluxCostMult().modifyMult(id, 10f);
	}
	public void unapply(MutableShipStatsAPI stats, String id) {
		stats.getEnergyWeaponDamageMult().unmodify(id);
//		stats.getEnergyWeaponRangeBonus().unmodify(id);
//		stats.getArmorDamageTakenMult().unmodify(id);
//		stats.getHullDamageTakenMult().unmodify(id);
//		stats.getShieldDamageTakenMult().unmodify(id);
//		stats.getWeaponDamageTakenMult().unmodify(id);
//		stats.getEngineDamageTakenMult().unmodify(id);
	}
	
	public StatusData getStatusData(int index, State state, float effectLevel) {
		float bonusPercent = DAMAGE_BONUS_PERCENT * effectLevel;
		float damageTakenPercent = EXTRA_DAMAGE_TAKEN_PERCENT * effectLevel;
		if (index == 0) {
			return new StatusData("+" + (int) bonusPercent + "% energy weapon damage" , false);
		} else if (index == 1) {
			//return new StatusData("+" + (int) damageTakenPercent + "% weapon/engine damage taken", false);
			return null;
		} else if (index == 2) {
			//return new StatusData("shield damage taken +" + (int) damageTakenPercent + "%", true);
			return null;
		}
		return null;
	}
}
