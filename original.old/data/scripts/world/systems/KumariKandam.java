//KumariKandam.javapackage data.scripts.world.systems;
package data.scripts.world.systems;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.StarTypes;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageSpecialAssigner;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
import com.fs.starfarer.api.util.Misc;

public class KumariKandam {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Kumari Kandam");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background6.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI kumarikandam_star = system.initStar("kumarikandam", // unique id for this star 
											StarTypes.RED_DWARF,  // id in planets.json
										    450f, 		  // radius (in pixels at default zoom)
										    400); // corona radius, from star edge
		
		system.setLightColor(new Color(210, 230, 255)); // light color in entire system, affects all entities
		
		
		PlanetAPI kumarikandam_b = system.addPlanet("kumari_aru", kumarikandam_star, "Kumari Aru", "gas_giant", 270, 300, 2800, 80);
		kumarikandam_b.getSpec().setPlanetColor(new Color(150,235,245,255));
		kumarikandam_b.getSpec().setAtmosphereColor(new Color(150,170,240,150));
		kumarikandam_b.getSpec().setCloudColor(new Color(180,250,240,200));
		kumarikandam_b.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "aurorae"));
		kumarikandam_b.getSpec().setGlowColor(new Color(250,50,105,100));
		kumarikandam_b.getSpec().setUseReverseLightForGlow(true);
		kumarikandam_b.getSpec().setIconColor(new Color(180,255,225,255));
		kumarikandam_b.applySpecChanges();
		kumarikandam_b.setCustomDescriptionId("planet_kumari_aru");
		
		
			// Beholder Station
			SectorEntityToken beholder_station = system.addCustomEntity("beholder_station", "Beholder Station", "station_side05", "luddic_church");
			beholder_station.setCircularOrbitPointingDown(system.getEntityById("kumari_aru"), 270, 430, 30);		
			beholder_station.setCustomDescriptionId("station_beholder");
			beholder_station.setInteractionImage("illustrations", "luddic_shrine");
			beholder_station.addTag("luddicShrine");
			
			// And the moons of Kumari Aru
			// Makal : sulphurous outgassing like Io
			PlanetAPI kumarikandam_b1 = system.addPlanet("makal", kumarikandam_b, "Makal", "barren", 270, 60, 520, 40);
			kumarikandam_b1.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren02"));

			kumarikandam_b1.getSpec().setPlanetColor(new Color(255,235,150,255));
			// kumarikandam_b1.getSpec().setAtmosphereColor(new Color(255,245,100,150));
			// kumarikandam_b1.getSpec().setCloudColor(new Color(255,215,50,50));
			//kumarikandam_b1.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
			kumarikandam_b1.applySpecChanges();
			
			PlanetAPI kumarikandam_b2 = system.addPlanet("kulantai", kumarikandam_b, "Kulantai", "barren-bombarded", 270, 40, 650, 35);
			//PlanetAPI kumarikandam_b3 = system.addPlanet("nanpan", kumarikandam_b, "Nanpan", "barren", 270, 40, 600, 90);
			
			// Kumari Aru trojans - L4 leads, L5 follows
			SectorEntityToken kumari_aruL4 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						300f, // min radius
						500f, // max radius
						16, // min asteroid count
						24, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Kumari Aru L4 Asteroids")); // null for default name
			
			SectorEntityToken kumari_aruL5 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						300f, // min radius
						500f, // max radius
						16, // min asteroid count
						24, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Kumari Aru L5 Asteroids")); // null for default name
			
			kumari_aruL4.setCircularOrbit(kumarikandam_star, 270 +60, 2800, 80);
			kumari_aruL5.setCircularOrbit(kumarikandam_star, 270 -60, 2800, 80);
			
			SectorEntityToken kumari_aru_l5_loc = system.addCustomEntity(null, null, "comm_relay_makeshift", Factions.LUDDIC_PATH); 
			kumari_aru_l5_loc.setCircularOrbitPointingDown(kumarikandam_star, 270 -60, 2800, 80);
			
			
		PlanetAPI chalcedon = system.addPlanet("chalcedon", kumarikandam_star, "Chalcedon", "terran-eccentric", 220, 160, 4300, 180);
		chalcedon.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
		chalcedon.getSpec().setGlowColor(new Color(170,255,240,255));
		chalcedon.getSpec().setUseReverseLightForGlow(true);
		chalcedon.applySpecChanges();
		chalcedon.setCustomDescriptionId("planet_chalcedon");
		
		// counter-orbit sensor array
		SectorEntityToken chalcedon_loc = system.addCustomEntity(null, null, "nav_buoy_makeshift", Factions.PERSEAN); 
		chalcedon_loc.setCircularOrbitPointingDown(kumarikandam_star, 220-180, 4300, 180);
		
			// Destroyed station
			SectorEntityToken abandoned_station1 = system.addCustomEntity("abandoned_spacedock", "Abandoned Spacedock", "station_side00", "neutral");
			abandoned_station1.setInteractionImage("illustrations", "abandoned_station3");
			abandoned_station1.setCircularOrbitPointingDown(kumarikandam_star, 220 + 14, 4300, 180);
			abandoned_station1.setCustomDescriptionId("station_chalcedon");
		
			// fun debris
			DebrisFieldParams params = new DebrisFieldParams(
					200f, // field radius - should not go above 1000 for performance reasons
					-1f, // density, visual - affects number of debris pieces
					10000000f, // duration in days 
					0f); // days the field will keep generating glowing pieces
			
			params.source = DebrisFieldSource.SALVAGE;
			params.baseSalvageXP = 250; // base XP for scavenging in field
			SectorEntityToken debris = Misc.addDebrisField(system, params, StarSystemGenerator.random);
			SalvageSpecialAssigner.assignSpecialForDebrisField(debris);
			
			// makes the debris field always visible on map/sensors and not give any xp or notification on being discovered
			debris.setSensorProfile(null);
			debris.setDiscoverable(null);
			debris.setCircularOrbit(kumarikandam_star, 220 + 16, 4300, 180);
		
			// a gate in the Lagrangian of Chalcedon
			SectorEntityToken gate = system.addCustomEntity("kumari_gate", // unique id
					 "Kumarian Gate", // name - if null, defaultName from custom_entities.json will be used
					 "inactive_gate", // type of object, defined in custom_entities.json
					 null); // faction
			gate.setCircularOrbit(kumarikandam_star, 220-60, 4300, 180);
			
			// a jump in the other one: Rama's Bridge :  Jump-point
			JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("kumarikandam_jump", "Rama's Bridge");
			jumpPoint2.setCircularOrbit( system.getEntityById("kumarikandam"), 220 + 60, 4300, 180);
			jumpPoint2.setRelatedPlanet(chalcedon);
			system.addEntity(jumpPoint2);
			
			
		// Kaykos
		PlanetAPI kaykos = system.addPlanet("kaykos", kumarikandam_star, "Kaykos", "barren-bombarded", 180, 100, 5300, 210);
		kaykos.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren02"));
		kaykos.getSpec().setPlanetColor(new Color(255,250,245,255));
		kaykos.getSpec().setPitch(-60f);
		kaykos.getSpec().setTilt(10f);
		kaykos.applySpecChanges();
		
		// Crocodile : moon
			PlanetAPI crocodile = system.addPlanet("crocodile", kaykos, "Crocodile", "barren", 0, 40, 200, 30);
			crocodile.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren03"));
			crocodile.getSpec().setPlanetColor(new Color(225,240,255,255));
			kaykos.getSpec().setPitch(-45f);
			kaykos.getSpec().setTilt(-20f);
			crocodile.applySpecChanges();
			
		//PlanetAPI kumarikandam_c = system.addPlanet("peru_aru", kumarikandam_star, "Peru Aru", "barren", 270, 100, 4000, 190);
		//PlanetAPI kumarikandam_d = system.addPlanet("muthur", kumarikandam_star, "Muthur", "frozen", 270, 100, 4800, 240);
		
		
		system.addRingBand(kumarikandam_star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 5900, 220f, null, null);
		system.addAsteroidBelt(kumarikandam_star, 150, 5900, 128, 200, 240, Terrain.ASTEROID_BELT, "The Mullam");
		
		PlanetAPI olinadu = system.addPlanet("olinadu", kumarikandam_star, "Olinadu", "cryovolcanic", 270, 100, 6800, 340);
		olinadu.setCustomDescriptionId("planet_olinadu");
		olinadu.setInteractionImage("illustrations", "cargo_loading");
		
		PlanetAPI kanni = system.addPlanet("kanni", kumarikandam_star, "Kanni", "barren", 180, 100, 8100, 500);
		kanni.setCustomDescriptionId("planet_kanni");
		kanni.setInteractionImage("illustrations", "mine");
		
		SectorEntityToken kanni_loc = system.addCustomEntity(null, null, "sensor_array_makeshift", Factions.PIRATES); 
		kanni_loc.setCircularOrbitPointingDown(kumarikandam_star, 180 + 60, 8100, 500);
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, kumarikandam_star, StarAge.AVERAGE,
				1, 2, // min/max entities to add
				9500, // radius to start adding at 
				6, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds
		
		system.autogenerateHyperspaceJumpPoints(true, true);
		
		//Misc.setFullySurveyed(kumarikandam_b.getMarket(), null, false);
	}
}
