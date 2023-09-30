package data.scripts.world.systems;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;

public class Naraka {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Naraka");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background2.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI naraka_star = system.initStar("naraka", // unique id for this star 
											    "star_orange",  // id in planets.json
											    650f, 		  // radius (in pixels at default zoom)
											    200, // corona
											    5f, // solar wind burn level
												0.65f, // flare probability
												2.2f); // CR loss multiplier, good values are in the range of 1-5
		
		system.setLightColor(new Color(255, 220, 200)); // light color in entire system, affects all entities
		
		SectorEntityToken naraka_stable1 = system.addCustomEntity(null, null, "stable_location", "neutral");
		naraka_stable1.setCircularOrbitPointingDown(naraka_star, 0, 2750, 160);
		
		system.addRingBand(naraka_star, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 3300, 220f, Terrain.RING, "Yamuna 小行星带");
		
		PlanetAPI naraka_b = system.addPlanet("yama", naraka_star, "Yama", "arid", 60, 165, 4600, 140);
		naraka_b.setCustomDescriptionId("planet_yama");
			PlanetAPI naraka_b1 = system.addPlanet("yami", naraka_b, "Yami", "barren-bombarded", 0, 60, 450, 40);
		
		// Yamidutas : asteroids
		system.addAsteroidBelt(naraka_b, 60, 900, 170, 200, 250, Terrain.ASTEROID_BELT, "Servants 小行星带");
		system.addRingBand(naraka_b, "misc", "rings_dust0", 256f, 0, Color.white, 256f, 900, 220f);
		
		PlanetAPI naraka_b2 = system.addPlanet("nachiketa", naraka_star, "Nachiketa", "barren", 60 - 60, 65, 4600, 140);
		naraka_b2.setCustomDescriptionId("planet_nachiketa");
		naraka_b2.getSpec().setGlowTexture(Global.getSettings().getSpriteName("hab_glows", "aurorae"));
		naraka_b2.getSpec().setGlowColor(new Color(255,60,240,200));
		naraka_b2.getSpec().setUseReverseLightForGlow(true);
		naraka_b2.getSpec().setTexture(Global.getSettings().getSpriteName("planets", "barren02"));
		naraka_b2.applySpecChanges();
		
		// naraka jump-point
		
		
		PlanetAPI naraka_c = system.addPlanet("chitagupta", naraka_star, "Chitagupta", "barren", 90, 100, 5750, 380);
		
		JumpPointAPI jumpPoint1 = Global.getFactory().createJumpPoint("naraka_jump", "Naraka 跳跃点");
		jumpPoint1.setCircularOrbit( system.getEntityById("naraka"), 60 + 60, 4600, 140);
		jumpPoint1.setRelatedPlanet(naraka_c);
		system.addEntity(jumpPoint1);
		
		// Naraka Relay
		SectorEntityToken relay = system.addCustomEntity("naraka_relay", "Naraka 通讯中继站", "comm_relay", "hegemony");
		relay.setCircularOrbitPointingDown(system.getEntityById("naraka"), 60 + 180, 4600, 140);
		
		float radiusAfter = StarSystemGenerator.addOrbitingEntities(system, naraka_star, StarAge.AVERAGE,
				4, 5, // min/max entities to add
				6500, // radius to start adding at 
				3, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds
		
		//StarSystemGenerator.addSystemwideNebula(system, StarAge.OLD);
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}