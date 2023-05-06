package data.missions.coralnebula;

import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public class MissionDefinition implements MissionDefinitionPlugin {

	public void defineMission(MissionDefinitionAPI api) {

		// Set up the fleets
		api.initFleet(FleetSide.PLAYER, "PLS", FleetGoal.ATTACK, false, 8);
		api.initFleet(FleetSide.ENEMY, "LSS", FleetGoal.ATTACK, true, 5);

		// Set a blurb for each fleet
		api.setFleetTagline(FleetSide.PLAYER, "由海军都统 Kato 所率领的 英仙座联盟 特遣队");
		api.setFleetTagline(FleetSide.ENEMY, "第三神圣舰队支队和当地非正规军");
		
		// These show up as items in the bulleted list under 
		// "Tactical Objectives" on the mission detail screen
		api.addBriefingItem("你的鱼雷轰炸机是胜利的关键所在");
		api.addBriefingItem("星体-级航母 Sirocco 的作用是确保战机联队持续作战的重要支柱, 请务必照顾好它");
		api.addBriefingItem("击败所有敌舰");
		
		// Set up the player's fleet
		api.addToFleet(FleetSide.PLAYER, "astral_Strike", FleetMemberType.SHIP, "PLS Sirocco", true);
		api.addToFleet(FleetSide.PLAYER, "aurora_Balanced", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "medusa_PD", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "omen_PD", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "omen_PD", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "eagle_Balanced", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "falcon_Attack", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "vigilance_FS", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "vigilance_AP", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hound_luddic_church_Standard", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hound_luddic_church_Standard", FleetMemberType.SHIP, false);
		

		// Set up the enemy fleet
		api.addToFleet(FleetSide.ENEMY, "onslaught_Standard", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "dominator_Assault", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "dominator_Support", FleetMemberType.SHIP, false, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.ENEMY, "dominator_Support", FleetMemberType.SHIP, false, CrewXPLevel.VETERAN);
		//api.addToFleet(FleetSide.ENEMY, "enforcer_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "enforcer_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "enforcer_CS", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "enforcer_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "condor_Strike", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "lasher_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "lasher_luddic_path_Raider", FleetMemberType.SHIP, "Keeper of the Flock", false);
		api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "hound_luddic_church_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "hound_luddic_church_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "hound_luddic_path_Attack", FleetMemberType.SHIP, "Ludd's Left Shoe", false);
		api.addToFleet(FleetSide.ENEMY, "kite_luddic_path_Raider", FleetMemberType.SHIP, "Wages of Sin", false);
		api.addToFleet(FleetSide.ENEMY, "kite_luddic_path_Strike", FleetMemberType.SHIP, "Memory of Light", false);
		
		//api.addToFleet(FleetSide.ENEMY, "talon_wing", FleetMemberType.FIGHTER_WING, false);
		//api.addToFleet(FleetSide.ENEMY, "talon_wing", FleetMemberType.FIGHTER_WING, false);
		//api.addToFleet(FleetSide.ENEMY, "broadsword_wing", FleetMemberType.FIGHTER_WING, false);
		//api.addToFleet(FleetSide.ENEMY, "broadsword_wing", FleetMemberType.FIGHTER_WING, false);
		//api.addToFleet(FleetSide.ENEMY, "piranha_wing", FleetMemberType.FIGHTER_WING, false);
		
		
		// Set up the map.
		float width = 24000f;
		float height = 18000f;
		api.initMap((float)-width/2f, (float)width/2f, (float)-height/2f, (float)height/2f);
		
		float minX = -width/2;
		float minY = -height/2;
		
		for (int i = 0; i < 25; i++) {
			float x = (float) Math.random() * width - width/2;
			float y = (float) Math.random() * height - height/2;
			float radius = 1000f + (float) Math.random() * 1000f; 
			api.addNebula(x, y, radius);
		}
		
		api.addNebula(minX + width * 0.8f - 2000, minY + height * 0.4f, 2000);
		api.addNebula(minX + width * 0.8f - 2000, minY + height * 0.5f, 2000);
		api.addNebula(minX + width * 0.8f - 2000, minY + height * 0.6f, 2000);
		
		api.addObjective(minX + width * 0.15f + 3000, minY + height * 0.3f + 1000, "nav_buoy");
		api.addObjective(minX + width * 0.4f + 1000, minY + height * 0.4f, "sensor_array");
		api.addObjective(minX + width * 0.8f - 2000, minY + height * 0.3f + 1000, "comm_relay");
		
		api.addObjective(minX + width * 0.85f - 3000, minY + height * 0.7f - 1000, "nav_buoy");
		api.addObjective(minX + width * 0.6f - 1000, minY + height * 0.6f, "sensor_array");
		api.addObjective(minX + width * 0.2f + 2000, minY + height * 0.7f - 1000, "comm_relay");
		
		api.addAsteroidField(minX, minY + height * 0.5f, 0, height,
							20f, 70f, 50);
		
		//api.addPlanet(0, 0, 350f, "barren", 200f, true);
	}

}






