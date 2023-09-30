package data.scripts.plugins;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.plugins.LevelupPlugin;

public class LevelupPluginImpl implements LevelupPlugin {

	/**
	 * Only used if max level is increased beyond 15 via settings.json
	 */
	public static float EXPONENT_BEYOND_MAX_SPECIFIED_LEVEL = 1.1f;
	
	/**
	 * Max level XP times this is how much XP it takes to gain storyPointsPerLevel story points once at max level.
	 */
	public static float XP_REQUIRED_FOR_STORY_POINT_GAIN_AT_MAX_LEVEL_MULT = 2f;
	public static int LEVEL_FOR_BASE_XP_FOR_MAXED_STORY_POINT_GAIN = 15;
	
	public static long [] XP_PER_LEVEL = new long [] {
		0,		// level 1
		50000,
		70000,
		90000,
		100000,  // level 5, ramp up after
		300000,
		500000,
		700000,
		900000,
		1000000, // level 10, ramp up after
		1200000,
		1400000,
		1600000,
		1800000,
		2000000, // level 15
//		0,		// level 1
//		20000,
//		30000,
//		40000,
//		50000,  // level 5, ramp up after
//		100000,
//		200000,
//		300000,
//		400000,
//		500000, // level 10, ramp up after
//		750000,
//		1000000,
//		1250000,
//		1500000,
//		2000000, // level 15
		
		
//		1000000,
//		2000000,
//		3000000,
//		4000000,
//		5000000, // level 15
	};
	
	public static long [] TOTAL_XP_PER_LEVEL = new long [XP_PER_LEVEL.length];
	
	static {
		long total = 0;
		for (int i = 0; i < XP_PER_LEVEL.length; i++) {
			total += XP_PER_LEVEL[i];
			TOTAL_XP_PER_LEVEL[i] = total;
		}
	}
	                                               
	                                              
	
	
	
	public int getPointsAtLevel(int level) {
		return (int) Global.getSettings().getFloat("skillPointsPerLevel");
	}

	public int getMaxLevel() {
		return (int) Global.getSettings().getFloat("playerMaxLevel");
	}
	
	public int getStoryPointsPerLevel() {
		return (int) Global.getSettings().getFloat("storyPointsPerLevel");
	}
	
//	public long getXPForNextLevel(int level) {
//		if (level < XP_PER_LEVEL.length) {
//			return XP_PER_LEVEL[level];
//		}
//		
//		return (long) (XP_PER_LEVEL[LEVEL_FOR_BASE_XP_FOR_MAXED_STORY_POINT_GAIN - 1] * XP_REQUIRED_FOR_STORY_POINT_GAIN_AT_MAX_LEVEL_MULT);
//	}

	
	public long getXPForLevel(int level) {
		if (level <= 1) return 0;
		
		if (level - 1 < TOTAL_XP_PER_LEVEL.length) {
			return TOTAL_XP_PER_LEVEL[level - 1];
		}
		
		int max = getMaxLevel();
		int maxSpecified = TOTAL_XP_PER_LEVEL.length;
		long curr = TOTAL_XP_PER_LEVEL[maxSpecified - 1];
		long last = XP_PER_LEVEL[maxSpecified - 1];
		for (int i = maxSpecified; i < level && i < max; i++) {
			last *= EXPONENT_BEYOND_MAX_SPECIFIED_LEVEL;
			curr += last;
		}
		
		if (level >= max + 1) {
			//last *= XP_REQUIRED_FOR_STORY_POINT_GAIN_AT_MAX_LEVEL_MULT;
			last = (long) (XP_PER_LEVEL[LEVEL_FOR_BASE_XP_FOR_MAXED_STORY_POINT_GAIN - 1] * 
						   XP_REQUIRED_FOR_STORY_POINT_GAIN_AT_MAX_LEVEL_MULT);
			curr += last;
		}
		
		return curr;
	}

	
	public static void main(String[] args) {
		LevelupPluginImpl plugin = new LevelupPluginImpl();
		
//		System.out.println(plugin.getXPForLevel(16) - plugin.getXPForLevel(15));
		
		for (int i = 1; i < 16; i++) {
			//System.out.println(i + ": " + (plugin.getXPForLevel(i) - plugin.getXPForLevel(i - 1)));
			System.out.println(i + ": " + (plugin.getXPForLevel(i)));
		}
	}


}
