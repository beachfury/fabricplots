package com.fabricplots.gui;

import com.fabricplots.compat.Compat;
import com.fabricplots.edit.PlotBrush;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Brushes screen — configures the paint-brush stick in the player's hand. Every change is
 * written straight onto the stick (each stick is its own brush). Palette and mask slots use the
 * vanilla cursor: pick a block up from your inventory, click it into a slot; click with an empty
 * cursor to clear.
 */
public final class PlotBrushGui {

    private PlotBrushGui() {}

    public static void open(ServerPlayer sp) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        gui.setTitle(Component.literal("Plot Editor — Brushes"));
        render(gui, sp);
        gui.open();
    }

    private static void render(SimpleGui gui, ServerPlayer sp) {
        for (int i = 0; i < 54; i++) gui.setSlot(i, PlotEditGui.filler());
        ItemStack stick = heldBrush(sp);

        if (stick == null) {
            gui.setSlot(22, new GuiElementBuilder(Items.BRUSH)
                    .setName(Component.literal("Get a paint brush"))
                    .addLoreLine(Component.literal("Hold the brush, then sneak + right-click to configure it"))
                    .addLoreLine(Component.literal("Each stick remembers its own settings — keep several!"))
                    .setCallback((i, t, a, g) -> {
                        sp.getInventory().placeItemBackInInventory(PlotBrush.createBrush());
                        sp.sendSystemMessage(Component.literal("[Plots] Brush added to your inventory."));
                        render(gui, sp);
                    }).build());
            gui.setSlot(45, PlotEditGui.btn(Items.ARROW, "Back to editor", (i, t, a, g) -> PlotEditGui.open(sp)));
            return;
        }
        PlotBrush.Config c = PlotBrush.read(stick);

        // Row 1 — brush type + surface toggle.
        typeBtn(gui, sp, stick, 0, Items.GRAVEL, PlotBrush.Type.SPLATTER, "Splatter", "Scattered random blocks — the path maker");
        typeBtn(gui, sp, stick, 1, Items.SNOWBALL, PlotBrush.Type.ROUND, "Round", "Solid stamp — disc on surface, ball in air mode");
        typeBtn(gui, sp, stick, 2, Items.GRASS_BLOCK, PlotBrush.Type.OVERLAY, "Overlay", "Repaints the exposed surface only (always surface mode)");
        typeBtn(gui, sp, stick, 3, Items.SUGAR, PlotBrush.Type.SPRAY, "Spray", "Very sparse dusting — flowers, ore flecks");
        typeBtn(gui, sp, stick, 4, Items.SPONGE, PlotBrush.Type.ERASE, "Erase", "Surface mode restores the ground; ball mode clears to air");
        typeBtn(gui, sp, stick, 5, Items.STONE_BRICKS, PlotBrush.Type.WALL, "Wall", "Textures the vertical face you aim at — mix up flat walls");
        gui.setSlot(7, new GuiElementBuilder(c.surface ? Items.GRASS_BLOCK : Items.ENDER_PEARL)
                .setName(Component.literal("Mode: " + (c.surface ? "Surface" : "Ball")))
                .addLoreLine(Component.literal(c.surface
                        ? "Paints the top solid block of each column (paths hug terrain)"
                        : "Paints the full ball where you aim (blobs, leaves, veins)"))
                .addLoreLine(Component.literal("Click to switch"))
                .setCallback((i, t, a, g) -> { c.surface = !c.surface; save(sp, stick, c); render(gui, sp); }).build());

        // Row 2 — dials + mask.
        gui.setSlot(9, new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal("Size (radius): " + c.size)).setCount(Math.max(1, c.size))
                .addLoreLine(Component.literal("Left-click +1 · Right-click −1"))
                .setCallback((i, t, a, g) -> { c.size = clamp(c.size + (t.isRight ? -1 : 1), 1, 15); save(sp, stick, c); render(gui, sp); }).build());
        gui.setSlot(10, new GuiElementBuilder(Items.REDSTONE)
                .setName(Component.literal("Density: " + c.density + "%")).setCount(Math.max(1, Math.min(64, c.density)))
                .addLoreLine(Component.literal("How much of the stroke area gets painted"))
                .addLoreLine(Component.literal("Left-click +10 · Right-click −10"))
                .setCallback((i, t, a, g) -> { c.density = clamp(c.density + (t.isRight ? -10 : 10), 10, 100); save(sp, stick, c); render(gui, sp); }).build());
        gui.setSlot(11, new GuiElementBuilder(c.fade ? Items.FEATHER : Items.IRON_INGOT)
                .setName(Component.literal("Fade edges: " + (c.fade ? "ON" : "OFF")))
                .addLoreLine(Component.literal("ON = strokes thin out toward the edge (hand-worn look)"))
                .setCallback((i, t, a, g) -> { c.fade = !c.fade; save(sp, stick, c); render(gui, sp); }).build());
        Item maskItem = c.mask.isEmpty() ? Items.BARRIER : Compat.item(c.mask);
        gui.setSlot(14, new GuiElementBuilder(maskItem == Items.AIR ? Items.BARRIER : maskItem)
                .setName(Component.literal(c.mask.isEmpty() ? "Paint over: anything" : "Paint over: only " + pretty(c.mask)))
                .addLoreLine(Component.literal("Click holding a block to only repaint that block"))
                .addLoreLine(Component.literal("(protects walls near your path) · empty cursor clears"))
                .setCallback((i, t, a, g) -> {
                    ItemStack carried = sp.containerMenu.getCarried();
                    if (!carried.isEmpty() && carried.getItem() instanceof BlockItem bi)
                        c.mask = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
                    else c.mask = "";
                    save(sp, stick, c); render(gui, sp);
                }).build());

        // Row 3 — the palette.
        for (int slot = 0; slot < 9; slot++) {
            final int idx = slot;
            String id = idx < c.palette.size() ? c.palette.get(idx) : null;
            Item icon = id != null ? Compat.item(id) : Compat.item("minecraft:light_gray_stained_glass_pane");
            GuiElementBuilder b = new GuiElementBuilder(id != null && icon != Items.AIR ? icon : (id != null ? Items.BARRIER : Compat.item("minecraft:light_gray_stained_glass_pane")))
                    .setName(Component.literal(id != null ? pretty(id) : "Empty palette slot"))
                    .addLoreLine(Component.literal("Click holding a block to set · empty cursor clears"))
                    .addLoreLine(Component.literal("Same block in several slots = more common"));
            gui.setSlot(18 + slot, b.setCallback((i, t, a, g) -> {
                ItemStack carried = sp.containerMenu.getCarried();
                if (!carried.isEmpty() && carried.getItem() instanceof BlockItem bi) {
                    String bid = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
                    while (c.palette.size() <= idx) c.palette.add(bid);
                    c.palette.set(idx, bid);
                } else if (idx < c.palette.size()) {
                    c.palette.remove(idx);
                }
                save(sp, stick, c); render(gui, sp);
            }).build());
        }

        // Row 4 — brush management.
        gui.setSlot(28, new GuiElementBuilder(Items.BRUSH)
                .setName(Component.literal("Get another brush"))
                .addLoreLine(Component.literal("A fresh stick with default settings"))
                .setCallback((i, t, a, g) -> {
                    sp.getInventory().placeItemBackInInventory(PlotBrush.createBrush());
                    sp.sendSystemMessage(Component.literal("[Plots] Brush added to your inventory."));
                }).build());
        gui.setSlot(30, PlotEditGui.btn(Items.BARRIER, "Clear palette",
                (i, t, a, g) -> { c.palette.clear(); save(sp, stick, c); render(gui, sp); }));
        gui.setSlot(34, PlotEditGui.btn(Items.CLOCK, "Undo last stroke",
                (i, t, a, g) -> com.fabricplots.edit.PlotEdit.undo(sp, (net.minecraft.server.level.ServerLevel) sp.level())));

        // Row 6 — back + status.
        gui.setSlot(45, PlotEditGui.btn(Items.ARROW, "Back to editor", (i, t, a, g) -> PlotEditGui.open(sp)));
        gui.setSlot(49, new GuiElementBuilder(Items.BRUSH)
                .setName(Component.literal("Editing: the brush in your hand"))
                .addLoreLine(Component.literal(c.type.name().charAt(0) + c.type.name().substring(1).toLowerCase()
                        + " · size " + c.size + " · " + (c.palette.isEmpty() ? "no blocks yet" : c.palette.size() + " palette entries")))
                .build());
    }

    private static void typeBtn(SimpleGui gui, ServerPlayer sp, ItemStack stick, int slot, Item icon,
                                PlotBrush.Type type, String name, String lore) {
        PlotBrush.Config c = PlotBrush.read(stick);
        boolean sel = c.type == type;
        gui.setSlot(slot, new GuiElementBuilder(icon)
                .setName(Component.literal((sel ? "▶ " : "") + name + (sel ? " (selected)" : "")))
                .addLoreLine(Component.literal(lore))
                .setCallback((i, t, a, g) -> { c.type = type; save(sp, stick, c); render(gui, sp); }).build());
    }

    /** The brush being edited: main hand first, then offhand. */
    private static ItemStack heldBrush(ServerPlayer sp) {
        if (PlotBrush.isBrush(sp.getMainHandItem())) return sp.getMainHandItem();
        if (PlotBrush.isBrush(sp.getOffhandItem())) return sp.getOffhandItem();
        return null;
    }

    private static void save(ServerPlayer sp, ItemStack stick, PlotBrush.Config c) {
        PlotBrush.write(stick, c);
    }

    private static String pretty(String id) {
        String n = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return n.replace('_', ' ');
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
