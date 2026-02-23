package data.scripts.world.systems;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoAPI.CargoItemType;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.OrbitAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI.SurveyLevel;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData;
import com.fs.starfarer.api.impl.campaign.JumpPointInteractionDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.People;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.DefenderDataOverride;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.DerelictThemeGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.SalvageSpecialAssigner.ShipRecoverySpecialCreator;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BaseSalvageSpecial;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.PerShipData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipCondition;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
import com.fs.starfarer.api.util.Misc;


/**
 * Starting/tutorial system.
 * 
 * @author Alex Mosolov
 *
 * Copyright 2017 Fractal Softworks, LLC
 */
public class Galatia {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Galatia");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background_galatia.jpg");
		
		PlanetAPI star = system.initStar("galatia", // unique id for this star 
									    "star_yellow",  // id in planets.json
									    600f, 		  // radius (in pixels at default zoom)
									    700, 		  // corona radius, from star edge
									    10, 0.5f, 3f);// solar wind/flares/CR loss
		
		system.setLightColor(new Color(255, 245, 225)); // light color in entire system, affects all entities
		
		
		PlanetAPI ancyra = system.addPlanet("ancyra", star, "Ancyra", "arid", 55, 150, 2800, 170);
		ancyra.setFaction(Factions.HEGEMONY);
		ancyra.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "asharu"));
		ancyra.getSpec().setGlowColor(new Color(255,255,255,255));
		ancyra.getSpec().setUseReverseLightForGlow(true);
		ancyra.applySpecChanges();
		ancyra.setCustomDescriptionId("planet_ancyra");

		SectorEntityToken ancyraStation = system.addCustomEntity("ancyra_station", "Ancyra 轨道工厂", "station_side00", Factions.HEGEMONY);
		ancyraStation.setCircularOrbitPointingDown(ancyra, 300, 350, 30);
		ancyraStation.setInteractionImage("illustrations", "orbital");
		ancyraStation.setCustomDescriptionId("station_ancyra");
		
		// create a market for galatia1 - not connected to the rest of the economy to start with
		MarketAPI market = Global.getFactory().createMarket("ancyra_market", ancyra.getName(), 0);
		market.setSize(5);
		market.setFactionId(Factions.HEGEMONY);
		
		market.setSurveyLevel(SurveyLevel.FULL);
		market.setPrimaryEntity(ancyra);
		market.getConnectedEntities().add(ancyraStation);
		
		market.setFactionId(ancyraStation.getFaction().getId());
		market.addCondition(Conditions.ARID);
		market.addCondition(Conditions.HOT);
		market.addCondition(Conditions.HABITABLE);
		market.addCondition(Conditions.POPULATION_5);
		
		market.addIndustry(Industries.POPULATION);
		market.addIndustry(Industries.LIGHTINDUSTRY);
		market.addIndustry(Industries.SPACEPORT);
		market.addIndustry(Industries.PATROLHQ);
		market.addIndustry(Industries.ORBITALSTATION);
		
		market.addSubmarket(Submarkets.SUBMARKET_OPEN);
		market.addSubmarket(Submarkets.SUBMARKET_BLACK);
		market.addSubmarket(Submarkets.SUBMARKET_STORAGE);
		market.getTariff().modifyFlat("default_tariff", market.getFaction().getTariffFraction());
		
		market.setUseStockpilesForShortages(true);
		
		ancyraStation.setMarket(market);
		ancyra.setMarket(market);
		
		market.addSubmarket(Submarkets.LOCAL_RESOURCES);
		Misc.getLocalResourcesCargo(market).addSupplies(4000f);
		Misc.getLocalResourcesCargo(market).addFuel(10000f);
		//Misc.getLocalResourcesCargo(market).addItems(CargoItemType.RESOURCES, Commodities.HEAVY_MACHINERY, 500f);
		Misc.getLocalResourcesCargo(market).addItems(CargoItemType.RESOURCES, Commodities.FOOD, 8000f);
		//Misc.getLocalResourcesCargo(market).addItems(CargoItemType.RESOURCES, Commodities.DOMESTIC_GOODS, 2000f);
		
		
		market.getMemoryWithoutUpdate().set(MemFlags.MARKET_DO_NOT_INIT_COMM_LISTINGS, true);
		market.getStats().getDynamic().getMod(Stats.PATROL_NUM_LIGHT_MOD).modifyMult("tut", 0f);
		market.getStats().getDynamic().getMod(Stats.PATROL_NUM_MEDIUM_MOD).modifyMult("tut", 0f);
		market.getStats().getDynamic().getMod(Stats.PATROL_NUM_HEAVY_MOD).modifyMult("tut", 0f);
		market.setEconGroup(market.getId());
		Global.getSector().getEconomy().addMarket(market, true);
		
		//ancyra.addScript(new GalatiaMarketScript(market));
		
		
		// Ancyra Relay - L4 (ahead)
		SectorEntityToken relay = system.addCustomEntity("ancyra_relay", // unique id
				 "Ancyra Relay", // name - if null, defaultName from custom_entities.json will be used
				 Entities.COMM_RELAY, // type of object, defined in custom_entities.json
				 Factions.HEGEMONY); // faction
		relay.setCircularOrbitPointingDown(star, 
										   ancyra.getCircularOrbitAngle() - 60f, 
										   ancyra.getCircularOrbitRadius(),
										   ancyra.getCircularOrbitPeriod());
		relay.getMemoryWithoutUpdate().set(MemFlags.OBJECTIVE_NON_FUNCTIONAL, true);
		
		
		SectorEntityToken pontus = system.addPlanet("pontus", star, "Pontus", "gas_giant", 200, 300, 6200, 400);
		
			SectorEntityToken galatiaAcademy = system.addCustomEntity("station_galatia_academy", "Galatia 学院 空间站", "station_galatia", Factions.INDEPENDENT);
			galatiaAcademy.setCircularOrbitPointingDown(pontus, 30, 434, 55);
			galatiaAcademy.setInteractionImage("illustrations", "galatia_academy");
			galatiaAcademy.setCustomDescriptionId("station_galatia_academy");
			configureAcademy(galatiaAcademy);
		
			
			
		
		SectorEntityToken probe = DerelictThemeGenerator.addSalvageEntity(system, Entities.DERELICT_SURVEY_PROBE, Factions.DERELICT);
		probe.setId("galatia_probe");
		probe.setCircularOrbit(star, 200, 10500, 400f);
		Misc.setDefenderOverride(probe, new DefenderDataOverride(Factions.DERELICT, 1f, 6, 6, 1));
		CargoAPI extraProbeSalvage = Global.getFactory().createCargo(true);
		extraProbeSalvage.addCommodity(Commodities.GAMMA_CORE, 1);
		BaseSalvageSpecial.addExtraSalvage(extraProbeSalvage, probe.getMemoryWithoutUpdate(), -1);
		
		system.addRingBand(pontus, "misc", "rings_ice0", 256f, 2, Color.white, 256f, 650, 45, Terrain.RING, null);
		system.addRingBand(pontus, "misc", "rings_ice0", 256f, 3, Color.white, 256f, 800, 50, Terrain.RING, null);
		
		
		SectorEntityToken galatia_loc2 = system.addCustomEntity(null, null, "stable_location", Factions.NEUTRAL); 
		galatia_loc2.setCircularOrbitPointingDown( star, 200-60, 6200, 400);
		
		float beltOrbitRadius = 8000;
		system.addAsteroidBelt(star, 150, beltOrbitRadius, 500, 150, 300, Terrain.ASTEROID_BELT, null);
		system.addRingBand(star, "misc", "rings_dust0", 256f, 3, Color.white, 256f, beltOrbitRadius - 50, 305f, null, null);
		system.addRingBand(star, "misc", "rings_asteroids0", 256f, 3, Color.white, 256f, beltOrbitRadius + 50, 295f, null, null);
		
		
		SectorEntityToken tetra = system.addPlanet("tetra", star, "Tetra", "barren", 135f + 180f, 70, beltOrbitRadius + 1000f, 310);
		addDerelict(system, tetra, "wolf_Assault", ShipCondition.BATTERED, 200f, true);
		addDerelict(system, tetra, "lasher_CS", ShipCondition.BATTERED, 270f, false);
		addDerelict(system, tetra, "kite_Standard", ShipCondition.AVERAGE, 300f, true);
		addDerelict(system, tetra, "kite_Standard", ShipCondition.BATTERED, 350f, false);
		addDerelict(system, tetra, "tarsus_d_Standard", ShipCondition.BATTERED, 375f, false);
		addDerelict(system, tetra, "buffalo2_FS", ShipCondition.BATTERED, 400f, false);
		addDerelict(system, tetra, "hammerhead_Balanced", ShipCondition.AVERAGE, 450f, true);
		addDerelict(system, tetra, "condor_Support", ShipCondition.BATTERED, 500f, false);
		addDerelict(system, tetra, "dram_Light", ShipCondition.BATTERED, 525f, true);
		
		SectorEntityToken derinkuyuStation = system.addCustomEntity("derinkuyu_station", "Derinkuyu 采矿空间站", "station_mining00", Factions.PIRATES);
		derinkuyuStation.setCircularOrbitWithSpin(star, 135, beltOrbitRadius - 100f, 300f, 3f, 5f);
		//derinkuyuStation.setCircularOrbitPointingDown(galatia1, 300, 350, 30);
		derinkuyuStation.setInteractionImage("illustrations", "orbital");
		derinkuyuStation.setCustomDescriptionId("station_derinkuyu");
		
		// create a market for derinkuyuStation - not connected to the rest of the economy to start with
		market = Global.getFactory().createMarket("derinkuyu_market", derinkuyuStation.getName(), 0);
		market.setSize(4);
		market.setFactionId(Factions.PIRATES);
		
		market.setSurveyLevel(SurveyLevel.FULL);
		market.setPrimaryEntity(derinkuyuStation);
		
		market.setFactionId(derinkuyuStation.getFaction().getId());
		market.addCondition(Conditions.POPULATION_4);
		market.addCondition(Conditions.ORE_SPARSE);
		Misc.setFullySurveyed(market, null, false);
		//market.addCondition(Conditions.ORBITAL_STATION);
		//market.addCondition(Conditions.FREE_PORT);
		
		market.addIndustry(Industries.POPULATION);
		market.addIndustry(Industries.SPACEPORT);
		market.addIndustry(Industries.MINING);

		
		market.addSubmarket(Submarkets.SUBMARKET_OPEN);
		market.addSubmarket(Submarkets.SUBMARKET_BLACK);
		market.addSubmarket(Submarkets.SUBMARKET_STORAGE);
		market.getTariff().modifyFlat("default_tariff", market.getFaction().getTariffFraction());
		
		derinkuyuStation.setMarket(market);
		//derinkuyuStation.addScript(new GalatiaMarketScript(market));
		
		market.setEconGroup(market.getId());
		Global.getSector().getEconomy().addMarket(market, true);
		
		// Galatia Gate
		SectorEntityToken gate = system.addCustomEntity("galatia_gate", // unique id
				 "Galatia 之门", // name - if null, defaultName from custom_entities.json will be used
				 Entities.INACTIVE_GATE, // type of object, defined in custom_entities.json
				 null); // faction
		gate.setCircularOrbit(star, 120, 4200, 400);
		
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
		debrisNextToGate.setId("galatia_debrisNextToGate");
		
		//DebrisFieldParams params = new DebrisFieldParams(
		params = new DebrisFieldParams(
				500f, // field radius - should not go above 1000 for performance reasons
				-1f, // density, visual - affects number of debris pieces
				10000000f, // duration in days 
				0f); // days the field will keep generating glowing pieces
		params.source = DebrisFieldSource.MIXED;
		params.baseSalvageXP = 250; // base XP for scavenging in field
		SectorEntityToken debrisNextToBelt = Misc.addDebrisField(system, params, StarSystemGenerator.random);
		debrisNextToBelt.setSensorProfile(null);
		debrisNextToBelt.setDiscoverable(null);
		debrisNextToBelt.setCircularOrbit(star, 210f, beltOrbitRadius - 500f, 320f);
		debrisNextToBelt.setId("galatia_debrisNextToBelt");
		

		
		JumpPointAPI inner = Global.getFactory().createJumpPoint("galatia_jump_point_alpha", "Galatia 星系内部跳跃点");
		//OrbitAPI orbit = Global.getFactory().createCircularOrbit(star, 0, 5200, 400);
		OrbitAPI orbit = Global.getFactory().createCircularOrbit(star, 
										pontus.getCircularOrbitAngle() + 180f,
										pontus.getCircularOrbitRadius() + 1000f,
										pontus.getCircularOrbitPeriod());
		inner.setOrbit(orbit);
		//jumpPoint_inner.setRelatedPlanet(galatia1);
		inner.setStandardWormholeToHyperspaceVisual();
		system.addEntity(inner);
		
		JumpPointAPI fringe = Global.getFactory().createJumpPoint("galatia_jump_point_fringe", "Galatia 星系边缘跳跃点");
		orbit = Global.getFactory().createCircularOrbit(star, 160, 11200, 600);
		fringe.setOrbit(orbit);
		fringe.setStandardWormholeToHyperspaceVisual();
		system.addEntity(fringe);
		
		inner.getMemoryWithoutUpdate().set(JumpPointInteractionDialogPluginImpl.UNSTABLE_KEY, true);
		fringe.getMemoryWithoutUpdate().set(JumpPointInteractionDialogPluginImpl.UNSTABLE_KEY, true);
		system.addTag(Tags.SYSTEM_CUT_OFF_FROM_HYPER);
			
		// L4 & L5 mini-nebulas
		SectorEntityToken pontus_L4_nebula = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
				"  x   " +
				"  xx x" +
				"xxxxx " +
				" xxx  " +
				" x  x " +
				"   x  ",
				6, 6, // size of the nebula grid, should match above string
				"terrain", "nebula_blue", 4, 4, null));
		pontus_L4_nebula.setId("pontus_L4");
		SectorEntityToken pontus_L5_nebula = system.addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
				"  x   " +
				" xx xx" +
				"x  xx " +
				" xxxx " +
				" x x x" +
				"  x   ",
				6, 6, // size of the nebula grid, should match above string
				"terrain", "nebula_blue", 4, 4, null));
		pontus_L5_nebula.setId("pontus_L5");
		pontus_L4_nebula.setCircularOrbit(star, 200 + 60, 7800, 500);
		pontus_L5_nebula.setCircularOrbit(star, 200 - 60, 7800, 500);
		
		
		
		params = new DebrisFieldParams(
						350f, // field radius - should not go above 1000 for performance reasons
						-1f, // density, visual - affects number of debris pieces
						10000000f, // duration in days 
						0f); // days the field will keep generating glowing pieces
		params.source = DebrisFieldSource.MIXED;
		params.baseSalvageXP = 250; // base XP for scavenging in field
		SectorEntityToken debris = Misc.addDebrisField(system, params, StarSystemGenerator.random);
		debris.setId("debris_tutorial");
		CargoAPI extraSalvage = Global.getFactory().createCargo(true);
		
		extraSalvage.addSupplies(20);
		extraSalvage.addCommodity(Commodities.HEAVY_MACHINERY, 10);
		//extraSalvage.addCommodity(Commodities.RARE_METALS, 10);
		
		BaseSalvageSpecial.addExtraSalvage(extraSalvage, debris.getMemoryWithoutUpdate(), -1);
		
		// makes the debris field always visible on map/sensors and not give any xp or notification on being discovered
		debris.setSensorProfile(null);
		debris.setDiscoverable(null);

		//debris.setCircularOrbit(mayasura_star, 45 + 10, 4500, 250);
		debris.getLocation().set(1000, -14000);
		
		
		
		// generates hyperspace destinations for in-system jump points
		system.autogenerateHyperspaceJumpPoints(true, false);
		
		// done automatically for systems with markets in them,
		// but since the markets in Galatia aren't part of the economy to start,
		// need to do manually here.
		system.setEnteredByPlayer(true);
		//Misc.setAllPlanetsKnown(system);
		Misc.setAllPlanetsSurveyed(system, true);
		
		
		
		// added in NGCAddStandardStartingScript
		//system.addScript(new CampaignTutorialScript(system));
	}
	
	protected void configureAcademy(SectorEntityToken galatiaAcademy) {
		MarketAPI market = Global.getFactory().createMarket("ga_market", galatiaAcademy.getName(), 3);
		market.setSize(3);
		market.setHidden(true);
		market.setFactionId(Factions.INDEPENDENT);
		market.setSurveyLevel(SurveyLevel.FULL);
		market.setFactionId(galatiaAcademy.getFaction().getId());
		market.getMemoryWithoutUpdate().set(MemFlags.MARKET_HAS_CUSTOM_INTERACTION_OPTIONS, true);
		
		// probably not necessary
//		market.addCondition(Conditions.POPULATION_3);
//		market.addIndustry(Industries.POPULATION);
//		market.addIndustry(Industries.SPACEPORT);
//		market.addIndustry(Industries.ORBITALSTATION);
//		
//		market.addSubmarket(Submarkets.SUBMARKET_OPEN);
//		market.addSubmarket(Submarkets.SUBMARKET_BLACK);
//		market.getTariff().modifyFlat("default_tariff", market.getFaction().getTariffFraction());
		
		market.setPrimaryEntity(galatiaAcademy);
		galatiaAcademy.setMarket(market);

		// if we wanted it to be discoverable 
//		galatiaAcademy.setSensorProfile(1f);
//		galatiaAcademy.setDiscoverable(true);
//		galatiaAcademy.getDetectedRangeMod().modifyFlat("gen", 5000f);
		
//		market.setEconGroup(market.getId());
//		market.getMemoryWithoutUpdate().set(DecivTracker.NO_DECIV_KEY, true);
		
		//market.reapplyIndustries();
		// of note: not adding this to the economy, so it's very much a "fake" market
		
		People.createAcademyPersonnel(market);
	}
	
	protected void addDerelict(StarSystemAPI system, SectorEntityToken focus, String variantId, 
								ShipCondition condition, float orbitRadius, boolean recoverable) {
		DerelictShipData params = new DerelictShipData(new PerShipData(variantId, condition, 0f), false);
		SectorEntityToken ship = BaseThemeGenerator.addSalvageEntity(system, Entities.WRECK, Factions.NEUTRAL, params);
		ship.setDiscoverable(true);
		
		float orbitDays = orbitRadius / (10f + (float) Math.random() * 5f);
		ship.setCircularOrbit(focus, (float) Math.random() * 360f, orbitRadius, orbitDays);
		
		if (recoverable) {
			ShipRecoverySpecialCreator creator = new ShipRecoverySpecialCreator(null, 0, 0, false, null, null);
			Misc.setSalvageSpecial(ship, creator.createSpecial(ship, null));
		}
		
		if (variantId.equals("condor_Support")) {
			ship.getMemoryWithoutUpdate().set("$tutorialUseStoryPoint", true);
		}
	}
}









