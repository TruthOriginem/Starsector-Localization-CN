package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

public class HardenedSubsystems extends BaseHullMod {

	public static final float PEAK_BONUS_PERCENT = 50f;
	public static final float DEGRADE_REDUCTION_PERCENT = 25f;
	
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getPeakCRDuration().modifyPercent(id, PEAK_BONUS_PERCENT);
		stats.getCRLossPerSecondPercent().modifyMult(id, 1f - DEGRADE_REDUCTION_PERCENT / 100f);
	}
	

	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int) PEAK_BONUS_PERCENT + "%";
		if (index == 1) return "" + (int) DEGRADE_REDUCTION_PERCENT + "%";
		return null;
	}

	public boolean isApplicableToShip(ShipAPI ship) {
		return ship != null && (ship.getHullSpec().getNoCRLossTime() < 10000 || ship.getHullSpec().getCRLossPerSecond(ship.getMutableStats()) > 0); 
	}
	
	
	@Override
	public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
		if (true) return;
		
		if (ship == null || ship.getMutableStats() == null) return;

		
		MutableShipStatsAPI stats = ship.getMutableStats();
		float decay = ship.getHullSpec().getCRLossPerSecond(stats);
		if (decay <= 0) return;
		
		float crPerDep = stats.getCRPerDeploymentPercent().computeEffective(ship.getHullSpec().getCRToDeploy()) / 100f;
		float minCRPerDep = Global.getSettings().getFloat("crDecayMinDeploymentCostForCalc");
		float secondsPerDeplomentCR = Global.getSettings().getFloat("crDecaySecondsPerDeploymentCostPercent");
		
		if (crPerDep < minCRPerDep) crPerDep = minCRPerDep;
		if (crPerDep <= 0) return;
	
		
		float opad = 10f;
		
		tooltip.addSectionHeading("战备衰减", Alignment.MID, opad);
		
		tooltip.addPara("该舰峰值时间耗尽后，若未安装该插件或没有同类型增/减益效果，会在 %s 秒内"
				+ "损失总计 %s 的战备值 (CR)。", opad,
				Misc.getHighlightColor(),
				"" + (int) Math.round(secondsPerDeplomentCR),
				"" + (int) Math.round(crPerDep * 100f) + "%");
		
		float crLossPerSecond = stats.getCRLossPerSecondPercent().computeEffective(decay);
		float seconds = (crPerDep * 100f) / crLossPerSecond;
		
		tooltip.addPara("若统计所有同类型增/减益效果，这个时间为 %s 秒。",
				opad, Misc.getHighlightColor(),
				"" + (int) Math.round(seconds));
		
		
		
	}


	public String getUnapplicableReason(ShipAPI ship) {
		return "该舰的战备值 (CR) 不会衰减";
	}
}


