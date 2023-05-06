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

public class AlGebbar {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Al Gebbar");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		// Al Gebbar, "the giant"
		PlanetAPI algebbar_star = system.initStar("algebbar", // unique id for this star 
										    StarTypes.BLUE_GIANT,  // id in planets.json
										    1200f, 		  // radius (in pixels at default zoom)
										    800); // corona radius, from star edge
		
		system.setLightColor(new Color(210, 230, 255)); // light color in entire system, affects all entities
		
		
		// Gebbar a
		PlanetAPI gebbar2 = system.addPlanet("gebbar2", algebbar_star, "Kolasis", "lava_minor", 0, 65, 2400, 80);
			//gebbar2.getSpec().setPlanetColor(new Color(230,240,255,255));
		
		PlanetAPI gebbar3 = system.addPlanet("gebbar3", algebbar_star, "Basanizo", "lava", 0, 90, 2800, 110);
			//gebbar3.getSpec().setPlanetColor(new Color(230,240,255,255));
		
		system.addRingBand(algebbar_star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 3850, 80f);
		system.addRingBand(algebbar_star, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 4000, 80f);
		system.addRingBand(algebbar_star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 4120, 130f);
		
		system.addAsteroidBelt(algebbar_star, 100, 4000, 100, 256, 140, Terrain.ASTEROID_BELT, null);
		
		// Al Gebbar Jumppoint
		JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("algebbar_jump", "Al Gebbar 跳跃点");
		jumpPoint2.setCircularOrbit( system.getEntityById("algebbar"), 270 + 60, 5000, 225);
		
		system.addEntity(jumpPoint2);
		
		// add one ring to rule them all 
		// TODO: figure out syntax for this thing 'eh
		//SectorEntityToken ring = system.addTerrain(Terrain.RING, new RingParams(256 + 256, 3500, null, "The Gebbarian Chaos"));
		//ring.setCircularOrbit(algebbar_star, 0, 3500, 100);

		// Gebbar 2: bombarded minor
		PlanetAPI gebbar4 = system.addPlanet("epiphany", algebbar_star, "Epiphany", "barren-bombarded", 270, 80, 5000, 225);
			gebbar4.setCustomDescriptionId("planet_epiphany");	
			gebbar4.getSpec().setPlanetColor(new Color(255,240,225,255));
			//system.addRingBand(gebbar4, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 400, 40f);
			system.addRingBand(gebbar4, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 400, 40f);
			system.addAsteroidBelt(gebbar4, 20, 400, 40, 30, 50, Terrain.ASTEROID_BELT, "Custodes");
			
			// btw, jump-point is related to this one.
			jumpPoint2.setRelatedPlanet(gebbar4);
		
		SectorEntityToken al_gebbar_loc1 = system.addCustomEntity(null,null, "sensor_array_makeshift",Factions.LUDDIC_PATH); 
		al_gebbar_loc1.setCircularOrbitPointingDown( algebbar_star, 270 - 60, 5000, 225);
		
		PlanetAPI gebbar5 = system.addPlanet("gebbar5", algebbar_star, "Loutron", "cryovolcanic", 90, 120, 6400, 295);
			gebbar5.getSpec().setPlanetColor(new Color(235,255,245,255));
			
			SectorEntityToken abandoned_station = system.addCustomEntity("abandoned_station", "遗弃的采矿站", "station_mining00", "neutral");
			abandoned_station.setCircularOrbitPointingDown(system.getEntityById("gebbar5"), 90, 220, 25);		
			abandoned_station.setCustomDescriptionId("station_abandoned_mining");
			abandoned_station.setInteractionImage("illustrations", "orbital_construction");
			abandoned_station.addTag("abandoned");
			abandoned_station.addTag("volatiles_extraction_platform");
			
		SectorEntityToken al_gebbar_loc2 = system.addCustomEntity(null,null, "stable_location",Factions.NEUTRAL); 
		al_gebbar_loc2.setCircularOrbitPointingDown( algebbar_star, 90 + 60, 6400, 295);
			
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
