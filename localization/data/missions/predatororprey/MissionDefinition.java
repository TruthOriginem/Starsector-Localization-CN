package data.missions.predatororprey;

import java.util.List;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public class MissionDefinition implements MissionDefinitionPlugin {

	public void defineMission(MissionDefinitionAPI api) {

		// Set up the fleets
		api.initFleet(FleetSide.PLAYER, "HSS", FleetGoal.ATTACK, false);
		api.initFleet(FleetSide.ENEMY, "TTS", FleetGoal.ATTACK, true, 5);

		// Set a blurb for each fleet
		api.setFleetTagline(FleetSide.PLAYER, "霸主 巡逻队");
		api.setFleetTagline(FleetSide.ENEMY, "速子科技 航母支队");
		
		// These show up as items in the bulleted list under 
		// "Tactical Objectives" on the mission detail screen
		api.addBriefingItem("击败所有敌舰");
		api.addBriefingItem("击退敌方战机联队将帮助你围攻敌军航母");
		api.addBriefingItem("在你推进时, 需时刻把握鱼雷来袭的节奏");
		
		// Set up the player's fleet
		//api.addToFleet(FleetSide.PLAYER, "dominator_Assault", FleetMemberType.SHIP, "HSS Shogun", true);
		api.addToFleet(FleetSide.PLAYER, "dominator_AntiCV", FleetMemberType.SHIP, "HSS Shogun", true);
		
		//api.addToFleet(FleetSide.PLAYER, "falcon_xiv_Escort", FleetMemberType.SHIP, "HSS Wyrm", false, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.PLAYER, "falcon_Attack", FleetMemberType.SHIP, "HSS Wyrm", false, CrewXPLevel.VETERAN);
		api.addToFleet(FleetSide.PLAYER, "lasher_CS", FleetMemberType.SHIP, "HSS Wyrm", false);
		api.addToFleet(FleetSide.PLAYER, "condor_Support", FleetMemberType.SHIP, "HSS Qulla", false);
		//api.addToFleet(FleetSide.PLAYER, "hound_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "hound_hegemony_Standard", FleetMemberType.SHIP, "HSS Jake", false);
		//api.addToFleet(FleetSide.PLAYER, "wolf_hegemony_CS", FleetMemberType.SHIP, "HSS Boxer", false, CrewXPLevel.REGULAR);
		//api.addToFleet(FleetSide.PLAYER, "broadsword_wing", FleetMemberType.FIGHTER_WING, false);
		
		// Set up the enemy fleet
		api.addToFleet(FleetSide.ENEMY, "astral_Strike", FleetMemberType.SHIP, "TTS Ephemeral", false);
		//api.addToFleet(FleetSide.ENEMY, "omen_PD", FleetMemberType.SHIP, "TTS Cassandra", false);
		
		// Set up the map.
		float width = 24000f;
		float height = 18000f;
		api.initMap((float)-width/2f, (float)width/2f, (float)-height/2f, (float)height/2f);
		
		float minX = -width/2;
		float minY = -height/2;
		
		for (int i = 0; i < 300; i++) {
			float x = (float) Math.random() * width - width/2;
			float y = (float) Math.random() * height - height/4;
			
			if (x > -1000 && x < 1500 && y < -1000) continue;
			float radius = 200f + (float) Math.random() * 900f; 
			api.addNebula(x, y, radius);
		}
		
		
		api.addObjective(minX + width * 0.7f - 3000, minY + height * 0.65f, "nav_buoy");
		api.addObjective(minX + width * 0.5f, minY + height * 0.35f + 2000, "nav_buoy");
		api.addObjective(minX + width * 0.2f + 3000, minY + height * 0.6f, "sensor_array");
		
		api.addPlugin(new BaseEveryFrameCombatPlugin() {
			public void init(CombatEngineAPI engine) {
				engine.getContext().setStandoffRange(12000f);
			}
			public void advance(float amount, List events) {
			}
		});
			
	}

}






