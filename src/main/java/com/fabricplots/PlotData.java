package com.fabricplots;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Ownership + trust + the grid cells this plot spans (one cell normally, several when merged). */
public final class PlotData {
    public UUID owner;
    public String ownerName = "";   // cached so the welcome message works for offline owners
    public String name = "";        // optional plot name set by the owner
    public BlockPos home = null;    // optional /plot home target inside the plot (else the cell centre)
    public String floorBlockId = "";// surface block id (e.g. "minecraft:sandstone"); blank = default grass
    public boolean pvp = false;     // is player-vs-player combat allowed on this plot?
    public String greeting = "";    // optional custom welcome (action bar) shown to visitors; blank = default
    public String ambience = "";    // client-only sky illusion "time:weather" (e.g. "sunset:rain"); blank = off
    public String biomeId = "";     // plot biome (e.g. "minecraft:cherry_grove"); blank = the default plot biome
    public boolean spawnHostile = true;  // allow the biome's hostile (MONSTER) mobs to spawn on this plot
    public boolean spawnPassive = true;  // allow the biome's passive mobs to spawn on this plot
    public String sidewalkPattern = ""; // designer: 4 rows x 9 cols (3 sidewalk + curb), rows '|', cells ',', "" = air
    public String wallPattern = "";     // designer: 3 rows x 9 cols (bottom->top) on the plot's edge ring
    public transient volatile String[][] sidewalkGridCache; // parsed-pattern caches (see PlotStyle.grid)
    public transient volatile String[][] wallGridCache;
    public long paidAmount = 0;     // economy: total actually paid to claim this plot (for refunds; 0 = free)
    public final Set<UUID> trusted = new LinkedHashSet<>();
    public final Set<UUID> denied = new LinkedHashSet<>();   // banned from entering this plot
    public final Set<UUID> likes = new LinkedHashSet<>();    // players who liked this plot
    public final Set<PlotPos> cells = new LinkedHashSet<>();

    public PlotData(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName == null ? "" : ownerName;
    }

    public boolean canBuild(UUID player) {
        return player.equals(owner) || trusted.contains(player);
    }

    public boolean isMerged() {
        return cells.size() > 1;
    }
}
