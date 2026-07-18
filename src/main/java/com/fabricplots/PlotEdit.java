package com.fabricplots;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * "WorldEdit-lite", jailed to a plot. Pick two corners (commands or the editor wand), then
 * /plot set or /plot replace — but every block written is checked against plot ownership first,
 * so the edit physically cannot escape the plots the player can build on. Each edit snapshots the
 * blocks it changes so /plot undo can reverse it. Admins are unclamped (can edit roads too) but
 * still never touch portal blocks.
 */
public final class PlotEdit {
    private static final String WAND_NAME = "plot_editor";
    private static final net.minecraft.world.item.Item WAND_ITEM = Items.WOODEN_AXE;
    private static final int MAX_BLOCKS = 65536;   // per edit — keeps a single op from freezing the server
    private static final int UNDO_DEPTH = 5;       // edits kept per player

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final Map<UUID, BlockPos> POS1 = new HashMap<>();
    private static final Map<UUID, BlockPos> POS2 = new HashMap<>();
    private static final Map<UUID, Boolean> NEXT_IS_POS2 = new HashMap<>(); // wand alternation
    private record Snapshot(BlockPos pos, BlockState old) {}
    private static final Map<UUID, Deque<List<Snapshot>>> UNDO = new HashMap<>();
    private static final Map<UUID, Deque<List<Snapshot>>> REDO = new HashMap<>();

    private record ClipBlock(int dx, int dy, int dz, BlockState state) {} // clipboard, relative to copy origin
    private record Write(BlockPos pos, BlockState state) {}
    private static final Map<UUID, List<ClipBlock>> CLIPBOARD = new HashMap<>();

    // Random-texture toggle: when ON, every written block rolls from the BLOCK items in the
    // player's hotbar (duplicate slots weight the mix). Session-only.
    private static final Set<UUID> RANDOM_TEXTURE = new HashSet<>();
    // Shape center marker (gold): the chosen center + the blocks it covered (restored on clear).
    private static final Map<UUID, BlockPos> SHAPE_CENTER = new HashMap<>();
    private static final Map<UUID, List<Snapshot>> CENTER_MARKER = new HashMap<>();
    private static final java.util.Random RNG = new java.util.Random();

    private PlotEdit() {}

    // ---- the editor wand (a named wooden axe — free since we ship no WorldEdit) ----

    public static ItemStack createWand() {
        ItemStack s = new ItemStack(WAND_ITEM);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(WAND_NAME));
        return s;
    }

    public static boolean isWand(ItemStack s) {
        if (s.isEmpty() || s.getItem() != WAND_ITEM) return false;
        Component n = s.get(DataComponents.CUSTOM_NAME);
        return n != null && WAND_NAME.equals(n.getString());
    }

    public static void register() {
        // The wand never breaks blocks — left-click is swallowed.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (world.dimension() != FabricPlots.PLOTS_DIM) return InteractionResult.PASS;
            return isWand(player.getItemInHand(hand)) ? InteractionResult.FAIL : InteractionResult.PASS;
        });
        // Right-click sets a corner, alternating 1 → 2 → 1. SUCCESS so the client forwards the click.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.dimension() != FabricPlots.PLOTS_DIM) return InteractionResult.PASS;
            if (!isWand(player.getItemInHand(hand))) return InteractionResult.PASS;
            if (player instanceof ServerPlayer sp) {
                boolean second = NEXT_IS_POS2.getOrDefault(sp.getUUID(), false);
                mark(sp, hit.getBlockPos(), !second);
                NEXT_IS_POS2.put(sp.getUUID(), !second);
            }
            return InteractionResult.SUCCESS;
        });
    }

    private static void mark(ServerPlayer sp, BlockPos pos, boolean isPos1) {
        (isPos1 ? POS1 : POS2).put(sp.getUUID(), pos.immutable());
        msg(sp, "Corner " + (isPos1 ? "1" : "2") + " set at " + xyz(pos) + ".");
    }

    /** The block the player is holding (default state), or null if their main hand isn't a block. */
    public static BlockState heldBlock(ServerPlayer sp) {
        return sp.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem bi
                ? bi.getBlock().defaultBlockState() : null;
    }

    public static void setPos1(ServerPlayer sp) { POS1.put(sp.getUUID(), sp.blockPosition().immutable()); msg(sp, "Corner 1 set at " + xyz(sp.blockPosition()) + "."); }
    public static void setPos2(ServerPlayer sp) { POS2.put(sp.getUUID(), sp.blockPosition().immutable()); msg(sp, "Corner 2 set at " + xyz(sp.blockPosition()) + "."); }

    // ---- random texture --------------------------------------------------

    /** Toggle hotbar-random texturing for this player. Returns the new state. */
    public static boolean toggleRandomTexture(ServerPlayer sp) {
        UUID id = sp.getUUID();
        if (RANDOM_TEXTURE.remove(id)) return false;
        RANDOM_TEXTURE.add(id);
        return true;
    }

    public static boolean isRandomTexture(ServerPlayer sp) {
        return RANDOM_TEXTURE.contains(sp.getUUID());
    }

    /**
     * The material source for an edit: the held block — or, with random texture ON, a fresh roll
     * from the hotbar's block items per block written. Duplicate hotbar slots weight the mix.
     */
    private static java.util.function.Supplier<BlockState> materials(ServerPlayer sp, BlockState held) {
        if (!isRandomTexture(sp)) return () -> held;
        List<BlockState> pool = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack s = sp.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.BlockItem bi)
                pool.add(bi.getBlock().defaultBlockState());
        }
        if (pool.isEmpty()) return () -> held;
        return () -> pool.get(RNG.nextInt(pool.size()));
    }

    // ---- operations ------------------------------------------------------

    public static int set(ServerPlayer sp, ServerLevel level, BlockState state) {
        return applyEdit(sp, level, (lvl, p) -> true, materials(sp, state), "Set");
    }

    public static int replace(ServerPlayer sp, ServerLevel level, BlockInput from, BlockState to) {
        return applyEdit(sp, level, from::test, materials(sp, to), "Replaced");
    }

    private static int applyEdit(ServerPlayer sp, ServerLevel level, BiPredicate<ServerLevel, BlockPos> include,
                                 BlockState newState, String verb) {
        return applyEdit(sp, level, include, () -> newState, verb);
    }

    private static int applyEdit(ServerPlayer sp, ServerLevel level, BiPredicate<ServerLevel, BlockPos> include,
                                 java.util.function.Supplier<BlockState> material, String verb) {
        UUID id = sp.getUUID();
        BlockPos p1 = POS1.get(id), p2 = POS2.get(id);
        if (p1 == null || p2 == null) { msg(sp, "Set both corners first — /plot pos1 and /plot pos2, or use /plot editwand."); return 0; }
        final boolean admin = PlotProtection.isBuildAdmin(sp);
        final int x1 = Math.min(p1.getX(), p2.getX()), x2 = Math.max(p1.getX(), p2.getX());
        final int z1 = Math.min(p1.getZ(), p2.getZ()), z2 = Math.max(p1.getZ(), p2.getZ());
        final int y1 = Math.max(PlotConfig.DIRT_BOTTOM_Y, Math.min(p1.getY(), p2.getY()));
        final int y2 = Math.min(PlotConfig.WORLD_TOP_Y, Math.max(p1.getY(), p2.getY()));

        List<Snapshot> snaps = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (!admin) {                                   // non-ops: only inside a plot they can build on
                    PlotData d = PlotManager.owningPlot(x, z);
                    if (d == null || !d.canBuild(id)) continue;
                }
                for (int y = y1; y <= y2; y++) {
                    pos.set(x, y, z);
                    if (PortalManager.isProtected(pos)) continue;       // never overwrite a portal
                    if (!include.test(level, pos)) continue;            // replace: only matching blocks
                    snaps.add(new Snapshot(pos.immutable(), level.getBlockState(pos)));
                    if (snaps.size() > MAX_BLOCKS) { msg(sp, "That's over " + MAX_BLOCKS + " blocks — narrow your selection."); return 0; }
                }
            }
        }
        if (snaps.isEmpty()) { msg(sp, "Nothing to change — make sure the selection overlaps a plot you own."); return 0; }
        for (Snapshot s : snaps) level.setBlock(s.pos(), material.get(), Block.UPDATE_CLIENTS);
        pushUndo(id, snaps);
        msg(sp, verb + " " + snaps.size() + " block" + (snaps.size() == 1 ? "" : "s") + ". /plot undo to revert.");
        return 1;
    }

    public static int undo(ServerPlayer sp, ServerLevel level) {
        return step(sp, level, UNDO, REDO, "Nothing to undo.", "Undone");
    }

    public static int redo(ServerPlayer sp, ServerLevel level) {
        return step(sp, level, REDO, UNDO, "Nothing to redo.", "Redone");
    }

    /** Pop one edit off {@code from}, restore those blocks, and push the pre-restore state onto {@code to}. */
    private static int step(ServerPlayer sp, ServerLevel level, Map<UUID, Deque<List<Snapshot>>> from,
                            Map<UUID, Deque<List<Snapshot>>> to, String empty, String verb) {
        Deque<List<Snapshot>> stack = from.get(sp.getUUID());
        if (stack == null || stack.isEmpty()) { msg(sp, empty); return 0; }
        List<Snapshot> edit = stack.pop();
        List<Snapshot> inverse = new ArrayList<>(edit.size());
        for (Snapshot s : edit) {
            inverse.add(new Snapshot(s.pos(), level.getBlockState(s.pos())));
            level.setBlock(s.pos(), s.old(), Block.UPDATE_CLIENTS);
        }
        to.computeIfAbsent(sp.getUUID(), k -> new ArrayDeque<>()).push(inverse);
        msg(sp, verb + " — " + edit.size() + " block" + (edit.size() == 1 ? "" : "s") + ".");
        return 1;
    }

    // ---- clipboard: copy / cut / paste / stack / move --------------------

    public static int copy(ServerPlayer sp, ServerLevel level) {
        int n = doCopy(sp, level);
        if (n < 0) return 0;
        msg(sp, "Copied " + n + " blocks. Stand where you want it and /plot paste.");
        return 1;
    }

    public static int cut(ServerPlayer sp, ServerLevel level) {
        if (doCopy(sp, level) < 0) return 0;
        return applyEdit(sp, level, (l, p) -> true, AIR, "Cut"); // copy, then clear the selection (clamped)
    }

    /** Capture the selection into the player's clipboard, relative to their position. -1 on error. */
    private static int doCopy(ServerPlayer sp, ServerLevel level) {
        UUID id = sp.getUUID();
        BlockPos p1 = POS1.get(id), p2 = POS2.get(id);
        if (p1 == null || p2 == null) { msg(sp, "Set both corners first."); return -1; }
        BlockPos origin = sp.blockPosition();
        int x1 = Math.min(p1.getX(), p2.getX()), x2 = Math.max(p1.getX(), p2.getX());
        int z1 = Math.min(p1.getZ(), p2.getZ()), z2 = Math.max(p1.getZ(), p2.getZ());
        int y1 = Math.max(PlotConfig.DIRT_BOTTOM_Y, Math.min(p1.getY(), p2.getY()));
        int y2 = Math.min(PlotConfig.WORLD_TOP_Y, Math.max(p1.getY(), p2.getY()));
        List<ClipBlock> clip = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) for (int y = y1; y <= y2; y++) {
            pos.set(x, y, z);
            clip.add(new ClipBlock(x - origin.getX(), y - origin.getY(), z - origin.getZ(), level.getBlockState(pos)));
            if (clip.size() > MAX_BLOCKS) { msg(sp, "Selection too big to copy (over " + MAX_BLOCKS + ")."); return -1; }
        }
        CLIPBOARD.put(id, clip);
        return clip.size();
    }

    public static int paste(ServerPlayer sp, ServerLevel level) {
        List<ClipBlock> clip = CLIPBOARD.get(sp.getUUID());
        if (clip == null || clip.isEmpty()) { msg(sp, "Nothing to paste — /plot copy first."); return 0; }
        boolean admin = PlotProtection.isBuildAdmin(sp);
        BlockPos origin = sp.blockPosition();
        List<Write> writes = new ArrayList<>();
        int skipped = 0;
        for (ClipBlock c : clip) {
            if (c.state().isAir()) continue; // stamp solids only, don't erase the surroundings
            int x = origin.getX() + c.dx(), y = origin.getY() + c.dy(), z = origin.getZ() + c.dz();
            if (!canEdit(sp, admin, x, y, z)) { skipped++; continue; }
            writes.add(new Write(new BlockPos(x, y, z), c.state()));
        }
        int r = commit(sp, level, writes, "Pasted");
        if (r == 1 && skipped > 0) msg(sp, "(" + skipped + " blocks skipped — outside your plot.)");
        return r;
    }

    public static int stack(ServerPlayer sp, ServerLevel level, int count) {
        UUID id = sp.getUUID();
        BlockPos p1 = POS1.get(id), p2 = POS2.get(id);
        if (p1 == null || p2 == null) { msg(sp, "Set both corners first."); return 0; }
        boolean admin = PlotProtection.isBuildAdmin(sp);
        Direction dir = sp.getDirection();
        int x1 = Math.min(p1.getX(), p2.getX()), x2 = Math.max(p1.getX(), p2.getX());
        int z1 = Math.min(p1.getZ(), p2.getZ()), z2 = Math.max(p1.getZ(), p2.getZ());
        int y1 = Math.max(PlotConfig.DIRT_BOTTOM_Y, Math.min(p1.getY(), p2.getY()));
        int y2 = Math.min(PlotConfig.WORLD_TOP_Y, Math.max(p1.getY(), p2.getY()));
        int span = switch (dir.getAxis()) { case X -> x2 - x1 + 1; case Z -> z2 - z1 + 1; default -> y2 - y1 + 1; };
        List<Write> writes = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= count; i++) {
            int ox = dir.getStepX() * span * i, oy = dir.getStepY() * span * i, oz = dir.getStepZ() * span * i;
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) for (int y = y1; y <= y2; y++) {
                pos.set(x, y, z);
                BlockState src = level.getBlockState(pos); // source region never overlaps a destination
                int nx = x + ox, ny = y + oy, nz = z + oz;
                if (!canEdit(sp, admin, nx, ny, nz)) continue;
                writes.add(new Write(new BlockPos(nx, ny, nz), src));
            }
        }
        return commit(sp, level, writes, "Stacked x" + count + " →");
    }

    public static int move(ServerPlayer sp, ServerLevel level, int count) {
        UUID id = sp.getUUID();
        BlockPos p1 = POS1.get(id), p2 = POS2.get(id);
        if (p1 == null || p2 == null) { msg(sp, "Set both corners first."); return 0; }
        boolean admin = PlotProtection.isBuildAdmin(sp);
        Direction dir = sp.getDirection();
        int ox = dir.getStepX() * count, oy = dir.getStepY() * count, oz = dir.getStepZ() * count;
        int x1 = Math.min(p1.getX(), p2.getX()), x2 = Math.max(p1.getX(), p2.getX());
        int z1 = Math.min(p1.getZ(), p2.getZ()), z2 = Math.max(p1.getZ(), p2.getZ());
        int y1 = Math.max(PlotConfig.DIRT_BOTTOM_Y, Math.min(p1.getY(), p2.getY()));
        int y2 = Math.min(PlotConfig.WORLD_TOP_Y, Math.max(p1.getY(), p2.getY()));
        List<Write> clears = new ArrayList<>(), places = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) for (int y = y1; y <= y2; y++) {
            if (!canEdit(sp, admin, x, y, z)) continue; // only move blocks you own
            pos.set(x, y, z);
            BlockState src = level.getBlockState(pos);
            clears.add(new Write(new BlockPos(x, y, z), AIR));
            int nx = x + ox, ny = y + oy, nz = z + oz;
            if (canEdit(sp, admin, nx, ny, nz)) places.add(new Write(new BlockPos(nx, ny, nz), src));
        }
        List<Write> writes = new ArrayList<>(clears); // clear first, then place (place wins on overlap)
        writes.addAll(places);
        return commit(sp, level, writes, "Moved");
    }

    // ---- shapes ----------------------------------------------------------

    public static int walls(ServerPlayer sp, ServerLevel level, BlockState state) {
        UUID id = sp.getUUID();
        BlockPos p1 = POS1.get(id), p2 = POS2.get(id);
        if (p1 == null || p2 == null) { msg(sp, "Set both corners first."); return 0; }
        boolean admin = PlotProtection.isBuildAdmin(sp);
        int x1 = Math.min(p1.getX(), p2.getX()), x2 = Math.max(p1.getX(), p2.getX());
        int z1 = Math.min(p1.getZ(), p2.getZ()), z2 = Math.max(p1.getZ(), p2.getZ());
        int y1 = Math.max(PlotConfig.DIRT_BOTTOM_Y, Math.min(p1.getY(), p2.getY()));
        int y2 = Math.min(PlotConfig.WORLD_TOP_Y, Math.max(p1.getY(), p2.getY()));
        var mat = materials(sp, state);
        List<Write> writes = new ArrayList<>();
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            if (x != x1 && x != x2 && z != z1 && z != z2) continue; // perimeter only
            for (int y = y1; y <= y2; y++) if (canEdit(sp, admin, x, y, z)) writes.add(new Write(new BlockPos(x, y, z), mat.get()));
        }
        return commit(sp, level, writes, "Built walls —");
    }

    public static int sphere(ServerPlayer sp, ServerLevel level, BlockState state, int r, boolean hollow) {
        boolean admin = PlotProtection.isBuildAdmin(sp);
        BlockPos c = sp.blockPosition();
        double outer = r + 0.5, inner = r - 0.5;
        var mat = materials(sp, state);
        List<Write> writes = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > outer || (hollow && dist < inner)) continue;
            int x = c.getX() + dx, y = c.getY() + dy, z = c.getZ() + dz;
            if (canEdit(sp, admin, x, y, z)) writes.add(new Write(new BlockPos(x, y, z), mat.get()));
        }
        return commit(sp, level, writes, hollow ? "Hollow sphere —" : "Sphere —");
    }

    public static int cylinder(ServerPlayer sp, ServerLevel level, BlockState state, int r, int height) {
        boolean admin = PlotProtection.isBuildAdmin(sp);
        BlockPos c = sp.blockPosition();
        double rr = (r + 0.5) * (r + 0.5);
        var mat = materials(sp, state);
        List<Write> writes = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > rr) continue;
            for (int dy = 0; dy < height; dy++) {
                int x = c.getX() + dx, y = c.getY() + dy, z = c.getZ() + dz;
                if (canEdit(sp, admin, x, y, z)) writes.add(new Write(new BlockPos(x, y, z), mat.get()));
            }
        }
        return commit(sp, level, writes, "Cylinder —");
    }

    // ---- the shapes engine (Shapes screen in /plot edit) -----------------

    public enum Shape { CIRCLE, SQUARE, SPHERE, CYLINDER, PYRAMID, LINE }

    /**
     * Place (or move) the gold center marker at the player's feet: a single block for odd sizes,
     * a 2x2 for even sizes (the true center is the corner between the four). Self-cleaning: the
     * covered blocks are restored when the marker moves, is cleared, or a shape is built.
     */
    public static void setShapeCenter(ServerPlayer sp, ServerLevel level, boolean evenSize) {
        clearShapeMarker(sp, level);
        UUID id = sp.getUUID();
        BlockPos feet = sp.blockPosition().immutable();
        List<Snapshot> covered = new ArrayList<>();
        boolean admin = PlotProtection.isBuildAdmin(sp);
        int span = evenSize ? 2 : 1;
        for (int dx = 0; dx < span; dx++) for (int dz = 0; dz < span; dz++) {
            int x = feet.getX() + dx, z = feet.getZ() + dz;
            if (!canEdit(sp, admin, x, feet.getY(), z)) continue;
            BlockPos p = new BlockPos(x, feet.getY(), z);
            covered.add(new Snapshot(p, level.getBlockState(p)));
            level.setBlock(p, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        SHAPE_CENTER.put(id, feet);
        CENTER_MARKER.put(id, covered);
        msg(sp, "Center marked at " + xyz(feet) + (evenSize ? " (2x2 — even size)" : "") + ". Build when ready.");
    }

    /** Restore whatever the gold marker covered (no-op if none). */
    public static void clearShapeMarker(ServerPlayer sp, ServerLevel level) {
        List<Snapshot> covered = CENTER_MARKER.remove(sp.getUUID());
        if (covered == null) return;
        for (Snapshot s : covered) {
            // Only un-place gold we placed (don't clobber a block the player changed since).
            if (level.getBlockState(s.pos()).is(Blocks.GOLD_BLOCK)) {
                level.setBlock(s.pos(), s.old(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Build a parametric shape centered on the marker (or the player's feet if none is set).
     * Flat shapes (circle/square) extrude {@code height} layers up; repeat stacks copies with
     * {@code spacing} air between them. Hollow shells are {@code thickness} blocks thick.
     */
    public static int buildShape(ServerPlayer sp, ServerLevel level, BlockState held, Shape shape,
                                 boolean hollow, int size, int height, int thickness, int repeat, int spacing) {
        if (shape == Shape.LINE) return line(sp, level, held, thickness);
        boolean admin = PlotProtection.isBuildAdmin(sp);
        BlockPos base = SHAPE_CENTER.getOrDefault(sp.getUUID(), sp.blockPosition().immutable());
        clearShapeMarker(sp, level); // the marker's ground state must be what the shape replaces
        SHAPE_CENTER.remove(sp.getUUID());

        int half = (size - 1) / 2;
        int xMin = base.getX() - half, xMax = xMin + size - 1;
        int zMin = base.getZ() - half, zMax = zMin + size - 1;
        double cx = (xMin + xMax) / 2.0, cz = (zMin + zMax) / 2.0;
        double outer = size / 2.0;

        int shapeHeight = switch (shape) {
            case SPHERE -> size;
            case PYRAMID -> (size + 1) / 2;
            default -> height;
        };
        var mat = materials(sp, held);
        List<Write> writes = new ArrayList<>();
        for (int k = 0; k < repeat; k++) {
            int yBase = base.getY() + k * (shapeHeight + spacing);
            switch (shape) {
                case CIRCLE, SQUARE, CYLINDER -> {
                    for (int x = xMin; x <= xMax; x++) for (int z = zMin; z <= zMax; z++) {
                        double dist = (shape == Shape.SQUARE)
                                ? Math.max(Math.abs(x - cx), Math.abs(z - cz))
                                : Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                        if (dist > outer) continue;
                        if (hollow && dist <= outer - thickness) continue;
                        for (int dy = 0; dy < height; dy++)
                            if (canEdit(sp, admin, x, yBase + dy, z))
                                writes.add(new Write(new BlockPos(x, yBase + dy, z), mat.get()));
                    }
                }
                case SPHERE -> {
                    double cy = yBase + (size - 1) / 2.0;
                    for (int x = xMin; x <= xMax; x++) for (int z = zMin; z <= zMax; z++)
                        for (int y = yBase; y < yBase + size; y++) {
                            double dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz));
                            if (dist > outer) continue;
                            if (hollow && dist <= outer - thickness) continue;
                            if (canEdit(sp, admin, x, y, z)) writes.add(new Write(new BlockPos(x, y, z), mat.get()));
                        }
                }
                case PYRAMID -> {
                    for (int layer = 0; layer * 2 < size; layer++) {
                        int lxMin = xMin + layer, lxMax = xMax - layer;
                        int lzMin = zMin + layer, lzMax = zMax - layer;
                        int y = yBase + layer;
                        for (int x = lxMin; x <= lxMax; x++) for (int z = lzMin; z <= lzMax; z++) {
                            if (hollow) {
                                boolean edge = (x - lxMin < thickness) || (lxMax - x < thickness)
                                        || (z - lzMin < thickness) || (lzMax - z < thickness);
                                if (!edge) continue;
                            }
                            if (canEdit(sp, admin, x, y, z)) writes.add(new Write(new BlockPos(x, y, z), mat.get()));
                        }
                    }
                }
                case LINE -> { /* handled above */ }
            }
        }
        String label = (hollow ? "Hollow " : "") + shape.name().toLowerCase() + " —";
        return commit(sp, level, writes, label.substring(0, 1).toUpperCase() + label.substring(1));
    }

    /**
     * Draw a straight line from corner 1 to corner 2 — any diagonal, any slope, full 3D. The line
     * visits every block the ideal segment passes through; thickness grows it into a square beam.
     */
    public static int line(ServerPlayer sp, ServerLevel level, BlockState held, int thickness) {
        UUID id = sp.getUUID();
        BlockPos p1 = POS1.get(id), p2 = POS2.get(id);
        if (p1 == null || p2 == null) { msg(sp, "A line runs corner 1 → corner 2 — set both first (/plot pos1, /plot pos2 or the wand)."); return 0; }
        boolean admin = PlotProtection.isBuildAdmin(sp);
        int steps = Math.max(Math.max(Math.abs(p2.getX() - p1.getX()), Math.abs(p2.getY() - p1.getY())),
                Math.abs(p2.getZ() - p1.getZ()));
        var mat = materials(sp, held);
        int radius = Math.max(0, thickness - 1);
        Set<BlockPos> cells = new java.util.LinkedHashSet<>();
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.round(p1.getX() + (p2.getX() - p1.getX()) * f);
            int y = (int) Math.round(p1.getY() + (p2.getY() - p1.getY()) * f);
            int z = (int) Math.round(p1.getZ() + (p2.getZ() - p1.getZ()) * f);
            for (int dx = -radius; dx <= radius; dx++)
                for (int dy = -radius; dy <= radius; dy++)
                    for (int dz = -radius; dz <= radius; dz++)
                        cells.add(new BlockPos(x + dx, y + dy, z + dz));
        }
        List<Write> writes = new ArrayList<>();
        for (BlockPos p : cells)
            if (canEdit(sp, admin, p.getX(), p.getY(), p.getZ())) writes.add(new Write(p, mat.get()));
        return commit(sp, level, writes, "Line —");
    }

    /** Snapshot (deduped, original states), apply all writes, push one undo entry. */
    private static int commit(ServerPlayer sp, ServerLevel level, List<Write> writes, String verb) {
        if (writes.isEmpty()) { msg(sp, "Nothing to change — make sure it lands on a plot you own."); return 0; }
        if (writes.size() > MAX_BLOCKS) { msg(sp, "That's over " + MAX_BLOCKS + " blocks — use a smaller selection or count."); return 0; }
        Set<BlockPos> seen = new HashSet<>();
        List<Snapshot> snaps = new ArrayList<>();
        for (Write w : writes) if (seen.add(w.pos())) snaps.add(new Snapshot(w.pos(), level.getBlockState(w.pos())));
        for (Write w : writes) level.setBlock(w.pos(), w.state(), Block.UPDATE_CLIENTS);
        pushUndo(sp.getUUID(), snaps);
        msg(sp, verb + " " + writes.size() + " blocks. /plot undo to revert.");
        return 1;
    }

    /** Can this player write at x/y/z? (Y in range, not a portal, and — unless admin — a plot they can build on.) */
    private static boolean canEdit(ServerPlayer sp, boolean admin, int x, int y, int z) {
        if (y < PlotConfig.DIRT_BOTTOM_Y || y > PlotConfig.WORLD_TOP_Y) return false;
        if (PortalManager.isProtected(new BlockPos(x, y, z))) return false;
        if (admin) return true;
        PlotData d = PlotManager.owningPlot(x, z);
        return d != null && d.canBuild(sp.getUUID());
    }

    private static void pushUndo(UUID id, List<Snapshot> snaps) {
        Deque<List<Snapshot>> stack = UNDO.computeIfAbsent(id, k -> new ArrayDeque<>());
        stack.push(snaps);
        while (stack.size() > UNDO_DEPTH) stack.removeLast();
        REDO.remove(id); // a fresh edit invalidates the redo history
    }

    private static String xyz(BlockPos p) { return p.getX() + ", " + p.getY() + ", " + p.getZ(); }
    private static void msg(ServerPlayer p, String t) { p.sendSystemMessage(Component.literal("[Plots] " + t)); }
}
