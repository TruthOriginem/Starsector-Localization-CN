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
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain;

public class Aztlan {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Aztlan");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg");
		
		//system.getMemoryWithoutUpdate().set(MusicPlayerPluginImpl.MUSIC_SET_MEM_KEY, "music_combat");
		
		PlanetAPI aztlan_star = system.initStar("aztlan", // unique id for this star 
										    "star_yellow",  // id in planets.json
										    700f, 		  // radius (in pixels at default zoom)
										    500); // corona radius, from star edge
		
		system.setLightColor(new Color(255, 220, 190)); // light color in entire system, affects all entities
		
		PlanetAPI aztlan1 = system.addPlanet("xolotl", aztlan_star, "Xolotl", "toxic", 30, 150, 2200, 90);
		
		
		system.addRingBand(aztlan_star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 3100, 70f, null, null);
		system.addAsteroidBelt(aztlan_star, 150, 3100, 128, 60, 80, Terrain.ASTEROID_BELT, "The Ciltetl");
		
	// Chicomoztoc & friends
		PlanetAPI aztlan2 = system.addPlanet("chicomoztoc", aztlan_star, "Chicomoztoc", "barren-desert", 60, 200, 4200, 300);
		aztlan2.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "sindria"));
		aztlan2.getSpec().setGlowColor(new Color(255,128,16,255));
		aztlan2.getSpec().setUseReverseLightForGlow(true);
		aztlan2.applySpecChanges();
		aztlan2.setCustomDescriptionId("planet_chicomoztoc");	
		aztlan2.setInteractionImage("illustrations", "urban03");
		
			//system.addRingBand(aztlan2, "misc", "rings1", 256f, 3, Color.white, 256f, 500, 30f);
			system.addRingBand(aztlan2, "misc", "rings_dust0", 256f, 3, Color.white, 256f, 500, 33f, Terrain.RING, null);
			
			SectorEntityToken hegemonyStation = system.addCustomEntity("aztlan_starport", "Aztlan Starport", "station_lowtech2", "hegemony");
			hegemonyStation.setCircularOrbitPointingDown(system.getEntityById("chicomoztoc"), 0, 350, 30);		
			hegemonyStation.setInteractionImage("illustrations", "orbital");
			//hegemonyStation.setCustomDescriptionId("station_jangala");
		
			// Sensor Array in far orbit of Chicomoztoc
			SectorEntityToken aztlan_loc = system.addCustomEntity(null, null, "sensor_array", Factions.HEGEMONY);
			aztlan_loc.setCircularOrbitPointingDown(aztlan_star, 240, 4200, 300);
			
			JumpPointAPI jumpPoint_aztlan = Global.getFactory().createJumpPoint("aztlan_jump_point_alpha", "Aztlan Inner System Jump-point");
			OrbitAPI orbit = Global.getFactory().createCircularOrbit(aztlan_star, 0, 4200, 300);
			jumpPoint_aztlan.setOrbit(orbit);
			jumpPoint_aztlan.setRelatedPlanet(aztlan2);
			jumpPoint_aztlan.setStandardWormholeToHyperspaceVisual();
			system.addEntity(jumpPoint_aztlan);
			
			// Aztlan Gate
			SectorEntityToken gate = system.addCustomEntity("aztlan_gate", // unique id
					 "Aztlan Gate", // name - if null, defaultName from custom_entities.json will be used
					 "inactive_gate", // type of object, defined in custom_entities.json
					 null); // faction
			gate.setCircularOrbit(system.getEntityById("aztlan"), 120, 4200, 300);
		
	// Tlalocan System
		PlanetAPI aztlan3 = system.addPlanet("tlalocan", aztlan_star, "Tlalocan", "ice_giant", 130, 290, 7500, 500);
		aztlan3.getSpec().setPlanetColor(new Color(255,210,170,255));
		aztlan3.getSpec().setPitch(20f);
		aztlan3.getSpec().setTilt(10f);
		aztlan3.applySpecChanges();
		
			// rocky ring system for Tlalocan
			system.addAsteroidBelt(aztlan3, 50, 650, 100, 30, 40, Terrain.ASTEROID_BELT, null);
			system.addRingBand(aztlan3, "misc", "rings_ice0", 256f, 2, Color.white, 256f, 630, 43f, Terrain.RING, null);
			
			PlanetAPI aztlan3a = system.addPlanet("coatl", aztlan3, "Coatl", "barren-bombarded", 30, 90, 1050, 25);
			//aztlan2a.setCustomDescriptionId("planet_volturn");
			aztlan3a.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "barren"));
			aztlan3a.getSpec().setGlowColor(new Color(200,225,255,255));
			aztlan3a.getSpec().setUseReverseLightForGlow(true);
			aztlan3a.applySpecChanges();
			aztlan3a.setCustomDescriptionId("planet_coatl");
			
			SectorEntityToken coatl_station = system.addCustomEntity("coatl_station", "Coatl Bastion", "station_side02", "hegemony");
			coatl_station.setCircularOrbitPointingDown(system.getEntityById("coatl"), 45, 200, 20);		
			coatl_station.setCustomDescriptionId("station_coatl");
			coatl_station.setInteractionImage("illustrations", "hound_hangar");
			
			
			PlanetAPI aztlan3b = system.addPlanet("zorrah", aztlan3, "Zorrah", "rocky_ice", 30, 50, 1400, 60);
			aztlan3b.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "asharu"));
			aztlan3b.getSpec().setGlowColor(new Color(255,205,128,255));
			aztlan3b.getSpec().setUseReverseLightForGlow(true);
			aztlan3b.applySpecChanges();
			aztlan3b.setCustomDescriptionId("planet_zorrah");
			
			SectorEntityToken relay = system.addCustomEntity("aztlan_relay", // unique id
					 "Aztlan Relay", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay", // type of object, defined in custom_entities.json
					 "hegemony"); // faction
	
			relay.setCircularOrbitPointingDown( system.getEntityById("tlalocan"), 150, 1900, 180);
			relay.addTag(Tags.STORY_CRITICAL); // used by GAKallichore
		
		
		// L4 & L5 mini-nebulas
		SectorEntityToken tlalocan_L4_nebula = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
				"  x   " +
				"  xx x" +
				"xxxxx " +
				" xxx  " +
				" x  x " +
				"   x  ",
				6, 6, // size of the nebula grid, should match above string
				"terrain", "nebula", 4, 4, null));
		
		SectorEntityToken tlalocan_L5_nebula = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
				"  x   " +
				" xx xx" +
				"x  xx " +
				" xxxx " +
				" x x x" +
				"  x   ",
				6, 6, // size of the nebula grid, should match above string
				"terrain", "nebula", 4, 4, null));
		
		tlalocan_L5_nebula.setCircularOrbit(aztlan_star, 130 - 60, 7500, 500);
		tlalocan_L4_nebula.setCircularOrbit(aztlan_star, 130 + 60, 7500, 500);
		
		// and a jump point hidden in Tlalocan's L5.
		JumpPointAPI jumpPoint_aztlan2 = Global.getFactory().createJumpPoint("aztlan_jump_point_beta", "Aztlan Outer System Jump-point");
		OrbitAPI orbit2 = Global.getFactory().createCircularOrbit(aztlan_star, 130 + 60, 7500, 500);
		jumpPoint_aztlan2.setOrbit(orbit2);
		//jumpPoint_aztlan2.setRelatedPlanet(aztlan3);
		jumpPoint_aztlan2.setStandardWormholeToHyperspaceVisual();
		system.addEntity(jumpPoint_aztlan2);
		
		
		// Loc in counter-orbit to Tlalocan
		SectorEntityToken tlalocan_loc = system.addCustomEntity(null, null, "stable_location", Factions.NEUTRAL);
		tlalocan_loc.setCircularOrbitPointingDown(aztlan_star, 130 + 180 , 7500, 500);
		
		//PlanetAPI aztlan4 = system.addPlanet("toci", aztlan_star, "Toci", "frozen", 30, 80, 13000, 900);

		// example of using custom visuals below
//		a1.setCustomInteractionDialogImageVisual(new InteractionDialogImageVisual("illustrations", "hull_breach", 800, 800));
//		jumpPoint.setCustomInteractionDialogImageVisual(new InteractionDialogImageVisual("illustrations", "space_wreckage", 1200, 1200));
//		station.setCustomInteractionDialogImageVisual(new InteractionDialogImageVisual("illustrations", "cargo_loading", 1200, 1200));
		
		// generates hyperspace destinations for in-system jump points
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, aztlan_star, StarAge.OLD,
				2, 4, // min/max entities to add
				10000, // radius to start adding at 
				3, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds
		
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
