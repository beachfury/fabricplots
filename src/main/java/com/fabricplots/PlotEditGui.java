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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The /plot edit menu — a server-rendered chest GUI (works on vanilla + Bedrock via Geyser) that
 * fronts the {@link PlotEdit} operations. The material for fill/shape ops is whatever block the
 * player is holding. A per-player "amount" knob drives sphere/cylinder radius and stack/move count.
 */
public final class PlotEditGui {
    private static final Map<UUID, Integer> AMOUNT = new HashMap<>();

    private PlotEditGui() {}

    private static int amount(UUID id) { return AMOUNT.getOrDefault(id, 5); }

    public static void open(ServerPlayer sp) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, sp, false);
        gui.setTitle(Component.literal("Plot Editor"));
        render(gui, sp);
        gui.open();
    }

    private static void render(SimpleGui gui, ServerPlayer sp) {
        final UUID id = sp.getUUID();
        final ServerLevel level = (ServerLevel) sp.level();
        final int amt = amount(id);

        // Row 1 — selection + clipboard.
        gui.setSlot(0, btn(Items.WOODEN_AXE, "Set corner 1 (here)", (i, t, a, g) -> PlotEdit.setPos1(sp)));
        gui.setSlot(1, btn(Items.WOODEN_AXE, "Set corner 2 (here)", (i, t, a, g) -> PlotEdit.setPos2(sp)));
        gui.setSlot(3, btn(Items.PAPER, "Copy selection", (i, t, a, g) -> PlotEdit.copy(sp, level)));
        gui.setSlot(4, btn(Items.SHEARS, "Cut selection", (i, t, a, g) -> PlotEdit.cut(sp, level)));
        gui.setSlot(5, btn(Items.SLIME_BALL, "Paste here", (i, t, a, g) -> PlotEdit.paste(sp, level)));
        gui.setSlot(7, btn(Items.CLOCK, "Undo last edit", (i, t, a, g) -> PlotEdit.undo(sp, level)));
        gui.setSlot(8, btn(Items.COMPASS, "Redo", (i, t, a, g) -> PlotEdit.redo(sp, level)));

        // Row 2 — fill + the Shapes screen (held block = material).
        gui.setSlot(9, btn(Items.STONE, "Fill selection (held block)", (i, t, a, g) -> withBlock(sp, bs -> PlotEdit.set(sp, level, bs))));
        gui.setSlot(10, btn(Items.BRICKS, "Walls around selection", (i, t, a, g) -> withBlock(sp, bs -> PlotEdit.walls(sp, level, bs))));
        gui.setSlot(12, new GuiElementBuilder(Items.SNOWBALL)
                .setName(Component.literal("Shapes…"))
                .addLoreLine(Component.literal("Circle, square, sphere, cylinder, pyramid, line"))
                .setCallback((i, t, a, g) -> PlotShapesGui.open(sp)).build());
        gui.setSlot(14, textureToggle(sp, () -> render(gui, sp)));

        // Row 3 — amount knob + transforms.
        gui.setSlot(18, btn(Items.REDSTONE, "Amount −1", (i, t, a, g) -> { AMOUNT.put(id, Math.max(1, amount(id) - 1)); render(gui, sp); }));
        gui.setSlot(19, new GuiElementBuilder(Items.PAPER).setName(Component.literal("Amount: " + amt)).setCount(Math.max(1, Math.min(64, amt))).build());
        gui.setSlot(20, btn(Items.EMERALD, "Amount +1", (i, t, a, g) -> { AMOUNT.put(id, Math.min(32, amount(id) + 1)); render(gui, sp); }));
        gui.setSlot(22, btn(Items.REPEATER, "Stack ×" + amt + " (facing)", (i, t, a, g) -> PlotEdit.stack(sp, level, amount(id))));
        gui.setSlot(23, btn(Items.PISTON, "Move " + amt + " (facing)", (i, t, a, g) -> PlotEdit.move(sp, level, amount(id))));

        // Held-block indicator.
        BlockState held = PlotEdit.heldBlock(sp);
        gui.setSlot(26, new GuiElementBuilder(held != null ? sp.getMainHandItem().getItem() : Items.BARRIER)
                .setName(Component.literal(held != null ? "Placing: the block in your hand" : "Hold a block to place")).build());
    }

    private static void withBlock(ServerPlayer sp, Consumer<BlockState> op) {
        BlockState bs = PlotEdit.heldBlock(sp);
        if (bs == null) { sp.sendSystemMessage(Component.literal("[Plots] Hold a block to place it.")); return; }
        op.accept(bs);
    }

    private static GuiElement btn(Item icon, String name, GuiElement.ClickCallback cb) {
        return new GuiElementBuilder(icon).setName(Component.literal(name)).setCallback(cb).build();
    }

    /**
     * The random-texture toggle, shared by the editor and Shapes screens: lime dye + "ON" when
     * active, gray dye + "OFF" when not, with the lore saying what a click does.
     */
    static GuiElement textureToggle(ServerPlayer sp, Runnable rerender) {
        boolean on = PlotEdit.isRandomTexture(sp);
        return new GuiElementBuilder(byRegistryId(on ? "minecraft:lime_dye" : "minecraft:gray_dye"))
                .setName(Component.literal("Random texture: " + (on ? "ON" : "OFF")))
                .addLoreLine(Component.literal(on
                        ? "Edits mix every BLOCK in your hotbar (1-9)"
                        : "Edits use only the block in your hand"))
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
