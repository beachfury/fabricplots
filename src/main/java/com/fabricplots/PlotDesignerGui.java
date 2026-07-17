package com.fabricplots;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;

/**
 * The sidewalk / wall pattern designers: a 9x6 chest GUI whose middle rows are an editable,
 * repeating design template (see {@link PlotStyle}) and whose locked marker rows anchor the
 * geometry (green = your plot's grass, black = the street, blue = the sky above a wall).
 *
 * Editing works with the vanilla cursor: pick any block from your inventory (creative in the plot
 * world = every block), then click a template cell to stamp it there. Click with an EMPTY cursor to
 * clear a cell back to air. The design is applied and saved automatically when the screen closes.
 */
public final class PlotDesignerGui {
    // Marker items — addressed by registry id so 26.1.2 and 26.2 share the code
    // (26.2 consolidated the colored-item CONSTANTS into ColorCollection; the registry ids didn't change).
    private static final Item PLOT_MARK = byRegistryId("minecraft:lime_concrete");
    private static final Item STREET_MARK = byRegistryId("minecraft:black_concrete");
    private static final Item SKY_MARK = byRegistryId("minecraft:light_blue_concrete");
    private static final Item AIR_MARK = byRegistryId("minecraft:gray_dye");

    private static Item byRegistryId(String id) {
        try {
            Item it = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
            return (it == null || it == Items.AIR) ? Items.GLASS_PANE : it;
        } catch (Exception e) { return Items.GLASS_PANE; }
    }

    // A few technical blocks that must not be stamped into a design.
    private static final Set<String> EXCLUDE = Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:barrier",
            "minecraft:light", "minecraft:moving_piston", "minecraft:spawner", "minecraft:trial_spawner",
            "minecraft:vault", "minecraft:bedrock");

    private PlotDesignerGui() {}

    public static void openSidewalk(ServerPlayer sp, PlotPos anchor, Runnable back) {
        open(sp, anchor, back, false);
    }

    public static void openWall(ServerPlayer sp, PlotPos anchor, Runnable back) {
        open(sp, anchor, back, true);
    }

    private static void open(ServerPlayer sp, PlotPos anchor, Runnable back, boolean wall) {
        PlotData d = PlotManager.get(anchor);
        if (d == null || (!d.owner.equals(sp.getUUID()) && !PlotProtection.isAdmin(sp))) { sp.closeContainer(); return; }

        int rows = wall ? PlotStyle.WALL_ROWS : PlotStyle.SIDEWALK_ROWS;
        String[][] existing = PlotStyle.parse(wall ? d.wallPattern : d.sidewalkPattern, rows);
        final String[][] grid = existing != null ? existing : defaultGrid(wall);

        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x6, sp, false) {
            private boolean applied = false;

            @Override
            public void close(boolean screenHandlerIsClosed) {
                super.close(screenHandlerIsClosed);
                if (!applied) {
                    applied = true;
                    applyAndSave(sp, anchor, grid, wall);
                }
            }
        };
        g.setTitle(Component.literal(wall ? "Wall designer" : "Sidewalk designer"));

        if (wall) {
            // Rows: sky, sky, wall top, wall mid, wall bottom, plot grass.
            markerRow(g, 0, SKY_MARK, "Sky", "Walls are capped at 3 blocks tall.");
            markerRow(g, 1, SKY_MARK, "Sky", "Walls are capped at 3 blocks tall.");
            editableRow(g, sp, grid, 2, 2, false);   // GUI row 2 = top layer   (grid row 2)
            editableRow(g, sp, grid, 3, 1, false);   // GUI row 3 = middle      (grid row 1)
            editableRow(g, sp, grid, 4, 0, false);   // GUI row 4 = bottom      (grid row 0)
            markerRow(g, 5, PLOT_MARK, "Your plot (grass edge)", "The wall stands on this ring.");
        } else {
            // Rows: plot grass, 3 sidewalk strips (inner→outer), curb, street.
            markerRow(g, 0, PLOT_MARK, "Your plot", "The design repeats along every edge.");
            editableRow(g, sp, grid, 1, 0, false);   // strip touching the plot
            editableRow(g, sp, grid, 2, 1, false);
            editableRow(g, sp, grid, 3, 2, false);   // outermost sidewalk strip
            editableRow(g, sp, grid, 4, 3, true);    // curb (stairs auto-face the street)
            markerRow(g, 5, STREET_MARK, "Street", "Curb stairs always face this way.");
        }

        // Controls live on the corners of the top marker row (the rest stay pure markers).
        g.setSlot(0, new GuiElementBuilder(Items.ARROW).setName(Component.literal("Save & back"))
                .setLore(List.of(Component.literal("Your design applies when you leave this screen.")))
                .setCallback((i, t, a, gg) -> { g.close(); back.run(); }).build());
        g.setSlot(8, new GuiElementBuilder(Items.TNT).setName(Component.literal("Reset to default"))
                .setLore(List.of(Component.literal(wall ? "Removes the wall." : "Back to the standard sidewalk.")))
                .setCallback((i, t, a, gg) -> {
                    if (wall) d.wallPattern = ""; else d.sidewalkPattern = "";
                    PlotStyle.invalidateCache(d);
                    PlotManager.save();
                    ServerLevel plots = sp.level().getServer().getLevel(FabricPlots.PLOTS_DIM);
                    if (plots != null) {
                        if (wall) PlotStyle.applyWall(plots, d);       // blank pattern clears the wall
                        else PlotStyle.applySidewalk(plots, d);        // blank pattern repaints defaults
                    }
                    sp.sendSystemMessage(Component.literal("[Plots] " + (wall ? "Wall removed." : "Sidewalk reset to default.")));
                    open(sp, anchor, back, wall);
                }).build());
        g.open();
    }

    // ---- rows -------------------------------------------------------------

    private static void markerRow(SimpleGui g, int guiRow, Item mark, String name, String lore) {
        for (int c = 0; c < 9; c++) {
            int slot = guiRow * 9 + c;
            if (g.getGuiElement(slot) != null) continue; // don't overwrite corner controls
            g.setSlot(slot, new GuiElementBuilder(mark).setName(Component.literal(name))
                    .setLore(List.of(Component.literal(lore))).build());
        }
    }

    private static void editableRow(SimpleGui g, ServerPlayer sp, String[][] grid, int guiRow, int gridRow, boolean curb) {
        for (int c = 0; c < 9; c++) {
            final int col = c;
            g.setSlot(guiRow * 9 + c, cellElement(sp, g, grid, guiRow, gridRow, col, curb));
        }
    }

    private static eu.pb4.sgui.api.elements.GuiElement cellElement(ServerPlayer sp, SimpleGui g, String[][] grid,
                                                                   int guiRow, int gridRow, int col, boolean curb) {
        String id = grid[gridRow][col];
        GuiElementBuilder b;
        if (id == null || id.isBlank()) {
            b = new GuiElementBuilder(AIR_MARK).setName(Component.literal("Air"))
                    .setLore(List.of(
                            Component.literal("Pick a block from your inventory,"),
                            Component.literal("then click here to place it.")));
        } else {
            b = new GuiElementBuilder(itemOf(id)).setName(Component.literal(prettyName(id)))
                    .setLore(List.of(
                            Component.literal(curb ? "Curb cell — stairs face the street." : "Click with a block to replace."),
                            Component.literal("Click with an empty cursor to clear.")));
        }
        return b.setCallback((i, t, a, gg) -> {
            ItemStack carried = sp.containerMenu.getCarried();
            if (carried == null || carried.isEmpty()) {
                grid[gridRow][col] = "";
            } else if (carried.getItem() instanceof BlockItem bi) {
                String bid = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
                if (EXCLUDE.contains(bid)) {
                    sp.sendSystemMessage(Component.literal("[Plots] That block can't be used in a design."));
                    return;
                }
                grid[gridRow][col] = bid;
            } else {
                sp.sendSystemMessage(Component.literal("[Plots] Hold a block on your cursor to place it."));
                return;
            }
            g.setSlot(guiRow * 9 + col, cellElement(sp, g, grid, guiRow, gridRow, col, curb));
        }).build();
    }

    // ---- apply ------------------------------------------------------------

    private static void applyAndSave(ServerPlayer sp, PlotPos anchor, String[][] grid, boolean wall) {
        PlotData d = PlotManager.get(anchor);
        if (d == null) return;
        String serialized = PlotStyle.isEmpty(grid) ? "" : PlotStyle.serialize(grid);
        String before = wall ? d.wallPattern : d.sidewalkPattern;
        // The default prefill (plain tuff sidewalk) isn't a customization — keep it as "no pattern".
        if (!wall && serialized.equals(PlotStyle.serialize(defaultGrid(false)))) serialized = "";
        if (serialized.equals(before)) return; // nothing changed
        if (wall) d.wallPattern = serialized; else d.sidewalkPattern = serialized;
        PlotStyle.invalidateCache(d);
        PlotManager.save();
        ServerLevel plots = sp.level().getServer().getLevel(FabricPlots.PLOTS_DIM);
        if (plots != null) {
            int n = wall ? PlotStyle.applyWall(plots, d) : PlotStyle.applySidewalk(plots, d);
            sp.sendSystemMessage(Component.literal("[Plots] " + (wall ? "Wall" : "Sidewalk") + " design applied ("
                    + n + " columns)."));
        }
    }

    /** The starting grid when a plot has no saved pattern yet. */
    private static String[][] defaultGrid(boolean wall) {
        int rows = wall ? PlotStyle.WALL_ROWS : PlotStyle.SIDEWALK_ROWS;
        String[][] g = new String[rows][PlotStyle.COLS];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < PlotStyle.COLS; c++)
                g[r][c] = "";
        if (!wall) {
            for (int c = 0; c < PlotStyle.COLS; c++) {
                for (int r = 0; r < PlotConfig.SIDEWALK_DEPTH; r++) g[r][c] = "minecraft:chiseled_tuff_bricks";
                g[PlotStyle.SIDEWALK_ROWS - 1][c] = "minecraft:stone_stairs";
            }
        }
        return g;
    }

    private static Item itemOf(String blockId) {
        try {
            Item it = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId)).asItem();
            return it == Items.AIR ? AIR_MARK : it;
        } catch (Exception e) { return AIR_MARK; }
    }

    private static String prettyName(String id) {
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
