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
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;

public class Askonia {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Askonia");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background4.jpg");
		
		//system.getMemoryWithoutUpdate().set(MusicPlayerPluginImpl.MUSIC_SET_MEM_KEY, "music_title");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI star = system.initStar("askonia", // unique id for this star 
										 StarTypes.RED_GIANT, // id in planets.json
										 1000f,		// radius (in pixels at default zoom)
										 1500, // corona radius, from star edge
										 5f, // solar wind burn level
										 0.5f, // flare probability
										 2f); // cr loss mult
		
		system.setLightColor(new Color(255, 210, 200)); // light color in entire system, affects all entities
		
		/*
		 * addPlanet() parameters:
		 * 1. Unique id for this planet (or null to have it be autogenerated)
		 * 2. What the planet orbits (orbit is always circular)
		 * 3. Name
		 * 4. Planet type id in planets.json
		 * 5. Starting angle in orbit, i.e. 0 = to the right of the star
		 * 6. Planet radius, pixels at default zoom
		 * 7. Orbit radius, pixels at default zoom
		 * 8. Days it takes to complete an orbit. 1 day = 10 seconds.
		 */
		PlanetAPI a1 = system.addPlanet("sindria", star, "Sindria", "rocky_metallic", 0, 150, 2900, 100);
		a1.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "sindria"));
		a1.getSpec().setGlowColor(new Color(255,255,255,255));
		a1.getSpec().setUseReverseLightForGlow(true);
		a1.applySpecChanges();
		a1.setCustomDescriptionId("planet_sindria");
		a1.setInteractionImage("illustrations", "sindria");
		
			JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("askonia_jump_point_alpha", "Sindria Jump-point");
			OrbitAPI orbit = Global.getFactory().createCircularOrbit(a1, 0, 500, 30);
			jumpPoint.setOrbit(orbit);
			jumpPoint.setRelatedPlanet(a1);
			jumpPoint.setStandardWormholeToHyperspaceVisual();
			jumpPoint.setCircularOrbit( system.getEntityById("askonia"), 60, 3000, 100);
			system.addEntity(jumpPoint);
			
			SectorEntityToken sindria_relay = system.addCustomEntity("sindria_relay", // unique id
					 "Sindria Relay", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay", // type of object, defined in custom_entities.json
					 "sindrian_diktat"); // faction
			sindria_relay.setCircularOrbitPointingDown( system.getEntityById("askonia"), -60, 3000, 100);
		
		// And now, the outer system.
			system.addRingBand(star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 3570, 220f, null, null);
			system.addRingBand(star, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 3660, 226f, null, null);
		system.addAsteroidBelt(star, 150, 3600, 170, 200, 250, Terrain.ASTEROID_BELT, "Stone River");
		
	// Salus system
		PlanetAPI a2 = system.addPlanet("salus", star, "Salus", "gas_giant", 230, 350, 7500, 250);
		a2.setCustomDescriptionId("planet_salus");
		a2.getSpec().setPlanetColor(new Color(255,225,170,255));
		a2.getSpec().setAtmosphereColor(new Color(160,110,45,140));
		a2.getSpec().setCloudColor(new Color(255,164,96,200));
		a2.getSpec().setTilt(15);
		a2.applySpecChanges();
		
			PlanetAPI a2a = system.addPlanet("cruor", a2, "Cruor", "rocky_unstable", 45, 80, 700, 25);
			a2a.setInteractionImage("illustrations", "desert_moons_ruins");
			a2a.setCustomDescriptionId("planet_cruor");
			
			system.addAsteroidBelt(a2, 50, 1100, 128, 40, 80, Terrain.ASTEROID_BELT, "Opis Ring");
			system.addRingBand(a2, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 1100, 40f);
			system.addRingBand(a2, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 1120, 50f);
			//system.addRingBand(a2, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 1100, 80f);
			
			SectorEntityToken opis_debris_cloud = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					200f, // min radius
					400f, // max radius
					20, // min asteroid count
					30, // max asteroid count
					4f, // min asteroid radius 
					12f, // max asteroid radius
					"Opis Debris Cloud")); // null for default name
			opis_debris_cloud.setCircularOrbitPointingDown(system.getEntityById("salus"), 45, 1100, 70);	
			
			PlanetAPI a2b = system.addPlanet("volturn", a2, "Volturn", "water", 110, 120, 1400, 45);
			a2b.setCustomDescriptionId("planet_volturn");
			a2b.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
			a2b.getSpec().setGlowColor(new Color(255,255,255,255));
			a2b.getSpec().setUseReverseLightForGlow(true);
			a2b.applySpecChanges();
			a2b.setInteractionImage("illustrations", "volturn");
			
				// Salus nav buoy, in L5
				SectorEntityToken salus_nav = system.addCustomEntity(null, "Salus Navigation Buoy", "nav_buoy", Factions.DIKTAT); 
				salus_nav.setCircularOrbitPointingDown( a2, 110-60, 1400, 45);
			
			system.addRingBand(a2, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 1800, 70f);
			system.addRingBand(a2, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 1800, 90f);
			system.addRingBand(a2, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 1800, 110f, Terrain.RING, "Dust Ring");
			
			system.addRingBand(a2, "misc", "rings_ice0", 256f, 0, Color.white, 256f, 2150, 50f);
			system.addRingBand(a2, "misc", "rings_ice0", 256f, 0, Color.white, 256f, 2150, 70f);
			system.addRingBand(a2, "misc", "rings_ice0", 256f, 0, Color.white, 256f, 2150, 80f);
			system.addRingBand(a2, "misc", "rings_ice0", 256f, 1, Color.white, 256f, 2150, 90f, Terrain.RING, "Cloud Ring");
		
		// Nortia - Independent (Charterist) base - caught in Salus' L4
			PlanetAPI a3 = system.addPlanet("nortia", star, "Nortia", "barren-bombarded", 230 + 60, 80, 7500, 250);
			a3.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "asharu"));
			a3.getSpec().setGlowColor(new Color(255,255,255,255));
			a3.getSpec().setUseReverseLightForGlow(true);
			a3.applySpecChanges();
			a3.setInteractionImage("illustrations", "hound_hangar");
			a3.setCustomDescriptionId("planet_nortia");
			
			// Salus trojans
			SectorEntityToken salusL4 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						400f, // min radius
						600f, // max radius
						20, // min asteroid count
						30, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Salus L4 Asteroids")); // null for default name
			
			SectorEntityToken salusL5 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						400f, // min radius
						600f, // max radius
						20, // min asteroid count
						30, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Salus L5 Asteroids")); // null for default name
			
			salusL4.setCircularOrbit(star, 230 + 60, 7500, 250);
			salusL5.setCircularOrbit(star, 230 - 60, 7500, 250);
			
			// Askonia Outer Jump (in Salus L5)
			JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("salus_jump", "Salus L5 Jump-point");
			jumpPoint2.setCircularOrbit(star, 230 - 60, 7500, 250);
			jumpPoint2.setStandardWormholeToHyperspaceVisual();
			system.addEntity(jumpPoint2);
			
			// Askonia Gate
			SectorEntityToken askonia_gate = system.addCustomEntity("askonia_gate", // unique id
					 "Askonia Gate", // name - if null, defaultName from custom_entities.json will be used
					 "inactive_gate", // type of object, defined in custom_entities.json
					 null); // faction
			askonia_gate.setCircularOrbit(star, 230-180, 7000, 230);
		
		// Umbra - the resistance (or pirates)
		PlanetAPI a4 = system.addPlanet("umbra", star, "Umbra", "rocky_ice", 280, 150, 11000, 600);
		a4.setCustomDescriptionId("planet_umbra");
		a4.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "asharu"));
		a4.getSpec().setGlowColor(new Color(255,255,255,255));
		a4.getSpec().setUseReverseLightForGlow(true);
		a4.applySpecChanges();
		a4.setInteractionImage("illustrations", "pirate_station");
		
		// makeshift sensor array in counter-orbit to Umbra
		SectorEntityToken askonia_outer_array = system.addCustomEntity(null, "Askonia Fringe Listening Station", "sensor_array_makeshift", Factions.DIKTAT); 
		askonia_outer_array.setCircularOrbitPointingDown( star, 150+180, 11000, 600);
	
//		system.addOrbitalJunk(a1,
//				 "orbital_junk", // from custom_entities.json 
//				 30, // num of junk
//				 12, 20, // min/max sprite size (assumes square)
//				 225, // orbit radius
//				 70, // orbit width
//				 10, // min orbit days
//				 20, // max orbit days
//				 60f, // min spin (degress/day)
//				 360f); // max spin (degrees/day)
		
		SectorEntityToken station = system.addCustomEntity("diktat_cnc", "Command & Control", "station_side02", "sindrian_diktat");
		station.setCircularOrbitPointingDown(system.getEntityById("sindria"), 45, 300, 50);		
		station.setInteractionImage("illustrations", "orbital");
//		station.setCustomDescriptionId("station_ragnar");
		
		// example of using custom visuals below
//		a1.setCustomInteractionDialogImageVisual(new InteractionDialogImageVisual("illustrations", "hull_breach", 800, 800));
//		jumpPoint.setCustomInteractionDialogImageVisual(new InteractionDialogImageVisual("illustrations", "space_wreckage", 1200, 1200));
//		station.setCustomInteractionDialogImageVisual(new InteractionDialogImageVisual("illustrations", "cargo_loading", 1200, 1200));
		
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, star, StarAge.OLD,
				1, 2, // min/max entities to add
				11750, // radius to start adding at 
				4, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds
		
		// generates hyperspace destinations for in-system jump points
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
	

	
}