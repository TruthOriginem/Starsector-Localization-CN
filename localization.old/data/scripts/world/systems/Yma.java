package data.scripts.world.systems;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.StarTypes;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator.StarSystemType;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidFieldTerrainPlugin.AsteroidFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.BaseRingTerrain.RingParams;
import com.fs.starfarer.api.util.Misc;

public class Yma {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Yma");
		system.setType(StarSystemType.BINARY_FAR);
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI yma_star = system.initStar("yma", // unique id for this star 
											StarTypes.YELLOW,  // id in planets.json
										    900f, 		  // radius (in pixels at default zoom)
										    500); // corona radius, from star edge
		
		system.setLightColor(new Color(250, 240, 210)); // light color in entire system, affects all entities
		
		PlanetAPI yma1 = system.addPlanet("huascar", yma_star, "Huascar", "lava", 0, 120, 2200, 100);
		
		// Inner Asteroids
		system.addRingBand(yma_star, "misc", "rings_asteroids0", 256f, 2, Color.white, 256f, 3700, 175f, null, null);
		system.addAsteroidBelt(yma_star, 75, 3700, 256, 150, 200, Terrain.ASTEROID_BELT, null);
		
		
		PlanetAPI yma2 = system.addPlanet("hanan_pacha", yma_star, "Hanan Pacha", "irradiated", 180, 200, 4400, 330);
		yma2.getSpec().setPlanetColor(new Color(220,245,255,255));
		yma2.getSpec().setAtmosphereColor(new Color(150,120,100,250));
		yma2.getSpec().setCloudColor(new Color(150,120,120,150));
		//yma2.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren"));
		yma2.setCustomDescriptionId("planet_hanan_pacha");
		
		Misc.initConditionMarket(yma2);
		yma2.getMarket().addCondition(Conditions.DECIVILIZED);
		yma2.getMarket().addCondition(Conditions.RUINS_EXTENSIVE);
		yma2.getMarket().getFirstCondition(Conditions.RUINS_EXTENSIVE).setSurveyed(true);
		yma2.getMarket().addCondition(Conditions.METEOR_IMPACTS);
		yma2.getMarket().addCondition(Conditions.ORE_MODERATE);
		yma2.getMarket().addCondition(Conditions.RARE_ORE_SPARSE);
		

		
		PlanetAPI yma2a = system.addPlanet("killa", yma2, "Killa", "barren-bombarded", 90, 50, 380, 16);
		Misc.initConditionMarket(yma2a);
		yma2a.getMarket().addCondition(Conditions.DECIVILIZED);
		yma2a.getMarket().addCondition(Conditions.RUINS_WIDESPREAD);
		yma2a.getMarket().getFirstCondition(Conditions.RUINS_WIDESPREAD).setSurveyed(true);
		
		yma2a.getMarket().addCondition(Conditions.NO_ATMOSPHERE);
		yma2a.getMarket().addCondition(Conditions.RARE_ORE_SPARSE);
		
		yma2a.setCustomDescriptionId("planet_killa");
		
		SectorEntityToken hanan_pacha_loc = system.addCustomEntity(null,null, "stable_location",Factions.NEUTRAL); 
		hanan_pacha_loc.setCircularOrbitPointingDown( yma_star, 180 - 60, 4400, 330);		
		
		// Outer asteroids
		
		system.addRingBand(yma_star, "misc", "rings_asteroids0", 256f, 3, Color.white, 256f, 5100, 475f, null, null);
		system.addAsteroidBelt(yma_star, 100, 5100, 256, 450, 500, Terrain.ASTEROID_BELT, null);
		
		PlanetAPI yma3 = system.addPlanet("chupi_orco", yma_star, "Chupi Orco", "gas_giant", 45, 300, 7300, 450);
		yma3.getSpec().setPlanetColor(new Color(200,235,245,255));
		yma3.getSpec().setAtmosphereColor(new Color(210,240,250,250));
		yma3.getSpec().setCloudColor(new Color(220,250,240,200));
		yma3.getSpec().setPitch(-22f);
		yma3.getSpec().setTilt(-9f);
		yma3.applySpecChanges();
		
			// Chupi Orco Siphon Project
			SectorEntityToken neutralStation = system.addCustomEntity("yma_abandoned_station", "Abandoned 虹吸站", "station_side05", "neutral");
			neutralStation.setCircularOrbitPointingDown(system.getEntityById("chupi_orco"), 45, 360, 50);		
			neutralStation.setCustomDescriptionId("station_chupi_orco");
			neutralStation.setInteractionImage("illustrations", "abandoned_station3");
			
			Misc.setAbandonedStationMarket("yma_abandoned_station_market", neutralStation);
	
			// Chupi Orco rings
			system.addRingBand(yma3, "misc", "rings_dust0", 256f, 2, Color.white, 256f, 600, 31f);
			system.addRingBand(yma3, "misc", "rings_ice0", 256f, 3, Color.white, 256f, 750, 35f);
			
			// add one ring that covers all of the above
			SectorEntityToken ring = system.addTerrain(Terrain.RING, new RingParams(150 + 256, 675, null, null));
			ring.setCircularOrbit(yma3, 0, 0, 100);
		
			PlanetAPI yma3a = system.addPlanet("viscacha", yma3, "Viscacha", "toxic_cold", 0, 65, 1000, 21);
			yma3a.getSpec().setPlanetColor(new Color(255,210,170,255));
			yma3a.getSpec().setAtmosphereColor(new Color(255,190,210,255));
			yma3a.getSpec().setCloudColor(new Color(255,180,200,255));
			yma3a.applySpecChanges();
			yma3a.setCustomDescriptionId("planet_viscacha");
			
			PlanetAPI yma3b = system.addPlanet("salamanca", yma3, "Salamanca", "toxic_cold", 0, 95, 1400, 29);
			yma3b.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "volturn"));
			yma3b.getSpec().setGlowColor( new Color(255,20,60,255) );
			yma3b.getSpec().setAtmosphereThickness(0.2f);
			yma3b.getSpec().setUseReverseLightForGlow(true);
			yma3b.applySpecChanges();
			yma3b.setCustomDescriptionId("planet_salamanca");
			
			SectorEntityToken chupi_orco_loc = system.addCustomEntity(null,null, "nav_buoy_makeshift",Factions.PERSEAN); 
			chupi_orco_loc.setCircularOrbitPointingDown( yma3, 0, 1800, 50);
		
			// Chupi Orco trojans
			SectorEntityToken chupi_orcoL4 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						500f, // min radius
						700f, // max radius
						20, // min asteroid count
						30, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Chupi Orco L4 小行星带")); // null for default name
			
			SectorEntityToken chupi_orcoL5 = system.addTerrain(Terrain.ASTEROID_FIELD,
					new AsteroidFieldParams(
						500f, // min radius
						700f, // max radius
						20, // min asteroid count
						30, // max asteroid count
						4f, // min asteroid radius 
						16f, // max asteroid radius
						"Chupi Orco L5 小行星带")); // null for default name
			
			chupi_orcoL4.setCircularOrbit(yma_star, 45 + 60, 7300, 450);
			chupi_orcoL5.setCircularOrbit(yma_star, 45 - 60, 7300, 450);
			
			// Viscacha Jumppoint
			JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("viscacha_jump", "Viscacha 跳跃点");
			jumpPoint.setCircularOrbit( system.getEntityById("yma"), 45+60, 7300, 450);
			jumpPoint.setRelatedPlanet(yma3a);
			system.addEntity(jumpPoint);
			
			// Yma Gate
			SectorEntityToken gate = system.addCustomEntity("yma_gate", // unique id
					 "Yma 之门", // name - if null, defaultName from custom_entities.json will be used
					 "inactive_gate", // type of object, defined in custom_entities.json
					 null); // faction
			gate.setCircularOrbit(system.getEntityById("yma"), 345 + 5, 7300, 450);
		
	// YMA B
		PlanetAPI yma_star_b = system.addPlanet("warawara", yma_star, "Warawara", "star_white", 270, 400, 12500, 800);
		system.setSecondary(yma_star_b);
		system.addCorona(yma_star_b, 250, 2f, 0.1f, 2f);
		
		PlanetAPI yma_b1 = system.addPlanet("qaras", yma_star_b, "Qaras", "tundra", 0, 140, 2000, 100);
		yma_b1.getSpec().setPitch(180f);
		yma_b1.getSpec().setTilt(-30f);
		yma_b1.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		yma_b1.getSpec().setGlowColor(new Color(240,255,250,64));
		yma_b1.getSpec().setUseReverseLightForGlow(true);
		yma_b1.applySpecChanges();
		yma_b1.setCustomDescriptionId("planet_qaras");
		
			// Qaras mirror system 
			SectorEntityToken qaras_mirror1 = system.addCustomEntity("qaras_mirror1", "Qaras 恒星镜", "stellar_mirror", "pirates");
			qaras_mirror1.setCircularOrbitPointingDown(system.getEntityById("qaras"), 0, 220, 40);		
			qaras_mirror1.setCustomDescriptionId("stellar_mirror");
	
		system.addRingBand(yma_b1, "misc", "rings_ice0", 256f, 1, Color.white, 256f, 400, 30f, Terrain.RING, null);
		
		// Qaras Relay - L5 (behind)
		SectorEntityToken qaras_relay = system.addCustomEntity("qaras_relay", // unique id
				 "Qaras 通讯中继站", // name - if null, defaultName from custom_entities.json will be used
				 "comm_relay", // type of object, defined in custom_entities.json
				 "pirates"); // faction
		qaras_relay.setCircularOrbitPointingDown( yma_star_b, 0 - 60, 2000, 100);
		
		// Yma B Jumppoint
		JumpPointAPI jumpPoint2 = Global.getFactory().createJumpPoint("viscacha_jump", "Yma B 跳跃点");
		jumpPoint2.setCircularOrbit( system.getEntityById("yma"), 270+60, 12500, 800);
		jumpPoint2.setRelatedPlanet(yma_b1);
		system.addEntity(jumpPoint2);
		
		// Warawara trojans
		SectorEntityToken warawaraL4 = system.addTerrain(Terrain.ASTEROID_FIELD, 
				new AsteroidFieldParams(500f, 700f,	30,	40, 4f,	24f, 
				"Warawara Orco L4 小行星带")); 
		
		SectorEntityToken warawaraL5 = system.addTerrain(Terrain.ASTEROID_FIELD, 
				new AsteroidFieldParams(500f, 700f,	30,	40, 4f,	24f, 
						"Warawara Orco L5 小行星带")); 
		
		warawaraL4.setCircularOrbit(yma_star, 270-60, 12500, 800);
		warawaraL5.setCircularOrbit(yma_star, 270+60, 12500, 800);
		
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
