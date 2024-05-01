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

public class Zagan {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Zagan");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background5.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI zagan_star = system.initStar("zagan", // unique id for this star 
											    "star_white",  // id in planets.json
											    700f, 		  // radius (in pixels at default zoom)
											    600, // corona
											    17f, // solar wind burn level
												1.5f, // flare probability
												5.5f); // CR loss multiplier, good values are in the range of 1-5
		
		system.setLightColor(new Color(235, 255, 240)); // light color in entire system, affects all entities
		
		PlanetAPI geburah = system.addPlanet("geburah", zagan_star, "Geburah", "lava", 0, 110, 2500, 80);
		geburah.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		geburah.getSpec().setGlowColor( new Color(255,140,10,200) );
		geburah.getSpec().setUseReverseLightForGlow(true);
		geburah.applySpecChanges();
		
		
		system.addAsteroidBelt(zagan_star, 90, 3650, 500, 150, 300, Terrain.ASTEROID_BELT,  "Shoals of Perdition");
		system.addRingBand(zagan_star, "misc", "rings_asteroids0", 256f, 3, Color.white, 256f, 3600, 295f, null, null);
		system.addRingBand(zagan_star, "misc", "rings_dust0", 256f, 3, Color.white, 256f, 3720, 305f, null, null);
		
		
		PlanetAPI mazalot = system.addPlanet("mazalot", zagan_star, "Mazalot", "arid", 180, 165, 5500, 220);
		mazalot.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "sindria"));
		mazalot.getSpec().setGlowColor(new Color(240,175,200,255));
		mazalot.getSpec().setPitch(10f); 
		mazalot.getSpec().setUseReverseLightForGlow(true);
		mazalot.getSpec().setTexture( Global.getSettings().getSpriteName("planets", "terran_eccentric") );
		mazalot.getSpec().setPlanetColor( new Color(255, 230, 150,255) );
		mazalot.applySpecChanges();
		mazalot.setCustomDescriptionId("planet_mazalot");
		
		SectorEntityToken mazalot_magfield = system.addTerrain(Terrain.MAGNETIC_FIELD,
		new MagneticFieldParams(mazalot.getRadius() + 150f, // terrain effect band width 
				(mazalot.getRadius() + 150f) / 2f, // terrain effect middle radius
				mazalot, // entity that it's around
				mazalot.getRadius() + 50f, // visual band start
				mazalot.getRadius() + 50f + 200f, // visual band end
				new Color(50, 20, 100, 50), // base color
				0.15f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
				new Color(90, 180, 40),
				new Color(130, 145, 90),
				new Color(165, 110, 145), 
				new Color(95, 55, 160), 
				new Color(45, 0, 130),
				new Color(20, 0, 130),
				new Color(10, 0, 150)));
		mazalot_magfield.setCircularOrbit(mazalot, 0, 0, 100);
				
		
			PlanetAPI tiferet = system.addPlanet("tiferet", mazalot, "Tiferet", "barren-bombarded", 0, 75, 600, 55);
			tiferet.getSpec().setPitch(-30f);
			tiferet.getSpec().setTexture( Global.getSettings().getSpriteName("planets", "barren02") );
			tiferet.getSpec().setPlanetColor( new Color(210, 230, 255, 255) );
			tiferet.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "aurorae"));
			tiferet.getSpec().setGlowColor( new Color(50, 230, 255, 155) );
			tiferet.getSpec().setUseReverseLightForGlow(true);
			tiferet.applySpecChanges();
			
			// Mazalot Relay - L5 (behind)
			SectorEntityToken relay = system.addCustomEntity("mazalot_relay", // unique id
					 "Mazalot Relay", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay", // type of object, defined in custom_entities.json
					 "persean"); // faction
			relay.setCircularOrbitPointingDown(system.getEntityById("zagan"), 120, 5500, 220);
			
			// Mazalot Gate
			SectorEntityToken gate = system.addCustomEntity("mazalot_gate", // unique id
					 "Mazalot Gate", // name - if null, defaultName from custom_entities.json will be used
					 "inactive_gate", // type of object, defined in custom_entities.json
					 null); // faction
			gate.setCircularOrbit(system.getEntityById("zagan"), 240, 5500, 220);
			
			
		PlanetAPI melikah = system.addPlanet("melikah", zagan_star, "Melikah", "gas_giant", 0, 350, 7800, 400);
		melikah.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		melikah.getSpec().setGlowColor( new Color(255,20,200,55) );
		melikah.getSpec().setAtmosphereThickness(0.2f);
		melikah.getSpec().setUseReverseLightForGlow(true);
		melikah.getSpec().setPitch(-30f);
		melikah.getSpec().setTilt(20f);
		melikah.getSpec().setPlanetColor( new Color(200, 55, 240,255) );
		melikah.applySpecChanges();
		//melikah.setCustomDescriptionId("planet_mazalot");
		
		SectorEntityToken melikah_magfield = system.addTerrain(Terrain.MAGNETIC_FIELD,
		new MagneticFieldParams(mazalot.getRadius() + 150f, // terrain effect band width 
				(melikah.getRadius() + 150f) / 2f, // terrain effect middle radius
				melikah, // entity that it's around
				melikah.getRadius() + 50f, // visual band start
				melikah.getRadius() + 50f + 200f, // visual band end
				new Color(50, 20, 100, 50), // base color
				0.3f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
				new Color(90, 180, 40),
				new Color(130, 145, 90),
				new Color(165, 110, 145), 
				new Color(95, 55, 160), 
				new Color(45, 0, 130),
				new Color(20, 0, 130),
				new Color(10, 0, 150)));
		melikah_magfield.setCircularOrbit(melikah, 0, 0, 100);
				
		SectorEntityToken zagan_stable2 = system.addCustomEntity(null, null, "nav_buoy_makeshift", Factions.INDEPENDENT);
		zagan_stable2.setCircularOrbitPointingDown(zagan_star, -60, 7800, 400);
		
			PlanetAPI hikmah = system.addPlanet("hikmah", melikah, "Hikmah", "toxic_cold", 0, 95, 750, 30);
			//hikmah.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
			//hikmah.getSpec().setGlowColor( new Color(255,20,60,255) );
			hikmah.getSpec().setAtmosphereThickness(0.2f);
			hikmah.getSpec().setUseReverseLightForGlow(true);
			hikmah.applySpecChanges();
			
			PlanetAPI ilm = system.addPlanet("ilm", melikah, "Ilm", "cryovolcanic", 0, 75, 1000, 40);
			ilm.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
			ilm.getSpec().setGlowColor( new Color(0,255,150,255) );
			ilm.getSpec().setUseReverseLightForGlow(true);
			ilm.applySpecChanges();
			ilm.setInteractionImage("illustrations", "ilm");
			ilm.setCustomDescriptionId("planet_ilm");
			
			SectorEntityToken nebula1 = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
					"   x  " +
					"  xx x" +
					" xxxxx" +
					"xxx xx" +
					" xxx  " +
					"    x ",
					6, 6, // size of the nebula grid, should match above string
					"terrain", "nebula", 4, 4, null));
			nebula1.getLocation().set(melikah.getLocation().x + 1000f, melikah.getLocation().y);
			nebula1.setCircularOrbit(zagan_star, 60f, 7800, 500);
			
			SectorEntityToken nebula2 = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
					"  x x " +
					" xxx  " +
					"   xx " +
					"xxxxxx" +
					"  xx  " +
					" x xx ",
					6, 6, // size of the nebula grid, should match above string
					"terrain", "nebula", 4, 4, null));
			nebula2.getLocation().set(melikah.getLocation().x - 1000f, melikah.getLocation().y);
			nebula2.setCircularOrbit(zagan_star, 300f, 7800, 500);
			
			
		PlanetAPI yesod = system.addPlanet("yesod", zagan_star, "Yesod", "rocky_ice", 180, 115, 9500, 580);
		yesod.setCustomDescriptionId("planet_yesod");
		
		// Zagan jump-point
		JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("yesod_jump", "Yesod Jump-point");
		jumpPoint2.setCircularOrbit( zagan_star, 180 + 60, 9500, 580);
		jumpPoint2.setRelatedPlanet(yesod);
		system.addEntity(jumpPoint2);
		
			
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, zagan_star, StarAge.AVERAGE,
				1, 3, // min/max entities to add
				11250, // radius to start adding at 
				4, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds

		StarSystemGenerator.addSystemwideNebula(system, StarAge.AVERAGE);
		
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}