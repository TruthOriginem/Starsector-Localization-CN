package data.missions.thelasthurrah;

import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public class MissionDefinition implements MissionDefinitionPlugin {

	public void defineMission(MissionDefinitionAPI api) {

		// Set up the fleets
		api.initFleet(FleetSide.PLAYER, "MSS", FleetGoal.ATTACK, false, 10);
		api.initFleet(FleetSide.ENEMY, "HSS", FleetGoal.ATTACK, true, 10);

		// Set a blurb for each fleet
		api.setFleetTagline(FleetSide.PLAYER, "Mayasurian 海军和重型战机支援队");
		api.setFleetTagline(FleetSide.ENEMY, "Commodore Jensulte 指挥的 霸主 舰队");
		
		// These show up as items in the bulleted list under 
		// "Tactical Objectives" on the mission detail screen
		api.addBriefingItem("击败所有敌舰");
		api.addBriefingItem("MSS Garuda 必须存活");
		api.addBriefingItem("保持战术意识, 利用机动战术来参与战斗");
		api.addBriefingItem("记住: 如果你想与敌方旗舰正面交锋, 那么你就已经输了");
		
		// Set up the player's fleet
		api.addToFleet(FleetSide.PLAYER, "conquest_Standard", FleetMemberType.SHIP, "MSS Garuda", true);
		api.addToFleet(FleetSide.PLAYER, "eagle_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "falcon_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "falcon_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "heron_Strike", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "heron_Strike", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "hammerhead_Balanced", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "sunder_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "gemini_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "vigilance_FS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "vigilance_FS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "vigilance_FS", FleetMemberType.SHIP, false);
		
//		api.addToFleet(FleetSide.PLAYER, "warthog_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "warthog_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "gladius_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "gladius_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "thunder_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "thunder_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "thunder_wing", FleetMemberType.FIGHTER_WING, false);
		
		// Mark player flagship as essential
		api.defeatOnShipLoss("MSS Garuda");
		
		// Set up the enemy fleet
		api.addToFleet(FleetSide.ENEMY, "onslaught_Standard", FleetMemberType.SHIP, "HSS Naga", true);
//		api.addToFleet(FleetSide.ENEMY, "onslaught_Outdated", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.ENEMY, "onslaught_Outdated", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "mora_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "mora_Strike", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "mora_Strike", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "mora_Support", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "dominator_Support", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "dominator_Support", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "condor_Support", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "condor_Support", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "enforcer_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "enforcer_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "enforcer_CS", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "enforcer_CS", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);
		
//		api.addToFleet(FleetSide.ENEMY, "talon_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "talon_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "talon_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "talon_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "broadsword_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "broadsword_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "broadsword_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "broadsword_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "piranha_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "piranha_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "piranha_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "piranha_wing", FleetMemberType.FIGHTER_WING, false);
		
		
		// Set up the map.
		float width = 24000f;
		float height = 18000f;
		api.initMap((float)-width/2f, (float)width/2f, (float)-height/2f, (float)height/2f);
		
		float minX = -width/2;
		float minY = -height/2;
		
		for (int i = 0; i < 15; i++) {
			float x = (float) Math.random() * width - width/2;
			float y = (float) Math.random() * height - height/2;
			float radius = 100f + (float) Math.random() * 900f; 
			api.addNebula(x, y, radius);
		}
		
		api.addNebula(minX + width * 0.8f - 1000, minY + height * 0.4f, 2000);
		api.addNebula(minX + width * 0.8f - 1000, minY + height * 0.5f, 2000);
		api.addNebula(minX + width * 0.8f - 1000, minY + height * 0.6f, 2000);
		
		api.addObjective(minX + width * 0.8f - 1000, minY + height * 0.4f, "nav_buoy");
		api.addObjective(minX + width * 0.8f - 1000, minY + height * 0.6f, "nav_buoy");
		api.addObjective(minX + width * 0.3f + 1000, minY + height * 0.3f, "comm_relay");
		api.addObjective(minX + width * 0.3f + 1000, minY + height * 0.7f, "comm_relay");
		api.addObjective(minX + width * 0.5f, minY + height * 0.5f, "sensor_array");
		api.addObjective(minX + width * 0.2f + 1000, minY + height * 0.5f, "sensor_array");
		
		// Add an asteroid field
		api.addAsteroidField(minX + width * 0.3f, minY, 90, 3000f,
								20f, 70f, 50);
		
		// Add some planets.  These are defined in data/config/planets.json.
		api.addPlanet(0, 0, 200f, "irradiated", 350f, true);
	}

}






