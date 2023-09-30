package data.missions.thewolfpack;

import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.combat.EscapeRevealPlugin;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

public class MissionDefinition implements MissionDefinitionPlugin {

	public void defineMission(MissionDefinitionAPI api) {

		// Set up the fleets so we can add ships and fighter wings to them.
		// In this scenario, the fleets are attacking each other, but
		// in other scenarios, a fleet may be defending or trying to escape
		api.initFleet(FleetSide.PLAYER, "ISS", FleetGoal.ATTACK, false, 5);
		api.initFleet(FleetSide.ENEMY, "HSS", FleetGoal.ESCAPE, true, 5);

		// Set a small blurb for each fleet that shows up on the mission detail and
		// mission results screens to identify each side.
		api.setFleetTagline(FleetSide.PLAYER, "参与突袭的佣兵舰队");
		api.setFleetTagline(FleetSide.ENEMY, "霸主 护航舰队与货运商队");
		
		// These show up as items in the bulleted list under 
		// "Tactical Objectives" on the mission detail screen
		api.addBriefingItem("霸主 的舰队将尝试从地图的顶端逃离");
		api.addBriefingItem("控制导航浮标是防止敌方快速撤离的关键");
		api.addBriefingItem("利用 \"全面进攻\" 命令来使你的舰队更积极的搜索并摧毁敌人");
		api.addBriefingItem("利用 \"消灭\" 命令来使你的舰队更具攻击性");
//		api.addBriefingItem("Disable as many enemy ships as you can");
//		api.addBriefingItem("The Deimos must survive");
		
		// Set up the player's fleet.  Variant names come from the
		// files in data/variants and data/variants/fighters
		//api.addToFleet(FleetSide.PLAYER, "medusa_Attack", FleetMemberType.SHIP, "Deimos", true);
		api.addToFleet(FleetSide.PLAYER, "aurora_Balanced", FleetMemberType.SHIP, "Deimos", true);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Strike", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Strike", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Attack", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "hyperion_Attack", FleetMemberType.SHIP, false);
		
		api.addToFleet(FleetSide.PLAYER, "wolf_Strike", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "wolf_Strike", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "wolf_Strike", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "tempest_Attack", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "tempest_Attack", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "sunder_CS", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "sunder_CS", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "vigilance_FS", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "vigilance_FS", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "brawler_Assault", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.PLAYER, "brawler_Assault", FleetMemberType.SHIP, false);
//		api.addToFleet(FleetSide.PLAYER, "brawler_Assault", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.PLAYER, "heron_Strike", FleetMemberType.SHIP, false);
		
		//api.defeatOnShipLoss("Deimos");
		
		// Set up the enemy fleet.
		api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "enforcer_Balanced", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "hound_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "dram_Light", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "tarsus_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "tarsus_Standard", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "tarsus_Standard", FleetMemberType.SHIP, false, CrewXPLevel.GREEN);
		//api.addToFleet(FleetSide.ENEMY, "tarsus_Standard", FleetMemberType.SHIP, false, CrewXPLevel.GREEN);
		api.addToFleet(FleetSide.ENEMY, "buffalo_hegemony_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "buffalo_hegemony_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "buffalo_hegemony_Standard", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "buffalo_hegemony_Standard", FleetMemberType.SHIP, false);
		
		api.addToFleet(FleetSide.ENEMY, "condor_Support", FleetMemberType.SHIP, false);
		api.addToFleet(FleetSide.ENEMY, "condor_Support", FleetMemberType.SHIP, false);
		//api.addToFleet(FleetSide.ENEMY, "condor_Attack", FleetMemberType.SHIP, false);
		
		
		// Set up the map.
		float width = 18000f;
		float height = 24000f;
		api.initMap((float)-width/2f, (float)width/2f, (float)-height/2f, (float)height/2f);
		
		float minX = -width/2;
		float minY = -height/2;
		
		// All the addXXX methods take a pair of coordinates followed by data for
		// whatever object is being added.
		
		// And a few random ones to spice up the playing field.
		// A similar approach can be used to randomize everything
		// else, including fleet composition.
		for (int i = 0; i < 7; i++) {
			float x = (float) Math.random() * width - width/2;
			float y = (float) Math.random() * height - height/2;
			float radius = 100f + (float) Math.random() * 800f; 
			api.addNebula(x, y, radius);
		}
		
		api.addObjective(minX + width * 0.25f, minY + 5500, "nav_buoy");
		api.addObjective(minX + width * 0.75f, minY + 5500, "sensor_array");
		
		
		api.addObjective(minX + width * 0.5f, minY + height * 0.5f, "sensor_array");
		
		api.addObjective(minX + width * 0.3f, minY + height * 0.75f, "comm_relay");
		api.addObjective(minX + width * 0.7f, minY + height * 0.7f, "nav_buoy");
//		api.addObjective(minX + width * 0.7f + 1000, minY + height * 0.25f - 1000, "comm_relay");
		//api.addObjective(minX + width * 0.8f, minY + height * 0.75f, "nav_buoy");
		//api.addObjective(minX + width * 0.2f, minY + height * 0.25f, "nav_buoy");
		
		//api.getContext().setInitialEscapeRange(3500);
		
		BattleCreationContext context = new BattleCreationContext(null, null, null, null);
		context.setInitialEscapeRange(7000f);
		api.addPlugin(new EscapeRevealPlugin(context));
	}
}
