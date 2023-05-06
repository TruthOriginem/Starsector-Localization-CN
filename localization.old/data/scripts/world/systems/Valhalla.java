package data.scripts.world.systems;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.OrbitAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.StarTypes;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator.StarSystemType;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;
import com.fs.starfarer.api.util.Misc;

public class Valhalla {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Valhalla");
		system.setType(StarSystemType.BINARY_FAR);
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background5.jpg");
		
		SectorEntityToken valhalla_nebula = Misc.addNebulaFromPNG("data/campaign/terrain/valhalla_nebula.png",
				  0, 0, // center of nebula
				  system, // location to add to
				  "terrain", "nebula", //"nebula_blue", // texture to use, uses xxx_map for map
				  4, 4, StarAge.AVERAGE); // number of cells in texture
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI star = system.initStar("valhalla",
										 "star_orange", // id in planets.json
										 650f, 		// radius (in pixels at default zoom)
										 500); // corona radius, from star edge
		system.setLightColor(new Color(255, 230, 220)); // light color in entire system, affects all entities
		
		// Or: Grimnir   / The Einherjar / Heidrun / Eikthyrnir ?
		PlanetAPI val1 = system.addPlanet("glasnir", star, "Glasnir", "barren", 0, 90, 1800, 100);
		
		/* The Valkyries asteroid belt - some notable large ones? */ 
		system.addRingBand(star, "misc", "rings_asteroids0", 256f, 1, Color.white, 256f, 3100, 190f, null, null);
		system.addAsteroidBelt(star, 100, 3150, 256, 150, 250, Terrain.ASTEROID_BELT, "Valkyries 小行星带");
		
			// Valkyrie jump
			JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("valkyrie_jump", " Valkyrie 跳跃点");
			OrbitAPI orbit = Global.getFactory().createCircularOrbit(star, 180, 3325, 195);
			jumpPoint.setOrbit(orbit);
			jumpPoint.setStandardWormholeToHyperspaceVisual();
			system.addEntity(jumpPoint);
		
		system.addRingBand(star, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 3450, 200f, null, null);
		system.addAsteroidBelt(star, 100, 3350, 256, 150, 250, Terrain.ASTEROID_BELT, "Valkyries 小行星带");
		
	// Yggdrasil, largest gas giant in the system.
		PlanetAPI val2 = system.addPlanet("yggdrasil", star, "Yggdrasil", "gas_giant", 230, 350, 6000, 250);
		val2.getSpec().setPlanetColor(new Color(110,255,135,255));
		val2.applySpecChanges();
		
			PlanetAPI val2a = system.addPlanet("nidhogg", val2, "Nidhogg", "lava_minor", 40, 40, 700, 22);
			val2a.setCustomDescriptionId("planet_nidhogg");
			
			system.addRingBand(val2, "misc", "rings_dust0", 256f, 3, Color.white, 256f, 1100, 40f, Terrain.RING, null);
			
			PlanetAPI val2b = system.addPlanet("ratatosk", val2, "Ratatosk", "barren-bombarded", 50, 80, 1400, 45);
			val2b.setCustomDescriptionId("planet_ratatosk");
			val2b.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren03"));
//			val2b.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "asharu"));
//			val2b.getSpec().setGlowColor(new Color(255,255,255,255));
//			val2b.getSpec().setUseReverseLightForGlow(true);
			val2b.applySpecChanges();
			//val2b.setInteractionImage("illustrations", "industrial_megafacility");
			
			PlanetAPI val2c = system.addPlanet("raesvelg", val2, "Raesvelg", "frozen", 50, 70, 1700, 40);
			val2c.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
			val2c.getSpec().setGlowColor(new Color(0,125,255,64));
			val2c.getSpec().setUseReverseLightForGlow(true);
			val2c.applySpecChanges();
			val2c.setInteractionImage("illustrations", "industrial_megafacility");
			val2c.setCustomDescriptionId("planet_raesvelg");
			//val2c.setInteractionImage("illustrations", "vacuum_colony");
			
			
			
				// Yggdrasil trojans
				SectorEntityToken yggdrasilL4 = system.addTerrain(Terrain.ASTEROID_FIELD,
						new AsteroidFieldParams(
							500f, // min radius
							700f, // max radius
							20, // min asteroid count
							30, // max asteroid count
							4f, // min asteroid radius 
							16f, // max asteroid radius
							"Yggdrasil L4 小行星带")); // null for default name
				
				SectorEntityToken yggdrasilL5 = system.addTerrain(Terrain.ASTEROID_FIELD,
						new AsteroidFieldParams(
							500f, // min radius
							700f, // max radius
							20, // min asteroid count
							30, // max asteroid count
							4f, // min asteroid radius 
							16f, // max asteroid radius
							"Yggdrasil L5 小行星带")); // null for default name
				
				yggdrasilL4.setCircularOrbit(star, 230 - 60, 6000, 250);
				yggdrasilL5.setCircularOrbit(star, 230 + 60, 6000, 250);
		
		
			// Yggdrasil loc 
			SectorEntityToken yggdrasil_location = system.addCustomEntity(null,null, "stable_location",Factions.NEUTRAL); 
			yggdrasil_location.setCircularOrbitPointingDown( star, 230 - 180, 6000, 250);		
				
				
				
	// Niflheim
		PlanetAPI val3 = system.addPlanet("niflheim", star, "Niflheim", "ice_giant", 230, 250, 9000, 450);
		
//		SectorEntityToken mimir_station = system.addOrbitalStation("mimir_platform", val3, 45, 500, 50, "Mimir Siphon Platform", "tritachyon");
//		mimir_station.setCustomDescriptionId("station_mimir");
		
		SectorEntityToken mimir_station = system.addCustomEntity("mimir_platform", "Mimir 虹吸作业平台", "station_side05", "tritachyon");
		mimir_station.setCircularOrbitPointingDown(system.getEntityById("niflheim"), 45, 500, 50);		
		mimir_station.setCustomDescriptionId("station_mimir");
//		initStationCargo(mimir_station);
		
		// Niflheim Nav Buoy 
		SectorEntityToken niflheim_location = system.addCustomEntity(null,null, "nav_buoy_makeshift",Factions.TRITACHYON); 
		niflheim_location.setCircularOrbitPointingDown( val3, 45 + 180, 520, 50);
		
		PlanetAPI val3a = system.addPlanet("skathi", val3, "Skathi", "frozen", 45, 70, 620, 50);
		val3a.setCustomDescriptionId("planet_skathi");
			val3a.setInteractionImage("illustrations", "cargo_loading");
			
		
			
			
		
	// Red Dwarf star Ragnar & friends
	// orbited by station, relay, and asteroid field each 1/3rd orbit off from one another.
		PlanetAPI val4 = system.addPlanet("ragnar", star, "Ragnar", StarTypes.RED_DWARF, 45, 350, 14000, 1000);
		system.setSecondary(val4);
		//val4.setCustomDescriptionId("star_red_dwarf");
		system.addCorona(val4, 150, 2f, 0f, 1f);
		
		// Ragnar Complex
			SectorEntityToken ragnar_station = system.addCustomEntity("ragnar_complex", "Ragnar 综合设施", "station_side02", "hegemony");
			ragnar_station.setCircularOrbitPointingDown(system.getEntityById("ragnar"), 45, 1400, 50);		
			ragnar_station.setCustomDescriptionId("station_ragnar");
			ragnar_station.setInteractionImage("illustrations", "hound_hangar");
			
		// Ragnar Relay
			SectorEntityToken relay = system.addCustomEntity("ragnar_relay", // unique id
					 "Ragnar 通讯中继站", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay", // type of object, defined in custom_entities.json
					 "hegemony"); // faction
			relay.setCircularOrbitPointingDown(system.getEntityById("ragnar"), 45 + 120, 1400, 50);
		
		// The Vipers (asteroid field)
			
			SectorEntityToken ragnar_asteroid_field = system.addTerrain(Terrain.ASTEROID_FIELD,
			new AsteroidFieldParams(
				500f, // min radius
				700f, // max radius
				25, // min asteroid count
				40, // max asteroid count
				4f, // min asteroid radius 
				16f, // max asteroid radius
				"Vipers 小行星带")); // null for default name
			
			ragnar_asteroid_field.setCircularOrbit(system.getEntityById("ragnar"), 45 + 240, 1400, 50);
				
		system.addRingBand(val4, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 2000, 250f, null, null);
		system.addAsteroidBelt(val4, 50, 2000, 256, 200, 300, Terrain.ASTEROID_BELT, null);
			
		JumpPointAPI jumpPoint_ragnar = Global.getFactory().createJumpPoint("ragnar_jump", "Ragnar 跳跃点");
		OrbitAPI orbit2 = Global.getFactory().createCircularOrbit(val4, 180, 2400, 45);
		jumpPoint_ragnar.setOrbit(orbit2);
		jumpPoint_ragnar.setStandardWormholeToHyperspaceVisual();
		system.addEntity(jumpPoint_ragnar);
	
		
		

	
		// example of using custom visuals below
//		a1.setCustomInteractionDialogImageVisual(new InteractionDialogImageVisual("illustrations", "hull_breach", 800, 800));
//		jumpPoint.setCustomInteractionDialogImageVisual(new InteractionDialogImageVisual("illustrations", "space_wreckage", 1200, 1200));
//		station.setCustomInteractionDialogImageVisual(new InteractionDialogImageVisual("illustrations", "cargo_loading", 1200, 1200));
		
		// generates hyperspace destinations for in-system jump points
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
	
}
