package data.scripts.world.systems;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;

public class Canaan {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Canaan");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background4.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		// Canaan, the promised land.
		PlanetAPI canaan_star = system.initStar("canaan", // unique id for this star 
										    "star_yellow",  // id in planets.json
										    600f, 		  // radius (in pixels at default zoom)
										    150); // corona radius, from star edge
		
		system.setLightColor(new Color(210, 210, 255)); // light color in entire system, affects all entities
		
		PlanetAPI khna = system.addPlanet("khna", canaan_star, "Khna", "barren_venuslike", 0, 105, 2500, 120);
		
		// Add a gate.
		PlanetAPI gilead = system.addPlanet("gilead", canaan_star, "Gilead", "terran", 60, 190, 5000, 250);
		gilead.getSpec().setPitch( 190.0f);
		gilead.getSpec().setPlanetColor(new Color(255,245,225,255));
		gilead.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
		gilead.getSpec().setGlowColor(new Color(250,225,195,255));
		gilead.getSpec().setUseReverseLightForGlow(true);
		gilead.applySpecChanges();
		gilead.setCustomDescriptionId("planet_gilead");
		gilead.setInteractionImage("illustrations", "gilead");
		
			PlanetAPI og = system.addPlanet("og", gilead, "Og", "barren", 0, 50, 500, 30);
			og.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren03"));
			og.getSpec().setPlanetColor(new Color(235,255,245,255));
			og.getSpec().setPitch( 140.0f);
			og.applySpecChanges();
			
			// Langrangrians for Gilead: relay + jump
			// L4
			SectorEntityToken relay = system.addCustomEntity("gilead_relay", // unique id
					 "Gilead Relay", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay_makeshift", // type of object, defined in custom_entities.json
					 "luddic_church"); // faction
			relay.setCircularOrbitPointingDown(canaan_star, 60-60, 5000, 250);
			
			// L5
			JumpPointAPI jumpPoint1 = Global.getFactory().createJumpPoint("gilead_jump", "Gilead Jump-point");
			jumpPoint1.setCircularOrbit( canaan_star, 60+60, 5000, 250);
			jumpPoint1.setRelatedPlanet(gilead);
			system.addEntity(jumpPoint1);
			
		PlanetAPI gad = system.addPlanet("gad", canaan_star, "Gad", "gas_giant", 180, 220, 6500, 350);
		gad.getSpec().setPitch( 100.0f);
		gad.getSpec().setPlanetColor(new Color(245,255,195,255));
		gad.applySpecChanges();
		
		system.addRingBand(gad, "misc", "rings_dust0", 256f, 2, Color.white, 256f, 400, 30, Terrain.RING, null);
		
			PlanetAPI asher = system.addPlanet("asher", gad, "Asher", "barren-desert", 0, 100, 1000, 40);
			asher.getSpec().setTilt( 190f );
			asher.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
			asher.getSpec().setGlowColor(new Color(250,225,195,255));
			asher.applySpecChanges();
			asher.setCustomDescriptionId("planet_asher");
			
			SectorEntityToken gad_loc = system.addCustomEntity(null, null, "sensor_array_makeshift", "luddic_church"); 
			gad_loc.setCircularOrbitPointingDown(canaan_star, 0 + 180, 1000, 40);
			
		// and have asteroids on the other side, too. - L5 is behind
		SectorEntityToken gadL5 = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					450f, // min radius
					600f, // max radius
					25, // min asteroid count
					45, // max asteroid count
					4f, // min asteroid radius 
					12f, // max asteroid radius
					"Gad L5 Asteroids")); // null for default name
		
		gadL5.setCircularOrbit(canaan_star, 180 - 60, 6500, 350);
		
		// Lagrangrian gate - embedd in some lovely asteroids? - L4 is ahead
		SectorEntityToken gate1 = system.addCustomEntity("canaan_gate", // unique id
				 "Gate of Canaan", // name - if null, defaultName from custom_entities.json will be used
				 "inactive_gate", // type of object, defined in custom_entities.json
				 null); // faction
		gate1.setCircularOrbit(system.getEntityById("canaan"), 180 + 60, 6500, 350);
		
		SectorEntityToken gadL4 = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					450f, // min radius
					600f, // max radius
					25, // min asteroid count
					45, // max asteroid count
					4f, // min asteroid radius 
					12f, // max asteroid radius
					"Gad L4 Asteroids")); // null for default name
		
		gadL4.setCircularOrbit(canaan_star, 180+60, 6500, 350);
			
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, canaan_star, StarAge.OLD,
				1, 3, // min/max entities to add
				10000, // radius to start adding at 
				4, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds
			
		// Canaan Jumppoint
		/*
		JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("canaan_jump", "Canaan Jump-point");
		jumpPoint2.setCircularOrbit( system.getEntityById("canaan"), 270+60, 7500, 800);
		jumpPoint2.setRelatedPlanet(gilead);
		system.addEntity(jumpPoint2);
		*/
		
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
