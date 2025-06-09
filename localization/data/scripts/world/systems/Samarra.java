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
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;

public class Samarra {

	public void generate(SectorAPI sector) {
		StarSystemAPI system = sector.createStarSystem("Samarra");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background4.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI samarra_star = system.initStar("samarra", // unique id for this star 
										 "star_orange", // id in planets.json
										 650f, 		// radius (in pixels at default zoom)
										 500); // corona radius, from star edge
		system.setLightColor(new Color(255, 235, 205)); // light color in entire system, affects all entities
	
	// Tigra Ring
		system.addAsteroidBelt(samarra_star, 100, 3000, 500, 100, 190, Terrain.ASTEROID_BELT, "Tigra Ring");
		system.addRingBand(samarra_star, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 3000, 201f, null, null);
		system.addRingBand(samarra_star, "misc", "rings_asteroids0", 256f, 1, Color.white, 256f, 3100, 225f, null, null);
		
		SectorEntityToken tigra_city = system.addCustomEntity("tigra_city", "Tigra City", "station_side00", "hegemony");
		tigra_city.setCircularOrbitPointingDown(system.getEntityById("samarra"), 270, 3020, 185);		
		tigra_city.setCustomDescriptionId("station_tigra_city");
		tigra_city.setInteractionImage("illustrations", "hound_hangar");
		
		PlanetAPI samarra0 = system.addPlanet("eridu", samarra_star, "Eridu", "barren-bombarded", 90, 30, 2940, 185);
		
	// Eventide
		PlanetAPI samarra1 = system.addPlanet("eventide", samarra_star, "Eventide", "terran-eccentric", 30, 150, 4000, 200);
		samarra1.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
		samarra1.getSpec().setGlowColor(new Color(255,255,255,255));
		samarra1.getSpec().setUseReverseLightForGlow(true);
		samarra1.getSpec().setRotation(0f);
		samarra1.applySpecChanges();
		samarra1.setCustomDescriptionId("planet_eventide");
		
			// Eventide mirror system 
			SectorEntityToken eventide_mirror1 = system.addCustomEntity("eventide_mirror1", "Eventide Stellar Mirror Alpha", "stellar_mirror", "hegemony");
			SectorEntityToken eventide_mirror2 = system.addCustomEntity("eventide_mirror2", "Eventide Stellar Mirror Beta", "stellar_mirror", "hegemony");	
			SectorEntityToken eventide_mirror3 = system.addCustomEntity("eventide_mirror3", "Eventide Stellar Mirror Gamma", "stellar_mirror", "hegemony");
			SectorEntityToken eventide_mirror4 = system.addCustomEntity("eventide_mirror4", "Eventide Stellar Mirror Delta", "stellar_mirror", "hegemony");
			SectorEntityToken eventide_mirror5 = system.addCustomEntity("eventide_mirror5", "Eventide Stellar Mirror Epsilon", "stellar_mirror", "hegemony");
			eventide_mirror1.setCircularOrbitPointingDown(system.getEntityById("eventide"), 30 - 60, 400, 200);
			eventide_mirror2.setCircularOrbitPointingDown(system.getEntityById("eventide"), 30 - 30, 400, 200);	
			eventide_mirror3.setCircularOrbitPointingDown(system.getEntityById("eventide"), 30 + 0, 400, 200);	
			eventide_mirror4.setCircularOrbitPointingDown(system.getEntityById("eventide"), 30 + 30, 400, 200);	
			eventide_mirror5.setCircularOrbitPointingDown(system.getEntityById("eventide"), 30 + 60, 400, 200);		
			eventide_mirror1.setCustomDescriptionId("stellar_mirror");
			eventide_mirror2.setCustomDescriptionId("stellar_mirror");
			eventide_mirror3.setCustomDescriptionId("stellar_mirror");
			eventide_mirror4.setCustomDescriptionId("stellar_mirror");
			eventide_mirror5.setCustomDescriptionId("stellar_mirror");
			
			// Eventide shade system 
			SectorEntityToken eventide_shade1 = system.addCustomEntity("eventide_mirror1", "Eventide Stellar Shade Omega", "stellar_shade", "hegemony");
			SectorEntityToken eventide_shade2 = system.addCustomEntity("eventide_mirror3", "Eventide Stellar Shade Psi", "stellar_shade", "hegemony");
			SectorEntityToken eventide_shade3 = system.addCustomEntity("eventide_mirror5", "Eventide Stellar Shade Chi", "stellar_shade", "hegemony");
			eventide_shade1.setCircularOrbitPointingDown(system.getEntityById("eventide"), 210 - 26, 390, 200);
			eventide_shade2.setCircularOrbitPointingDown(system.getEntityById("eventide"), 210 + 0, 425, 200);	
			eventide_shade3.setCircularOrbitPointingDown(system.getEntityById("eventide"), 210 + 26, 390, 200);		
			eventide_shade1.setCustomDescriptionId("stellar_shade");
			eventide_shade2.setCustomDescriptionId("stellar_shade");
			eventide_shade3.setCustomDescriptionId("stellar_shade");
			
			PlanetAPI samarra1a = system.addPlanet("lumen", samarra1, "Lumen", "barren-bombarded", 30, 25, 610, 26);
			
			// Samarra Relay - L5 (behind)
			SectorEntityToken samarra_relay = system.addCustomEntity("samarra_relay", // unique id
					 "Samarra Relay", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay", // type of object, defined in custom_entities.json
					 "hegemony"); // faction
			samarra_relay.setCircularOrbitPointingDown( system.getEntityById("samarra"), 30 - 60, 4000, 200);
	
			// Samarra Jump - L4 (ahead)
			JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("samarra_jump_point_alpha", "Samarra Jump-point");
			OrbitAPI orbit = Global.getFactory().createCircularOrbit(samarra_star,  30 + 60, 4000, 200);
			jumpPoint.setOrbit(orbit);
			jumpPoint.setRelatedPlanet(samarra1);
			jumpPoint.setStandardWormholeToHyperspaceVisual();
			system.addEntity(jumpPoint);
		
		// Samarra Gate
		SectorEntityToken samarra_gate = system.addCustomEntity("samarra_gate", // unique id
				 "Samarra Gate", // name - if null, defaultName from custom_entities.json will be used
				 "inactive_gate", // type of object, defined in custom_entities.json
				 null); // faction
		samarra_gate.setCircularOrbit(samarra_star, 210, 4250, 200);
		
	// Typhon System
		PlanetAPI samarra2 = system.addPlanet("typhon", samarra_star, "Typhon", "gas_giant", 60, 350, 7000, 500);
		samarra2.getSpec().setPlanetColor(new Color(250,180,120,255));
		samarra2.getSpec().setCloudColor(new Color(250,180,120,150));
		samarra2.getSpec().setAtmosphereColor(new Color(250,180,120,150));
		samarra2.applySpecChanges();
		samarra2.setCustomDescriptionId("planet_typhon");
		
			PlanetAPI samarra2a = system.addPlanet("chimera", samarra2, "Chimera", "toxic_cold", 20, 50, 500, 12);
			PlanetAPI samarra2b = system.addPlanet("ladon", samarra2, "Ladon", "barren-bombarded", 40, 30, 620, 16);
			
			system.addRingBand(samarra2, "misc", "rings_ice0", 256f, 3, Color.white, 256f, 850, 30f, Terrain.RING, null);
			system.addRingBand(samarra2, "misc", "rings_dust0", 256f, 2, Color.white, 256f, 975, 33f, Terrain.RING, null);
			
			PlanetAPI samarra2c = system.addPlanet("orthrus", samarra2, "Orthrus", "rocky_ice", 40, 70, 1400, 41);
			samarra2c.setCustomDescriptionId("planet_orthrus");
			// Orthrus Relay - L5 (behind)
			SectorEntityToken orthrus_relay = system.addCustomEntity("orthrus_relay", // unique id
					 "Orthrus Relay", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay", // type of object, defined in custom_entities.json
					 "independent"); // faction
			orthrus_relay.setCircularOrbit( samarra2, 40 -60, 1475, 41);
			
			PlanetAPI samarra3d = system.addPlanet("sphinx", samarra2, "Sphinx", "barren", 150, 60, 1600, 56);
			samarra3d.setCustomDescriptionId("planet_sphinx");
			
			// Typhon trojans
			SectorEntityToken typhonL4 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						500f, // min radius
						700f, // max radius
						20, // min asteroid count
						30, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Typhon L4 Asteroids")); // null for default name
			
			SectorEntityToken typhonL5 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						500f, // min radius
						700f, // max radius
						20, // min asteroid count
						30, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Typhon L5 Asteroids")); // null for default name
			
			typhonL4.setCircularOrbit(samarra_star, 60 + 60, 7000, 500);
			typhonL5.setCircularOrbit(samarra_star, 60 - 60, 7000, 500);
			
			/* Typon Jump - L5 (behind)
			JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("samarra_jump_point_beta", "Typon Jump Point");
			OrbitAPI orbit2 = Global.getFactory().createCircularOrbit(samarra_star,  0, 7000, 500);
			jumpPoint2.setOrbit(orbit2);
			jumpPoint2.setRelatedPlanet(samarra1);
			jumpPoint2.setStandardWormholeToHyperspaceVisual();
			system.addEntity(jumpPoint2);
			naw. */
			
			
			SectorEntityToken typhon_opposite_loc = system.addCustomEntity(null, null, "sensor_array", Factions.HEGEMONY); 
			typhon_opposite_loc.setCircularOrbitPointingDown( samarra_star, 350-180, 7000, 500);
			
			
			
			float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, samarra_star, StarAge.OLD,
					1, 2, // min/max entities to add
					9000, // radius to start adding at 
					3, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
					true, // whether to use custom or system-name based names
					false); // whether to allow habitable worlds
			
		// generates hyperspace destinations for in-system jump points
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
