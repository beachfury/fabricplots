package com.fabricplots;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Paints the plot grid as a raised promenade above y64: plots/sidewalks sit at
 * GROUND_Y, the road is carved one block lower (ROAD_Y), joined by a stair curb.
 * Cross-section per side: 3 chiseled-tuff sidewalk · 1 stone stair · 9 black-concrete
 * road (smooth-quartz dashes) · 1 stair · 3 sidewalk. Furniture lines the sidewalk
 * middle row. Deterministic from world coords (gen == repaint).
 */
public final class PlotWorldPainter {
    private static final BlockState ROAD = Blocks.BLACK_CONCRETE.defaultBlockState();
    private static final BlockState DASH = Blocks.SMOOTH_QUARTZ.defaultBlockState();
    private static final BlockState SIDEWALK = Blocks.CHISELED_TUFF_BRICKS.defaultBlockState();
    private static final BlockState CURB = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState FILL = Blocks.DIRT.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();

    // Lamp post (bottom -> top).
    private static final BlockState LAMP_BASE = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
    private static final BlockState LAMP_POST = Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState();
    private static final BlockState LAMP_ANVIL = Blocks.ANVIL.defaultBlockState();
    private static final BlockState LAMP_HOPPER = Blocks.HOPPER.defaultBlockState();
    private static final BlockState LAMP_BEACON = Blocks.BEACON.defaultBlockState();

    // Tree.
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState FENCE = Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState TREE_TRAPDOOR = Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(BlockStateProperties.OPEN, true).setValue(BlockStateProperties.HALF, Half.BOTTOM);

    // Bench material pools — each bench picks one shelf wood + one trapdoor wood (kept internally consistent).
    private static final BlockState[] SHELVES = {
            Blocks.OAK_SHELF.defaultBlockState(), Blocks.SPRUCE_SHELF.defaultBlockState(),
            Blocks.BIRCH_SHELF.defaultBlockState(), Blocks.JUNGLE_SHELF.defaultBlockState(),
            Blocks.ACACIA_SHELF.defaultBlockState(), Blocks.DARK_OAK_SHELF.defaultBlockState(),
            Blocks.MANGROVE_SHELF.defaultBlockState(), Blocks.CHERRY_SHELF.defaultBlockState(),
            Blocks.PALE_OAK_SHELF.defaultBlockState(), Blocks.BAMBOO_SHELF.defaultBlockState() };
    private static final BlockState[] TRAPDOORS = {
            Blocks.OAK_TRAPDOOR.defaultBlockState(), Blocks.SPRUCE_TRAPDOOR.defaultBlockState(),
            Blocks.BIRCH_TRAPDOOR.defaultBlockState(), Blocks.JUNGLE_TRAPDOOR.defaultBlockState(),
            Blocks.ACACIA_TRAPDOOR.defaultBlockState(), Blocks.DARK_OAK_TRAPDOOR.defaultBlockState(),
            Blocks.MANGROVE_TRAPDOOR.defaultBlockState(), Blocks.CHERRY_TRAPDOOR.defaultBlockState(),
            Blocks.PALE_OAK_TRAPDOOR.defaultBlockState(), Blocks.BAMBOO_TRAPDOOR.defaultBlockState() };


    @FunctionalInterface
    private interface Setter { void set(int x, int y, int z, BlockState state); }

    private static boolean loggedError = false;

    private PlotWorldPainter() {}

    /** DIAGNOSTIC flags to bisect the gen hang. */
    private static final boolean DISABLE_PAINTER = false;
    // Furniture is never placed during world-gen (block-entities hang there). It goes down on
    // CHUNK_LOAD (post-gen) and via /plot repaint — both use level.setBlock on a live chunk.
    private static final boolean GEN_FURNITURE = false;
    private static final boolean REPAINT_FURNITURE = true;
    // Per-type toggles for incremental testing.
    private static final boolean LAMPS = true;
    private static final boolean TREES = false;
    private static final boolean BENCHES = false;

    public static void onGenerate(ServerLevel level, LevelChunk chunk) {
        if (level.dimension() != FabricPlots.PLOTS_DIM) return;
        if (DISABLE_PAINTER) return;
        final int baseX = chunk.getPos().getMinBlockX();
        final int baseZ = chunk.getPos().getMinBlockZ();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final Setter setter = (x, y, z, s) -> { pos.set(x, y, z); chunk.setBlockState(pos, s); };
        try {
            // No tall airspace clear during generation (the flat base is already air above) — keep it light.
            for (int dx = 0; dx < 16; dx++)
                for (int dz = 0; dz < 16; dz++) paintBase(baseX + dx, baseZ + dz, setter, false);
            if (GEN_FURNITURE)
                for (int dx = 0; dx < 16; dx++)
                    for (int dz = 0; dz < 16; dz++) decorate(baseX + dx, baseZ + dz, setter);
        } catch (Throwable t) {
            if (!loggedError) {
                loggedError = true;
                System.out.println("[FabricPlots] painter error during CHUNK_GENERATE: " + t);
                t.printStackTrace();
            }
        }
    }

    public static int repaint(ServerLevel level, int centerX, int centerZ, int radius) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final Setter setter = (x, y, z, s) -> {
            pos.set(x, y, z);
            if (PortalManager.isProtected(pos)) return; // never paint over a live exit portal
            level.setBlock(pos, s, Block.UPDATE_CLIENTS);
        };
        int columns = 0;
        for (int x = centerX - radius; x <= centerX + radius; x++)
            for (int z = centerZ - radius; z <= centerZ + radius; z++)
                if (paintBase(x, z, setter, true)) columns++;
        if (REPAINT_FURNITURE)
            for (int x = centerX - radius; x <= centerX + radius; x++)
                for (int z = centerZ - radius; z <= centerZ + radius; z++) decorate(x, z, setter);
        return columns;
    }

    /** Repaint a freshly-merged region: wipe its cells to grass, dissolve interior roads, repaint the perimeter. */
    public static void combineRepaint(ServerLevel level, java.util.Collection<PlotPos> cells) {
        if (cells.isEmpty()) return;
        int pxMin = Integer.MAX_VALUE, pxMax = Integer.MIN_VALUE, pzMin = Integer.MAX_VALUE, pzMax = Integer.MIN_VALUE;
        for (PlotPos c : cells) {
            pxMin = Math.min(pxMin, c.px()); pxMax = Math.max(pxMax, c.px());
            pzMin = Math.min(pzMin, c.pz()); pzMax = Math.max(pzMax, c.pz());
        }
        final int xStart = pxMin * PlotConfig.STEP - PlotConfig.ROAD_WIDTH;
        final int xEnd = (pxMax + 1) * PlotConfig.STEP + PlotConfig.ROAD_WIDTH;
        final int zStart = pzMin * PlotConfig.STEP - PlotConfig.ROAD_WIDTH;
        final int zEnd = (pzMax + 1) * PlotConfig.STEP + PlotConfig.ROAD_WIDTH;
        final PlotData merge = PlotManager.get(cells.iterator().next());
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final Setter setter = (x, y, z, s) -> {
            pos.set(x, y, z);
            if (PortalManager.isProtected(pos)) return;
            level.setBlock(pos, s, Block.UPDATE_CLIENTS);
        };

        for (int x = xStart; x <= xEnd; x++) {
            for (int z = zStart; z <= zEnd; z++) {
                boolean core = Math.floorMod(x, PlotConfig.STEP) < PlotConfig.PLOT_SIZE
                        && Math.floorMod(z, PlotConfig.STEP) < PlotConfig.PLOT_SIZE;
                if (core && PlotManager.owningPlot(x, z) == merge) {
                    // Merged cell core → reset the WHOLE column top-to-bottom (clears towers AND tunnels).
                    resetColumn(level, x, z, GRASS);
                } else {
                    // Non-core: paintBase fills merged interior with grass, keeps perimeter as tuff/stair/road,
                    // and leaves any neighbouring (non-merged) plot cores untouched.
                    paintBase(x, z, setter, true);
                }
            }
        }
        for (int x = xStart; x <= xEnd; x++)
            for (int z = zStart; z <= zEnd; z++) decorate(x, z, setter);
    }

    /** Wipe every column of a plot (all its cells) top-to-bottom back to the standard ground. */
    public static int clearPlot(ServerLevel level, PlotData data) {
        int columns = 0;
        for (PlotPos cell : data.cells) {
            final int baseX = cell.px() * PlotConfig.STEP;
            final int baseZ = cell.pz() * PlotConfig.STEP;
            for (int dx = 0; dx < PlotConfig.PLOT_SIZE; dx++)
                for (int dz = 0; dz < PlotConfig.PLOT_SIZE; dz++) {
                    resetColumn(level, baseX + dx, baseZ + dz, GRASS);
                    columns++;
                }
        }
        return columns;
    }

    /**
     * Reset one column to the canonical stack: bedrock at y-1, solid dirt y0..ROAD_Y, the surface
     * block at GROUND_Y, and air all the way to the world top. Only blocks that differ are written,
     * so an untouched column costs reads but no block updates.
     */
    private static void resetColumn(ServerLevel level, int x, int z, BlockState surface) {
        setIfDiff(level, x, PlotConfig.BEDROCK_Y, z, BEDROCK);                       // y-1
        for (int y = PlotConfig.DIRT_BOTTOM_Y; y < PlotConfig.GROUND_Y; y++)         // dirt y0..65
            setIfDiff(level, x, y, z, FILL);
        setIfDiff(level, x, PlotConfig.GROUND_Y, z, surface);                        // y66
        for (int y = PlotConfig.GROUND_Y + 1; y <= PlotConfig.WORLD_TOP_Y; y++)      // air to the top
            setIfDiff(level, x, y, z, AIR);
    }

    private static void setIfDiff(ServerLevel level, int x, int y, int z, BlockState s) {
        BlockPos pos = new BlockPos(x, y, z);
        if (PortalManager.isProtected(pos)) return; // leave exit-portal blocks intact
        if (!level.getBlockState(pos).equals(s)) level.setBlock(pos, s, Block.UPDATE_CLIENTS);
    }

    /** Repaint the bounding box of some cells (re-cuts roads between them) without touching plot interiors. */
    public static void repaintCells(ServerLevel level, java.util.Collection<PlotPos> cells) {
        if (cells.isEmpty()) return;
        int pxMin = Integer.MAX_VALUE, pxMax = Integer.MIN_VALUE, pzMin = Integer.MAX_VALUE, pzMax = Integer.MIN_VALUE;
        for (PlotPos c : cells) {
            pxMin = Math.min(pxMin, c.px()); pxMax = Math.max(pxMax, c.px());
            pzMin = Math.min(pzMin, c.pz()); pzMax = Math.max(pzMax, c.pz());
        }
        final int xStart = pxMin * PlotConfig.STEP - PlotConfig.ROAD_WIDTH;
        final int xEnd = (pxMax + 1) * PlotConfig.STEP + PlotConfig.ROAD_WIDTH;
        final int zStart = pzMin * PlotConfig.STEP - PlotConfig.ROAD_WIDTH;
        final int zEnd = (pzMax + 1) * PlotConfig.STEP + PlotConfig.ROAD_WIDTH;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final Setter setter = (x, y, z, s) -> {
            pos.set(x, y, z);
            if (PortalManager.isProtected(pos)) return;
            level.setBlock(pos, s, Block.UPDATE_CLIENTS);
        };
        for (int x = xStart; x <= xEnd; x++) for (int z = zStart; z <= zEnd; z++) paintBase(x, z, setter, true);
        for (int x = xStart; x <= xEnd; x++) for (int z = zStart; z <= zEnd; z++) decorate(x, z, setter);
    }

    // ---- post-generation furniture (block-entities are safe here, unlike CHUNK_GENERATE) ----

    /** Chunks already decorated, so furniture is placed once and never re-stamped over player edits. */
    private static final Set<Long> DECORATED = ConcurrentHashMap.newKeySet();
    /** Plots chunks that loaded and are awaiting decoration on an upcoming tick. */
    private static final Queue<Long> PENDING = new ConcurrentLinkedQueue<>();
    private static Path decoratedFile;
    private static volatile boolean decoratedDirty = false;

    public static void loadDecorated(MinecraftServer server) {
        decoratedFile = server.getWorldPath(LevelResource.ROOT).resolve("fabricplots-decorated.txt");
        DECORATED.clear();
        decoratedDirty = false;
        if (!Files.exists(decoratedFile)) return;
        try {
            for (String line : Files.readAllLines(decoratedFile))
                if (!line.isBlank()) DECORATED.add(Long.parseLong(line.trim()));
        } catch (Exception e) {
            System.err.println("[FabricPlots] failed to load decorated chunks: " + e);
        }
    }

    public static void saveDecorated() {
        if (decoratedFile == null || !decoratedDirty) return;
        try {
            List<String> lines = new ArrayList<>(DECORATED.size());
            for (Long k : DECORATED) lines.add(Long.toString(k));
            Files.write(decoratedFile, lines);
            decoratedDirty = false;
        } catch (Exception e) {
            System.err.println("[FabricPlots] failed to save decorated chunks: " + e);
        }
    }

    /** A plots chunk loaded — queue it; the actual furniture goes down a tick later (see processPending). */
    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        if (level.dimension() != FabricPlots.PLOTS_DIM) return;
        final int cx = chunk.getPos().getMinBlockX() >> 4;
        final int cz = chunk.getPos().getMinBlockZ() >> 4;
        final long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
        if (!DECORATED.contains(key)) PENDING.add(key);
    }

    /** Called each server tick: decorate a few queued chunks via level.setBlock (chunk is now live + sent). */
    public static void processPending(ServerLevel plots, int budget) {
        if (plots == null) return;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final Setter setter = (x, y, z, s) -> { pos.set(x, y, z); plots.setBlock(pos, s, Block.UPDATE_CLIENTS); };
        Long key;
        int done = 0;
        while (done < budget && (key = PENDING.poll()) != null) {
            if (!DECORATED.add(key)) continue; // already decorated
            decoratedDirty = true;
            final int baseX = ((int) (key >> 32)) << 4;
            final int baseZ = ((int) (long) key) << 4;
            try {
                for (int dx = 0; dx < 16; dx++)
                    for (int dz = 0; dz < 16; dz++) decorate(baseX + dx, baseZ + dz, setter);
            } catch (Throwable t) {
                if (!loggedError) {
                    loggedError = true;
                    System.out.println("[FabricPlots] decorate error: " + t);
                    t.printStackTrace();
                }
            }
            done++;
        }
    }

    /** Base surfaces + levels. Returns true if this is a road-band column. */
    private static boolean paintBase(int x, int z, Setter setter, boolean clearTall) {
        final int lx = Math.floorMod(x, PlotConfig.STEP);
        final int lz = Math.floorMod(z, PlotConfig.STEP);
        final boolean inPlotX = lx < PlotConfig.PLOT_SIZE;
        final boolean inPlotZ = lz < PlotConfig.PLOT_SIZE;
        if (inPlotX && inPlotZ) return false; // plot interior untouched (raised grass at GROUND_Y)

        // Only repaint clears the tall airspace (to wipe old furniture); generation skips it (already air).
        if (clearTall) {
            for (int yy = PlotConfig.GROUND_Y; yy <= PlotConfig.GROUND_Y + 10; yy++) setter.set(x, yy, z, AIR);
        }

        // Buildable (a claimed cell's sidewalk, or a merge's dissolved interior): tuff sidewalk within
        // SIDEWALK_DEPTH of the edge, grass deeper in. Perimeter stairs/road fall through to geometry below.
        if (PlotManager.owningPlot(x, z) != null) {
            setter.set(x, PlotConfig.ROAD_Y, z, FILL);
            setter.set(x, PlotConfig.GROUND_Y, z, PlotManager.nearMergeEdge(x, z) ? SIDEWALK : GRASS);
            return true;
        }

        final int roadX = inPlotX ? -1 : Math.min(lx - PlotConfig.PLOT_SIZE, (PlotConfig.STEP - 1) - lx);
        final int roadZ = inPlotZ ? -1 : Math.min(lz - PlotConfig.PLOT_SIZE, (PlotConfig.STEP - 1) - lz);

        // Combine the two axes so sidewalks wrap plot corners: a corner column uses the DEEPER
        // axis (max), so anything within 2 blocks of a plot stays raised sidewalk.
        final boolean corner = roadX >= 0 && roadZ >= 0;
        final int rd = corner ? Math.max(roadX, roadZ) : (roadX >= 0 ? roadX : roadZ);

        // A column is a CURB if it's the geometric plot edge (rd==3) OR it borders a claimed/merged
        // plot. The second case is what closes the gaps around merges: when the interior road
        // dissolves to grass, its mouth at the perimeter has rd 4..8 (no geometric curb), so without
        // the boundary check the curb line would break there.
        final boolean nearOwned = adjacentToOwned(x, z);
        if (rd == 3 || nearOwned) {
            if (rd == 3 && !corner) {
                // Clean straight run → keep the stair slope (these tile perfectly).
                setter.set(x, PlotConfig.GROUND_Y, z, Blocks.STONE_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, stairFacing(lx, lz, roadX, roadZ))
                        .setValue(BlockStateProperties.HALF, Half.BOTTOM));
            } else {
                // Corners (a stair can't turn 90°) and merge boundaries → full curb block, always connects.
                setter.set(x, PlotConfig.GROUND_Y, z, CURB);
            }
            setter.set(x, PlotConfig.ROAD_Y, z, CURB);
        } else if (rd <= 2) {                           // sidewalk on unclaimed-plot streets
            setter.set(x, PlotConfig.GROUND_Y, z, SIDEWALK);
        } else {                                        // central road (rd 4..8)
            final int s = inPlotZ ? z : x;
            final boolean dash = !corner && (rd == (PlotConfig.ROAD_WIDTH - 1) / 2) && Math.floorMod(s, 6) < 2;
            setter.set(x, PlotConfig.GROUND_Y, z, AIR);
            setter.set(x, PlotConfig.ROAD_Y, z, dash ? DASH : ROAD);
        }
        return true;
    }

    /** True if any cardinal neighbour of this (unowned) column belongs to a plot — i.e. this is a curb boundary. */
    private static boolean adjacentToOwned(int x, int z) {
        return PlotManager.owningPlot(x + 1, z) != null || PlotManager.owningPlot(x - 1, z) != null
                || PlotManager.owningPlot(x, z + 1) != null || PlotManager.owningPlot(x, z - 1) != null;
    }

    private static void decorate(int x, int z, Setter setter) {
        final int lx = Math.floorMod(x, PlotConfig.STEP);
        final int lz = Math.floorMod(z, PlotConfig.STEP);
        final boolean inPlotX = lx < PlotConfig.PLOT_SIZE;
        final boolean inPlotZ = lz < PlotConfig.PLOT_SIZE;
        if (inPlotX && inPlotZ) return;
        // No furniture deep inside a plot (only the sidewalk ring / perimeter gets lamps).
        if (PlotManager.owningPlot(x, z) != null && !PlotManager.nearMergeEdge(x, z)) return;
        final int roadX = inPlotX ? -1 : Math.min(lx - PlotConfig.PLOT_SIZE, (PlotConfig.STEP - 1) - lx);
        final int roadZ = inPlotZ ? -1 : Math.min(lz - PlotConfig.PLOT_SIZE, (PlotConfig.STEP - 1) - lz);
        // One lamp at the centre of each sidewalk corner.
        if (roadX == 1 && roadZ == 1) {
            if (LAMPS) placeLamp(x, z, setter);
            return;
        }
        if (roadX >= 0 && roadZ >= 0) return; // rest of the corner: nothing

        // Straight runs (single street axis) — trees & benches on the middle sidewalk row.
        final boolean vertical = inPlotZ;
        final int rd = vertical ? roadX : roadZ;
        if (rd != 1) return;
        final int s = vertical ? z : x;
        final int m = Math.floorMod(s, 8);
        if (m == 4 && TREES && rand(x, z, 2) < 600 && fits3x3(x, z)) {
            placeTree(x, z, setter);
        } else if ((m == 2 || m == 6) && BENCHES && rand(x, z, 1) < 450 && fits3x3(x, z)) {
            placeBench(x, z, vertical, lx, lz, setter);
        }
    }

    private static void placeLamp(int x, int z, Setter setter) {
        final int y = PlotConfig.FLOOR_Y;
        setter.set(x, y, z, LAMP_BASE);
        setter.set(x, y + 1, z, LAMP_POST);
        setter.set(x, y + 2, z, LAMP_POST);
        setter.set(x, y + 3, z, LAMP_ANVIL);
        setter.set(x, y + 4, z, LAMP_HOPPER);
        setter.set(x, y + 5, z, LAMP_BEACON);
    }

    private static void placeTree(int x, int z, Setter setter) {
        final int y = PlotConfig.FLOOR_Y;
        setter.set(x, y, z, DIRT);
        setter.set(x, y, z - 1, TREE_TRAPDOOR.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        setter.set(x, y, z + 1, TREE_TRAPDOOR.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        setter.set(x + 1, y, z, TREE_TRAPDOOR.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        setter.set(x - 1, y, z, TREE_TRAPDOOR.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        for (int i = 1; i <= 6; i++) setter.set(x, y + i, z, FENCE);
        setter.set(x + 1, y + 3, z, LEAVES);
        setter.set(x - 1, y + 3, z, LEAVES);
        setter.set(x, y + 3, z + 1, LEAVES);
        setter.set(x, y + 3, z - 1, LEAVES);
        for (int yy = 4; yy <= 6; yy++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dz != 0) setter.set(x + dx, y + yy, z + dz, LEAVES);
        setter.set(x, y + 7, z, LEAVES);
        setter.set(x + 1, y + 7, z, LEAVES);
        setter.set(x - 1, y + 7, z, LEAVES);
        setter.set(x, y + 7, z + 1, LEAVES);
        setter.set(x, y + 7, z - 1, LEAVES);
    }

    private static void placeBench(int x, int z, boolean vertical, int lx, int lz, Setter setter) {
        final int y = PlotConfig.FLOOR_Y;
        final BlockState shelf = SHELVES[Math.floorMod(rand(x, z, 3), SHELVES.length)];
        final BlockState td = TRAPDOORS[Math.floorMod(rand(x, z, 4), TRAPDOORS.length)];
        final Direction road = benchRoad(vertical, lx, lz);         // bench faces the road
        final Direction plot = road.getOpposite();

        final int adx = vertical ? 0 : 1;   // arm offset along the street
        final int adz = vertical ? 1 : 0;
        setter.set(x - adx, y, z - adz, shelf.setValue(ShelfBlock.FACING, road));
        setter.set(x + adx, y, z + adz, shelf.setValue(ShelfBlock.FACING, road));

        // Seat (flat trapdoor) at the centre, back (upright trapdoor) one block toward the plot.
        setter.set(x, y, z, td.setValue(BlockStateProperties.OPEN, false).setValue(BlockStateProperties.HALF, Half.TOP));
        setter.set(x + plot.getStepX(), y, z + plot.getStepZ(), td
                .setValue(BlockStateProperties.OPEN, true)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, road));
    }

    /** Direction toward the road centre from a sidewalk/stair column. */
    private static Direction benchRoad(boolean vertical, int lx, int lz) {
        if (vertical) return (lx <= PlotConfig.PLOT_SIZE + PlotConfig.SIDEWALK_DEPTH) ? Direction.EAST : Direction.WEST;
        return (lz <= PlotConfig.PLOT_SIZE + PlotConfig.SIDEWALK_DEPTH) ? Direction.SOUTH : Direction.NORTH;
    }

    private static Direction stairFacing(int lx, int lz, int roadX, int roadZ) {
        // Stair sits at road-distance 3; step UP toward the plot. Use the deeper axis at corners.
        final boolean xAxis = roadX >= 0 && (roadZ < 0 || roadX >= roadZ);
        final int mid = (PlotConfig.PLOT_SIZE + PlotConfig.STEP - 1) / 2; // 40
        if (xAxis) return (lx < mid) ? Direction.WEST : Direction.EAST;
        return (lz < mid) ? Direction.NORTH : Direction.SOUTH;
    }

    private static boolean fits3x3(int x, int z) {
        final int cx = Math.floorMod(x, 16);
        final int cz = Math.floorMod(z, 16);
        return cx >= 1 && cx <= 14 && cz >= 1 && cz <= 14;
    }

    private static int rand(int x, int z, int salt) {
        int h = x * 374761393 + z * 668265263 + salt * 0x9E3779B1;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= (h >>> 16);
        return Math.floorMod(h, 1000);
    }
}
