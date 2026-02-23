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
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.StarTypes;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator.StarSystemType;
import com.fs.starfarer.api.impl.campaign.terrain.BaseRingTerrain.RingParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
import com.fs.starfarer.api.impl.campaign.terrain.MagneticFieldTerrainPlugin.MagneticFieldParams;
import com.fs.starfarer.api.util.Misc;

public class Magec {

	public void generate(SectorAPI sector) {
		StarSystemAPI system = sector.createStarSystem("Magec");
		system.setType(StarSystemType.BINARY_FAR);
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg");

		PlanetAPI star = system.initStar("magec", // unique id for this star
										 StarTypes.BLUE_GIANT, // id in planets.json
										 900f,		// radius (in pixels at default zoom)
										 500); // corona radius, from star edge
		
		system.setLightColor(new Color(225, 245, 255)); // light color in entire system, affects all entities
		
		PlanetAPI magec1 = system.addPlanet("chaxiraxi", star, "Chaxiraxi", "gas_giant", 0, 280, 1850, 40);
		magec1.getSpec().setPlanetColor(new Color(50,100,255,255));
		magec1.getSpec().setAtmosphereColor(new Color(120,130,100,150));
		magec1.getSpec().setCloudColor(new Color(195,230,255,200));
		magec1.getSpec().setIconColor(new Color(120,130,100,255));
		magec1.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "aurorae"));
		magec1.getSpec().setGlowColor(new Color(235,38,8,145));
		magec1.getSpec().setUseReverseLightForGlow(true);
		magec1.getSpec().setAtmosphereThickness(0.5f);
		magec1.applySpecChanges();
		magec1.setCustomDescriptionId("planet_chaxiraxi");
		
		SectorEntityToken magec1_field = system.addTerrain(Terrain.MAGNETIC_FIELD,
						new MagneticFieldParams(200f, // terrain effect band width 
						380, // terrain effect middle radius
						magec1, // entity that it's around
						280f, // visual band start
						480f, // visual band end
						new Color(50, 30, 100, 30), // base color
						1f, // probability to spawn aurora sequence, checked once/day when no aurora in progress
						new Color(50, 20, 110, 130),
						new Color(150, 30, 120, 150), 
						new Color(200, 50, 130, 190),
						new Color(250, 70, 150, 240),
						new Color(200, 80, 130, 255),
						new Color(75, 0, 160), 
						new Color(127, 0, 255)
						));
			magec1_field.setCircularOrbit(magec1, 0, 0, 100);
				
		
		PlanetAPI magec2 = system.addPlanet("maxios", star, "Maxios", "barren", 230, 100, 2675, 100);
		Misc.initConditionMarket(magec2);
		magec2.getMarket().addCondition(Conditions.DECIVILIZED);
		magec2.getMarket().addCondition(Conditions.RUINS_EXTENSIVE);
		magec2.getMarket().getFirstCondition(Conditions.RUINS_EXTENSIVE).setSurveyed(true);
		
		magec2.getMarket().addCondition(Conditions.METEOR_IMPACTS);
		magec2.getMarket().addCondition(Conditions.ORE_MODERATE);
		magec2.getMarket().addCondition(Conditions.RARE_ORE_SPARSE);
		magec2.getMarket().addCondition(Conditions.HOT);
		magec2.getMarket().addCondition(Conditions.THIN_ATMOSPHERE);
		
		magec2.setCustomDescriptionId("planet_maxios");
		magec2.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "asharu"));
		magec2.getSpec().setGlowColor(new Color(255,245,235,255));
		magec2.getSpec().setUseReverseLightForGlow(true);
		magec2.getSpec().setAtmosphereThicknessMin(25);
		magec2.getSpec().setAtmosphereThickness(0.2f);
		magec2.getSpec().setAtmosphereColor( new Color(80,90,100,120) );
		magec2.applySpecChanges();

		
		// And herrrrre's Achaman
		PlanetAPI magec3 = system.addPlanet("achaman", star, "Achaman", StarTypes.WHITE_DWARF, 45, 120, 8000, 700);
		system.setSecondary(magec3);
		system.addCorona(magec3, 150, 3f, 0.05f, 1f); // it's a very docile star.
		
		SectorEntityToken achaman_buoy = system.addCustomEntity("achaman_relay", // unique id
				 "Achaman 通讯中继站", // name - if null, defaultName from custom_entities.json will be used
				 "nav_buoy", // type of object, defined in custom_entities.json
				 "tritachyon"); // faction
		
		achaman_buoy.setCircularOrbitPointingDown( star, 45+60, 8000, 700);
		
		PlanetAPI magec3a = system.addPlanet("tibicena", magec3, "Tibicena", "rocky_metallic", 200, 80, 800, 45);
		magec3a.setCustomDescriptionId("planet_tibicena");
		
			SectorEntityToken achaman_station = system.addCustomEntity("achaman_enterprise_station", 
																		"Achaman Enterprise Station",
																		"station_side04",
																		"tritachyon");
			
			achaman_station.setCircularOrbitPointingDown(system.getEntityById("tibicena"), 90, 200, 25);		
			achaman_station.setCustomDescriptionId("station_achaman_enterprise");
			achaman_station.setInteractionImage("illustrations", "hound_hangar");
			
			
		// Asteroid belts; which I guess we're not doing in quite proper order.

		system.addAsteroidBelt(star, 100, 3300, 256, 150, 250, Terrain.ASTEROID_BELT, null);
		system.addAsteroidBelt(star, 100, 3700, 256, 150, 250, Terrain.ASTEROID_BELT, null);
		
		system.addAsteroidBelt(star, 100, 4150, 128, 200, 300, Terrain.ASTEROID_BELT, null);
		system.addAsteroidBelt(star, 100, 4450, 188, 200, 300, Terrain.ASTEROID_BELT, null);
		system.addAsteroidBelt(star, 100, 4675, 256, 200, 300, Terrain.ASTEROID_BELT, null);
			
		system.addRingBand(star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 3200, 80f);
		system.addRingBand(star, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 3400, 100f);
		system.addRingBand(star, "misc", "rings_dust0", 256f, 2, Color.white, 256f, 3600, 130f);
		system.addRingBand(star, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 3800, 80f);
		
		// add one ring that covers all of the above
		SectorEntityToken ring = system.addTerrain(Terrain.RING, new RingParams(600 + 256, 3500, null, "Guayota 吸积盘"));
		ring.setCircularOrbit(star, 0, 0, 100);
		
		
		system.addRingBand(star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 4000, 80f);
		system.addRingBand(star, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 4100, 120f);
		system.addRingBand(star, "misc", "rings_dust0", 256f, 2, Color.white, 256f, 4200, 160f);
		
		// add one ring that covers all of the above
		ring = system.addTerrain(Terrain.RING, new RingParams(200 + 256, 4100, null, "Guayota 吸积盘"));
		ring.setCircularOrbit(star, 0, 0, 100);
		
		
//		system.addRingBand(a2, "misc", "rings1", 256f, 0, Color.white, 256f, 1700, 50f);
//		system.addRingBand(a2, "misc", "rings1", 256f, 0, Color.white, 256f, 1700, 70f);
//		system.addRingBand(a2, "misc", "rings1", 256f, 1, Color.white, 256f, 1700, 90f);
//		system.addRingBand(a2, "misc", "rings1", 256f, 1, Color.white, 256f, 1700, 110f);
		
		system.addRingBand(star, "misc", "rings_dust0", 256f, 3, Color.white, 256f, 4300, 140f);
		system.addRingBand(star, "misc", "rings_dust0", 256f, 2, Color.white, 256f, 4400, 180f);
		system.addRingBand(star, "misc", "rings_dust0", 256f, 1, Color.white, 256f, 4500, 220f);
		
		// add one ring that covers all of the above
		ring = system.addTerrain(Terrain.RING, new RingParams(200 + 256, 4400, null, "Guayota 吸积盘"));
		ring.setCircularOrbit(star, 0, 0, 100);
		
		
		system.addRingBand(star, "misc", "rings_ice0", 256f, 0, Color.white, 256f, 4500, 100f);
		system.addRingBand(star, "misc", "rings_ice0", 256f, 2, Color.white, 256f, 4600, 140f);
		system.addRingBand(star, "misc", "rings_ice0", 256f, 1, Color.white, 256f, 4700, 160f);
		system.addRingBand(star, "misc", "rings_ice0", 256f, 2, Color.white, 256f, 4800, 180f);
		
		// add one ring that covers all of the above
		ring = system.addTerrain(Terrain.RING, new RingParams(300 + 256, 4650, null, "Guayota 吸积盘"));
		ring.setCircularOrbit(star, 0, 0, 100);
		
		
		//SectorEntityToken civilianStation = system.addOrbitalStation("new_maxios", star, 0, 3900, 160, "New Maxios", "independent");
		SectorEntityToken civilianStation = system.addCustomEntity("new_maxios", "Nova Maxios", "station_side07", "independent");
		civilianStation.setCustomDescriptionId("station_new_maxios");
		civilianStation.setInteractionImage("illustrations", "cargo_loading");
		civilianStation.setCircularOrbitWithSpin(star, 0, 3900, 160, 2, 5);
		
		// Guayota Relay - L5 (behind); well, okay, not quite the L5. But whatever.
		SectorEntityToken guayota_relay = system.addCustomEntity("guayota_relay", // unique id
				 "Guayota 通讯中继站", // name - if null, defaultName from custom_entities.json will be used
				 "comm_relay_makeshift", // type of object, defined in custom_entities.json
				 "independent"); // faction
		
		guayota_relay.setCircularOrbitPointingDown( star, 0 + 30, 3900, 160);
		
	
		
		//SectorEntityToken pirateStation = system.addOrbitalStation("kantas_den", star, 240, 4250, 160, "Kanta's Den", "pirates");
		SectorEntityToken pirateStation = system.addCustomEntity("kantas_den", "Kanta 之巢", "station_side06", "pirates");
		pirateStation.setCustomDescriptionId("station_kantas_den");
		pirateStation.setInteractionImage("illustrations", "pirate_station");
		pirateStation.setCircularOrbitWithSpin(star, 220, 4250, 160, 3, 5);
		
		
		JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("maxios_jump_point", "Maxios 跳跃点");
		OrbitAPI orbit = Global.getFactory().createCircularOrbit(magec2, 0, 500, 30);
		jumpPoint.setOrbit(orbit);
		jumpPoint.setRelatedPlanet(magec2);
		jumpPoint.setStandardWormholeToHyperspaceVisual();
		system.addEntity(jumpPoint);
		
		// Magec Gate - counter-orbit to Achaman 
		SectorEntityToken gate = system.addCustomEntity("magec_gate", // unique id
				 "Magec 之门", // name - if null, defaultName from custom_entities.json will be used
				 Entities.INACTIVE_GATE, // type of object, defined in custom_entities.json
				 null); // faction
		gate.setCircularOrbit(star, 45+180, 8000, 700);

		DebrisFieldParams params = new DebrisFieldParams(
				500f, // field radius - should not go above 1000 for performance reasons
				-1f, // density, visual - affects number of debris pieces
				10000000f, // duration in days 
				0f); // days the field will keep generating glowing pieces
		params.source = DebrisFieldSource.MIXED;
		params.baseSalvageXP = 250; // base XP for scavenging in field
		SectorEntityToken debrisNextToGate = Misc.addDebrisField(system, params, StarSystemGenerator.random);
		debrisNextToGate.setSensorProfile(null);
		debrisNextToGate.setDiscoverable(null);
		debrisNextToGate.setCircularOrbit(gate, 0f, 0f, 100f);
		debrisNextToGate.setId("magec_debrisNextToGate");
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, star, StarAge.YOUNG,
				1, 2, // min/max entities to add
				11500, // radius to start adding at 
				4, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds

		system.autogenerateHyperspaceJumpPoints(true, true);
	}


}
