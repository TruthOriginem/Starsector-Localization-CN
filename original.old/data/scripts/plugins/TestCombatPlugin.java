package data.scripts.plugins;

import java.awt.Color;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.util.IntervalUtil;

public class TestCombatPlugin extends BaseEveryFrameCombatPlugin {

	/**
	 * Set this to true to have the plugin actually do stuff.
	 */
	private static boolean TEST_MODE = false;
	
	private CombatEngineAPI engine;

	
	public TestCombatPlugin() {
		//System.out.println("Created " + this);
	}
	public void init(CombatEngineAPI engine) {
		this.engine = engine;
		//System.out.println("Inited " + this);  
		
//		System.out.println("isInCampaign: " + engine.isInCampaign());
//		System.out.println("isInCampaignSim: " + engine.isInCampaignSim());
//		System.out.println("isMission: " + engine.isMission());
//		System.out.println("isSimulation: " + engine.isSimulation());
	}
	
	private IntervalUtil interval = new IntervalUtil(3f, 5f);
	private IntervalUtil interval2 = new IntervalUtil(0.1f, 0.1f);
	private boolean printed = false;
	public void advance(float amount, List events) {
//		if (!printed) {
//			//System.out.println("Advancing " + this);
//			printed = true;
//		}
		
//		for (ShipAPI curr : engine.getShips()) {
//			if (curr.isFighter() && curr.isLanding()) {
//				System.out.println("LANDING");
//			}
//		}
		
//		for (DamagingProjectileAPI proj : engine.getProjectiles()) {
//			float dam = proj.getWeapon().getDerivedStats().getDamagePerShot();
//			System.out.println("Damage: " + dam);
//		}
//		Damage: 700.0
//		try {
//            if (Global.getCurrentState().equals(GameState.TITLE) &&
//                    !Global.getSoundPlayer().getCurrentMusicId().equals("miscallenous_main_menu.ogg")) {
//                // we're on the title screen, and the title music is not playing, oh no!
//                Global.getSoundPlayer().playMusic(0, 0, "music_title");
//            } else if (Global.getCurrentState().equals(GameState.COMBAT)) {
//                // we are in combat
//                if (Global.getCombatEngine().getMissionId() != null ||
//                        Global.getCombatEngine().isSimulation()) {
//                    // we're in a mission or mission simulator, check for title music and get rid of it
//                    if (Global.getSoundPlayer().getCurrentMusicId().equals("miscallenous_main_menu.ogg")) {
//                        Global.getSoundPlayer().playMusic(0, 0, "music_combat");
//                    }
//                }
//            }
//        } catch (RuntimeException e) {
//        }
		
//        CombatEngineAPI engine = Global.getCombatEngine();
//        for (MissileAPI missile : engine.getMissiles()) {
//        	if (missile.didDamage()) {
//        		System.out.println("Missile did damage: " + missile.getDamageTarget());
//        	}
//        }
//        if (engine.getViewport() != null) {
//	        if (engine == null || engine.isUIShowingDialog()) {
//	            return;
//	        }
//        }
		
		//System.out.println("SHIPS: " + engine.getShips().size());
		
		if (!TEST_MODE || engine == null) return;
		if (engine.isPaused()) return;

//		List projectiles = Global.getCombatEngine().getProjectiles();
//		for (int i = 0; i < projectiles.size(); i++) {
//			DamagingProjectileAPI proj = (DamagingProjectileAPI) projectiles.get(i);
//			proj.getVelocity().set(proj.getVelocity());
//		}
		
//		if ((float) Math.random() > 0.95f && Global.getCombatEngine().getFleetManager(0) != null) {
//			Global.getCombatEngine().getFleetManager(0).spawnShipOrWing("wasp_wing", new Vector2f(), 0);
//		}
//		if ((float) Math.random() > 0.75f) {
//			MissileAPI missile = (MissileAPI) engine.spawnProjectile(engine.getPlayerShip(), null, "harpoon", engine.getPlayerShip().getLocation(), 0, new Vector2f());
//			System.out.println("Source: " + missile.getSource());
//		}
//		List allShips = engine.getAllShips();
//		for (Iterator iter = allShips.iterator(); iter.hasNext();) {
//			ShipAPI ship = (ShipAPI) iter.next();
//			if (ship == engine.getPlayerShip()) {
//				ship.getLocation().set(0, 0);
//			} else {
//				ship.setFacing(90);
//				ship.getLocation().set(0, 330);
//			}
//		}
//		if (true) return;
		
		interval.advance(amount);
		if (interval.intervalElapsed() && false) {
			Global.getSoundPlayer().playUISound("ui_refit_slot_cleared_large", 1, 1);
			
			ShipAPI playerShip = engine.getPlayerShip();
			if (playerShip != null && !playerShip.isShuttlePod()) {
				playerShip.setSprite("misc", "wormhole_ring");
			}
			if (playerShip != null && !playerShip.isShuttlePod()) {
				engine.addHitParticle(playerShip.getLocation(), playerShip.getVelocity(), 250f, 1f, 3f, Color.YELLOW);
				engine.addHitParticle(playerShip.getLocation(), playerShip.getVelocity(), 75f, 1f, 3f, Color.WHITE);
				
				engine.addFloatingText(playerShip.getLocation(), "Tick", 20f, Color.BLUE, playerShip, 1f, 0f);
				
				if ((float) Math.random() > 0.5f) {
					engine.applyDamage(playerShip, playerShip.getLocation(), 250f,
								DamageType.ENERGY, 0f, false, true, null);
					
					Vector2f loc = new Vector2f(playerShip.getLocation());
					loc.x += 500f - 1000f * (float) Math.random();
					loc.y += 500f - 1000f * (float) Math.random();
					engine.spawnExplosion(loc, new Vector2f(), new Color(0, 255, 165, 255), 200f, 2f);
				}
			}
		}
		
		interval2.advance(amount);
		if (interval2.intervalElapsed() || true) {
			ShipAPI playerShip = engine.getPlayerShip();
			if (playerShip != null && !playerShip.isShuttlePod()) {
				//engine.spawnProjectile(null, null, "swarmer", playerShip.getLocation(),
				WeaponAPI w = ((WeaponAPI)((WeaponGroupAPI) playerShip.getWeaponGroupsCopy().get(0)).getWeaponsCopy().get(0));
				for (int i = 0; i < 10; i++) {
					engine.spawnProjectile(playerShip, null, "lightmg", playerShip.getLocation(),
							playerShip.getFacing() + (float) Math.random() * 360f, playerShip.getVelocity());
				}
						//(float) Math.random() * 360f, playerShip.getVelocity());

//				for (int i = 0; i < 5; i++) {
//					Vector2f loc = new Vector2f(playerShip.getLocation());
//					loc.x += 50f - 100f * (float) Math.random();
//					loc.y += 50f - 100f * (float) Math.random();
//					engine.addSmokeParticle(loc, playerShip.getVelocity(), 35f, 1f, 1f, new Color(100,100,100,255));
//				}
			}

		}
		
		
		for (int i = 0; i < events.size(); i++) {
			InputEventAPI event = (InputEventAPI) events.get(i);
			if (event.isConsumed()) continue;
			
			
			if (event.isKeyDownEvent()) {
				if (event.getEventValue() == Keyboard.KEY_P) {
					ShipAPI playerShip = engine.getPlayerShip();
					Vector2f loc = new Vector2f(playerShip.getLocation());
					loc.x += 750;
					engine.getFleetManager(FleetSide.ENEMY).spawnShipOrWing("talon_wing", loc, 180);
					event.consume();
					continue;
				} else if (event.getEventValue() == Keyboard.KEY_I) {
					engine.endCombat(1f);
					event.consume();
					continue;
				}
			}
		}
	}
	public void render(ViewportAPI viewport, float alphaMult) {
		// TODO Auto-generated method stub
		if (!TEST_MODE) return;
	}
	public void renderInUICoords(ViewportAPI viewport) {
		if (!TEST_MODE) return;
		//renderQuad(100, 100, 200, 200, Color.white, 1f);
	}
	public void renderInWorldCoords(ViewportAPI viewport) {
		if (!TEST_MODE) return;
		//Vector2f loc = engine.getPlayerShip().getLocation();
		//renderQuad(loc.x, loc.y, 10, 10, Color.red, 1f);
	}


	
	public static void renderQuad(float x, float y, float width, float height, Color color, float alphaMult) {
		
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		//System.out.println((float)color.getAlpha() * alphaMult);
		GL11.glColor4ub((byte)color.getRed(),
						(byte)color.getGreen(),
						(byte)color.getBlue(),
						(byte)((float)color.getAlpha() * alphaMult));
		
		GL11.glBegin(GL11.GL_QUADS);
		{
			GL11.glVertex2f(x, y);
			GL11.glVertex2f(x, y + height);
			GL11.glVertex2f(x + width, y + height);
			GL11.glVertex2f(x + width, y);
		}
		GL11.glEnd();
	}
}
