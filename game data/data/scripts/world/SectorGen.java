package data.scripts.world;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SectorGeneratorPlugin;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.CoreCampaignPluginImpl;
import com.fs.starfarer.api.impl.campaign.CoreScript;
import com.fs.starfarer.api.impl.campaign.events.CoreEventProbabilityManager;
import com.fs.starfarer.api.impl.campaign.fleets.DisposableLuddicPathFleetManager;
import com.fs.starfarer.api.impl.campaign.fleets.DisposablePirateFleetManager;
import com.fs.starfarer.api.impl.campaign.fleets.EconomyFleetRouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.MercFleetManagerV2;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.NebulaEditor;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.util.Misc;

import data.hullmods.HeavyArmor;
import data.scripts.world.corvus.Corvus;
import data.scripts.world.systems.AlGebbar;
import data.scripts.world.systems.Arcadia;
import data.scripts.world.systems.Askonia;
import data.scripts.world.systems.Aztlan;
import data.scripts.world.systems.Canaan;
import data.scripts.world.systems.Duzahk;
import data.scripts.world.systems.Eos;
import data.scripts.world.systems.Galatia;
import data.scripts.world.systems.Hybrasil;
import data.scripts.world.systems.Isirah;
import data.scripts.world.systems.KumariKandam;
import data.scripts.world.systems.Magec;
import data.scripts.world.systems.Mayasura;
import data.scripts.world.systems.Naraka;
import data.scripts.world.systems.Penelope;
import data.scripts.world.systems.Samarra;
import data.scripts.world.systems.Thule;
import data.scripts.world.systems.TiaTaxet;
import data.scripts.world.systems.Tyle;
import data.scripts.world.systems.Valhalla;
import data.scripts.world.systems.Westernesse;
import data.scripts.world.systems.Yma;
import data.scripts.world.systems.Zagan;

public class SectorGen implements SectorGeneratorPlugin {

	public void generate(SectorAPI sector) {
		//ClassLoader cl = Global.getSettings().getScriptClassLoader();
		
		StarSystemAPI system = sector.createStarSystem("Corvus");
		//system.getLocation().set(16000 - 8000, 9000 - 10000);
		system.setBackgroundTextureFilename("graphics/backgrounds/background4.jpg");
		
		//sector.setCurrentLocation(system);
		sector.setRespawnLocation(system);
		sector.getRespawnCoordinates().set(-2500, -3500);
		
		initFactionRelationships(sector);
		
		new Galatia().generate(sector);
		new Askonia().generate(sector);
		new Eos().generate(sector);
		new Valhalla().generate(sector);
		new Arcadia().generate(sector);
		new Magec().generate(sector);
		new Corvus().generate(sector);
		new Aztlan().generate(sector);
		new Samarra().generate(sector);
		new Penelope().generate(sector);
		new Yma().generate(sector);
		new Hybrasil().generate(sector);
		new Duzahk().generate(sector);
		new TiaTaxet().generate(sector);
		new Canaan().generate(sector);
		new AlGebbar().generate(sector);
		new Isirah().generate(sector);
		new KumariKandam().generate(sector);
		new Naraka().generate(sector);
		new Thule().generate(sector);
		new Mayasura().generate(sector);
		new Zagan().generate(sector);
		new Westernesse().generate(sector);
		new Tyle().generate(sector);
		
		LocationAPI hyper = Global.getSector().getHyperspace();
		SectorEntityToken atlanticLabel = hyper.addCustomEntity("atlantic_label_id", null, "atlantic_label", null);
		SectorEntityToken perseanLabel = hyper.addCustomEntity("persean_label_id", null, "persean_label", null);
		SectorEntityToken luddicLabel = hyper.addCustomEntity("luddic_label_id", null, "luddic_label", null);
		SectorEntityToken zinLabel = hyper.addCustomEntity("zin_label_id", null, "zin_label", null);
		SectorEntityToken abyssLabel = hyper.addCustomEntity("opabyss_label_id", null, "opabyss_label", null);
		SectorEntityToken telmunLabel = hyper.addCustomEntity("telmun_label_id", null, "telmun_label", null);
		SectorEntityToken cathedralLabel = hyper.addCustomEntity("cathedral_label_id", null, "cathedral_label", null);
		SectorEntityToken coreLabel = hyper.addCustomEntity("core_label_id", null, "core_label", null);
		
		atlanticLabel.setFixedLocation(500, -2000);
		perseanLabel.setFixedLocation(-10000, 1000);
		luddicLabel.setFixedLocation(-14000, -9500);
		zinLabel.setFixedLocation(-22000, -17000); 
		telmunLabel.setFixedLocation(-16000, 0);
		cathedralLabel.setFixedLocation(-12700, -12000);
		coreLabel.setFixedLocation(0, -6000);
		
		abyssLabel.setFixedLocation(-65000, -47000);
		
		/*SectorEntityToken deep_hyperspace_test = Global.getSector().getHyperspace().addTerrain(Terrain.NEBULA, new BaseTiledTerrain.TileParams(
				"   xx     " +
				"   xxx    " +
				"  xxx x   " +
				"  xx   x  " +
				" xxxx xxx " +
				"  xxxxxxx " +
				" xxxxxxxxx" +
				" xxxxxxxxx" +
				"  xxxxxxx " +
				" xxxxxxx  " +
				" x xxxxx  " +
				"  xxxxxx  " +
				" xxxx xxx " +
				"xxxx  xxx " +
				" xxxx     " +
				"xxxxxxxxx " +
				"  xxxxxxxx" +
				" xxxxxxxxx" +
				"  xxxxxxx " +
				"   xxx    ",
				10, 20, // size of the nebula grid, should match above string
				"terrain", "deep_hyperspace", 4, 4));
		
		deep_hyperspace_test.getLocation().set(5000,5000);*/
		
		
		SectorEntityToken deep_hyperspace = Misc.addNebulaFromPNG("data/campaign/terrain/hyperspace_map.png",
		//SectorEntityToken deep_hyperspace = Misc.addNebulaFromPNG("data/campaign/terrain/hyperspace_map_filled.png",
				  0, 0, // center of nebula
				  Global.getSector().getHyperspace(), // location to add to
				  "terrain", "deep_hyperspace", // "nebula_blue", // texture to use, uses xxx_map for map
				  4, 4, Terrain.HYPERSPACE, null); // number of cells in texture
		
		
		
		// ensure area around stars is clear
		HyperspaceTerrainPlugin plugin = (HyperspaceTerrainPlugin) Misc.getHyperspaceTerrain().getPlugin();
		NebulaEditor editor = new NebulaEditor(plugin);
		float minRadius = plugin.getTileSize() * 2f;
		for (StarSystemAPI curr : sector.getStarSystems()) {
			float radius = curr.getMaxRadiusInHyperspace() * 0.5f;
			editor.clearArc(curr.getLocation().x, curr.getLocation().y, 0, radius + minRadius * 0.5f, 0, 360f);
			editor.clearArc(curr.getLocation().x, curr.getLocation().y, 0, radius + minRadius, 0, 360f, 0.25f);
		}
		
		
		
//		PirateSpawnPoint pirateSpawn = new PirateSpawnPoint(sector, sector.getHyperspace(), 1, 15, system.getHyperspaceAnchor());
//		system.addSpawnPoint(pirateSpawn);
//		for (int i = 0; i < 2; i++) {
//			pirateSpawn.spawnFleet();
//		}
		
		// need to do this after hyperspace terrain exists
		//SectorProcGen.generate();
		// this is done through settings.json, "plugins"->"newGameSectorProcGen"
		
		sector.registerPlugin(new CoreCampaignPluginImpl());
		sector.addScript(new CoreScript());
		sector.addScript(new CoreEventProbabilityManager());
		
		sector.addScript(new EconomyFleetRouteManager());
		//sector.addScript(new MercFleetManager());
		sector.addScript(new MercFleetManagerV2());
		
		
		sector.addScript(new DisposablePirateFleetManager());
		sector.addScript(new DisposableLuddicPathFleetManager());
		
//		sector.addScript(new LuddicPathFleetManager());
//		sector.addScript(new PirateFleetManager());
//		sector.addScript(new BountyPirateFleetManager());
		
	}
	
	public static void initFactionRelationships(SectorAPI sector) {
		
		
		// forget why this is necessary - workaround for some JANINO issue, I think
		Class c = HeavyArmor.class;
		
		FactionAPI hegemony = sector.getFaction(Factions.HEGEMONY);
		FactionAPI tritachyon = sector.getFaction(Factions.TRITACHYON);
		FactionAPI pirates = sector.getFaction(Factions.PIRATES);
		FactionAPI independent = sector.getFaction(Factions.INDEPENDENT);
		FactionAPI kol = sector.getFaction(Factions.KOL);
		FactionAPI church = sector.getFaction(Factions.LUDDIC_CHURCH);
		FactionAPI path = sector.getFaction(Factions.LUDDIC_PATH);
		FactionAPI player = sector.getFaction(Factions.PLAYER);
		FactionAPI diktat = sector.getFaction(Factions.DIKTAT);
		FactionAPI persean = sector.getFaction(Factions.PERSEAN);
		FactionAPI remnant = sector.getFaction(Factions.REMNANTS);
		FactionAPI threat = sector.getFaction(Factions.THREAT);
		FactionAPI dweller = sector.getFaction(Factions.DWELLER);
		FactionAPI derelict = sector.getFaction(Factions.DERELICT);
		
		player.setRelationship(hegemony.getId(), 0);
		player.setRelationship(tritachyon.getId(), 0);
		player.setRelationship(persean.getId(), 0);
		//player.setRelationship(pirates.getId(), RepLevel.HOSTILE);
		player.setRelationship(pirates.getId(), -0.65f);
		
		player.setRelationship(independent.getId(), 0);
		player.setRelationship(kol.getId(), 0);
		player.setRelationship(church.getId(), 0);
		//player.setRelationship(path.getId(), RepLevel.HOSTILE);
		player.setRelationship(path.getId(), -0.65f);
		

		// replaced by hostilities set in CoreLifecyclePluginImpl
		//hegemony.setRelationship(tritachyon.getId(), RepLevel.HOSTILE);
		//hegemony.setRelationship(persean.getId(), RepLevel.HOSTILE);
		
		hegemony.setRelationship(pirates.getId(), RepLevel.HOSTILE);
		
		tritachyon.setRelationship(pirates.getId(), RepLevel.HOSTILE);
		//tritachyon.setRelationship(independent.getId(), -1);
		tritachyon.setRelationship(kol.getId(), RepLevel.HOSTILE);
		//tritachyon.setRelationship(church.getId(), RepLevel.HOSTILE);
		tritachyon.setRelationship(path.getId(), RepLevel.HOSTILE);
		tritachyon.setRelationship(persean.getId(), RepLevel.SUSPICIOUS);
		
		pirates.setRelationship(kol.getId(), RepLevel.HOSTILE);
		pirates.setRelationship(church.getId(), RepLevel.HOSTILE);
		pirates.setRelationship(path.getId(), 0);
		pirates.setRelationship(independent.getId(), RepLevel.HOSTILE);
		pirates.setRelationship(diktat.getId(), RepLevel.HOSTILE);
		pirates.setRelationship(persean.getId(), RepLevel.HOSTILE);
		
		church.setRelationship(kol.getId(), RepLevel.COOPERATIVE);
		path.setRelationship(kol.getId(), RepLevel.FAVORABLE);
		
		path.setRelationship(independent.getId(), RepLevel.HOSTILE);
		path.setRelationship(hegemony.getId(), RepLevel.HOSTILE);
		path.setRelationship(diktat.getId(), RepLevel.HOSTILE);
		path.setRelationship(persean.getId(), RepLevel.HOSTILE);
		path.setRelationship(church.getId(), RepLevel.COOPERATIVE);
		
		persean.setRelationship(tritachyon.getId(), RepLevel.SUSPICIOUS);
		persean.setRelationship(pirates.getId(), RepLevel.HOSTILE);
		persean.setRelationship(path.getId(), RepLevel.HOSTILE);
		persean.setRelationship(diktat.getId(), RepLevel.COOPERATIVE);
		
		player.setRelationship(remnant.getId(), RepLevel.HOSTILE);
		independent.setRelationship(remnant.getId(), RepLevel.HOSTILE);
		pirates.setRelationship(remnant.getId(), RepLevel.HOSTILE);
		hegemony.setRelationship(remnant.getId(), RepLevel.HOSTILE);
		kol.setRelationship(remnant.getId(), RepLevel.HOSTILE);
		church.setRelationship(remnant.getId(), RepLevel.HOSTILE);
		path.setRelationship(remnant.getId(), RepLevel.HOSTILE);
		diktat.setRelationship(remnant.getId(), RepLevel.HOSTILE);
		persean.setRelationship(remnant.getId(), RepLevel.HOSTILE);
		
		player.setRelationship(threat.getId(), RepLevel.HOSTILE);
		independent.setRelationship(threat.getId(), RepLevel.HOSTILE);
		pirates.setRelationship(threat.getId(), RepLevel.HOSTILE);
		hegemony.setRelationship(threat.getId(), RepLevel.HOSTILE);
		kol.setRelationship(threat.getId(), RepLevel.HOSTILE);
		church.setRelationship(threat.getId(), RepLevel.HOSTILE);
		path.setRelationship(threat.getId(), RepLevel.HOSTILE);
		diktat.setRelationship(threat.getId(), RepLevel.HOSTILE);
		persean.setRelationship(threat.getId(), RepLevel.HOSTILE);
		remnant.setRelationship(threat.getId(), RepLevel.HOSTILE);
		derelict.setRelationship(threat.getId(), RepLevel.HOSTILE);
		
		player.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		independent.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		pirates.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		hegemony.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		kol.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		church.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		path.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		diktat.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		persean.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		remnant.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		derelict.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		threat.setRelationship(dweller.getId(), RepLevel.HOSTILE);
		
//		independent.setRelationship(hegemony.getId(), 0);
//		independent.setRelationship(tritachyon.getId(), 0);
//		independent.setRelationship(pirates.getId(), 0);
//		independent.setRelationship(independent.getId(), 0);
//		independent.setRelationship(player.getId(), 0);
		
	}
}