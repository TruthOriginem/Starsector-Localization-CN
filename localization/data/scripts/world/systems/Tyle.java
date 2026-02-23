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
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator.StarSystemType;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin.MagneticFieldParams;

public class Tyle {

	public void generate(SectorAPI sector) {
		
		
		StarSystemAPI system = sector.createStarSystem("Tyle");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background3.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI tyle_star = system.initStar("tyle", // unique id for this star 
										    "star_orange",  // id in planets.json
										    675f, 		  // radius (in pixels at default zoom)
										    460, // corona
										    10f, // solar wind burn level
											0.75f, // flare probability
											3.2f); // CR loss multiplier, good values are in the range of 1-5
		
		system.setLightColor(new Color(245, 230, 235)); // light color in entire system, affects all entities
		
		PlanetAPI tyle_a = system.addPlanet("satanazes", tyle_star, "Satanazes", "gas_giant", 0, 280, 1900, 40);
		//PlanetAPI tyle_a = system.addPlanet("satanazes", tyle_star, "Satanazes", "lava", 0, 115, 1900, 40);
		tyle_a.getSpec().setPlanetColor(new Color(40,25,35,255));
		tyle_a.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		tyle_a.getSpec().setGlowColor( new Color(255,50,20,45) );
		tyle_a.getSpec().setAtmosphereThickness(0.2f);
		tyle_a.getSpec().setUseReverseLightForGlow(true);
		tyle_a.getSpec().setPitch(-20f);
		tyle_a.getSpec().setTilt(-10f);
		tyle_a.applySpecChanges();
		tyle_a.setCustomDescriptionId("planet_satanazes");
		
		
		PlanetAPI tyle_b = system.addPlanet("cibola", tyle_star, "Cibola", "desert", 0, 145, 3500, 210);
		tyle_b.setCustomDescriptionId("planet_cibola");
		tyle_b.setInteractionImage("illustrations", "desert_moons_ruins");
		
			SectorEntityToken cibola_stable = system.addCustomEntity(null, null, "nav_buoy_makeshift", "persean");
			cibola_stable.setCircularOrbitPointingDown(tyle_star, 60 , 3500, 210);
		
		PlanetAPI tyle_b1 = system.addPlanet("dorado", tyle_b, "Dorado", "barren-bombarded", 0, 48, 400, 24);
		
		// An asteroid field
		system.addRingBand(tyle_star, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 4300, 220f, null, null);
		system.addRingBand(tyle_star, "misc", "rings_asteroids0", 256f, 1, Color.white, 256f, 4400, 226f, null, null);
		system.addAsteroidBelt(tyle_star, 60, 4350, 170, 200, 250, Terrain.ASTEROID_BELT, "Tyle's 小行星带");
		
		PlanetAPI tyle_c = system.addPlanet("zuni", tyle_star, "Zuni", "barren", 90, 60, 4800, 260);
		
		// Arbitrary Asteroid field 
		SectorEntityToken tyle_field1 = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					300f, // min radius
					640f, // max radius
					30, // min asteroid count
					40, // max asteroid count
					4f, // min asteroid radius 
					16f, // max asteroid radius
					"Wuya 小行星团")); // null for default name
		
		tyle_field1.setCircularOrbit(tyle_star, 90 + 60, 4800, 260);
		
			// and hide a gate in it
			// Wuya Gate
/*			SectorEntityToken gate = system.addCustomEntity("wuya_gate", // unique id
					 "Wuya Gate", // name - if null, defaultName from custom_entities.json will be used
					 "inactive_gate", // type of object, defined in custom_entities.json
					 null); // faction
			gate.setCircularOrbit(system.getEntityById("tyle"), 90+62, 4800, 260);*/
		
		// Wuya Jump
		JumpPointAPI jumpPoint1 = Global.getFactory().createJumpPoint("wuya_jump", "Wuya 跳跃点");
		jumpPoint1.setCircularOrbit( system.getEntityById("tyle"), 90-60, 4800, 260);
		jumpPoint1.setRelatedPlanet(tyle_c);
		system.addEntity(jumpPoint1);
		
		// Canaria gas giant, Lagrangian of Antilles
		PlanetAPI tyle_d = system.addPlanet("canaria", tyle_star, "Canaria", "ice_giant", 180 + 60, 250, 9000, 750);
		tyle_d.setCustomDescriptionId("planet_canaria");
			
			PlanetAPI tyle_d1 = system.addPlanet("madeira", tyle_d, "Madeira", "tundra", 0, 80, 700, 34);
			tyle_d1.setCustomDescriptionId("planet_madeira");
			//pirateStation.setInteractionImage("illustrations", "pirate_station");
			
			// Canaria Relay
			SectorEntityToken canaria_relay = system.addCustomEntity("canaria_relay", "Canaria 通讯中继站", "comm_relay", "persean");
			canaria_relay.setCircularOrbitPointingDown( system.getEntityById("canaria"), 180, 700, 34);
		
		SectorEntityToken tyle_field2 = system.addTerrain(Terrain.ASTEROID_FIELD,
				new AsteroidFieldParams(
					500f, // min radius
					740f, // max radius
					45, // min asteroid count
					60, // max asteroid count
					4f, // min asteroid radius 
					20f, // max asteroid radius
					"Sargossa 小行星团")); // null for default name
		
		tyle_field2.setCircularOrbit(tyle_star, 180 - 60, 9000, 750);
		
		// stable loc orbits the asteroid field.
		SectorEntityToken sargossa_stable = system.addCustomEntity(null, null, "stable_location", Factions.NEUTRAL);
		sargossa_stable.setCircularOrbitPointingDown(tyle_field2, 00 , 500, 110);
		
		// Star: Antillia gets a strong mag field (can we do coronas on fake stars? something to add to the list ... )
		PlanetAPI antillia_star = system.addPlanet("antillia",
												tyle_star,
												"Antillia",
												"star_white",
												180, // angle
												350, // radius
												9000, // orbit radius
												750); // orbit days
		system.setSecondary(antillia_star);
		system.setType(StarSystemType.BINARY_FAR);
		SectorEntityToken antillia_star_field = system.addTerrain(Terrain.MAGNETIC_FIELD,
				new MagneticFieldParams(antillia_star.getRadius() + 160f, // terrain effect band width 
				( antillia_star.getRadius() + 160f) / 2f, // terrain effect middle radius
				  antillia_star, // entity that it's around
				  antillia_star.getRadius() + 50f, // visual band start
				  antillia_star.getRadius() + 50f + 200f, // visual band end
				new Color(75, 105, 165, 75), // base color
				1.0f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
				new Color(55, 60, 140),
				new Color(65, 85, 155),
				new Color(175, 105, 165), 
				new Color(90, 130, 180), 
				new Color(105, 150, 190),
				new Color(120, 175, 205),
				new Color(135, 200, 220)));
		
		antillia_star_field.setCircularOrbit(antillia_star, 0, 0, 50);
		
		PlanetAPI antillia_b = system.addPlanet("antillia_b", antillia_star, "Kardara", "barren-bombarded", 0, 65, 2000, 120);
		
		// Tyle-Antillia Jumppoint
		/*JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("antillia_jump", "Antillia Jump-point");
		jumpPoint2.setCircularOrbit( system.getEntityById("antillia_b"), 270+60, 11500, 800);
		jumpPoint2.setRelatedPlanet(antillia_b);
		system.addEntity(jumpPoint2);*/
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, tyle_star, StarAge.OLD,
				2, 3, // min/max entities to add
				11500, // radius to start adding at 
				5, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds
		
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
