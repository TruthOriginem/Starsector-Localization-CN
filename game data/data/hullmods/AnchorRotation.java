package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

public class AnchorRotation extends BaseHullMod {

	public static final float ROTATION_DEGREES_PER_SECOND = -3f;
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		return null;
	}
	
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
	}


	@Override
	public boolean isApplicableToShip(ShipAPI ship) {
		return true;
	}


	@Override
	public void advanceInCombat(ShipAPI ship, float amount) {
		super.advanceInCombat(ship, amount);
		
		ship.ensureClonedStationSlotSpec();
		WeaponSlotAPI slot = ship.getStationSlot();
		
		if (slot == null) return;
		
		float angle = slot.getAngle();
		//angle += amount * ROTATION_DEGREES_PER_SECOND;
		angle += amount * -1f * ship.getMutableStats().getMaxTurnRate().getBaseValue();
		
		slot.setAngle(angle);
		
	}
	

	
	
}
