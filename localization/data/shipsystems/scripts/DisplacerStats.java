package data.shipsystems.scripts;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

public class DisplacerStats extends BaseShipSystemScript {

	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
//		if (state == ShipSystemStatsScript.State.OUT) {
//			stats.getMaxSpeed().unmodify(id); // to slow down ship to its regular top speed while powering down
//		} else {
//			stats.getMaxSpeed().modifyFlat(id, 500f * effectLevel);
//			stats.getAcceleration().modifyFlat(id, 10000f * effectLevel);
//		}
	}
	public void unapply(MutableShipStatsAPI stats, String id) {
//		stats.getMaxSpeed().unmodify(id);
//		stats.getMaxTurnRate().unmodify(id);
//		stats.getTurnAcceleration().unmodify(id);
//		stats.getAcceleration().unmodify(id);
//		stats.getDeceleration().unmodify(id);
	}
	
	public StatusData getStatusData(int index, State state, float effectLevel) {
		if (index == 0) {
			return new StatusData("解除相位状态", false);
		}
		return null;
	}
}
