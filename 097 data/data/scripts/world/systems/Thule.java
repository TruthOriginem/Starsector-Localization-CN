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
import com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain;
import com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin.MagneticFieldParams;

public class Thule {

	public void generate(SectorAPI sector) {	
		
		StarSystemAPI system = sector.createStarSystem("Thule");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI thule_star = system.initStar("thule", // unique id for this star 
											    "star_white",  // id in planets.json
											    600f, 		  // radius (in pixels at default zoom)
											    150, // corona
											    4f, // solar wind burn level
												0.5f, // flare probability
												1.5f); // CR loss multiplier, good values are in the range of 1-5
		
		system.setLightColor(new Color(200, 230, 255)); // light color in entire system, affects all entities
		
		
		PlanetAPI hekla = system.addPlanet("hekla", thule_star, "Hekla", "toxic", 0, 160, 2870, 90);
		hekla.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		hekla.getSpec().setGlowColor( new Color(255,190,10,100) );
		hekla.getSpec().setUseReverseLightForGlow(true);
		hekla.getSpec().setPlanetColor( new Color(255, 235, 170,255) );
		hekla.applySpecChanges();
		
				SectorEntityToken array1 = system.addCustomEntity(null, null, "sensor_array", Factions.PERSEAN); 
				array1.setCircularOrbitPointingDown( thule_star, 60, 2870, 90);
		
		system.addAsteroidBelt(thule_star, 90, 3750, 500, 100, 120, Terrain.ASTEROID_BELT,  "The Ingwin");
		system.addRingBand(thule_star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 3600, 105f, null, null);
		system.addRingBand(thule_star, "misc", "rings_asteroids0", 256f, 0, Color.white, 256f, 3720, 115f, null, null);
		
		PlanetAPI laki = system.addPlanet("laki", thule_star, "Laki", "barren3", 0, 95, 4000, 100);
		
		system.addAsteroidBelt(thule_star, 90, 4550, 500, 290, 310, Terrain.ASTEROID_BELT,  "Hama's Band");
		system.addRingBand(thule_star, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 4500, 305f, null, null);
		system.addRingBand(thule_star, "misc", "rings_asteroids0", 256f, 1, Color.white, 256f, 4600, 295f, null, null);
		
		PlanetAPI kazeron = system.addPlanet("kazeron", thule_star, "Kazeron", "barren_castiron", 90, 170, 5200, 225);
		kazeron.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "sindria"));
		kazeron.getSpec().setGlowColor( new Color(255,160,30,255) );
		kazeron.getSpec().setUseReverseLightForGlow(true);
		kazeron.getSpec().setPitch(-15f);
		kazeron.getSpec().setTilt(20f);
		kazeron.applySpecChanges();
		kazeron.setCustomDescriptionId("planet_kazeron");
		kazeron.setInteractionImage("illustrations", "kazeron");
		
		//SectorEntityToken kazeronStation = system.addCustomEntity("kazeron_station", "Kazeron Star Command", "station_midline2", Factions.PERSEAN);
		//kazeronStation.setCircularOrbitPointingDown( kazeron, 0, 150, 30);		
		//kazeronStation.setInteractionImage("illustrations", "orbital");
		
			PlanetAPI draugr = system.addPlanet("draugr", kazeron, "Draugr", "barren-bombarded", 0, 50, 420, 24);
			draugr.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
			draugr.getSpec().setGlowColor( new Color(255,220,50,35) );
			draugr.getSpec().setUseReverseLightForGlow(true);
			draugr.getSpec().setPitch(-90f);
			draugr.getSpec().setTilt(90f);
			draugr.getSpec().setPlanetColor( new Color(255, 245, 230,255) );
			draugr.applySpecChanges();

			// Kazeron jump-point
			JumpPointAPI jumpPoint1 = Global.getFactory().createJumpPoint("thule_jump", "Thule Jump-point");
			jumpPoint1.setCircularOrbit( system.getEntityById("thule"), 30, 5200, 225);
			jumpPoint1.setRelatedPlanet(kazeron);
			system.addEntity(jumpPoint1);
			
			// Kazeron Relay - L5 (behind)
			SectorEntityToken kazeron_relay = system.addCustomEntity("kazeron_relay", "Kazeron Relay",  "comm_relay", Factions.PERSEAN); 
			kazeron_relay.setCircularOrbitPointingDown( thule_star, 150, 5200, 225);
			
		system.addAsteroidBelt(thule_star, 90, 5950, 500, 150, 300, Terrain.ASTEROID_BELT,  "Inged's Crown");
		system.addRingBand(thule_star, "misc", "rings_ice0", 256f, 1, Color.white, 256f, 5900, 305f, null, null);
		system.addRingBand(thule_star, "misc", "rings_ice0", 256f, 2, Color.white, 256f, 6020, 295f, null, null);
			
		PlanetAPI eldfell = system.addPlanet("eldfell", thule_star, "Eldfell", "barren2", 180, 140, 6700, 360);
		eldfell.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		eldfell.getSpec().setGlowColor( new Color(250,220,210,45) );
		eldfell.getSpec().setUseReverseLightForGlow(true);
		eldfell.getSpec().setPitch(20f);
		eldfell.getSpec().setTilt(30f);
		eldfell.getSpec().setPlanetColor( new Color(220, 245, 255,255) );
		eldfell.getSpec().setAtmosphereThicknessMin(16);
		eldfell.getSpec().setAtmosphereThickness(0.14f);
		eldfell.getSpec().setAtmosphereColor( new Color(230,245,255,30) );
		eldfell.applySpecChanges();
		eldfell.setCustomDescriptionId("planet_eldfell");
		
				// Gate of Thule
				SectorEntityToken gate = system.addCustomEntity("thule_gate", // unique id
						 "Gate of Thule", // name - if null, defaultName from custom_entities.json will be used
						 "inactive_gate", // type of object, defined in custom_entities.json
						 null); // faction

				gate.setCircularOrbit(thule_star, 180+60, 6700, 360);
				
				SectorEntityToken eldfell_stable = system.addCustomEntity(null, null, "stable_location", "neutral");
				eldfell_stable.setCircularOrbitPointingDown( thule_star, 180-60 , 6700, 360);
				
		system.addAsteroidBelt(thule_star, 90, 7650, 500, 150, 300, Terrain.ASTEROID_BELT,  "Garmund's Ring");
		system.addRingBand(thule_star, "misc", "rings_ice0", 256f, 2, Color.white, 256f, 7750, 385, null, null);
		system.addRingBand(thule_star, "misc", "rings_ice0", 256f, 1, new Color(230,240,255,255), 256f, 7870, 395, null, null);
		
			// Pirate Station - nestled amongst the icestroids
			SectorEntityToken thule_pirate_station = system.addCustomEntity("thule_pirate_station",
					"Thulian Raider Base", "station_pirate_type", "pirates");
			
			thule_pirate_station.setCircularOrbitPointingDown(system.getEntityById("thule"), 240, 7900, 380);		
			thule_pirate_station.setCustomDescriptionId("station_thulian_raiders");
			thule_pirate_station.setInteractionImage("illustrations", "pirate_station");
		
		PlanetAPI morn = system.addPlanet("morn", thule_star, "Morn", "ice_giant", 90, 240, 8500, 390);
		morn.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		morn.getSpec().setGlowColor( new Color(235,250,150,45) );
		morn.getSpec().setUseReverseLightForGlow(true);
		morn.getSpec().setPitch(-5f);
		morn.getSpec().setTilt(20f);
		morn.getSpec().setPlanetColor( new Color(255, 185, 165, 255) );
		morn.applySpecChanges();
		
			// Morn L5 cloud
			SectorEntityToken nebula1 = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
					"      " +
					"  xx x" +
					" xxxxx" +
					"xxx xx" +
					" xxx  " +
					"  xxx ",
					6, 6, // size of the nebula grid, should match above string
					"terrain", "nebula", 4, 4, "Morn L5 Cloud"));
			nebula1.getLocation().set(morn.getLocation().x + 1000f, morn.getLocation().y);
			nebula1.setCircularOrbit(thule_star,
										morn.getCircularOrbitAngle() - 60f,
										morn.getCircularOrbitRadius(), 
										390);
			
			// Morn L4 cloud
			SectorEntityToken nebula2 = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
					"  x xx" +
					" xxx  " +
					"   xx " +
					"xxxxxx" +
					"  xx  " +
					" x    ",
					6, 6, // size of the nebula grid, should match above string
					"terrain", "nebula", 4, 4, "Morn L4 Cloud"));
			nebula2.getLocation().set(morn.getLocation().x - 1000f, morn.getLocation().y);
			nebula2.setCircularOrbit(thule_star,
									morn.getCircularOrbitAngle() + 60f,
									morn.getCircularOrbitRadius(), 
									390);
		
		PlanetAPI skoll = system.addPlanet("skoll", thule_star, "Skoll", "ice_giant", 270, 260, 9450, 420);
		skoll.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "aurorae"));
		skoll.getSpec().setGlowColor( new Color(50,255,250,75) );
		skoll.getSpec().setUseReverseLightForGlow(true);
		skoll.getSpec().setPitch(150f);
		skoll.getSpec().setTilt(80f);
		skoll.getSpec().setPlanetColor( new Color(150, 255, 205,255) );
		skoll.applySpecChanges();
		
			SectorEntityToken skoll_magfield = system.addTerrain(Terrain.MAGNETIC_FIELD,
			new MagneticFieldParams(skoll.getRadius() + 150f, // terrain effect band width 
					(skoll.getRadius() + 150f) / 2f, // terrain effect middle radius
					skoll, // entity that it's around
					skoll.getRadius() + 50f, // visual band start
					skoll.getRadius() + 50f + 200f, // visual band end
					new Color(50, 20, 100, 50), // base color
					0.15f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
					new Color(90, 180, 40),
					new Color(130, 145, 90),
					new Color(165, 110, 145), 
					new Color(95, 55, 160), 
					new Color(45, 0, 130),
					new Color(20, 0, 130),
					new Color(10, 0, 150)));
			skoll_magfield.setCircularOrbit(skoll, 0, 0, 100);
		
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, thule_star, StarAge.AVERAGE,
				1, 2, // min/max entities to add
				11200, // radius to start adding at 
				6, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds
		
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
