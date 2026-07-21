package com.fabricplots;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;

/**
 * The Measure screen of /plot edit — everything measuring in one place: a live readout of the
 * corner 1 → corner 2 selection size, the gold find-center marker, and the yellow/black
 * measuring tape with numbered signs. Nothing here costs blocks permanently — the marker and
 * tape are self-cleaning.
 */
public final class PlotMeasureGui {

    private PlotMeasureGui() {}

    public static void open(ServerPlayer sp) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, sp, false);
        gui.setTitle(Component.literal("Plot Editor — Measure"));
        render(gui, sp);
        gui.open();
    }

    private static void render(SimpleGui gui, ServerPlayer sp) {
        final ServerLevel level = (ServerLevel) sp.level();
        for (int i = 0; i < 27; i++) gui.setSlot(i, PlotEditGui.filler());

        // Row 1 — corners + live selection readout.
        gui.setSlot(0, PlotEditGui.btn(Items.WOODEN_AXE, "Set corner 1 (here)",
                (i, t, a, g) -> { PlotEdit.setPos1(sp); render(gui, sp); }));
        gui.setSlot(1, PlotEditGui.btn(Items.WOODEN_AXE, "Set corner 2 (here)",
                (i, t, a, g) -> { PlotEdit.setPos2(sp); render(gui, sp); }));
        String info = PlotEdit.selectionInfo(sp);
        gui.setSlot(4, new GuiElementBuilder(info != null ? Items.MAP : Items.PAPER)
                .setName(Component.literal(info != null ? "Selection: " + info : "No selection yet"))
                .addLoreLine(Component.literal(info != null
                        ? "Width x Height x Length between your corners"
                        : "Set corner 1 and corner 2 to see its size"))
                .build());

        // Row 2 — the measuring tools.
        gui.setSlot(9, new GuiElementBuilder(Items.GOLD_INGOT)
                .setName(Component.literal("Find center of line"))
                .addLoreLine(Component.literal("Marks the middle of corner 1 → corner 2 with gold"))
                .addLoreLine(Component.literal("Odd length = 1 block, even = the middle 2"))
                .addLoreLine(Component.literal("Doubles as the shape center — build right on it"))
                .setCallback((i, t, a, g) -> PlotEdit.findLineCenter(sp, level)).build());
        gui.setSlot(12, new GuiElementBuilder(Items.OAK_SIGN)
                .setName(Component.literal("Measuring tape"))
                .addLoreLine(Component.literal("Yellow/black stripes corner 1 → corner 2 (straight runs)"))
                .addLoreLine(Component.literal("Counts from 1; numbered signs every 2, 5, or 10"))
                .setCallback((i, t, a, g) -> PlotEdit.tape(sp, level)).build());
        gui.setSlot(14, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Clear measuring tape"))
                .addLoreLine(Component.literal("Puts back exactly what the tape covered"))
                .setCallback((i, t, a, g) -> PlotEdit.clearTape(sp, level)).build());

        // Row 3 — back.
        gui.setSlot(18, PlotEditGui.btn(Items.ARROW, "Back to editor", (i, t, a, g) -> PlotEditGui.open(sp)));
    }
}
