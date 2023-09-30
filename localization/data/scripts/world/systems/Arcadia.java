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
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.StarTypes;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;

public class Arcadia {

	public void generate(SectorAPI sector) {
		StarSystemAPI system = sector.createStarSystem("Arcadia");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background4.jpg");
		
		//system.getMemoryWithoutUpdate().set(MusicPlayerPluginImpl.MUSIC_SET_MEM_KEY, "music_title");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI star = system.initStar("arcadia", // unique id for star
										 StarTypes.WHITE_DWARF, // id in planets.json
										 180f,		// radius (in pixels at default zoom)
										 300); // corona radius, from star edge
		
		system.setLightColor(new Color(200, 200, 200)); // light color in entire system, affects all entities
		//star.setCustomDescriptionId("star_white");
		
		
		PlanetAPI arcadia1 = system.addPlanet("nomios", star, "Nomios", "frozen", 90, 130, 3000, 100);
		arcadia1.setCustomDescriptionId("planet_nomios");
		
			SectorEntityToken nomios_location = system.addCustomEntity(null,null, "stable_location",Factions.NEUTRAL); 
			nomios_location.setCircularOrbitPointingDown( star, 90 + 60, 3000, 100);		
		
		PlanetAPI arcadia2 = system.addPlanet("syrinx", star, "Syrinx", "ice_giant", 180, 300, 6000, 200);
		arcadia2.setCustomDescriptionId("planet_syrinx");
		
			// Moon of syrinx w/ ship-wrecking & industrial stuff
			PlanetAPI arcadia2a = system.addPlanet("agreus", arcadia2, "Agreus", "barren", 0, 130, 1600, 50);
			arcadia2a.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren02"));
			arcadia2a.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "sindria"));
			arcadia2a.getSpec().setGlowColor(new Color(235,245,255,255));
			arcadia2a.getSpec().setUseReverseLightForGlow(true);
			arcadia2a.applySpecChanges();
			arcadia2a.setInteractionImage("illustrations", "industrial_megafacility"); // TODO something better for this.
			arcadia2a.setCustomDescriptionId("planet_agreus");
		
		system.addRingBand(arcadia2, "misc", "rings_asteroids0", 256f, 0, new Color(170,210,255,255), 256f, 800, 40f, Terrain.RING, null);
		system.addAsteroidBelt(arcadia2, 20, 1000, 128, 40, 80, Terrain.ASTEROID_BELT, null);
		
			// lagrangian point of Syrinx
			SectorEntityToken relay = system.addCustomEntity("syrinx_relay", // unique id
					 "Syrinx 通讯中继站", // name - if null, defaultName from custom_entities.json will be used
					 "comm_relay_makeshift", // type of object, defined in custom_entities.json
					 "hegemony"); // faction
			relay.setCircularOrbitPointingDown( system.getEntityById("arcadia"), 180 + 60, 6000, 200);
			
			// lagrangian point of Syrinx
			JumpPointAPI jumpPoint = Global.getFactory().createJumpPoint("syrinx_passage","Syrinx 跳跃点");
			OrbitAPI orbit = Global.getFactory().createCircularOrbit(star, 180 - 60, 6000, 200);
			jumpPoint.setOrbit(orbit);	
			jumpPoint.setRelatedPlanet(arcadia2a);
			jumpPoint.setStandardWormholeToHyperspaceVisual();
			system.addEntity(jumpPoint);
		
//		SectorEntityToken arc_station = system.addOrbitalStation("arcadia_station", arcadia2, 45, 750, 30, "Citadel Arcadia", "hegemony");
//		arc_station.setCustomDescriptionId("station_arcadia"); 
		
		SectorEntityToken arc_station = system.addCustomEntity("arcadia_station", "Arcadia 星垒", "station_side02", "hegemony");
		arc_station.setCircularOrbitPointingDown(system.getEntityById("syrinx"), 45, 730, 30);		
		arc_station.setCustomDescriptionId("station_arcadia");
		arc_station.setInteractionImage("illustrations", "hound_hangar");
		

		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, star, StarAge.AVERAGE,
				3, 5, // min/max entities to add
				9400, // radius to start adding at 
				2, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds

		// being cheeky here.
		if (StarSystemGenerator.random.nextFloat() > 0.5)  {
			StarSystemGenerator.addSystemwideNebula(system, StarAge.AVERAGE);
		}

		system.autogenerateHyperspaceJumpPoints(true, true);
		
		//system.addScript(new IndependentTraderSpawnPoint(sector, hyper, 1, 10, hyper.createToken(-6000, 2000), station));
	}
		
	
}
