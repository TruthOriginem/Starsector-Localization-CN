package data.missions.m2;

import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public class MissionDefinition implements MissionDefinitionPlugin {

	public void defineMission(MissionDefinitionAPI api) {
		
		api.initFleet(FleetSide.PLAYER, "ISS", FleetGoal.ESCAPE, false);
		api.initFleet(FleetSide.ENEMY, "ISS", FleetGoal.ATTACK, true);

		api.addToFleet(FleetSide.PLAYER, "astral_Strike", FleetMemberType.SHIP, true);

		api.addToFleet(FleetSide.PLAYER, "dagger_wing", FleetMemberType.FIGHTER_WING, false);
		api.addToFleet(FleetSide.PLAYER, "dagger_wing", FleetMemberType.FIGHTER_WING, false);
		api.addToFleet(FleetSide.PLAYER, "dagger_wing", FleetMemberType.FIGHTER_WING, false);
		api.addToFleet(FleetSide.PLAYER, "longbow_wing", FleetMemberType.FIGHTER_WING, false);
		api.addToFleet(FleetSide.PLAYER, "longbow_wing", FleetMemberType.FIGHTER_WING, false);
		api.addToFleet(FleetSide.PLAYER, "longbow_wing", FleetMemberType.FIGHTER_WING, false);
 		
		
//		api.addToFleet(FleetSide.PLAYER, "onslaught7", FleetMemberType.SHIP, true);
//		api.addToFleet(FleetSide.PLAYER, "gemini1", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "buffalo1", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "buffalo1", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "atlas1", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "xyphos_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.PLAYER, "xyphos_wing", FleetMemberType.FIGHTER_WING, false);
		
		
		
		api.addToFleet(FleetSide.ENEMY, "onslaught_Outdated", FleetMemberType.SHIP, false);
		
//		api.addToFleet(FleetSide.ENEMY, "astral1", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.ENEMY, "falcon2", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.ENEMY, "sunder1", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.ENEMY, "sunder1", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.ENEMY, "sunder1", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.ENEMY, "sunder1", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.ENEMY, "wolf_CS", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.ENEMY, "wolf_CS", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.ENEMY, "wolf_CS", FleetMemberType.SHIP, false);
//		
//		api.addToFleet(FleetSide.ENEMY, "dagger_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "dagger_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "wasp_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "wasp_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "wasp_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "xyphos_wing", FleetMemberType.FIGHTER_WING, false);
//		api.addToFleet(FleetSide.ENEMY, "xyphos_wing", FleetMemberType.FIGHTER_WING, false);
		
		
		float width = 6000f;
		float height = 6000f;
		api.initMap((float)-width/2f, (float)width/2f, (float)-height/2f, (float)height/2f);
		
		for (int i = 0; i < 5; i++) {
			float x = (float) Math.random() * width - width/2;
			float y = (float) Math.random() * height - height/2;
			float radius = 100f + (float) Math.random() * 1000f; 
			api.addNebula(x, y, radius);
		}
		
		for (float x = -10000f; x < 10000f; x += 500f) {
			float y = x;
			float radius = 400f + (float) Math.random() * 200f; 
			api.addNebula(x, y, radius);
		}
		
		float minX = -width/2;
		float minY = -height/2;
//		api.addObjective((float)minX + width * 0.05f, minY + height * 0.95f, "nav_buoy");
//		api.addObjective((float)minX + width * 0.95f, minY + height * 0.95f, "nav_buoy");
//		api.addObjective((float)minX + width * 0.5f, minY + height * 0.5f, "sensor_array");
//		api.addObjective((float)minX + width * 0.25f, minY + height * 0.7f, "comm_relay");
//		api.addObjective((float)minX + width * 0.25f, minY + height * 0.3f, "nav_buoy");
//		api.addObjective((float)minX + width * 0.75f, minY + height * 0.5f, "comm_relay");
		
		
		api.addAsteroidField(0, 0, 0, 2000f,
								20f, 70f, 100);
		
//		api.addPlanet((float)(minX + width * 0.1f), (float)(height * 0.05f),  (float)320f, "star_yellow", (float) 300f);
//		api.addPlanet((float)(minX + width * 0.75f), (float)(height * 0.07f),  (float)256f, "desert", (float) 250f);
//		api.addPlanet((float)(minX + width * 0.85f), (float)(-height * 0.07f), (float) 96f, "barren", (float) 100f);
	}

}
