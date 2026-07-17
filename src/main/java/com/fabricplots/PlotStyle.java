package com.fabricplots;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Per-plot sidewalk &amp; wall pattern styling — the backend for the chest-GUI designers.
 *
 * A pattern is a small repeating template the painter tiles along the plot's edges:
 *  • sidewalk: {@link #SIDEWALK_ROWS} rows × {@link #COLS} cols, viewed top-down. Row 0 touches the
 *    plot's grass, row {@code SIDEWALK_DEPTH-1} is the outermost sidewalk strip, and the final row is
 *    the curb column on the street side. Columns tile along the edge (floorMod of the coordinate).
 *  • wall: {@link #WALL_ROWS} rows × {@link #COLS} cols, viewed side-on, built on the plot's outermost
 *    grass ring (bottom row = ground level). Row order in the string is bottom→top.
 *
 * A cell is a block id or "" for air. Stairs are stored directionless and rotated at paint time so
 * their low steps always face the street. Serialized form: rows joined with '|', cells with ','.
 */
public final class PlotStyle {
    public static final int COLS = 9;
    public static final int SIDEWALK_ROWS = PlotConfig.SIDEWALK_DEPTH + 1; // 3 sidewalk strips + 1 curb
    public static final int WALL_ROWS = 3;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private PlotStyle() {}

    // ---- pattern (de)serialization ---------------------------------------

    /** Parse a serialized pattern into rows×COLS cell ids ("" = air), or null if blank/invalid. */
    public static String[][] parse(String pattern, int rows) {
        if (pattern == null || pattern.isBlank()) return null;
        String[] rowStrs = pattern.split("\\|", -1);
        if (rowStrs.length != rows) return null;
        String[][] grid = new String[rows][COLS];
        for (int r = 0; r < rows; r++) {
            String[] cells = rowStrs[r].split(",", -1);
            for (int c = 0; c < COLS; c++) grid[r][c] = c < cells.length ? cells[c].trim() : "";
        }
        return grid;
    }

    public static String serialize(String[][] grid) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < grid.length; r++) {
            if (r > 0) sb.append('|');
            for (int c = 0; c < grid[r].length; c++) {
                if (c > 0) sb.append(',');
                sb.append(grid[r][c] == null ? "" : grid[r][c]);
            }
        }
        return sb.toString();
    }

    /** True if every cell of the grid is empty (an all-air design means "no pattern"). */
    public static boolean isEmpty(String[][] grid) {
        for (String[] row : grid) for (String cell : row) if (cell != null && !cell.isBlank()) return false;
        return true;
    }

    /** Memoized parsed grid for a plot (patterns are read per-column by the painter). */
    public static String[][] grid(PlotData d, boolean wall) {
        String[][] cached = wall ? d.wallGridCache : d.sidewalkGridCache;
        if (cached != null) return cached;
        String[][] parsed = parse(wall ? d.wallPattern : d.sidewalkPattern, wall ? WALL_ROWS : SIDEWALK_ROWS);
        if (wall) d.wallGridCache = parsed; else d.sidewalkGridCache = parsed;
        return parsed;
    }

    public static void invalidateCache(PlotData d) {
        d.sidewalkGridCache = null;
        d.wallGridCache = null;
    }

    // ---- world mapping ---------------------------------------------------

    /**
     * Direction from an owned column toward the nearest street (i.e. nearest column NOT owned by
     * {@code d}), or null if none within {@code max}. Deterministic N/S/W/E order breaks corner ties.
     */
    public static Direction streetDir(PlotData d, int x, int z, int max) {
        for (int t = 1; t <= max; t++) {
            if (PlotManager.owningPlot(x, z - t) != d) return Direction.NORTH;
            if (PlotManager.owningPlot(x, z + t) != d) return Direction.SOUTH;
            if (PlotManager.owningPlot(x - t, z) != d) return Direction.WEST;
            if (PlotManager.owningPlot(x + t, z) != d) return Direction.EAST;
        }
        return null;
    }

    /** Distance (1..max) from an owned column to the nearest non-owned column, or max+1. */
    private static int streetDist(PlotData d, int x, int z, int max) {
        for (int t = 1; t <= max; t++) {
            if (PlotManager.owningPlot(x, z - t) != d || PlotManager.owningPlot(x, z + t) != d
                    || PlotManager.owningPlot(x - t, z) != d || PlotManager.owningPlot(x + t, z) != d) return t;
        }
        return max + 1;
    }

    /** Pattern column for a position, tiling along the edge perpendicular to {@code toStreet}. */
    private static int patternCol(int x, int z, Direction toStreet) {
        int along = (toStreet.getAxis() == Direction.Axis.X) ? z : x;
        return Math.floorMod(along, COLS);
    }

    /** Resolve a cell id to the state to paint; stairs are rotated so their steps face the street. */
    private static BlockState stateFor(String cellId, Direction toStreet) {
        if (cellId == null || cellId.isBlank()) return AIR;
        try {
            Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(cellId));
            if (b == null || b == Blocks.AIR) return AIR;
            BlockState s = b.defaultBlockState();
            if (b instanceof StairBlock) {
                // FACING is the ascent direction: ascend toward the plot = low steps toward the street.
                s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, toStreet.getOpposite());
            }
            return s;
        } catch (Exception e) {
            return AIR;
        }
    }

    // ---- sidewalk lookups (used by the painter) --------------------------

    /**
     * The pattern surface state for an OWNED sidewalk-ring column of {@code d}, or null when the plot
     * has no sidewalk pattern (caller falls back to the default tuff sidewalk).
     */
    public static BlockState sidewalkState(PlotData d, int x, int z) {
        String[][] grid = grid(d, false);
        if (grid == null) return null;
        int t = streetDist(d, x, z, PlotConfig.SIDEWALK_DEPTH);          // 1..3 from the street side
        if (t > PlotConfig.SIDEWALK_DEPTH) return null;                   // not actually on the ring
        Direction toStreet = streetDir(d, x, z, PlotConfig.SIDEWALK_DEPTH);
        if (toStreet == null) return null;
        int row = PlotConfig.SIDEWALK_DEPTH - t;                          // 0 = touches the plot grass
        return stateFor(grid[row][patternCol(x, z, toStreet)], toStreet);
    }

    /**
     * The plot owning the curb at an UNOWNED column (cardinal-adjacent to its sidewalk), or null.
     * Deterministic N/S/W/E priority decides which plot styles a curb shared at a merge boundary.
     */
    public static PlotData curbOwner(int x, int z) {
        PlotData n = PlotManager.owningPlot(x, z - 1);
        if (n != null) return n;
        PlotData s = PlotManager.owningPlot(x, z + 1);
        if (s != null) return s;
        PlotData w = PlotManager.owningPlot(x - 1, z);
        if (w != null) return w;
        return PlotManager.owningPlot(x + 1, z);
    }

    /**
     * The pattern curb state (last pattern row) for an unowned curb column bordering {@code d},
     * or null when the plot has no sidewalk pattern.
     */
    public static BlockState curbState(PlotData d, int x, int z) {
        String[][] grid = grid(d, false);
        if (grid == null) return null;
        Direction toPlot;
        if (PlotManager.owningPlot(x, z - 1) == d) toPlot = Direction.NORTH;
        else if (PlotManager.owningPlot(x, z + 1) == d) toPlot = Direction.SOUTH;
        else if (PlotManager.owningPlot(x - 1, z) == d) toPlot = Direction.WEST;
        else if (PlotManager.owningPlot(x + 1, z) == d) toPlot = Direction.EAST;
        else return null;
        Direction toStreet = toPlot.getOpposite();
        return stateFor(grid[SIDEWALK_ROWS - 1][patternCol(x, z, toStreet)], toStreet);
    }

    // ---- wall application ------------------------------------------------

    /** True if an owned column is on the plot's outermost grass ring (where walls are built). */
    public static boolean isWallRing(PlotData d, int x, int z) {
        if (PlotManager.owningPlot(x, z) != d || PlotManager.nearMergeEdge(x, z)) return false;
        // Adjacent (cardinal) to the sidewalk ring = first row of grass.
        return (PlotManager.owningPlot(x, z - 1) == d && PlotManager.nearMergeEdge(x, z - 1))
                || (PlotManager.owningPlot(x, z + 1) == d && PlotManager.nearMergeEdge(x, z + 1))
                || (PlotManager.owningPlot(x - 1, z) == d && PlotManager.nearMergeEdge(x - 1, z))
                || (PlotManager.owningPlot(x + 1, z) == d && PlotManager.nearMergeEdge(x + 1, z));
    }

    /**
     * Build (or clear, when the pattern is blank/air) the plot's wall on its edge ring.
     * Writes exactly {@link #WALL_ROWS} layers starting at {@link PlotConfig#FLOOR_Y}.
     */
    public static int applyWall(ServerLevel level, PlotData d) {
        String[][] grid = grid(d, true);
        int[] box = boundingBox(d);
        int painted = 0;
        for (int x = box[0]; x <= box[1]; x++) {
            for (int z = box[2]; z <= box[3]; z++) {
                if (!isWallRing(d, x, z)) continue;
                Direction toStreet = streetDir(d, x, z, PlotConfig.SIDEWALK_DEPTH + 1);
                if (toStreet == null) toStreet = Direction.NORTH;
                int col = patternCol(x, z, toStreet);
                for (int layer = 0; layer < WALL_ROWS; layer++) {
                    BlockState s = grid == null ? AIR : stateFor(grid[layer][col], toStreet);
                    setIfDiff(level, x, PlotConfig.FLOOR_Y + layer, z, s);
                }
                painted++;
            }
        }
        return painted;
    }

    /**
     * Repaint the plot's sidewalk ring + curb line from its pattern (or back to the defaults when the
     * pattern is blank). Touches only the surface layer — never the airspace above it.
     */
    public static int applySidewalk(ServerLevel level, PlotData d) {
        int[] box = boundingBox(d);
        int painted = 0;
        for (int x = box[0] - 1; x <= box[1] + 1; x++) {
            for (int z = box[2] - 1; z <= box[3] + 1; z++) {
                PlotData here = PlotManager.owningPlot(x, z);
                if (here == d && PlotManager.nearMergeEdge(x, z)) {
                    BlockState s = sidewalkState(d, x, z);
                    setIfDiff(level, x, PlotConfig.GROUND_Y, z,
                            s != null ? s : Blocks.CHISELED_TUFF_BRICKS.defaultBlockState());
                    painted++;
                } else if (here == null && curbOwner(x, z) == d) {
                    BlockState s = curbState(d, x, z);
                    if (s == null) s = defaultCurb(d, x, z);
                    setIfDiff(level, x, PlotConfig.GROUND_Y, z, s);
                    setIfDiff(level, x, PlotConfig.ROAD_Y, z, Blocks.STONE.defaultBlockState());
                    painted++;
                }
            }
        }
        return painted;
    }

    /** The vanilla curb look: a stone stair ascending toward the plot (stone block if ambiguous). */
    private static BlockState defaultCurb(PlotData d, int x, int z) {
        BlockState s = curbStateWithCells(d, x, z, "minecraft:stone_stairs");
        return s != null ? s : Blocks.STONE.defaultBlockState();
    }

    private static BlockState curbStateWithCells(PlotData d, int x, int z, String id) {
        Direction toPlot;
        if (PlotManager.owningPlot(x, z - 1) == d) toPlot = Direction.NORTH;
        else if (PlotManager.owningPlot(x, z + 1) == d) toPlot = Direction.SOUTH;
        else if (PlotManager.owningPlot(x - 1, z) == d) toPlot = Direction.WEST;
        else if (PlotManager.owningPlot(x + 1, z) == d) toPlot = Direction.EAST;
        else return null;
        return stateFor(id, toPlot.getOpposite());
    }

    /** xMin, xMax, zMin, zMax of every column the plot owns (cells ± sidewalk). */
    private static int[] boundingBox(PlotData d) {
        int pxMin = Integer.MAX_VALUE, pxMax = Integer.MIN_VALUE, pzMin = Integer.MAX_VALUE, pzMax = Integer.MIN_VALUE;
        for (PlotPos c : d.cells) {
            pxMin = Math.min(pxMin, c.px()); pxMax = Math.max(pxMax, c.px());
            pzMin = Math.min(pzMin, c.pz()); pzMax = Math.max(pzMax, c.pz());
        }
        return new int[] {
                pxMin * PlotConfig.STEP - PlotConfig.SIDEWALK_DEPTH,
                pxMax * PlotConfig.STEP + PlotConfig.PLOT_SIZE + PlotConfig.SIDEWALK_DEPTH,
                pzMin * PlotConfig.STEP - PlotConfig.SIDEWALK_DEPTH,
                pzMax * PlotConfig.STEP + PlotConfig.PLOT_SIZE + PlotConfig.SIDEWALK_DEPTH };
    }

    private static void setIfDiff(ServerLevel level, int x, int y, int z, BlockState s) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
        if (PortalManager.isProtected(pos)) return;
        if (!level.getBlockState(pos).equals(s)) level.setBlock(pos, s, Block.UPDATE_CLIENTS);
    }
}
