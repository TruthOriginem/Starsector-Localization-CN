package data.scripts.world.systems;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin.MagneticFieldParams;
import com.fs.starfarer.api.util.Misc;

public class Eos {

	public void generate(SectorAPI sector) {
		StarSystemAPI system = sector.createStarSystem("Eos Exodus");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background6.jpg");
		
		SectorEntityToken eos_nebula = Misc.addNebulaFromPNG("data/campaign/terrain/eos_nebula.png",
				  0, 0, // center of nebula
				  system, // location to add to
				  "terrain", "nebula_amber", // "nebula_blue", // texture to use, uses xxx_map for map
				  4, 4, StarAge.OLD); // number of cells in texture
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI star = system.initStar("eos",
										 "star_white", // id in planets.json
										 750f, 		// radius (in pixels at default zoom)
										 500); // corona radius, from star edge
		system.setLightColor(new Color(255, 255, 255)); // light color in entire system, affects all entities

		
		PlanetAPI eos1 = system.addPlanet("phaosphoros", star, "Phaosphoros", "gas_giant", 240, 300, 2300, 40);
		eos1.getSpec().setAtmosphereColor(new Color(255,245,200,220));
		eos1.getSpec().setPlanetColor(new Color(245,250,255,255));
		eos1.applySpecChanges();
		eos1.setCustomDescriptionId("hot_gas_giant");
		eos1.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "aurorae"));
		eos1.getSpec().setGlowColor(new Color(245,50,20,100));
		eos1.getSpec().setUseReverseLightForGlow(true);
		eos1.getSpec().setAtmosphereThickness(0.5f);
		eos1.getSpec().setCloudRotation( 10f );
		eos1.getSpec().setPitch(20);
		eos1.getSpec().setAtmosphereThicknessMin(80);
		eos1.getSpec().setAtmosphereThickness(0.30f);
		eos1.getSpec().setAtmosphereColor(new Color(255,150,50,205));
			
			PlanetAPI eos1a = system.addPlanet("lucifer", eos1, "Lucifer", "lava", 0, 60, 650, 18);
			system.addAsteroidBelt(eos1, 30, 500, 100, 15, 25, Terrain.ASTEROID_BELT, null);
			
			// Phaosphoros trojans
			SectorEntityToken phaosphorosL4 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						300f, // min radius
						500f, // max radius
						10, // min asteroid count
						15, // max asteroid count
						4f, // min asteroid radius 
						12f, // max asteroid radius
						"Cherubim 小行星团")); // null for default name
			
			SectorEntityToken phaosphorosL5 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						300f, // min radius
						500f, // max radius
						10, // min asteroid count
						15, // max asteroid count
						4f, // min asteroid radius 
						12f, // max asteroid radius
						"Seraphim 小行星团")); // null for default name
			
			phaosphorosL4.setCircularOrbit(star, 240 + 60, 2300, 40);
			phaosphorosL5.setCircularOrbit(star, 240 - 60, 2300, 40);
			
		PlanetAPI eos2 = system.addPlanet("tartessus", star, "Tartessus", "arid", 200, 180, 4400, 120);
		eos2.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "sindria"));
		eos2.getSpec().setGlowColor(new Color(245,255,250,255));
		eos2.getSpec().setUseReverseLightForGlow(true);
		eos2.applySpecChanges();
		eos2.setCustomDescriptionId("planet_tartessus");
		eos2.setInteractionImage("illustrations", "tartessus");
		
			system.addRingBand(eos2, "misc", "rings_special0", 256f, 1, new Color(225,215,255,200), 128f, 380, 30f, Terrain.RING, "The Grace of Tartessus"); 
			// 256f
		
			// Tartessus Jumppoint - Tartessus L5 (behind)
			JumpPointAPI eos2JumpPoint = Global.getFactory().createJumpPoint("paladins_bridge", "Paladins 之桥");
			eos2JumpPoint.setCircularOrbit(system.getEntityById("eos"), 200-60, 4400, 120);
			eos2JumpPoint.setRelatedPlanet(eos2);
			
			eos2JumpPoint.setStandardWormholeToHyperspaceVisual();
			system.addEntity(eos2JumpPoint);
			
			// Baetis - Tartessus L4 (ahead)
			PlanetAPI eos2a = system.addPlanet("baetis", star, "Baetis", "barren-bombarded", 200 + 60, 60, 4400, 120);
			eos2a.setCustomDescriptionId("planet_baetis");
			eos2a.getSpec().setAtmosphereThicknessMin(20);
			eos2a.getSpec().setAtmosphereThickness(0.06f);
			eos2a.getSpec().setAtmosphereColor( new Color(250, 220, 120, 128) );
			eos2a.applySpecChanges();
			
			// counter-orbit sensor array
			SectorEntityToken tartessus_sensor = system.addCustomEntity(null, null, "sensor_array_makeshift", "luddic_church");
			tartessus_sensor.setCircularOrbitPointingDown( star, 200 - 180, 4400, 120);
			
		// Asteroids - "The Pilgrims"
		system.addRingBand(star, "misc", "rings_asteroids0", 256f, 1, Color.white, 256f, 5980, 205f, null, null);
		system.addAsteroidBelt(star, 150, 6000, 250, 150, 250, Terrain.ASTEROID_BELT, "朝圣者之带");
			
		PlanetAPI eos3 = system.addPlanet("hesperus", star, "Hesperus", "rocky_ice", 0, 150, 7400, 200);
		eos3.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "sindria"));
		eos3.getSpec().setGlowColor(new Color(245,255,250,255));
		eos3.getSpec().setUseReverseLightForGlow(true);
		eos3.applySpecChanges();
		eos3.setCustomDescriptionId("planet_hesperus");
		eos3.setInteractionImage("illustrations", "hesperus");
		
			PlanetAPI eos3a = system.addPlanet("ceyx", eos3, "Ceyx", "barren-bombarded", 0, 35, 440, 16);
			eos3a.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "barren"));
			eos3a.getSpec().setGlowColor(new Color(200,230,255,200));
			eos3a.getSpec().setUseReverseLightForGlow(true);
			eos3a.applySpecChanges();
			eos3a.setCustomDescriptionId("planet_ceyx");
			eos3a.setFaction(Factions.KOL);
			
			PlanetAPI eos3b = system.addPlanet("daedaleon", eos3, "Daedaleon", "irradiated", 0, 50, 620, 33);
			eos3b.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
			eos3b.getSpec().setGlowColor(new Color(255,55,250,200));
			eos3b.getSpec().setUseReverseLightForGlow(true);
			eos3b.applySpecChanges();
			eos3b.setCustomDescriptionId("planet_daedaleon");
			SectorEntityToken eos3b_field = system.addTerrain(Terrain.MAGNETIC_FIELD,
					new MagneticFieldParams(200f, // terrain effect band width 
					160f, // terrain effect middle radius
					eos3b, // entity that it's around
					60f, // visual band start
					260f, // visual band end
					new Color(50, 20, 100, 50), // base color
					0.25f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
					new Color(90, 180, 140),
					new Color(130, 145, 190),
					new Color(165, 110, 225), 
					new Color(95, 55, 240), 
					new Color(45, 0, 250),
					new Color(20, 0, 240),
					new Color(10, 0, 150)));
			eos3b_field.setCircularOrbit(eos3b, 0, 0, 100);
			
			// needs a warning beacon!
			CustomCampaignEntityAPI beacon = system.addCustomEntity(null, null, Entities.WARNING_BEACON, Factions.NEUTRAL);
			beacon.setCircularOrbitPointingDown(eos3, 0, 750, 33);
			beacon.getMemoryWithoutUpdate().set("$daedaleon", true);
			//Misc.setWarningBeaconGlowColor(beacon, Global.getSector().getFaction(Factions.LUDDIC_CHURCH).getBrightUIColor());
			//Misc.setWarningBeaconPingColor(beacon, Global.getSector().getFaction(Factions.LUDDIC_CHURCH).getBrightUIColor());
			//And then use $daedaleon as a condition for custom interaction text in rules.csv.
			
			
		SectorEntityToken relay = system.addCustomEntity("hesperus_relay", "Hesperus Relay", "comm_relay", "luddic_church");
		relay.setCircularOrbitPointingDown( star, 0 + 60, 7400, 200);
		
		// Eos Exodus Gate - Tartessus L4 (ahead)
		SectorEntityToken gate = system.addCustomEntity("eos_exodus_gate", // unique id
				 "Eos Exodus Gate", // name - if null, defaultName from custom_entities.json will be used
				 "inactive_gate", // type of object, defined in custom_entities.json
				 null); // faction
		
		gate.setCircularOrbit(system.getEntityById("eos"), 150+60, 7400, 200);
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, star, StarAge.AVERAGE,
				1, 3, // min/max entities to add
				10000, // radius to start adding at 
				3, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds

		//StarSystemGenerator.addSystemwideNebula(system, StarAge.OLD);

		// generates hyperspace destinations for in-system jump points
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
	
	
}
