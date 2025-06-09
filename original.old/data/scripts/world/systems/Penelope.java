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
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.StarTypes;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin.MagneticFieldParams;

public class Penelope {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Penelope's Star");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		if (Global.getSettings().getBoolean("factionsClaimUnpopulatedCoreSystems")) {
			system.getMemoryWithoutUpdate().set(MemFlags.CLAIMING_FACTION, Factions.LUDDIC_CHURCH);
		}
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background4.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI penelope_star = system.initStar("penelope", // unique id for this star 
											StarTypes.RED_GIANT,  // id in planets.json
										    1100f, 		  // radius (in pixels at default zoom)
										    500); // corona radius, from star edge
		system.setLightColor(new Color(255, 200, 210)); // light color in entire system, affects all entities
		
		// hot asteroid belt
//		system.addAsteroidBelt(penelope_star, 50, 2200, 100, 30, 40, Terrain.ASTEROID_BELT, null);
//		system.addRingBand(penelope_star, "misc", "rings_asteroids0", 256f, 3, Color.white, 256f, 13750, 345f, Terrain.ASTEROID_BELT, null);
		
		system.addAsteroidBelt(penelope_star, 50, 2200, 100, 30, 40, Terrain.ASTEROID_BELT, null);
		system.addRingBand(penelope_star, "misc", "rings_asteroids0", 256f, 3, Color.white, 256f, 2200, 345f, null, null);
		
		// Thrinakia: lava-fied by tidal forces from primary.
		PlanetAPI penelope1 = system.addPlanet("penelope1", penelope_star, "Thrinakia", "lava_minor", 30, 110, 2850, 90);
		
		// Ithaca, terraforming target A
		PlanetAPI penelope2 = system.addPlanet("penelope2", penelope_star, "Ithaca", "desert", 50, 150, 4500, 135);
		penelope2.setCustomDescriptionId("planet_ithaca");
			// TODO: some abandoned infrastructure?
		
			SectorEntityToken ithaca_loc = system.addCustomEntity(null,null, "stable_location",Factions.NEUTRAL); 
			ithaca_loc.setCircularOrbitPointingDown( penelope_star, 50 + 60, 4500, 135);	
		
		// Ogygia, terraforming target B
		PlanetAPI penelope3 = system.addPlanet("penelope3", penelope_star, "Ogygia", "barren-bombarded", 80, 130, 6800, 225);
		penelope3.getSpec().setPlanetColor(new Color(230,240,255,255));
		penelope3.applySpecChanges();
		penelope3.setCustomDescriptionId("planet_ogygia");
		
			PlanetAPI penelope3a = system.addPlanet("penelope3a", penelope3, "Calypso", "barren-bombarded", 80, 60, 400, 25);
			penelope3a.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren02"));
			penelope3a.getSpec().setPlanetColor(new Color(220,230,255,255));
			penelope3a.applySpecChanges();
			
			// Penelope's Jumppoint - L4 (ahead)
			JumpPointAPI p_jumpPoint = Global.getFactory().createJumpPoint("penelope_jump", "Penelope's Inner Jump-point");
			p_jumpPoint.setCircularOrbit(system.getEntityById("penelope"), 80+60, 6800, 225);
			p_jumpPoint.setRelatedPlanet(penelope3);
			
			p_jumpPoint.setStandardWormholeToHyperspaceVisual();
			system.addEntity(p_jumpPoint);
		
		// Aeolus, magnetic storms
		PlanetAPI penelope4 = system.addPlanet("penelope4", penelope_star, "Aeolus", "gas_giant", 230, 275, 9500, 450);
		penelope4.getSpec().setPlanetColor(new Color(150,245,255,255));
		penelope4.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		penelope4.getSpec().setGlowColor(new Color(250,225,55,64));
		penelope4.getSpec().setUseReverseLightForGlow(true);
		penelope4.applySpecChanges();
		
		SectorEntityToken penelope4_field = system.addTerrain(Terrain.MAGNETIC_FIELD,
				new MagneticFieldParams(penelope4.getRadius() + 160f, // terrain effect band width 
				(penelope4.getRadius() + 160f) / 2f, // terrain effect middle radius
				penelope4, // entity that it's around
				penelope4.getRadius() + 50f, // visual band start
				penelope4.getRadius() + 50f + 200f, // visual band end
				new Color(50, 20, 100, 50), // base color
				0.5f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
				new Color(90, 180, 40),
				new Color(130, 145, 90),
				new Color(165, 110, 145), 
				new Color(95, 55, 160), 
				new Color(45, 0, 130),
				new Color(20, 0, 130),
				new Color(10, 0, 150)));
		penelope4_field.setCircularOrbit(penelope4, 0, 0, 100);
		
			// Dorus, Xuthus
			PlanetAPI penelope4a = system.addPlanet("penelope4a", penelope4, "Dorus", "barren", 30, 50, 700, 22);
			penelope4a.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "venuslike"));
			penelope4a.getSpec().setPlanetColor(new Color(185,245,255,255));
			penelope4a.applySpecChanges();
			
			PlanetAPI penelope4b = system.addPlanet("penelope4b", penelope4, "Xuthus", "toxic_cold", 120, 70, 1000, 45);
			penelope4b.getSpec().setPlanetColor(new Color(190,235,255,255));
			penelope4b.applySpecChanges();
			// todo: abortive orbital AM harvesting scheme?
			
			// Penelope trojans
			SectorEntityToken penelopeL4 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						400f, // min radius
						600f, // max radius
						16, // min asteroid count
						24, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Penelope L4 Asteroids")); // null for default name
			
			SectorEntityToken penelopeL5 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						400f, // min radius
						600f, // max radius
						16, // min asteroid count
						24, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Penelope L5 Asteroids")); // null for default name
			
			penelopeL4.setCircularOrbit(penelope_star, 230 + 60, 9500, 450);
			penelopeL5.setCircularOrbit(penelope_star, 230 - 60, 9500, 450);
			
			SectorEntityToken aeolus_l5_loc = system.addCustomEntity(null,null, "stable_location",Factions.NEUTRAL); 
			aeolus_l5_loc.setCircularOrbitPointingDown( penelope_star, 230 - 60, 9500, 450);		
			
			SectorEntityToken aeolus_counter_loc = system.addCustomEntity(null,null, "stable_location",Factions.NEUTRAL); 
			aeolus_counter_loc.setCircularOrbitPointingDown( penelope_star, 230 - 180, 9500, 450);
			
		PlanetAPI penelope5 = system.addPlanet("penelope5", penelope_star, "Telepylus", "gas_giant", 250, 280, 12050, 650);
		penelope5.getSpec().setPlanetColor(new Color(170,190,255,255));
		penelope5.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		penelope5.getSpec().setGlowColor(new Color(250,225,155,32));
		penelope5.applySpecChanges();
		
		// Telepylus station : staging area for volatiles transport Oxen
			SectorEntityToken telepylus_station = system.addCustomEntity("telepylus_station", "Telepylus Station", "station_sporeship_derelict", "neutral");
			telepylus_station.setCircularOrbitPointingDown(system.getEntityById("penelope5"), 90, 420, 25);		
			telepylus_station.setCustomDescriptionId("station_telepylus");
			telepylus_station.setInteractionImage("illustrations", "abandoned_station3");
		
			system.addRingBand(penelope5, "misc", "rings_special0", 256f, 1, new Color(200,200,200,255), 256f, 600, 30f, Terrain.RING, null); 
		
		// Outer system jump-point
			// Telepylus Jumppoint - L5 (behind)
			JumpPointAPI p_jumpPoint2 = Global.getFactory().createJumpPoint("penelope_jump", "Penelope's Outer Jump-point");
			p_jumpPoint2.setCircularOrbit(system.getEntityById("penelope"), 250-60, 12050, 650);
			//p_jumpPoint2.setRelatedPlanet(penelope5);
			
			p_jumpPoint2.setStandardWormholeToHyperspaceVisual();
			system.addEntity(p_jumpPoint2);
			
			
		// Asteroid belt.
//		system.addRingBand(penelope_star, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 13750, 345f, Terrain.ASTEROID_BELT, null);
//		system.addAsteroidBelt(penelope_star, 100, 13750, 200, 330, 360, Terrain.ASTEROID_BELT, "The Cyclopeans");
		system.addAsteroidBelt(penelope_star, 100, 13750, 200, 330, 360, Terrain.ASTEROID_BELT, "The Cyclopeans");
		system.addRingBand(penelope_star, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 13750, 345f, null, null);

		// Ismara : mass driven volatiles, looted
		PlanetAPI penelope6 = system.addPlanet("penelope6", penelope_star, "Ismara", "cryovolcanic", 130, 100, 14520, 335);
		penelope6.setCustomDescriptionId("planet_ismara");
		
		//JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("penelope_jump_point_alpha", "Penelope's Star Inner System Jump");
		//OrbitAPI orbit = Global.getFactory().createCircularOrbit(penelope_star, 0, 800, 45);
		//jumpPoint.setOrbit(orbit);
		//jumpPoint.setRelatedPlanet(penelope_star);
		//jumpPoint.setStandardWormholeToHyperspaceVisual();
		//system.addEntity(jumpPoint);

		system.autogenerateHyperspaceJumpPoints(true, true);
		
	}
}
