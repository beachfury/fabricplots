package com.fabricplots;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Op-only "combine_plots" wand — a named GOLDEN AXE (a non-wooden axe so it never clashes with
 * WorldEdit's wooden-axe wand). It selects an arbitrary SET of plot cells, so any rectilinear
 * shape can be merged (L, T, H, +, filled blocks, …):
 *   • Right-click a plot to add it to the selection (a gold marker drops at its centre).
 *   • Right-click an ALREADY-SELECTED plot to remove it (toggle it back off).
 *   • Right-click another plot in the SAME ROW OR COLUMN as your last click → every plot between
 *     them is added too (a straight "stroke").
 *   • Right-click a plot that lines up with neither (a diagonal) → it just starts a new arm.
 * Union the strokes to draw any letter/shape. Markers are restored on cancel or after combining.
 */
public final class CombineWand {
    private static final String WAND_NAME = "combine_plots";
    private static final net.minecraft.world.item.Item WAND_ITEM = Items.GOLDEN_AXE;
    private static final BlockState GOLD = Blocks.GOLD_BLOCK.defaultBlockState();

    private record Marker(BlockPos pos, BlockState original) {}
    /** The selected plot cells per player (the actual shape that gets merged). */
    private static final Map<UUID, LinkedHashSet<PlotPos>> CELLS = new HashMap<>();
    /** Gold marker blocks placed per player, kept so they can be restored. */
    private static final Map<UUID, List<Marker>> MARKERS = new HashMap<>();
    /** The last clicked block per player, used to line up straight-fill strokes. */
    private static final Map<UUID, BlockPos> LAST = new HashMap<>();

    private CombineWand() {}

    public static ItemStack createWand() {
        ItemStack stack = new ItemStack(WAND_ITEM);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(WAND_NAME));
        return stack;
    }

    public static boolean isWand(ItemStack s) {
        if (s.isEmpty() || s.getItem() != WAND_ITEM) return false;
        Component name = s.get(DataComponents.CUSTOM_NAME);
        return name != null && WAND_NAME.equals(name.getString());
    }

    /** The selected plot cells for a player (the shape to merge), or an empty set. */
    public static Set<PlotPos> cells(UUID id) {
        LinkedHashSet<PlotPos> sel = CELLS.get(id);
        return sel == null ? new LinkedHashSet<>() : new LinkedHashSet<>(sel);
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.dimension() != FabricPlots.PLOTS_DIM) return InteractionResult.PASS;
            if (!isWand(player.getItemInHand(hand))) return InteractionResult.PASS;
            // Do the actual marking server-side; on the client we still must return a non-PASS
            // result so the client forwards this (otherwise no-op) click to the server.
            if (player instanceof ServerPlayer sp && world instanceof ServerLevel level) {
                if (PlotProtection.isAdmin(sp) || PlotsConfig.allowPlayerCombine) {
                    addPoint(sp, level, hit.getBlockPos());
                } else {
                    msg(sp, "The combine wand is ops only.");
                }
            }
            return InteractionResult.SUCCESS; // consume the click + forward client→server
        });
    }

    private static void addPoint(ServerPlayer p, ServerLevel level, BlockPos clicked) {
        final UUID id = p.getUUID();
        final PlotPos cell = PlotManager.plotAt(clicked.getX(), clicked.getZ());
        final LinkedHashSet<PlotPos> cells = CELLS.computeIfAbsent(id, k -> new LinkedHashSet<>());

        // Re-click an already-selected plot to REMOVE it (toggle off).
        if (cells.contains(cell)) {
            cells.remove(cell);
            unmarkCell(level, id, cell);
            LAST.put(id, clicked.immutable());
            msg(p, "Removed that plot. " + cells.size() + " plot" + (cells.size() == 1 ? "" : "s")
                    + " selected. /plot combine <player> to merge · /plot wand cancel to clear.");
            return;
        }

        List<PlotPos> toAdd;
        BlockPos last = LAST.get(id);
        if (last != null) {
            PlotPos lastCell = PlotManager.plotAt(last.getX(), last.getZ());
            if (!lastCell.equals(cell) && (lastCell.px() == cell.px() || lastCell.pz() == cell.pz())) {
                toAdd = cellsBetween(lastCell, cell);   // aligned → fill the straight run
            } else {
                toAdd = List.of(cell);                  // same cell, or a diagonal (new arm)
            }
        } else {
            toAdd = List.of(cell);
        }

        int added = 0;
        for (PlotPos c : toAdd) if (cells.add(c)) { markCell(level, id, c); added++; }
        LAST.put(id, clicked.immutable());
        msg(p, "Selected " + cells.size() + " plot" + (cells.size() == 1 ? "" : "s")
                + (added > 1 ? " (+" + added + " along the line)" : "")
                + ". Re-click a plot to unselect · /plot combine <player> to merge · /plot wand cancel to clear.");
    }

    /** All cells on the straight line between two row- or column-aligned cells (inclusive). */
    private static List<PlotPos> cellsBetween(PlotPos a, PlotPos b) {
        List<PlotPos> out = new ArrayList<>();
        if (a.px() == b.px()) {
            int lo = Math.min(a.pz(), b.pz()), hi = Math.max(a.pz(), b.pz());
            for (int pz = lo; pz <= hi; pz++) out.add(new PlotPos(a.px(), pz));
        } else { // a.pz() == b.pz()
            int lo = Math.min(a.px(), b.px()), hi = Math.max(a.px(), b.px());
            for (int px = lo; px <= hi; px++) out.add(new PlotPos(px, a.pz()));
        }
        return out;
    }

    /** Drop a gold marker at a cell's centre so the selection is visible (Bedrock-safe, a real block). */
    private static void markCell(ServerLevel level, UUID id, PlotPos cell) {
        int[] xz = PlotManager.homeXZ(cell);
        BlockPos pos = new BlockPos(xz[0], PlotConfig.FLOOR_Y, xz[1]);
        BlockState original = level.getBlockState(pos);
        if (original.is(Blocks.GOLD_BLOCK)) return;
        MARKERS.computeIfAbsent(id, k -> new ArrayList<>()).add(new Marker(pos, original));
        level.setBlock(pos, GOLD, Block.UPDATE_CLIENTS);
    }

    /** Restore the original block under a single cell's gold marker and forget that marker. */
    private static void unmarkCell(ServerLevel level, UUID id, PlotPos cell) {
        int[] xz = PlotManager.homeXZ(cell);
        BlockPos pos = new BlockPos(xz[0], PlotConfig.FLOOR_Y, xz[1]);
        List<Marker> markers = MARKERS.get(id);
        if (markers == null) return;
        for (Iterator<Marker> it = markers.iterator(); it.hasNext(); ) {
            Marker m = it.next();
            if (m.pos().equals(pos)) {
                if (level.getBlockState(pos).is(Blocks.GOLD_BLOCK)) level.setBlock(pos, m.original(), Block.UPDATE_CLIENTS);
                it.remove();
                break;
            }
        }
    }

    /** Restore the original blocks under all of a player's markers and clear their selection. */
    public static void restore(ServerLevel plots, ServerPlayer p) {
        final UUID id = p.getUUID();
        List<Marker> sel = MARKERS.remove(id);
        CELLS.remove(id);
        LAST.remove(id);
        if (sel == null || plots == null) return;
        for (Marker m : sel) {
            if (plots.getBlockState(m.pos()).is(Blocks.GOLD_BLOCK)) {
                plots.setBlock(m.pos(), m.original(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static void msg(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal("[Plots] " + text));
    }
}
