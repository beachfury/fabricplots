package com.fabricplots;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Shapes screen of /plot edit — pick a shape, dial in its parameters, drop the gold center
 * marker at your feet, and build with the block in your hand. Every build goes through
 * {@link PlotEdit#commit} so it is plot-jailed and undoable like any other edit.
 */
public final class PlotShapesGui {

    /** Per-player shape parameters (session-only). */
    private static final class Params {
        PlotEdit.Shape shape = PlotEdit.Shape.CIRCLE;
        boolean hollow = false;
        int size = 7;       // diameter / side length
        int height = 1;     // extrusion for circle/square, height for cylinder
        int thickness = 1;  // hollow shell / line beam thickness
        int repeat = 1;     // vertical copies
        int spacing = 0;    // air gap between copies
    }

    private static final Map<UUID, Params> PARAMS = new HashMap<>();

    private PlotShapesGui() {}

    private static Params params(UUID id) { return PARAMS.computeIfAbsent(id, k -> new Params()); }

    public static void open(ServerPlayer sp) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        gui.setTitle(Component.literal("Plot Editor — Shapes"));
        render(gui, sp);
        gui.open();
    }

    private static void render(SimpleGui gui, ServerPlayer sp) {
        final UUID id = sp.getUUID();
        final ServerLevel level = (ServerLevel) sp.level();
        final Params p = params(id);
        for (int i = 0; i < 54; i++) gui.setSlot(i, PlotEditGui.filler());

        // Row 1 — pick a shape.
        shapeBtn(gui, sp, 0, Items.SNOWBALL, PlotEdit.Shape.CIRCLE, "Circle", "Flat disc — hollow = ring");
        shapeBtn(gui, sp, 1, Items.SMOOTH_STONE, PlotEdit.Shape.SQUARE, "Square", "Flat square — hollow = frame");
        shapeBtn(gui, sp, 2, Items.ENDER_PEARL, PlotEdit.Shape.SPHERE, "Sphere", "Full ball — hollow = shell");
        shapeBtn(gui, sp, 3, Items.BAMBOO, PlotEdit.Shape.CYLINDER, "Cylinder", "Round tower — hollow = tube");
        shapeBtn(gui, sp, 4, Items.SANDSTONE, PlotEdit.Shape.PYRAMID, "Pyramid", "Steps in by 1 each layer — hollow = frame layers");
        shapeBtn(gui, sp, 5, Items.BLAZE_ROD, PlotEdit.Shape.LINE, "Line", "Corner 1 → corner 2, any diagonal. Thickness = beam");

        // Style toggle.
        gui.setSlot(7, new GuiElementBuilder(p.hollow ? Items.GLASS : Items.STONE_BRICKS)
                .setName(Component.literal("Style: " + (p.hollow ? "Hollow" : "Filled")))
                .addLoreLine(Component.literal("Click to switch. Hollow = ring / frame / shell / tube"))
                .setCallback((i, t, a, g) -> { p.hollow = !p.hollow; render(gui, sp); }).build());

        // Row 2 — parameters. Left-click +1, right-click −1, shift = ±5.
        param(gui, sp, 10, Items.PAPER, "Size (width)", p.size,
                v -> p.size = clamp(v, 1, 256), () -> p.size, "Diameter / side length");
        param(gui, sp, 11, Items.LADDER, "Height", p.height,
                v -> p.height = clamp(v, 1, 128), () -> p.height, "Layers up (circle/square/cylinder)");
        param(gui, sp, 12, Items.BRICKS, "Thickness", p.thickness,
                v -> p.thickness = clamp(v, 1, 8), () -> p.thickness, "Hollow wall / line beam thickness");
        param(gui, sp, 14, Items.REPEATER, "Repeat", p.repeat,
                v -> p.repeat = clamp(v, 1, 8), () -> p.repeat, "Vertical copies of the shape");
        param(gui, sp, 15, Items.FEATHER, "Spacing", p.spacing,
                v -> p.spacing = clamp(v, 0, 16), () -> p.spacing, "Air gap between repeated copies");

        // Row 4 — actions.
        gui.setSlot(28, new GuiElementBuilder(Items.GOLD_BLOCK)
                .setName(Component.literal("Set center (at your feet)"))
                .addLoreLine(Component.literal("Odd size = 1 gold block, even size = 2x2"))
                .addLoreLine(Component.literal("The marker restores the ground when you build"))
                .setCallback((i, t, a, g) -> PlotEdit.setShapeCenter(sp, level, p.size % 2 == 0)).build());
        gui.setSlot(29, btn(Items.BARRIER, "Clear center marker",
                (i, t, a, g) -> PlotEdit.clearShapeMarker(sp, level)));
        gui.setSlot(31, new GuiElementBuilder(Items.EMERALD_BLOCK)
                .setName(Component.literal("Build " + (p.hollow ? "hollow " : "") + p.shape.name().toLowerCase()))
                .addLoreLine(Component.literal(p.shape == PlotEdit.Shape.LINE
                        ? "Draws corner 1 → corner 2 with the held block"
                        : "Builds on the gold marker (or your feet) with the held block"))
                .setCallback((i, t, a, g) -> withBlock(sp, bs ->
                        PlotEdit.buildShape(sp, level, bs, p.shape, p.hollow, p.size, p.height, p.thickness, p.repeat, p.spacing)))
                .build());
        gui.setSlot(33, PlotEditGui.textureToggle(sp, () -> render(gui, sp)));
        gui.setSlot(35, btn(Items.CLOCK, "Undo last edit", (i, t, a, g) -> PlotEdit.undo(sp, level)));

        // Row 6 — back, corners (for the line tool), held-block indicator.
        gui.setSlot(45, btn(Items.ARROW, "Back to editor", (i, t, a, g) -> PlotEditGui.open(sp)));
        gui.setSlot(47, PlotEditGui.cornerBtn(sp, true));
        gui.setSlot(48, PlotEditGui.cornerBtn(sp, false));
        gui.setSlot(51, PlotEditGui.heldIndicator(sp));
    }

    private static void shapeBtn(SimpleGui gui, ServerPlayer sp, int slot, Item icon,
                                 PlotEdit.Shape shape, String name, String lore) {
        Params p = params(sp.getUUID());
        boolean sel = p.shape == shape;
        gui.setSlot(slot, new GuiElementBuilder(icon)
                .setName(Component.literal((sel ? "▶ " : "") + name + (sel ? " (selected)" : "")))
                .addLoreLine(Component.literal(lore))
                .setCallback((i, t, a, g) -> { p.shape = shape; render(gui, sp); }).build());
    }

    private interface IntSetter { void set(int v); }
    private interface IntGetter { int get(); }

    private static void param(SimpleGui gui, ServerPlayer sp, int slot, Item icon, String name,
                              int value, IntSetter set, IntGetter get, String lore) {
        gui.setSlot(slot, new GuiElementBuilder(icon)
                .setName(Component.literal(name + ": " + value))
                .setCount(Math.max(1, Math.min(64, value)))
                .addLoreLine(Component.literal(lore))
                .addLoreLine(Component.literal("Left-click +1 · Right-click −1 · Shift = ±10"))
                .setCallback((i, t, a, g) -> {
                    int step = t.shift ? 10 : 1;
                    set.set(get.get() + (t.isRight ? -step : step));
                    render(gui, sp);
                }).build());
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void withBlock(ServerPlayer sp, java.util.function.Consumer<BlockState> op) {
        BlockState bs = PlotEdit.heldBlock(sp);
        if (bs == null) { sp.sendSystemMessage(Component.literal("[Plots] Hold a block to place it.")); return; }
        op.accept(bs);
    }

    private static GuiElement btn(Item icon, String name, GuiElement.ClickCallback cb) {
        return new GuiElementBuilder(icon).setName(Component.literal(name)).setCallback(cb).build();
    }
}
