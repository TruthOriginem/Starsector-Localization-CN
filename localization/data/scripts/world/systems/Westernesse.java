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
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;

public class Westernesse {

	public void generate(SectorAPI sector) {
		
		StarSystemAPI system = sector.createStarSystem("Westernesse");
		LocationAPI hyper = Global.getSector().getHyperspace();
		
		system.setBackgroundTextureFilename("graphics/backgrounds/background6.jpg");
		
		// create the star and generate the hyperspace anchor for this system
		PlanetAPI westernesse_star = system.initStar("westernesse",
				"star_yellow",
				750f,
				500, // extent of corona outside star
				10f, // solar wind burn level
				1f, // flare probability
				3f); // CR loss multiplier, good values are in the range of 1-5
		
		system.setLightColor(new Color(255, 245, 185)); // light color in entire system, affects all entities
		
		float radiusAfter1 = StarSystemGenerator.addOrbitingEntities(system, westernesse_star, StarAge.OLD,
				1, 2, // min/max entities to add
				1800, // radius to start adding at 
				0, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
				true, // whether to use custom or system-name based names
				false); // whether to allow habitable worlds
		
		PlanetAPI horn = system.addPlanet("horn", westernesse_star, "Horn", "rocky_unstable", 90, 225, radiusAfter1 + 1500, 90);
		horn.setCustomDescriptionId("planet_horn");
		
			PlanetAPI athulf = system.addPlanet("athulf", horn, "Athulf", "rocky_metallic", 30, 65, 575, 24);
			athulf.setCustomDescriptionId("planet_athulf");
			
			PlanetAPI fikenhild = system.addPlanet("fikenhild", horn, "Fikenhild", "water", 270, 85, 800, 34);
			fikenhild.getSpec().setPlanetColor(new Color(240,225,255,255));
			fikenhild.applySpecChanges();
			fikenhild.setInteractionImage("illustrations", "fikenhild");
			fikenhild.setCustomDescriptionId("planet_fikenhild");
			
		//	SectorEntityToken fikenhildStation = system.addCustomEntity("fikenhild_station", "Horn Starport", "station_midline1",  Factions.PERSEAN);
		//	fikenhildStation.setCircularOrbitPointingDown( fikenhild, 0, 150, 30);		
		//	fikenhildStation.setInteractionImage("illustrations", "orbital");
		
		JumpPointAPI jumpPoint1 = Global.getFactory().createJumpPoint("westernesse_jump", "Westernesse Jump-point");
		jumpPoint1.setCircularOrbit( system.getEntityById("westernesse"), 90 + 60, radiusAfter1 + 1500 , 90);
		jumpPoint1.setRelatedPlanet(horn);
		jumpPoint1.setStandardWormholeToHyperspaceVisual();
		system.addEntity(jumpPoint1);
		
		// Westernesse Relay - L5 (behind)
		SectorEntityToken westernesse_relay = system.addCustomEntity("westernesse_relay", "Westernesse Relay", "comm_relay", "persean");
		westernesse_relay.setCircularOrbitPointingDown( system.getEntityById("westernesse"), 90 - 60, radiusAfter1 + 1500 , 90);
				
		PlanetAPI suddene = system.addPlanet("suddene", westernesse_star, "Suddene", "barren-desert", 180, 145, radiusAfter1+2600, 210);
		suddene.setCustomDescriptionId("planet_suddene");
		
		PlanetAPI ailmar = system.addPlanet("ailmar", westernesse_star, "Ailmar", "tundra", 0, 165, radiusAfter1+3300, 390);
		ailmar.setCustomDescriptionId("planet_ailmar");
			PlanetAPI rymenhild = system.addPlanet("rymenhild", ailmar, "Rymenhild", "barren-bombarded", 180, 65, 450, 40);
			
		SectorEntityToken westernesse_stable2 = system.addCustomEntity(null, null, "nav_buoy_makeshift", Factions.INDEPENDENT);
		westernesse_stable2.setCircularOrbitPointingDown(westernesse_star, -60, radiusAfter1+3300, 390);
			
			
		float radiusAfter2 = StarSystemGenerator.addOrbitingEntities(system, westernesse_star, StarAge.AVERAGE,
					2, 3, // min/max entities to add
					radiusAfter1 + 7600, // radius to start adding at 
					4, // name offset - next planet will be <system name> <roman numeral of this parameter + 1>
					true, // whether to use custom or system-name based names
					false); // whether to allow habitable worlds

		StarSystemGenerator.addSystemwideNebula(system, StarAge.OLD);
		system.autogenerateHyperspaceJumpPoints(true, true);
	}
}