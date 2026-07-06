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
