package data.scripts.world.systems;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.StarTypes;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator.StarSystemType;
import com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageSpecialAssigner;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
import com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin.MagneticFieldParams;
import com.fs.starfarer.api.util.Misc;

public class TiaTaxet {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Tia");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		// Tia, the peaceful star.
		PlanetAPI tia_star = system.initStar("tia", // unique id for this star 
											StarTypes.ORANGE_GIANT,  // id in planets.json
										    950f, 		  // radius (in pixels at default zoom)
										    200); // corona radius, from star edge
		
		system.setLightColor(new Color(255, 230, 200)); // light color in entire system, affects all entities
		
		// double star, second partner: Tax'et, the violent star.
		// gets a strong mag field (can we do coronas on fake stars? something to add to the TODO list ... )
		PlanetAPI taxet_star = system.addPlanet("taxet",
												tia_star,
												"Ta'xet",
												StarTypes.RED_DWARF,
												0, 400, 1600, 30);
		system.setSecondary(taxet_star);
		system.setType(StarSystemType.BINARY_FAR);
		/*
		SectorEntityToken taxet_star_field = system.addTerrain(Terrain.MAGNETIC_FIELD,
				new MagneticFieldParams(taxet_star.getRadius() + 160f, // terrain effect band width 
				(taxet_star.getRadius() + 160f) / 2f, // terrain effect middle radius
				taxet_star, // entity that it's around
				taxet_star.getRadius() + 50f, // visual band start
				taxet_star.getRadius() + 50f + 200f, // visual band end
				new Color(75, 105, 165, 75), // base color
				1.0f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
				new Color(55, 60, 140),
				new Color(65, 85, 155),
				new Color(175, 105, 165), 
				new Color(90, 130, 180), 
				new Color(105, 150, 190),
				new Color(120, 175, 205),
				new Color(135, 200, 220))); 
			
		taxet_star_field.setCircularOrbit(taxet_star, 0, 0, 50);
		*/
		
		SectorEntityToken tia_stable1 = system.addCustomEntity(null, null, "stable_location", "neutral");
		tia_stable1.setCircularOrbitPointingDown(tia_star, 0 , 2900, 100);
		
		// An asteroid belts
		system.addRingBand(tia_star, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 4300, 220f, null, null);
		system.addRingBand(tia_star, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 4400, 226f, null, null);
		system.addAsteroidBelt(tia_star, 150, 3600, 170, 200, 250, Terrain.ASTEROID_BELT, "Lagua's Torus");
		
		// Arbitrary Asteroid field 
		SectorEntityToken tia_field1 = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					500f, // min radius
					700f, // max radius
					30, // min asteroid count
					40, // max asteroid count
					4f, // min asteroid radius 
					16f, // max asteroid radius
					"Tia-Tax'et Asteroid Cluster")); // null for default name
		
		tia_field1.setCircularOrbit(tia_star, 180, 4500, 250);
		
		
		// Abandoned shipyard /research station
			SectorEntityToken abandoned_station1 = system.addCustomEntity("abandoned_spacedock", "Abandoned Research Station", "station_side00", "neutral");
			//abandoned_station1.setCustomDescriptionId("station_tiataxet_abandoned_spacedock");
			abandoned_station1.setInteractionImage("illustrations", "space_wreckage");
			abandoned_station1.setCircularOrbitPointingDown(system.getEntityById("tia"), 180, 4450, 250);
			abandoned_station1.setCustomDescriptionId("station_tiataxet_spacedock");
		
			// "Nothing Personal" battle debris :D
			DebrisFieldParams params = new DebrisFieldParams(
					200f, // field radius - should not go above 1000 for performance reasons
					-1f, // density, visual - affects number of debris pieces
					10000000f, // duration in days 
					0f); // days the field will keep generating glowing pieces
			
			params.source = DebrisFieldSource.MIXED;
			params.baseSalvageXP = 250; // base XP for scavenging in field
			SectorEntityToken debris = Misc.addDebrisField(system, params, StarSystemGenerator.random);
			SalvageSpecialAssigner.assignSpecialForDebrisField(debris);
			
			// makes the debris field always visible on map/sensors and not give any xp or notification on being discovered
			debris.setSensorProfile(null);
			debris.setDiscoverable(null);
			debris.setCircularOrbit(system.getEntityById("tia"), 180 + 15, 4550, 250);
			
			
		// More asteroid belts, please
		system.addRingBand(tia_star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 5000, 320f, null, null);
		system.addRingBand(tia_star, "misc", "rings_asteroids0", 256f, 3, Color.white, 256f, 5100, 326f, null, null);
		system.addAsteroidBelt(tia_star, 150, 5050, 350, 200, 250, Terrain.ASTEROID_BELT, null);
		
		
		// And more clusters
		
		// Arbitrary Asteroid field #2
		SectorEntityToken tia_field2 = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					500f, // min radius
					700f, // max radius
					35, // min asteroid count
					45, // max asteroid count
					4f, // min asteroid radius 
					16f, // max asteroid radius
					null)); // null for default name
		
		tia_field2.setCircularOrbit(tia_star, 45, 5500, 340);
		
		// Arbitrary Asteroid field #3
		SectorEntityToken tia_field3 = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					300f, // min radius
					500f, // max radius
					24, // min asteroid count
					30, // max asteroid count
					4f, // min asteroid radius 
					16f, // max asteroid radius
					null)); // null for default name
		
		tia_field3.setCircularOrbit(tia_star, 45 + 180, 5400, 340);
		
		
		PlanetAPI tia2 = system.addPlanet("tia2", tia_star, "Ogre", "irradiated", 0, 90, 5700, 400);
		
		SectorEntityToken tia_stable2 = system.addCustomEntity(null, null, "stable_location", "neutral");
		tia_stable2.setCircularOrbitPointingDown(tia_star, -60 , 5700, 400);
		
		// Arbitrary Asteroid field #4
		SectorEntityToken tia_field4 = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					300f, // min radius
					500f, // max radius
					38, // min asteroid count
					50, // max asteroid count
					4f, // min asteroid radius 
					16f, // max asteroid radius
					"The Mosquitos")); // null for default name
		
		tia_field4.setCircularOrbit(tia_star, 180, 5700, 400);
		
		
		// Tia-Tax'et Jumppoint
		JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("tia-taxet_jump", "Tia-Tax'et Jump-point");
		jumpPoint2.setCircularOrbit( system.getEntityById("tia"), 270+60, 11500, 800);
		jumpPoint2.setRelatedPlanet(tia2);
		system.addEntity(jumpPoint2);
		
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
