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
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain;
import com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin.MagneticFieldParams;
import com.fs.starfarer.api.util.Misc;

public class Hybrasil {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Hybrasil");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background5.jpg");
		
		SectorEntityToken hybrasil_nebula = Misc.addNebulaFromPNG("data/campaign/terrain/hybrasil_nebula.png",
				  0, 0, // center of nebula
				  system, // location to add to
				  "terrain", "nebula_blue", // "nebula_blue", // texture to use, uses xxx_map for map
				  4, 4, StarAge.YOUNG); // number of cells in texture
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI hybrasil_star = system.initStar("hybrasil", // unique id for this star 
										    "star_white",  // id in planets.json
										    600f, 		  // radius (in pixels at default zoom)
										    500); // corona radius, from star edge
		system.setLightColor(new Color(245, 250, 255)); // light color in entire system, affects all entities
		
		PlanetAPI hybrasil1 = system.addPlanet("culann", hybrasil_star, "Culann", "barren", 10, 110, 2150, 110);
		hybrasil1.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "castiron"));
		hybrasil1.getSpec().setPlanetColor(new Color(220,235,245,255));
		hybrasil1.setCustomDescriptionId("planet_culann");
		hybrasil1.applySpecChanges();
		
		SectorEntityToken culannStation = system.addCustomEntity("culann_starforge", "Culann 星际锻炉", "station_side07", "tritachyon");
		culannStation.setCircularOrbitPointingDown(system.getEntityById("culann"), 0, 250, 30);		
		culannStation.setInteractionImage("illustrations", "orbital");
		culannStation.setCustomDescriptionId("station_culann");
		
	// Elada System 
		PlanetAPI hybrasil2 = system.addPlanet("elada", hybrasil_star, "Elada", "gas_giant", 20, 400, 4750, 220);
		hybrasil2.getSpec().setPlanetColor(new Color(200,255,245,255));
		hybrasil2.getSpec().setAtmosphereColor(new Color(220,250,240,150));
		hybrasil2.getSpec().setCloudColor(new Color(220,250,240,200));
		hybrasil2.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		hybrasil2.getSpec().setGlowColor(new Color(0,255,205,62));
		hybrasil2.getSpec().setUseReverseLightForGlow(true);
		hybrasil2.getSpec().setIconColor(new Color(250,225,205,255));
		hybrasil2.applySpecChanges();
		
		// & the moons of Elada
			system.addRingBand(hybrasil2, "misc", "rings_dust0", 256f, 3, Color.white, 256f, 875, 33f, Terrain.RING, null);
			system.addRingBand(hybrasil2, "misc", "rings_dust0", 256f, 4, Color.white, 256f, 1050, 33f, Terrain.RING, null);
			
			PlanetAPI hybrasil2a = system.addPlanet("eochu_bres", hybrasil2, "Eochu Bres", "tundra", 30, 140, 1550, 40);
			hybrasil2a.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
			hybrasil2a.getSpec().setGlowColor(new Color(255,255,255,255));
			hybrasil2a.getSpec().setUseReverseLightForGlow(true);
			hybrasil2a.applySpecChanges();
			hybrasil2a.setCustomDescriptionId("planet_eochu_bres");
			hybrasil2a.setInteractionImage("illustrations", "eochu_bres");
			
				// Eochu Bres mirror system 
				SectorEntityToken eochu_bres_mirror1 = system.addCustomEntity("eochu_bres_mirror1", "Eochu Bres 恒星镜", "stellar_mirror", "tritachyon");
				eochu_bres_mirror1.setCircularOrbitPointingDown(system.getEntityById("eochu_bres"), 0, 220, 40);		
				eochu_bres_mirror1.setCustomDescriptionId("stellar_mirror");
				
				SectorEntityToken eochu_bres_mirror2 = system.addCustomEntity("eochu_bres_mirror2", "Eochu Bres 恒星镜", "stellar_mirror", "tritachyon");
				eochu_bres_mirror2.setCircularOrbitPointingDown(system.getEntityById("eochu_bres"), 120, 220, 40);		
				eochu_bres_mirror2.setCustomDescriptionId("stellar_mirror");
				
				SectorEntityToken eochu_bres_mirror3 = system.addCustomEntity("eochu_bres_mirror3", "Eochu Bres 恒星镜", "stellar_mirror", "tritachyon");
				eochu_bres_mirror3.setCircularOrbitPointingDown(system.getEntityById("eochu_bres"), 240, 220, 40);
				eochu_bres_mirror3.setCustomDescriptionId("stellar_mirror");
				
			PlanetAPI hybrasil2b = system.addPlanet("ogma", hybrasil2, "Ogma", "rocky_metallic", 40, 100, 2050, 56);
			//hybrasil2b.setCustomDescriptionId("planet_ogma");
			
			//SectorEntityToken hybrasil_station = system.addCustomEntity("hybrasil_station", "Hybrasil Astropolis", "station_side03", "tritachyon");
			//hybrasil_station.setCircularOrbitPointingDown(system.getEntityById("ogma"), 90, 220, 25);		
			//hybrasil_station.setCustomDescriptionId("station_ogma");
			//hybrasil_station.setInteractionImage("illustrations", "orbital");
			
			// Elada Relay, L5 (behind)
			SectorEntityToken relay = system.addCustomEntity("elada_relay", // unique id
					 "Elada 通讯中继站", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay", // type of object, defined in custom_entities.json
					 "tritachyon"); // faction
			relay.setCircularOrbitPointingDown( system.getEntityById("hybrasil"), 320, 4750, 220);
		
		
	// Crom Cruach
		PlanetAPI hybrasil3 = system.addPlanet("crom_cruach", hybrasil_star, "Crom Cruach", "barren-bombarded", 50, 180, 7300, 340);
		hybrasil3.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren03"));
		hybrasil3.getSpec().setPlanetColor(new Color(185,240,255,255));
		hybrasil3.applySpecChanges();
		hybrasil3.setCustomDescriptionId("planet_crom_cruach");
//		hybrasil3.setInteractionImage("illustrations", "mine");
		
		Misc.initConditionMarket(hybrasil3);
		hybrasil3.getMarket().addCondition(Conditions.DECIVILIZED);
		hybrasil3.getMarket().addCondition(Conditions.RUINS_SCATTERED);
		hybrasil3.getMarket().getFirstCondition(Conditions.RUINS_SCATTERED).setSurveyed(true);
		hybrasil3.getMarket().addCondition(Conditions.ORE_MODERATE);
		hybrasil3.getMarket().addCondition(Conditions.RARE_ORE_MODERATE);
		hybrasil3.getMarket().addCondition(Conditions.HOT);
		hybrasil3.getMarket().addCondition(Conditions.THIN_ATMOSPHERE);
		
		
		SectorEntityToken crom_cruach_loc = system.addCustomEntity(null,null, "sensor_array_makeshift",Factions.TRITACHYON); 
		crom_cruach_loc.setCircularOrbitPointingDown( hybrasil_star, 180-60, 7300, 340);		
		
		// jump point Crom Leim!
		JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("hybrasil_inner_jump", "Hybrasil 星系内部跳跃点");
		OrbitAPI orbit = Global.getFactory().createCircularOrbit(hybrasil3, 0, 1500, 65);
		jumpPoint.setOrbit(orbit);
		jumpPoint.setRelatedPlanet(hybrasil3);
		jumpPoint.setStandardWormholeToHyperspaceVisual();
		system.addEntity(jumpPoint);
		
	// Balar System
		PlanetAPI hybrasil4 = system.addPlanet("balar", hybrasil_star, "Balar", "ice_giant", 60, 340, 11500, 820);
		hybrasil4.getSpec().setPlanetColor(new Color(255,245,215,255));
		hybrasil4.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "aurorae"));
		hybrasil4.getSpec().setGlowColor(new Color(0,255,205,64));
		hybrasil4.getSpec().setUseReverseLightForGlow(true);
		hybrasil4.applySpecChanges();
		hybrasil4.setCustomDescriptionId("planet_balar");
		
		system.addRingBand(hybrasil4, "misc", "rings_ice0", 256f, 2, Color.white, 256f, 650, 21f, Terrain.RING, null);
		
		SectorEntityToken hybrasil4_field = system.addTerrain(Terrain.MAGNETIC_FIELD,
		new MagneticFieldParams(hybrasil4.getRadius() + 150f, // terrain effect band width 
				(hybrasil4.getRadius() + 150f) / 2f, // terrain effect middle radius
				hybrasil4, // entity that it's around
				hybrasil4.getRadius() + 50f, // visual band start
				hybrasil4.getRadius() + 50f + 200f, // visual band end
				new Color(50, 20, 100, 50), // base color
				0.5f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
				new Color(90, 180, 40),
				new Color(130, 145, 90),
				new Color(165, 110, 145), 
				new Color(95, 55, 160), 
				new Color(45, 0, 130),
				new Color(20, 0, 130),
				new Color(10, 0, 150)));
		hybrasil4_field.setCircularOrbit(hybrasil4, 0, 0, 100);
		
			// the moons of Balar
			PlanetAPI hybrasil4a = system.addPlanet("ena", hybrasil4, "Ena", "rocky_ice", 0, 70, 900, 15);
			hybrasil4a.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
			hybrasil4a.getSpec().setGlowColor(new Color(0,255,205,64));
			hybrasil4a.getSpec().setUseReverseLightForGlow(true);
			hybrasil4a.applySpecChanges();
			hybrasil4a.setCustomDescriptionId("planet_ena");
			
			PlanetAPI hybrasil4b = system.addPlanet("cethlenn", hybrasil4, "Cethlenn", "cryovolcanic", 70, 120, 1300, 30);
			hybrasil4b.setCustomDescriptionId("planet_cethlenn");
			
			PlanetAPI hybrasil4c = system.addPlanet("birog", hybrasil4, "Birog", "frozen", 140, 80, 1580, 60);
			hybrasil4c.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "frozen00"));
			hybrasil4c.applySpecChanges();
			
			PlanetAPI hybrasil4d = system.addPlanet("cian", hybrasil4, "Cian", "barren-bombarded", 210, 50, 1800, 90);
			hybrasil4d.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren02"));
			hybrasil4d.applySpecChanges();
			
			// L4 & L5 mini-nebulas
			SectorEntityToken balar_L4_nebula = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
					"          " +
					" x   xxxx " +
					"   xxx    " +
					"  xx  xx  " +
					"  xxxxx   " +
					"  xxxxx x " +
					"   xxxx   " +
					"x  xxxxx  " +
					"  xxxxxxx " +
					"    xxx   ",
					10, 10, // size of the nebula grid, should match above string
					"terrain", "nebula_blue", 4, 4, null));
			balar_L4_nebula.setCircularOrbit(hybrasil_star, 60 + 60, 11500, 820);
			
			SectorEntityToken balar_loc = system.addCustomEntity(null,null, "nav_buoy_makeshift",Factions.TRITACHYON); 
			balar_loc.setCircularOrbitPointingDown( hybrasil_star, 60 + 60, 11500, 820);	
			
			SectorEntityToken balar_L5_nebula = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
					"          " +
					" x   xxxx " +
					"   xxx    " +
					"  xx  xx  " +
					"  xxxxx   " +
					"  xxxxx x " +
					"   xxxx   " +
					"x  xxxxx  " +
					"  xxxxxxx " +
					"    xxx   ",
					10, 10, // size of the nebula grid, should match above string
					"terrain", "nebula_blue", 4, 4, null));
			balar_L5_nebula.setCircularOrbit(hybrasil_star, 60 - 60, 11500, 820);
		
	// And way out in the back, Donn
		
		PlanetAPI hybrasil5 = system.addPlanet("donn", hybrasil_star, "Donn", "barren", 180, 140, 13050, 800);
		hybrasil5.setCustomDescriptionId("planet_donn");
		hybrasil5.setInteractionImage("illustrations", "pirate_station");
		
		// generates hyperspace destinations for in-system jump points
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
