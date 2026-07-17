package com.fabricplots;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Self-healing streets. Roads, curbs, stairs and lamp posts are fully deterministic from world
 * coordinates, so anything else found there is foreign — tree canopies grown over the road, snow
 * layers from a plot biome's 4-block bleed, dispensed fluids, piston debris, fallen concrete powder.
 *
 * Every sweep rebuilds the canonical surface zone of street columns (via the painter's own
 * paintBase + decorate, captured per column) and writes ONLY the differences, so a clean street
 * costs reads and zero block updates. Sweeps run near players, a budget of chunks per pass.
 * Owned plot columns are never touched — plots belong to their owners. Also despawns street litter
 * (dropped items, boats/minecarts, armor stands) that has sat on a road for a few minutes.
 */
public final class StreetSweeper {
    /** Surface zone checked above ground (lamps are 6 tall; tree canopies hang low). */
    private static final int AIRSPACE = 10;
    /** Street chunks swept per pass. */
    private static final int CHUNK_BUDGET = 6;
    /** Ticks an item/vehicle/armor stand may sit on a street before it is cleaned up (5 min). */
    private static final int LITTER_GRACE_TICKS = 5 * 60 * 20;

    private static int cursor = 0; // rotates through the candidate chunk list across passes

    private StreetSweeper() {}

    /** Called every few seconds with the plots level. */
    public static void sweep(ServerLevel plots) {
        if (plots == null) return;
        List<long[]> chunks = candidateChunks(plots);
        if (chunks.isEmpty()) return;
        for (int i = 0; i < Math.min(CHUNK_BUDGET, chunks.size()); i++) {
            long[] c = chunks.get(Math.floorMod(cursor++, chunks.size()));
            sweepChunk(plots, (int) c[0], (int) c[1]);
        }
    }

    /** Chunk coords (deduped) in a 5x5 square around each player in the plot world. */
    private static List<long[]> candidateChunks(ServerLevel plots) {
        Set<Long> seen = new LinkedHashSet<>();
        for (ServerPlayer p : plots.players()) {
            int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    seen.add((((long) (pcx + dx)) << 32) | ((pcz + dz) & 0xFFFFFFFFL));
        }
        List<long[]> out = new ArrayList<>(seen.size());
        for (long key : seen) out.add(new long[] { (int) (key >> 32), (int) key });
        return out;
    }

    private static void sweepChunk(ServerLevel plots, int cx, int cz) {
        LevelChunk chunk = plots.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return; // not loaded — nothing to heal, nothing to load
        int baseX = cx << 4, baseZ = cz << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx, z = baseZ + dz;
                // Sweep ONLY the street band: never plot areas (claimed or unclaimed — their canonical
                // model is empty and would be "healed" to air) and never a merge's dissolved interior.
                if (PlotManager.isInsidePlot(x, z) || PlotManager.owningPlot(x, z) != null) continue;
                // Leave the spawn plaza alone — admins decorate the streets there.
                int r = PlotsConfig.sweeperSpawnRadius;
                if (Math.abs(x - PlotsConfig.spawnX) <= r && Math.abs(z - PlotsConfig.spawnZ) <= r) continue;
                healColumn(plots, x, z);
            }
        }
    }

    /** Rebuild one street column's canonical states and fix only what differs. */
    private static void healColumn(ServerLevel plots, int x, int z) {
        Map<Integer, BlockState> expected = canonicalColumn(x, z);
        int yFrom = PlotConfig.ROAD_Y, yTo = PlotConfig.GROUND_Y + AIRSPACE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = yFrom; y <= yTo; y++) {
            pos.set(x, y, z);
            if (PortalManager.isProtected(pos)) continue; // exit portals live on streets — keep them
            BlockState want = expected.get(y);
            // Below the surface, absence means "not painted here" (e.g. the dirt under an unclaimed
            // sidewalk) — never touch it. At/above the surface, absence means the airspace is AIR.
            if (want == null) {
                if (y < PlotConfig.GROUND_Y) continue;
                want = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
            if (!plots.getBlockState(pos).equals(want)) {
                plots.setBlock(pos, want, Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * What this street column is SUPPOSED to look like: run the painter's own generators with a
     * collector that records only this column's writes (lamps included; trees/benches are disabled
     * flags, and even when enabled they only ever write their own column or are re-collected here).
     */
    private static Map<Integer, BlockState> canonicalColumn(int x, int z) {
        Map<Integer, BlockState> out = new HashMap<>();
        PlotWorldPainter.paintColumnCanonical(x, z, (px, py, pz, s) -> {
            if (px == x && pz == z) out.put(py, s);
        });
        return out;
    }

    /** Despawn litter (items, boats/minecarts, armor stands) sitting on streets past the grace period. */
    public static int cleanLitter(ServerLevel plots) {
        if (plots == null) return 0;
        List<Entity> doomed = new ArrayList<>();
        for (Entity e : plots.getAllEntities()) {
            boolean litter = e instanceof ItemEntity || e instanceof VehicleEntity
                    || (e instanceof ArmorStand stand && !stand.hasCustomName());
            if (!litter) continue;
            if (e.tickCount < LITTER_GRACE_TICKS) continue;
            if (PlotManager.owningPlot(e.getBlockX(), e.getBlockZ()) != null) continue; // on a plot = fine
            doomed.add(e);
        }
        for (Entity e : doomed) e.discard();
        return doomed.size();
    }
}
