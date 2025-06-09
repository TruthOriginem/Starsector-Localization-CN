//Isirah.javapackage data.scripts.world.systems;
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
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.BaseRingTerrain.RingParams;
import com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin.MagneticFieldParams;

public class Isirah {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Isirah");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI isirah_star = system.initStar("isirah", // unique id for this star 
											StarTypes.BLUE_GIANT,  // id in planets.json
										    1100f, 		  // radius (in pixels at default zoom)
										    650); // corona radius, from star edge
		
		system.setLightColor(new Color(210, 230, 255)); // light color in entire system, affects all entities
		
		// giant lava world
		PlanetAPI isirah_b = system.addPlanet("sutr", isirah_star, "Surtr", "lava", 180, 190, 2000, 50);
		//isirah_b.setCustomDescriptionId("planet_sutr");
		
		SectorEntityToken isirah_b_field = system.addTerrain(Terrain.MAGNETIC_FIELD,
						new MagneticFieldParams(200f, // terrain effect band width 
						380, // terrain effect middle radius
						isirah_b, // entity that it's around
						180f, // visual band start
						580f, // visual band end
						new Color(100, 30, 50, 30), // base color
						1f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
						new Color(165, 35, 135, 130),
						new Color(200, 30, 115, 150), 
						new Color(220, 25, 100, 190),
						new Color(235, 25, 90, 240),
						new Color(240, 35, 80, 255),
						new Color(240, 65, 60), 
						new Color(240, 90, 45)
						));
		
		isirah_b_field.setCircularOrbit(isirah_b, 0, 0, 100);
		
		// Gilling w/ Fjalar / Galar 
		PlanetAPI isirah_c = system.addPlanet("gilling", isirah_star, "Gilling", "lava", 270, 100, 2750, 80);
			PlanetAPI isirah_c1 = system.addPlanet("fjalar", isirah_c, "Fjalar", "lava_minor", 270, 40, 200, 20);
			PlanetAPI isirah_c2 = system.addPlanet("galar", isirah_c, "Galar", "barren", 270, 30, 280, 37);
		
		// Isirah Inner Jumppoint
		JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("isirah_jump", "Isirah Inner Jump-point");
		jumpPoint2.setCircularOrbit( system.getEntityById("isirah"), 120 + 60, 2700, 80);
		jumpPoint2.setRelatedPlanet(isirah_c);
		system.addEntity(jumpPoint2);
			
		// Utgarda Disk : "the outyards" 
		system.addRingBand(isirah_star, "misc", "rings_dust0", 		256f, 0, Color.white, 256f, 3200, 80f);
		system.addRingBand(isirah_star, "misc", "rings_asteroids0", 256f, 1, Color.white, 256f, 3400, 100f);
		system.addRingBand(isirah_star, "misc", "rings_dust0", 		256f, 1, Color.white, 256f, 3300, 100f);
		system.addRingBand(isirah_star, "misc", "rings_asteroids0", 256f, 2, Color.white, 256f, 3400, 100f);
		system.addRingBand(isirah_star, "misc", "rings_dust0", 		256f, 1, Color.white, 256f, 3500, 100f);
		system.addRingBand(isirah_star, "misc", "rings_asteroids0", 256f, 3, Color.white, 256f, 3600, 130f);
		system.addRingBand(isirah_star, "misc", "rings_dust0", 		256f, 1, Color.white, 256f, 3750, 80f);
		
		system.addAsteroidBelt(isirah_star, 100, 3300, 180, 100, 140, Terrain.ASTEROID_BELT, null);
		system.addAsteroidBelt(isirah_star, 140, 3500, 180, 120, 160, Terrain.ASTEROID_BELT, null);
		system.addAsteroidBelt(isirah_star, 80, 3700, 180, 140, 180, Terrain.ASTEROID_BELT, null);
		
		SectorEntityToken ring1 = system.addTerrain(Terrain.RING, new RingParams(900, 3500, null, "Utgarda's Wall"));
		ring1.setCircularOrbit(isirah_star, 0, 0, 100);
		
		
		PlanetAPI isirah_d = system.addPlanet("corb", isirah_star, "Corb", "lava_minor", 180, 90, 4300, 400);
		// Morn accretion cloud
		SectorEntityToken isirah_d1 = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					400f, // min radius
					600f, // max radius
					20, // min asteroid count
					30, // max asteroid count
					4f, // min asteroid radius 
					14f, // max asteroid radius
					"Corb Accretion Swarm")); // null for default name
		
		isirah_d1.setCircularOrbit(isirah_star, 180 -10, 4300, 400);
		
		system.addAsteroidBelt(isirah_star, 50, 4200, 150, 170, 210, Terrain.ASTEROID_BELT, null);
		
		
		SectorEntityToken station1 = system.addCustomEntity("laicaille_habitat", "Laicaille Habitat", "station_side00", "persean");
		station1.setCustomDescriptionId("station_laicaille");
		station1.setInteractionImage("illustrations", "orbital");
		station1.setCircularOrbitWithSpin(isirah_star, 180+60, 4300, 400, -1f, -3f);
		
		SectorEntityToken station2 = system.addCustomEntity("station_kapteyn", "Kapteyn Starworks", "station_mining00", "pirates");
		station2.setCustomDescriptionId("station_kapteyn");
		station2.setInteractionImage("illustrations", "industrial_megafacility");
		//station2.setCircularOrbitPointingDown(system.getEntityById("isirah"), 180-60, 4300, 400);		
		station2.setCircularOrbitWithSpin(isirah_star, 180-60, 4300, 400, -1f, -3f);
		
		SectorEntityToken isirah_loc1 = system.addCustomEntity(null, null, "sensor_array_makeshift", Factions.PIRATES); 
		isirah_loc1.setCircularOrbitPointingDown(isirah_star, 180-120, 4300, 400);

		SectorEntityToken isirah_loc2 = system.addCustomEntity(null, null, "comm_relay_makeshift", Factions.PERSEAN); 
		isirah_loc2.setCircularOrbitPointingDown(isirah_star, 180+120, 4300, 400);
		
		// Isirah Gate
		SectorEntityToken gate = system.addCustomEntity("isirah_gate", // unique id
				 "Isirah Gate", // name - if null, defaultName from custom_entities.json will be used
				 "inactive_gate", // type of object, defined in custom_entities.json
				 null); // faction
		
		gate.setCircularOrbit(system.getEntityById("isirah"), 0, 4300, 160);
		
		
		// Vosud Disc : "grandfather of winter"
		system.addRingBand(isirah_star, "misc", "rings_ice0", 		256f, 0, Color.white, 256f, 4700, 80f);
		system.addRingBand(isirah_star, "misc", "rings_asteroids0", 256f, 1, Color.white, 256f, 4800, 100f);
		system.addRingBand(isirah_star, "misc", "rings_ice0", 		256f, 1, Color.white, 256f, 4900, 100f);
		system.addRingBand(isirah_star, "misc", "rings_asteroids0", 256f, 2, Color.white, 256f, 5000, 100f);
		system.addRingBand(isirah_star, "misc", "rings_ice0",		256f, 2, Color.white, 256f, 5100, 130f);
		system.addRingBand(isirah_star, "misc", "rings_ice0",		256f, 1, Color.white, 256f, 5250, 80f);
		
		system.addAsteroidBelt(isirah_star, 100, 5300, 180, 100, 140, Terrain.ASTEROID_BELT, null);
		system.addAsteroidBelt(isirah_star, 80, 5700, 180, 140, 180, Terrain.ASTEROID_BELT, null);
		
		SectorEntityToken ring2 = system.addTerrain(Terrain.RING, new RingParams(900, 4900, null, "Disk of Vosud"));
		ring2.setCircularOrbit(isirah_star, 0, 0, 100);
		
		
		// Norvia: mother of night
		// & Nott : "night"	
		PlanetAPI isirah_e = system.addPlanet("norvia", isirah_star, "Norvia", "frozen", 270, 100, 7000, 500);
		//isirah_d.setCustomDescriptionId("planet_norvia");
			PlanetAPI isirah_e1 = system.addPlanet("nott", isirah_e, "Nott", "frozen", 180, 40, 240, 60);	
			isirah_e1.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "frozen00"));
			isirah_e1.applySpecChanges();
			
		SectorEntityToken station3 = system.addCustomEntity("groombridge_habitat", "Groombridge Habitat", "station_side00", "neutral");
		station3.setCustomDescriptionId("station_groombridge");
		station3.setInteractionImage("illustrations", "abandoned_station3");
		station3.setCircularOrbitPointingDown(isirah_star, 270+60, 7000, 500);
		
		system.autogenerateHyperspaceJumpPoints(true, true);
		
		StarSystemGenerator.addSystemwideNebula(system, StarAge.YOUNG);
	}
}
