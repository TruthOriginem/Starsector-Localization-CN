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
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageSpecialAssigner;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
import com.fs.starfarer.api.util.Misc;

public class Mayasura {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Mayasura");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background5.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI mayasura_star = system.initStar("mayasura",
				"star_yellow",
				800f,
				500, // extent of corona outside star
				10f, // solar wind burn level
				1f, // flare probability
				3f); // CR loss multiplier, good values are in the range of 1-5
		
		system.setLightColor(new Color(255, 245, 205)); // light color in entire system, affects all entities
		
		PlanetAPI mayasura_b = system.addPlanet("kasyapa", mayasura_star, "Kasyapa", "barren", 0, 60, 1550, 36);
		mayasura_b.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		mayasura_b.getSpec().setGlowColor(new Color(255,150,50,50));
		mayasura_b.getSpec().setUseReverseLightForGlow(true);
		mayasura_b.getSpec().setAtmosphereThicknessMin(16);
		mayasura_b.getSpec().setAtmosphereThickness(0.14f);
		mayasura_b.getSpec().setAtmosphereColor( new Color(150,125,100,30));
		mayasura_b.applySpecChanges();
		
		PlanetAPI mayasura_c = system.addPlanet("gelan", mayasura_star, "Gelan", "toxic", 300, 200, 2400, 104);
		mayasura_c.setCustomDescriptionId("planet_gelan");
		mayasura_c.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "banded"));
		mayasura_c.getSpec().setGlowColor(new Color(255,150,0,255));
		mayasura_c.getSpec().setUseReverseLightForGlow(true);
		mayasura_c.getSpec().setCloudColor(new Color(255,245,205,220));
		mayasura_c.getSpec().setAtmosphereThicknessMin(62f); // fix for aliasing issue on outward side of planet
		mayasura_c.applySpecChanges();

			system.addRingBand(mayasura_c, "misc", "rings_special0", 256f, 0, new Color(255,230,100,255), 256f, 420, 60f, Terrain.RING, "Gelan 之环");
		
		// Mayasura Jumppoint in the L-something of Gelan
		JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("mayasura_jump", "Mayasura 跳跃点");
		jumpPoint.setCircularOrbit( mayasura_star, 300 + 60, 2400, 104);
		jumpPoint.setRelatedPlanet(mayasura_c);
		system.addEntity(jumpPoint);
		
		
		PlanetAPI mayasura_d = system.addPlanet("mairaath", mayasura_star, "Mairaath", "desert", 0, 200, 3800, 183);
		mayasura_d.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "desert01"));
		mayasura_d.getSpec().setPlanetColor(new Color(255,210,225,255));
		mayasura_d.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "aurorae"));
		mayasura_d.getSpec().setGlowColor(new Color(255,250,200,80));
		mayasura_d.getSpec().setUseReverseLightForGlow(true);
		
		mayasura_d.applySpecChanges();
		
		mayasura_d.setCustomDescriptionId("planet_mairaath");
		mayasura_d.setInteractionImage("illustrations", "mairaath");
		
		
			// Mairaath gets 1 orbiting derelict station, one lost station, and ideally plenty of debris
			SectorEntityToken mairaathStation1 = system.addCustomEntity("mairaath_abandoned_station1",
				"废弃的生态空间站", "station_side06", "neutral");
		
			mairaathStation1.setCircularOrbitPointingDown( system.getEntityById("mairaath"), 45, 350, 50);
	
			Misc.setAbandonedStationMarket("mairaath_abandoned_station1_market", mairaathStation1);
			
			mairaathStation1.setCustomDescriptionId("mairaath_station1");
			mairaathStation1.setInteractionImage("illustrations", "abandoned_station3");
		
			// Mairaath stellar shade - out of orbit
			SectorEntityToken mairaath_shade = system.addCustomEntity("mairaath_shade", "恒星镜", "stellar_shade", "neutral");
			mairaath_shade.setCircularOrbitPointingDown( mayasura_star, 300, 3550, 183 );		
			mairaath_shade.setCustomDescriptionId("stellar_shade");
		
			// pirate sensor array counter-rotating Mairaath
			SectorEntityToken mayasura_stable1 = system.addCustomEntity(null, null, "sensor_array_makeshift", Factions.PIRATES);
			mayasura_stable1.setCircularOrbitPointingDown(mayasura_star, 180, 3800, 183);
			
		system.addAsteroidBelt(mayasura_star, 90, 4300, 200, 140, 180, Terrain.ASTEROID_BELT, "Danavas 小行星带");
		system.addRingBand(mayasura_star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 4360, 160f);
		
		
			// Pirates took over the lost station
			SectorEntityToken pirateStation = system.addCustomEntity("mairaath_abandoned_station2",
					"失落的生态空间站", "station_side06", "pirates");
			
			pirateStation.setCircularOrbitPointingDown(system.getEntityById("mayasura"), 45, 4500, 250);		
			pirateStation.setCustomDescriptionId("mairaath_station2");
			pirateStation.setInteractionImage("illustrations", "pirate_station");
			
			// Debris for the Lost Astropolis
			DebrisFieldParams params = new DebrisFieldParams(
							150f, // field radius - should not go above 1000 for performance reasons
							-1f, // density, visual - affects number of debris pieces
							10000000f, // duration in days 
							0f); // days the field will keep generating glowing pieces
			params.source = DebrisFieldSource.MIXED;
			params.baseSalvageXP = 500; // base XP for scavenging in field
			SectorEntityToken debris = Misc.addDebrisField(system, params, StarSystemGenerator.random);
			SalvageSpecialAssigner.assignSpecialForDebrisField(debris);
			
			// makes the debris field always visible on map/sensors and not give any xp or notification on being discovered
			debris.setSensorProfile(null);
			debris.setDiscoverable(null);
			
			// makes it discoverable and give 200 xp on being found
			// sets the range at which it can be detected (as a sensor contact) to 2000 units
			// commented out.
			//debris.setDiscoverable(true);
			//debris.setDiscoveryXP(200f);
			//debris.setSensorProfile(1f);
			//debris.getDetectedRangeMod().modifyFlat("gen", 2000);

			debris.setCircularOrbit(mayasura_star, 45 + 10, 4500, 250);
			
			
			// a gate opposite of the Lost Astropolis
			/*SectorEntityToken gate = system.addCustomEntity("mayasura_gate", // unique id
					 "Mayasura Gate", // name - if null, defaultName from custom_entities.json will be used
					 "inactive_gate", // type of object, defined in custom_entities.json
					 null); // faction
			
			gate.setCircularOrbit(system.getEntityById("mayasura"), 45 + 180, 4520, 250);*/
		
		
		system.addRingBand(mayasura_star, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 5100, 100f);
		system.addAsteroidBelt(mayasura_star, 120, 5130, 300, 200, 300, Terrain.ASTEROID_BELT, "Daitya 小行星带");
		
		
		PlanetAPI mayasura_e = system.addPlanet("diti", mayasura_star, "Diti", "ice_giant", 270, 240, 6200, 290);
		mayasura_e.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "aurorae"));
		mayasura_e.getSpec().setGlowColor(new Color(255,50,240,245));
		mayasura_e.getSpec().setUseReverseLightForGlow(true);
		mayasura_e.getSpec().setPitch(15f);
		mayasura_e.getSpec().setTilt(40);
		mayasura_e.getSpec().setPlanetColor(new Color(255,240,235));
		mayasura_e.applySpecChanges();
		
			// rumoured to have the finest cocktail lounge in the entire Sector
			SectorEntityToken tritachStation = system.addCustomEntity("port_tse", "Port Tse 特许经营站", "station_side00", "tritachyon");
			tritachStation.setCustomDescriptionId("station_tse_enterprise");
			tritachStation.setInteractionImage("illustrations", "space_bar");
			tritachStation.setCircularOrbitWithSpin(mayasura_e, 60, 510, 30, 3, 5);
		
			PlanetAPI mayasura_e1 = system.addPlanet("arjun", mayasura_e, "Arjun's World", "rocky_unstable", 0, 60, 510, 30);
			mayasura_e1.getSpec().setAtmosphereThicknessMin(16);
			mayasura_e1.getSpec().setAtmosphereThickness(0.2f);
			mayasura_e1.getSpec().setAtmosphereColor( new Color(160,175,200,10) );
			mayasura_e1.getSpec().setPlanetColor(new Color(200,240,255));
			mayasura_e1.applySpecChanges();
			
		// Mayasura Relay
		SectorEntityToken mayasura_relay = system.addCustomEntity("mayasura_relay", "Mayasura 通讯中继站", "comm_relay", "tritachyon");
		mayasura_relay.setCircularOrbitPointingDown( mayasura_star, 270 + 60, 6200, 290);
			
		// stable loc in counter-orbit to Diti
		SectorEntityToken mayasura_stable2 = system.addCustomEntity(null, null, "stable_location", Factions.NEUTRAL);
		mayasura_stable2.setCircularOrbitPointingDown(mayasura_star, 90, 6200, 290);
		
		system.addAsteroidBelt(mayasura_star, 150, 7500, 400, 370, 430, Terrain.ASTEROID_BELT, "Takshaka 小行星带");
		system.addRingBand(mayasura_star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 7520, 400f);
		
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}
