package data.missions.hornetsnest;

import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public class MissionDefinition implements MissionDefinitionPlugin {

	public void defineMission(MissionDefinitionAPI api) {

		// Set up the fleets
		api.initFleet(FleetSide.PLAYER, "ISS", FleetGoal.ATTACK, false, 2);
		api.initFleet(FleetSide.ENEMY, "", FleetGoal.ATTACK, true, 3);

		// Set a blurb for each fleet
		api.setFleetTagline(FleetSide.PLAYER, "ISS Van Rijn 和打捞舰队");
		api.setFleetTagline(FleetSide.ENEMY, "Kanta 海盗联军");
		
		// These show up as items in the bulleted list under 
		// "Tactical Objectives" on the mission detail screen
		api.addBriefingItem("击败所有敌舰");
		//api.addBriefingItem("ISS Van Rijn must survive");
		api.addBriefingItem("珍惜你有限的命令点数");
		api.addBriefingItem("集中你的优势兵力来打击主要目标");
		
		// Set up the player's fleet
		api.addToFleet(FleetSide.PLAYER, "apogee_Balanced", FleetMemberType.SHIP, "ISS Van Rijn", true);
		//api.addToFleet(FleetSide.PLAYER, "doom_Strike", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
//		api.addToFleet(FleetSide.PLAYER, "paragon_Elite", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.PLAYER, "odyssey_Balanced", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.PLAYER, "conquest_Elite", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.PLAYER, "hyperion_Attack", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.PLAYER, "eagle_Assault", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.PLAYER, "dominator_Support", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.PLAYER, "falcon_Attack", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.PLAYER, "sunder_CS", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
//		api.addToFleet(FleetSide.PLAYER, "gemini_Standard", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "gemini_Standard", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "gemini_Standard", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "monitor_Escort", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "cerberus_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "gemini_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "heron_Attack", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "heron_Strike", FleetMemberType.SHIP, false);
		
		api.addToFleet(FleetSide.PLAYER, "medusa_PD", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "tempest_Attack", FleetMemberType.SHIP, false, CrewXPLevel.VETERAN);
		api.addToFleet(FleetSide.PLAYER, "tempest_Attack", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "tempest_Attack", FleetMemberType.SHIP, false, CrewXPLevel.VETERAN);
		api.addToFleet(FleetSide.PLAYER, "wolf_CS", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "wolf_CS", FleetMemberType.SHIP, false, CrewXPLevel.ELITE);
		//api.addToFleet(FleetSide.PLAYER, "wolf_CS", FleetMemberType.SHIP, false, CrewXPLevel.ELITE);
		//api.addToFleet(FleetSide.PLAYER, "omen_PD", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "wolf_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "wolf_CS", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "xyphos_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "longbow_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "longbow_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "wasp_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "wasp_wing", FleetMemberType.FIGHTER_WING, false);
		
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Attack", FleetMemberType.SHIP, "ISS Van Rijn", true, CrewXPLevel.VETERAN);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Attack", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Attack", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Attack", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Attack", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Strike", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Strike", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Strike", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Strike", FleetMemberType.SHIP, false);
		// Mark flagship as essential
		//api.defeatOnShipLoss("ISS Van Rijn");
		
		// Set up the enemy fleet
		//api.addToFleet(FleetSide.ENEMY, "venture_Balanced", FleetMemberType.SHIP, false, CrewXPLevel.VETERAN);
		
		FleetMemberAPI fleetMember;
		fleetMember = api.addToFleet(FleetSide.ENEMY, "dominator_Outdated", FleetMemberType.SHIP, "Rhinoceros", false);
		fleetMember.getCaptain().setPersonality("aggressive");
		
		api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, "Bully", false);
		api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, "Bruiser", false);
		
		api.addToFleet(FleetSide.ENEMY, "condor_Support", FleetMemberType.SHIP, "Nidus", false);
		//api.addToFleet(FleetSide.ENEMY, "condor_Support", FleetMemberType.SHIP, "Nidus", false);
		api.addToFleet(FleetSide.ENEMY, "condor_Strike", FleetMemberType.SHIP, "Nexus", false);
		api.addToFleet(FleetSide.ENEMY, "condor_Strike", FleetMemberType.SHIP, "Eyrie", false);
		//api.addToFleet(FleetSide.ENEMY, "condor_Attack", FleetMemberType.SHIP, "Nidus", false);
		api.addToFleet(FleetSide.ENEMY, "condor_Attack", FleetMemberType.SHIP, "Nidus", false);
		api.addToFleet(FleetSide.ENEMY, "hammerhead_Balanced", FleetMemberType.SHIP, "ISS Argentum", false);
		api.addToFleet(FleetSide.ENEMY, "buffalo2_FS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "buffalo2_FS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, "Rex", false);
		api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, "Spot", false);
		api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, "Lucky", false);
		api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, "Rusty", false);
		api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, "Overseer", false);
		api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, "Taskmaster", false);
		
//		api.addToFleet(FleetSide.ENEMY, "mining_drone_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "mining_drone_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "piranha_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "piranha_wing", FleetMemberType.FIGHTER_WING, false);
		//api.addToFleet(FleetSide.ENEMY, "broadsword_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "broadsword_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "talon_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "talon_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "talon_wing", FleetMemberType.FIGHTER_WING, false);
		
		
		// Set up the map.
		float width = 24000f;
		float height = 18000f;
		api.initMap((float)-width/2f, (float)width/2f, (float)-height/2f, (float)height/2f);
		
		float minX = -width/2;
		float minY = -height/2;
		
		// All the addXXX methods take a pair of coordinates followed by data for
		// whatever object is being added.
		
		// Add two big nebula clouds
		api.addNebula(minX + width * 0.66f, minY + height * 0.5f, 2000);
		api.addNebula(minX + width * 0.25f, minY + height * 0.6f, 1000);
		api.addNebula(minX + width * 0.25f, minY + height * 0.4f, 1000);
		
		// And a few random ones to spice up the playing field.
		for (int i = 0; i < 5; i++) {
			float x = (float) Math.random() * width - width/2;
			float y = (float) Math.random() * height - height/2;
			float radius = 100f + (float) Math.random() * 400f; 
			api.addNebula(x, y, radius);
		}
		
		// add objectives
		api.addObjective(minX + width * 0.25f + 2000f, minY + height * 0.5f, 
						 "sensor_array");
		api.addObjective(minX + width * 0.75f - 2000f, minY + height * 0.5f,
						 "comm_relay");
		api.addObjective(minX + width * 0.33f + 2000f, minY + height * 0.4f, 
						 "nav_buoy");
		api.addObjective(minX + width * 0.66f - 2000f, minY + height * 0.6f, 
						 "nav_buoy");
		

		api.addAsteroidField(-(minY + height), minY + height, -45, 2000f,
								20f, 70f, 100);
		
		api.addPlanet(0, 0, 400f, "barren", 200f, true);
		api.addRingAsteroids(0,0, 30, 32, 32, 48, 200);
	}

}






