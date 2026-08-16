package com.fabricplots.edit;

import com.draftsmith.api.EditAccess;
import com.fabricplots.FabricPlots;
import com.fabricplots.core.PlotConfig;
import com.fabricplots.core.PlotData;
import com.fabricplots.core.PlotManager;
import com.fabricplots.protect.PlotProtection;
import com.fabricplots.protect.PortalManager;
import com.fabricplots.world.PlotWorldPainter;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * FabricPlots' access provider for the bundled DraftSmith editor — the plot jail. The editor
 * only runs in the plots dimension, every block write must land on a plot the player can build
 * on (admins in /plot admin mode are unclamped but still never touch portal blocks), and the
 * Erase brush restores plot floors through {@link PlotWorldPainter#surfaceFor}. Installed by
 * {@link FabricPlots#onInitialize} — behavior is identical to the built-in editor of 0.4.0.
 */
public final class PlotEditAccess implements EditAccess {

    /** The editor runs in the plots dimension only. */
    @Override
    public boolean isActiveDimension(Level level) {
        return level.dimension() == FabricPlots.PLOTS_DIM;
    }

    /** Unclamped editing = an op who has ENABLED build-admin mode (/plot admin). */
    @Override
    public boolean isAdmin(ServerPlayer p) {
        return PlotProtection.isBuildAdmin(p);
    }

    /** Y in range, not a portal, and — unless admin — a plot the player can build on. */
    @Override
    public boolean canEdit(ServerPlayer p, boolean admin, int x, int y, int z) {
        if (y < PlotConfig.DIRT_BOTTOM_Y || y > PlotConfig.WORLD_TOP_Y) return false;
        BlockPos pos = new BlockPos(x, y, z);
        if (PortalManager.isProtected(pos)) return false;
        // DraftSmith 1.0 history stores block states only, not container/sign NBT. Preserve any
        // existing block entity; the companion mixin also rejects creating a new one via the editor.
        if (p.level().getBlockEntity(pos) != null) return false;
        PlotData d = PlotManager.owningPlot(x, z);
        if (PlotWorldPainter.isBusy(d)) return false;
        if (admin) return true;
        return d != null && d.canBuild(p.getUUID());
    }

    @Override
    public int minY(ServerLevel level) { return PlotConfig.DIRT_BOTTOM_Y; }

    @Override
    public int maxY(ServerLevel level) { return PlotConfig.WORLD_TOP_Y; }

    /**
     * "Erase to ground" = the plot-world floor: clear down to GROUND_Y and repaint the plot's
     * own floor block (custom floors respected). Off-plot columns (streets — admin edits) still
     * clear to GROUND_Y but are never repainted.
     */
    @Override
    public Ground ground(ServerLevel level, int x, int z) {
        PlotData plot = PlotManager.owningPlot(x, z);
        return new Ground(PlotConfig.GROUND_Y, plot != null ? PlotWorldPainter.surfaceFor(plot) : null);
    }

    @Override
    public String messagePrefix() { return "[Plots] "; }

    @Override
    public String notHereMessage() { return "Run this in the plot world."; }

    @Override
    public String editableAreaName() { return "your plot"; }
}
