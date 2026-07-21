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
import java.util.function.Consumer;

/**
 * The /plot edit home screen — the hub of the build tools (sgui chest GUI; vanilla + Bedrock via
 * Geyser). Quick bar for the everyday actions, plus doors into the Shapes and Measure screens.
 * Material for every build op is the block in the player's hand. Layout:
 *   Row 1  corners · copy/cut/paste · undo/redo
 *   Row 2  fill/walls · stack/move + amount knob
 *   Row 3  Shapes… / Measure… · find center · random texture
 *   Row 4  held-block indicator
 */
public final class PlotEditGui {
    private static final Map<UUID, Integer> AMOUNT = new HashMap<>();

    private PlotEditGui() {}

    private static int amount(UUID id) { return AMOUNT.getOrDefault(id, 5); }

    public static void open(ServerPlayer sp) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x4, sp, false);
        gui.setTitle(Component.literal("Plot Editor"));
        render(gui, sp);
        gui.open();
    }

    private static void render(SimpleGui gui, ServerPlayer sp) {
        final UUID id = sp.getUUID();
        final ServerLevel level = (ServerLevel) sp.level();
        final int amt = amount(id);
        for (int i = 0; i < 36; i++) gui.setSlot(i, filler());

        // Row 1 — selection, clipboard, safety.
        gui.setSlot(0, cornerBtn(sp, true));
        gui.setSlot(1, cornerBtn(sp, false));
        gui.setSlot(3, btn(Items.PAPER, "Copy selection", (i, t, a, g) -> PlotEdit.copy(sp, level)));
        gui.setSlot(4, btn(Items.SHEARS, "Cut selection", (i, t, a, g) -> PlotEdit.cut(sp, level)));
        gui.setSlot(5, btn(Items.SLIME_BALL, "Paste here", (i, t, a, g) -> PlotEdit.paste(sp, level)));
        gui.setSlot(7, btn(Items.CLOCK, "Undo last edit", (i, t, a, g) -> PlotEdit.undo(sp, level)));
        gui.setSlot(8, btn(Items.COMPASS, "Redo", (i, t, a, g) -> PlotEdit.redo(sp, level)));

        // Row 2 — direct build ops + transforms with their amount knob.
        gui.setSlot(9, btn(Items.STONE, "Fill selection (held block)", (i, t, a, g) -> withBlock(sp, bs -> PlotEdit.set(sp, level, bs))));
        gui.setSlot(10, btn(Items.BRICKS, "Walls around selection", (i, t, a, g) -> withBlock(sp, bs -> PlotEdit.walls(sp, level, bs))));
        gui.setSlot(12, btn(Items.REPEATER, "Stack ×" + amt + " (facing)", (i, t, a, g) -> PlotEdit.stack(sp, level, amount(id))));
        gui.setSlot(13, btn(Items.PISTON, "Move " + amt + " (facing)", (i, t, a, g) -> PlotEdit.move(sp, level, amount(id))));
        gui.setSlot(15, btn(Items.REDSTONE, "Amount −1", (i, t, a, g) -> { AMOUNT.put(id, Math.max(1, amount(id) - 1)); render(gui, sp); }));
        gui.setSlot(16, new GuiElementBuilder(Items.PAPER).setName(Component.literal("Amount: " + amt))
                .addLoreLine(Component.literal("Used by Stack and Move"))
                .setCount(Math.max(1, Math.min(64, amt))).build());
        gui.setSlot(17, btn(Items.EMERALD, "Amount +1", (i, t, a, g) -> { AMOUNT.put(id, Math.min(64, amount(id) + 1)); render(gui, sp); }));

        // Row 3 — the sub-screens + everyday quick buttons.
        gui.setSlot(18, new GuiElementBuilder(Items.SNOWBALL)
                .setName(Component.literal("Shapes…"))
                .addLoreLine(Component.literal("Circle, square, sphere, cylinder, pyramid, line"))
                .setCallback((i, t, a, g) -> PlotShapesGui.open(sp)).build());
        gui.setSlot(20, new GuiElementBuilder(Items.OAK_SIGN)
                .setName(Component.literal("Measure…"))
                .addLoreLine(Component.literal("Selection size, find center, measuring tape"))
                .setCallback((i, t, a, g) -> PlotMeasureGui.open(sp)).build());
        gui.setSlot(23, btn(Items.GOLD_INGOT, "Find center of line",
                (i, t, a, g) -> PlotEdit.findLineCenter(sp, level)));
        gui.setSlot(25, textureToggle(sp, () -> render(gui, sp)));

        // Row 4 — held-block indicator.
        gui.setSlot(35, heldIndicator(sp));
    }

    // ---- shared bits for all editor screens --------------------------------

    static GuiElement cornerBtn(ServerPlayer sp, boolean first) {
        return btn(Items.WOODEN_AXE, "Set corner " + (first ? 1 : 2) + " (here)",
                (i, t, a, g) -> { if (first) PlotEdit.setPos1(sp); else PlotEdit.setPos2(sp); });
    }

    static GuiElement heldIndicator(ServerPlayer sp) {
        BlockState held = PlotEdit.heldBlock(sp);
        return new GuiElementBuilder(held != null ? sp.getMainHandItem().getItem() : Items.BARRIER)
                .setName(Component.literal(held != null ? "Placing: the block in your hand" : "Hold a block to place")).build();
    }

    static GuiElement filler() {
        return new GuiElementBuilder(byRegistryId("minecraft:gray_stained_glass_pane"))
                .setName(Component.literal(" ")).build();
    }

    static void withBlock(ServerPlayer sp, Consumer<BlockState> op) {
        BlockState bs = PlotEdit.heldBlock(sp);
        if (bs == null) { sp.sendSystemMessage(Component.literal("[Plots] Hold a block to place it.")); return; }
        op.accept(bs);
    }

    static GuiElement btn(Item icon, String name, GuiElement.ClickCallback cb) {
        return new GuiElementBuilder(icon).setName(Component.literal(name)).setCallback(cb).build();
    }

    /**
     * The random-texture toggle, shared by all editor screens: lime dye + "ON" when active,
     * gray dye + "OFF" when not, with the lore saying what a click does.
     */
    static GuiElement textureToggle(ServerPlayer sp, Runnable rerender) {
        boolean on = PlotEdit.isRandomTexture(sp);
        return new GuiElementBuilder(byRegistryId(on ? "minecraft:lime_dye" : "minecraft:gray_dye"))
                .setName(Component.literal("Random texture: " + (on ? "ON" : "OFF")))
                .addLoreLine(Component.literal(on
                        ? "Edits AND hand-placing mix every BLOCK in your hotbar"
                        : "Edits and hand-placing use only the held block"))
                .addLoreLine(Component.literal("Duplicate hotbar slots make that block more common"))
                .addLoreLine(Component.literal(on ? "Click to turn OFF" : "Click to turn ON"))
                .setCallback((i, t, a, g) -> { PlotEdit.toggleRandomTexture(sp); rerender.run(); }).build();
    }

    // Registry-ID lookup so colored items work on both the 26.1.2 and 26.2 branches
    // (26.2 moved the colored-item constants into ColorCollection; registry ids didn't change).
    private static Item byRegistryId(String id) {
        try {
            Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getValue(net.minecraft.resources.Identifier.parse(id));
            return (it == null || it == Items.AIR) ? Items.PAINTING : it;
        } catch (Exception e) { return Items.PAINTING; }
    }
}
