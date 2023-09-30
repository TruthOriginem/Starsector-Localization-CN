package data.shipsystems.scripts;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

public class FortressShieldStats extends BaseShipSystemScript {

	public static float DAMAGE_MULT = 0.9f;
	//public static float DAMAGE_MULT = 0.8f;
	
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		//stats.getShieldTurnRateMult().modifyMult(id, 1f);
		//stats.getShieldUnfoldRateMult().modifyPercent(id, 2000);
		
		//stats.getShieldDamageTakenMult().modifyMult(id, 0.1f);
		stats.getShieldDamageTakenMult().modifyMult(id, 1f - DAMAGE_MULT * effectLevel);
		
		stats.getShieldUpkeepMult().modifyMult(id, 0f);
		
		//System.out.println("level: " + effectLevel);
	}
	
	public void unapply(MutableShipStatsAPI stats, String id) {
		//stats.getShieldAbsorptionMult().unmodify(id);
		stats.getShieldArcBonus().unmodify(id);
		stats.getShieldDamageTakenMult().unmodify(id);
		stats.getShieldTurnRateMult().unmodify(id);
		stats.getShieldUnfoldRateMult().unmodify(id);
		stats.getShieldUpkeepMult().unmodify(id);
	}
	
	public StatusData getStatusData(int index, State state, float effectLevel) {
		if (index == 0) {
			return new StatusData("shield absorbs 10x damage", false);
		}
//		else if (index == 1) {
//			return new StatusData("shield upkeep reduced to 0", false);
//		} else if (index == 2) {
//			return new StatusData("shield upkeep reduced to 0", false);
//		}
		return null;
	}
}
