package com.fabricplots;

/**
 * Tunables for the plot grid. A "cell" is one plot plus the road on its
 * positive X/Z edges, so STEP = PLOT_SIZE + ROAD_WIDTH.
 */
public final class PlotConfig {
    /** Build area of a single plot, in blocks. */
    public static final int PLOT_SIZE = 32;
    /** Road gap: 3 sidewalk + 1 stair + 9 road + 1 stair + 3 sidewalk = 17. */
    public static final int ROAD_WIDTH = 17;
    /** Sidewalk depth on each side (road distance 0..2). Road distance 3 = transition stair. */
    public static final int SIDEWALK_DEPTH = 3;
    /** Buildable area per plot: grass core + the sidewalk ring, i.e. stair-to-stair (38). */
    public static final int BUILDABLE = PLOT_SIZE + 2 * SIDEWALK_DEPTH;
    /** Distance from one plot's origin to the next. */
    public static final int STEP = PLOT_SIZE + ROAD_WIDTH;

    // Levels — raised above y64 so the void-fog horizon band disappears, with the
    // road carved one block below the plots/sidewalks (the "raised plots").
    /** Top solid block of plots and sidewalks (grass / chiseled tuff). */
    public static final int GROUND_Y = 66;
    /** Top solid block of the central road (one below ground). */
    public static final int ROAD_Y = 65;
    /** Bedrock floor at the very bottom (y-1): players (non-ops) can't break at or below this. */
    public static final int BEDROCK_Y = -1;
    /** Solid dirt fills from here up to (ROAD_Y) under the surface. */
    public static final int DIRT_BOTTOM_Y = 0;
    /** Highest buildable block (min_y -16 + height 272 - 1). Used when wiping a column top-to-bottom. */
    public static final int WORLD_TOP_Y = 255;
    /** Standing height on plots/sidewalks; furniture sits here. */
    public static final int FLOOR_Y = 67;

    public static final String DIM_NAMESPACE = "fabricplots";
    public static final String DIM_PATH = "plots";

    /** Unnamed mobs in the plots world are removed after this long (10 minutes). */
    public static final int UNNAMED_MOB_GRACE_TICKS = 10 * 60 * 20; // 12000
    /** How often (ticks) to scan for stale unnamed mobs. */
    public static final int MOB_SCAN_INTERVAL_TICKS = 600; // 30s

    /** /plot world drops players here — on the road plaza near plot (0,0). */
    public static final int SPAWN_X = 40;
    public static final int SPAWN_Z = 40;

    private PlotConfig() {}
}
