package data.scripts.world.corvus;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain;
import com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin.MagneticFieldParams;
import com.fs.starfarer.api.util.Misc;

@SuppressWarnings("unchecked")
public class Corvus { // implements SectorGeneratorPlugin {

	public void generate(SectorAPI sector) {
		final StarSystemAPI system = sector.getStarSystem("Corvus");

		system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg");
		
		PlanetAPI star = system.initStar("corvus",
				"star_yellow", 
				775f,
				500, // extent of corona outside star
				10f, // solar wind burn level
				1f, // flare probability
				3f); // CR loss multiplier, good values are in the range of 1-5
//		star.getSpec().setPlanetColor(new Color(255, 100, 100));
//		star.getSpec().setAtmosphereColor(new Color(255, 100, 100));
//		star.getSpec().setIconColor(new Color(255, 100, 100));
//		star.getSpec().setCloudColor(new Color(255, 100, 100));
//		star.applySpecChanges();
//		
//		system.setLightColor(new Color(0, 255, 0));
		
//		PlanetAPI corvusXa = system.addPlanet("test1", star, "Anomaly Alpha", "lava_minor", 240, 120, 2500, 120);
//		PlanetAPI corvusXb = system.addPlanet("test2", star, "Anomaly Beta", "lava", 250, 120, 2100, 120);
		
	// Asharu
		PlanetAPI corvusI = system.addPlanet("asharu", star, "Asharu", "desert", 55, 150, 2800, 100);
		corvusI.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "asharu"));
		corvusI.getSpec().setGlowColor(new Color(255,255,255,255));
		corvusI.getSpec().setUseReverseLightForGlow(true);
		corvusI.applySpecChanges();
		corvusI.setCustomDescriptionId("planet_asharu");
		
		SectorEntityToken corvus_loc1 = system.addCustomEntity(null, null, "sensor_array_makeshift", Factions.HEGEMONY);
		corvus_loc1.setCircularOrbitPointingDown(star, 55 + 60, 2800, 100);
		
		// Asharu abandoned station
		SectorEntityToken neutralStation = system.addCustomEntity("corvus_abandoned_station",
				"废弃的地貌改造平台", "station_side06", "neutral");
		
			neutralStation.setCircularOrbitPointingDown(system.getEntityById("asharu"), 45, 300, 30);		
			neutralStation.setCustomDescriptionId("asharu_platform");
			neutralStation.setInteractionImage("illustrations", "abandoned_station2");
			Misc.setAbandonedStationMarket("corvus_abandoned_station_market", neutralStation);
			neutralStation.getMarket().getSubmarket(Submarkets.SUBMARKET_STORAGE).getCargo().addMothballedShip(FleetMemberType.SHIP, "hermes_d_Hull", null);
		
//	SectorEntityToken neutralStation = system.addOrbitalStation("corvus_abandoned_station", 
//																system.getEntityById("asharu"),
//																45, 
//																300, 
//																30,
//																"Abandoned Terraforming Platform",
//																"neutral");
		
		
		// Asharu stellar shade - out of orbit, settled in one of Asharu's lagrangian points 
		SectorEntityToken asharu_shade = system.addCustomEntity("asharu_shade", "Asharu 恒星罩", "stellar_shade", "neutral");
		asharu_shade.setCircularOrbitPointingDown(system.getEntityById("corvus"), 55 + 60 + 10, 2800, 100);		
		asharu_shade.setCustomDescriptionId("stellar_shade");
		
	// Jangala 
		PlanetAPI corvusII = system.addPlanet("jangala", star, "Jangala", "jungle", 245, 200, 4500, 200);		
		corvusII.setCustomDescriptionId("planet_jangala");
		corvusII.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
		corvusII.getSpec().setGlowColor(new Color(255,255,255,255));
		corvusII.getSpec().setUseReverseLightForGlow(true);
		corvusII.applySpecChanges();
		
			// Jangala Station 
			SectorEntityToken hegemonyStation = system.addCustomEntity("corvus_hegemony_station",
					"Jangala 空间站", "station_jangala_type", "hegemony");
					// "Jangala Station", "station_jangala_type", "hegemony");
			
			hegemonyStation.setCircularOrbitPointingDown(system.getEntityById("jangala"), 45 + 180, 360, 30);		
			hegemonyStation.setCustomDescriptionId("station_jangala");
			
			// Jangala Relay - L5 (behind)
			SectorEntityToken relay = system.addCustomEntity("corvus_relay", // unique id
					 "Jangala 通讯中继站", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay", // type of object, defined in custom_entities.json
					 "hegemony"); // faction
			relay.setCircularOrbitPointingDown(system.getEntityById("corvus"), 245-60, 4500, 200);
			
			
			// testing objectives
	//		SectorEntityToken array1 = system.addCustomEntity(null, // unique id
	//				 null,
	//				 "sensor_array_makeshift", // type of object, defined in custom_entities.json
	//				 Factions.PLAYER); // faction
	//		array1.setCircularOrbitPointingDown(system.getEntityById("corvus"), 245-60-10, 4500, 200);
	//		
	//		SectorEntityToken buoy1 = system.addCustomEntity(null, // unique id
	//				null,
	//				"nav_buoy_makeshift", // type of object, defined in custom_entities.json
	//				Factions.HEGEMONY); // faction
	//		buoy1.setCircularOrbitPointingDown(system.getEntityById("corvus"), 245-60-20, 4500, 200);
	//		
	//		SectorEntityToken stable = system.addCustomEntity(null, // unique id
	//				null,
	//				"stable_location", // type of object, defined in custom_entities.json
	//				"neutral"); // faction
	//		stable.setCircularOrbitPointingDown(system.getEntityById("corvus"), 245-60-30, 4500, 200);
			
			
			
			
		
			// Jangala Jumppoint - L4 (ahead)
			JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("jangala_jump", "Jangala 跳跃点");
			jumpPoint.setCircularOrbit(system.getEntityById("corvus"), 245+60, 4500, 200);
			jumpPoint.setRelatedPlanet(corvusII);
			
			jumpPoint.setStandardWormholeToHyperspaceVisual();
			system.addEntity(jumpPoint);
		
		// Corvus Gate
		SectorEntityToken gate = system.addCustomEntity("jangala_gate", // unique id
				 "Corvus 之门", // name - if null, defaultName from custom_entities.json will be used
				 "inactive_gate", // type of object, defined in custom_entities.json
				 null); // faction
		gate.setCircularOrbit(system.getEntityById("corvus"), 0, 6000, 350);

	// Not-yet-named Asteroids // Let's try "Nemo"
		system.addAsteroidBelt(star, 90, 5650, 500, 150, 300, Terrain.ASTEROID_BELT,  "Nemo 小行星带");
		system.addRingBand(star, "misc", "rings_dust0", 256f, 3, Color.white, 256f, 5600, 305f, null, null);
		system.addRingBand(star, "misc", "rings_asteroids0", 256f, 3, Color.white, 256f, 5720, 295f, null, null);
		
	// Barad system
		//SectorEntityToken corvusIII = system.addPlanet("barad", star, "Barad", "gas_giant", 200, 300, 7800, 400);
		// move further along orbit so it's not so close to Jangala on game start
		float baradAngle = 100;
		SectorEntityToken corvusIII = system.addPlanet("barad", star, "Barad", "gas_giant", baradAngle, 300, 7800, 400);
		
		// Barad magnetic field
		SectorEntityToken barad_field = system.addTerrain(Terrain.MAGNETIC_FIELD,
				new MagneticFieldParams(corvusIII.getRadius() + 200f, // terrain effect band width 
						(corvusIII.getRadius() + 200f) / 2f, // terrain effect middle radius
						corvusIII, // entity that it's around
						corvusIII.getRadius() + 50f, // visual band start
						corvusIII.getRadius() + 50f + 250f, // visual band end
						new Color(50, 20, 100, 40), // base color
						0.5f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
						new Color(140, 100, 235),
						new Color(180, 110, 210),
						new Color(150, 140, 190),
						new Color(140, 190, 210),
						new Color(90, 200, 170), 
						new Color(65, 230, 160),
						new Color(20, 220, 70)
				));
		barad_field.setCircularOrbit(corvusIII, 0, 0, 100);
		
			SectorEntityToken corvusIIIA = system.addPlanet("corvus_IIIa", corvusIII, "Garnir", "cryovolcanic", 135, 100, 790, 20);
			corvusIIIA.setCustomDescriptionId("planet_barad_a");
			
			// Pirate Station
			SectorEntityToken pirateStation = system.addCustomEntity("corvus_pirate_station",
					"Garnir Extraction Depot", "station_lowtech1", "pirates");
			pirateStation.setCircularOrbitPointingDown(system.getEntityById("corvus_IIIa"), 45, 180, 20);		
			pirateStation.setCustomDescriptionId("pirate_base_barad");
			pirateStation.setInteractionImage("illustrations", "pirate_station");
			
			//system.addAsteroidBelt(corvusIII, 50, 1000, 200, 10, 45, Terrain.ASTEROID_BELT, null);
			system.addRingBand(corvusIII, "misc", "rings_ice0", 256f, 2, Color.white, 256f, 1050, 45, Terrain.RING, null);
			
			SectorEntityToken corvusIIIB = system.addPlanet("corvus_IIIb", corvusIII, "Warion", "barren", 235, 70, 1300, 60);
			// corvusIIIB.setInteractionImage("illustrations", "vacuum_colony");
				
			// Barad trojans - L4 leads, L5 follows; therefore L4 is + in orbit, L5 is - (Thanks Tartiflette.)
			SectorEntityToken baradL4 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						500f, // min radius
						700f, // max radius
						20, // min asteroid count
						30, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Barad L4 小行星团")); // null for default name
			
			SectorEntityToken baradL5 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						500f, // min radius
						700f, // max radius
						20, // min asteroid count
						30, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Barad L5 小行星团")); // null for default name
			
			baradL4.setCircularOrbit(star, baradAngle -60f, 7800, 400);
			baradL5.setCircularOrbit(star, baradAngle +60f, 7800, 400);
			
			
			SectorEntityToken corvus_loc3 = system.addCustomEntity(null, null, "stable_location", Factions.NEUTRAL);
			corvus_loc3.setCircularOrbitPointingDown(star, baradAngle - 180, 7800, 400);
				
	// Outer system
	
			/*PlanetAPI corvusIV = system.addPlanet("corvus_IV", star, "Somnus", "barren-bombarded", 0, 160, 10000, 600);
		corvusIV.getSpec().setPlanetColor(new Color(225,255,245,255));
		corvusIV.applySpecChanges();
		
		PlanetAPI corvusV = system.addPlanet("corvus_V", star, "Mors", "frozen", 300, 135, 11800, 450);	
		corvusV.getSpec().setPlanetColor(new Color(225,255,245,255));
		corvusV.applySpecChanges();
		*/ 
			
	
		
		SectorEntityToken nebula = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
				"   xx " +
				"  xx x" +
				" xxxx " +
				"xxxxxx" +
				"  xx  " +
				"    x ",
				6, 6, // size of the nebula grid, should match above string
				"terrain", "nebula_amber", 4, 4, null));
		nebula.getLocation().set(corvusII.getLocation().x + 1000f, corvusII.getLocation().y);
		nebula.setCircularOrbit(star, 140f, 10000, 500);
		
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, star, StarAge.AVERAGE,
												2, 4, // min/max entities to add
												12500, // radius to start adding at 
												3, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
												true, // whether to use custom or system-name based names
												false); // whether to allow habitable worlds
		
		StarSystemGenerator.addSystemwideNebula(system, StarAge.OLD);
		
		system.autogenerateHyperspaceJumpPoints(true, true);
		
		/*SectorEntityToken hyperChunk = hyper.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
				"x         " +
				"xx        " +
				"xxx       " +
				"xxxx      " +
				"xxxxx     " +
				"xxxxxx    " +
				"xxxxxxx   " +
				"xxxxxxxx  " +
				"xxxxxxxxx " +
				"xxxxxxxxxx",
				10, 10, // size of the nebula grid, should match above string
				"terrain", "nebula_dark", 4, 4));
		hyperChunk.getLocation().set(system.getLocation().x + 3000f,
									 system.getLocation().y); */
		
		/*SectorEntityToken nebula2 = Misc.addNebulaFromPNG("data/campaign/terrain/nebula_test.png",
														  0, 0, // center of nebula
														  system, // location to add to
														  "terrain", "nebula", // texture to use, uses xxx_map for map
														  4, 4); // number of cells in texture
		 */
		//nebula2.setCircularOrbit(star, 0, 2000, 200f);
		
		/*
		SectorEntityToken asteroidField = system.addTerrain(Terrain.ASTEROID_FIELD,
						new AsteroidFieldParams(
								500f, // min radius
								700f, // max radius
								20, // min asteroid count
								30, // max asteroid count
								4f, // min asteroid radius 
								16f, // max asteroid radius
								"<name goes here>")); // null for default name
		asteroidField.setCircularOrbit(star, 180f, 4000, 500);
		
		*/
	}

	
}









