package data.scripts.world;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpawnPointPlugin;

@SuppressWarnings("unchecked")
public abstract class BaseSpawnPoint implements EveryFrameScript, SpawnPointPlugin {

	protected float daysInterval;
	private int maxFleets;
	private SectorEntityToken anchor;
	
	private List fleets = new ArrayList();
	private long lastSpawnTime = Long.MIN_VALUE;
	
	private SectorAPI sector;
	private LocationAPI location;
	
	public BaseSpawnPoint(SectorAPI sector, LocationAPI location, 
						 float daysInterval, int maxFleets, SectorEntityToken anchor) {
		this.daysInterval = daysInterval;
		this.maxFleets = maxFleets;
		this.anchor = anchor;
		this.sector = sector;
		this.location = location;
		
		lastSpawnTime = Global.getSector().getClock().getTimestamp();
	}

	
	/* 
	 * Included only for backwards compatibility with some test saves.
	 */
	public void advance(SectorAPI sector, LocationAPI location) {
		advance(0f); 
	}

	public void advance(float amount) {
		CampaignClockAPI clock = sector.getClock();
		
		if (clock.getElapsedDaysSince(lastSpawnTime) >= daysInterval) {
			lastSpawnTime = clock.getTimestamp();
			
			Iterator iter = fleets.iterator();
			while (iter.hasNext()) {
				CampaignFleetAPI fleet = (CampaignFleetAPI) iter.next();
				if (!fleet.isAlive()) iter.remove();
			}
			
			if (fleets.size() < maxFleets) {
				CampaignFleetAPI fleet = spawnFleet();
				if (fleet != null) fleets.add(fleet);
			}
		}
	}
		
	public boolean isDone() {
		return false;
	}

	public boolean runWhilePaused() {
		return false;
	}

	public float getDaysInterval() {
		return daysInterval;
	}

	public int getMaxFleets() {
		return maxFleets;
	}

	public SectorEntityToken getAnchor() {
		return anchor;
	}

	public List getFleets() {
		return fleets;
	}

	public long getLastSpawnTime() {
		return lastSpawnTime;
	}

	public SectorAPI getSector() {
		return sector;
	}

	public LocationAPI getLocation() {
		return location;
	}

	protected abstract CampaignFleetAPI spawnFleet();
	
}




