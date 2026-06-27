package com.fabricplots;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The clickable FabricPlots menu system (crossplay via sgui): a hub plus screens for the player's
 * plots, per-plot settings, and trusted/denied member management with player heads + name search.
 * Each screen is a static open(...) method; buttons navigate by calling the next screen's method.
 */
public final class PlotMenus {
    private static final int PER_PAGE = 45; // a 9x6 menu's top 5 rows

    private PlotMenus() {}

    // ---- hub -------------------------------------------------------------

    public static void hub(ServerPlayer sp) {
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x3, sp, false);
        g.setTitle(Component.literal("FabricPlots"));
        g.setSlot(11, btn(Items.GRASS_BLOCK, "My Plots", "Browse and manage the plots you own.", (i, t, a, gg) -> myPlots(sp, 0)));
        g.setSlot(13, btn(Items.DIAMOND_PICKAXE, "Build Editor", "Set, replace, shapes, copy/paste.", (i, t, a, gg) -> PlotEditGui.open(sp)));
        g.setSlot(15, btn(Items.ENDER_PEARL, "Go to Spawn", "Teleport to the plot-world spawn.", (i, t, a, gg) -> {
            sp.teleportTo(plots(sp), PlotsConfig.spawnX + 0.5, PlotsConfig.spawnY, PlotsConfig.spawnZ + 0.5, Set.of(), sp.getYRot(), 0f, false);
            g.close();
        }));
        g.open();
    }

    // ---- list of the player's plots --------------------------------------

    public static void myPlots(ServerPlayer sp, int page) {
        List<PlotData> owned = new ArrayList<>();
        for (PlotData d : PlotManager.allPlots()) if (sp.getUUID().equals(d.owner)) owned.add(d);

        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        g.setTitle(Component.literal("My Plots (" + owned.size() + ")"));
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < owned.size(); i++) {
            PlotData d = owned.get(start + i);
            PlotPos anchor = d.cells.iterator().next();
            String label = d.name.isBlank() ? "Plot " + anchor.px() + ", " + anchor.pz() : d.name;
            List<Component> lore = List.of(
                    Component.literal("Cells: " + d.cells.size() + (d.isMerged() ? " (merged)" : "")),
                    Component.literal("Trusted: " + d.trusted.size() + " · Denied: " + d.denied.size()),
                    Component.literal("Click to manage"));
            g.setSlot(i, new GuiElementBuilder(Items.FILLED_MAP).setName(Component.literal(label)).setLore(lore)
                    .setCallback((x, t, a, gg) -> settings(sp, anchor)).build());
        }
        nav(g, page, start + PER_PAGE < owned.size(), p -> myPlots(sp, p), () -> hub(sp));
        if (owned.isEmpty()) g.setSlot(22, info(Items.BARRIER, "No plots yet", "Walk into an empty plot and /plot claim."));
        g.open();
    }

    // ---- one plot's settings ---------------------------------------------

    public static void settings(ServerPlayer sp, PlotPos anchor) {
        PlotData d = PlotManager.get(anchor);
        if (d == null || (!d.owner.equals(sp.getUUID()) && !PlotProtection.isAdmin(sp))) { sp.closeContainer(); return; }
        String title = d.name.isBlank() ? "Plot " + anchor.px() + ", " + anchor.pz() : d.name;
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x3, sp, false);
        g.setTitle(Component.literal(title));
        g.setSlot(10, btn(Items.NAME_TAG, "Rename plot", "Currently: " + (d.name.isBlank() ? "(unnamed)" : d.name), (i, t, a, gg) ->
                anvil(sp, "Plot name", d.name, txt -> { d.name = txt; PlotManager.save(); settings(sp, anchor); })));
        g.setSlot(12, btn(Items.PLAYER_HEAD, "Trusted (" + d.trusted.size() + ")", "People who can build here.", (i, t, a, gg) -> members(sp, anchor, false, 0)));
        g.setSlot(13, btn(Items.IRON_BARS, "Denied (" + d.denied.size() + ")", "People banned from this plot.", (i, t, a, gg) -> members(sp, anchor, true, 0)));
        g.setSlot(14, btn(Items.ENDER_PEARL, "Teleport here", "Go to this plot.", (i, t, a, gg) -> {
            int[] xz = PlotManager.homeXZ(anchor);
            sp.teleportTo(plots(sp), xz[0] + 0.5, PlotConfig.FLOOR_Y, xz[1] + 0.5, Set.of(), sp.getYRot(), 0f, false);
            g.close();
        }));
        g.setSlot(16, btn(Items.TNT, "Clear plot", "Reset every block to flat ground.", (i, t, a, gg) -> confirmClear(sp, anchor)));
        g.setSlot(22, btn(Items.ARROW, "Back", "", (i, t, a, gg) -> myPlots(sp, 0)));
        g.open();
    }

    private static void confirmClear(ServerPlayer sp, PlotPos anchor) {
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x3, sp, false);
        g.setTitle(Component.literal("Clear this plot?"));
        g.setSlot(11, btn(Blocks.CONCRETE.lime().asItem(), "Yes, clear it", "Wipes the whole plot to flat ground.", (i, t, a, gg) -> {
            PlotData d = PlotManager.get(anchor);
            if (d != null) { PlotWorldPainter.clearPlot(plots(sp), d); sp.sendSystemMessage(Component.literal("[Plots] Plot cleared.")); }
            settings(sp, anchor);
        }));
        g.setSlot(15, btn(Blocks.CONCRETE.red().asItem(), "No, go back", "", (i, t, a, gg) -> settings(sp, anchor)));
        g.open();
    }

    // ---- trusted / denied member lists -----------------------------------

    public static void members(ServerPlayer sp, PlotPos anchor, boolean deny, int page) {
        PlotData d = PlotManager.get(anchor);
        if (d == null) { sp.closeContainer(); return; }
        List<UUID> ids = new ArrayList<>(deny ? d.denied : d.trusted);
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        g.setTitle(Component.literal((deny ? "Denied" : "Trusted") + " players (" + ids.size() + ")"));
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < ids.size(); i++) {
            UUID id = ids.get(start + i);
            g.setSlot(i, new GuiElementBuilder(Items.PLAYER_HEAD).setProfile(id)
                    .setName(Component.literal(nameOf(sp, id)))
                    .setLore(List.of(Component.literal("Click to remove")))
                    .setCallback((x, t, a, gg) -> { (deny ? d.denied : d.trusted).remove(id); PlotManager.save(); members(sp, anchor, deny, page); }).build());
        }
        nav(g, page, start + PER_PAGE < ids.size(), p -> members(sp, anchor, deny, p), () -> settings(sp, anchor));
        g.setSlot(50, btn(Items.EMERALD, "Add player", "Pick from online players or search a name.", (i, t, a, gg) -> picker(sp, anchor, deny, 0)));
        g.open();
    }

    // ---- player picker (online heads + name search) ----------------------

    public static void picker(ServerPlayer sp, PlotPos anchor, boolean deny, int page) {
        PlotData d = PlotManager.get(anchor);
        if (d == null) { sp.closeContainer(); return; }
        Set<UUID> already = deny ? d.denied : d.trusted;
        List<ServerPlayer> online = new ArrayList<>();
        for (ServerPlayer pl : sp.level().getServer().getPlayerList().getPlayers())
            if (!pl.getUUID().equals(d.owner) && !already.contains(pl.getUUID())) online.add(pl);

        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        g.setTitle(Component.literal("Add " + (deny ? "denied" : "trusted") + " — pick a player"));
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < online.size(); i++) {
            ServerPlayer pl = online.get(start + i);
            g.setSlot(i, new GuiElementBuilder(Items.PLAYER_HEAD).setProfile(pl.getUUID())
                    .setName(Component.literal(pl.getName().getString()))
                    .setLore(List.of(Component.literal("Click to add")))
                    .setCallback((x, t, a, gg) -> { addMember(d, pl.getUUID(), deny); PlotManager.save(); members(sp, anchor, deny, 0); }).build());
        }
        nav(g, page, start + PER_PAGE < online.size(), p -> picker(sp, anchor, deny, p), () -> members(sp, anchor, deny, 0));
        g.setSlot(50, btn(Items.OAK_SIGN, "Search by name", "Type an online player's name.", (i, t, a, gg) ->
                anvil(sp, "Player name", "", name -> {
                    UUID id = resolve(sp.level().getServer(), name);
                    if (id == null) { sp.sendSystemMessage(Component.literal("[Plots] No online player named \"" + name + "\".")); members(sp, anchor, deny, 0); return; }
                    addMember(d, id, deny); PlotManager.save(); members(sp, anchor, deny, 0);
                })));
        if (online.isEmpty()) g.setSlot(22, info(Items.BARRIER, "No online players", "Use Search by name instead."));
        g.open();
    }

    private static void addMember(PlotData d, UUID id, boolean deny) {
        if (id.equals(d.owner)) return;
        if (deny) { d.denied.add(id); d.trusted.remove(id); }
        else { d.trusted.add(id); d.denied.remove(id); }
    }

    private static UUID resolve(MinecraftServer server, String name) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p == null ? null : p.getUUID();
    }

    // ---- helpers ---------------------------------------------------------

    private static void anvil(ServerPlayer sp, String title, String def, Consumer<String> onConfirm) {
        AnvilInputGui a = new AnvilInputGui(sp, false);
        a.setTitle(Component.literal(title));
        a.setDefaultInputValue(def == null ? "" : def);
        a.setSlot(2, new GuiElementBuilder(Items.NAME_TAG).setName(Component.literal("Confirm"))
                .setCallback((i, t, act, g) -> { String txt = a.getInput(); if (txt != null && !txt.isBlank()) onConfirm.accept(txt.trim()); }).build());
        a.open();
    }

    /** Bottom-row navigation: prev / back / next at slots 45 / 49 / 53. */
    private static void nav(SimpleGui g, int page, boolean hasNext, Consumer<Integer> goPage, Runnable back) {
        if (page > 0) g.setSlot(45, btn(Items.ARROW, "Previous page", "", (i, t, a, gg) -> goPage.accept(page - 1)));
        g.setSlot(49, btn(Items.BARRIER, "Back", "", (i, t, a, gg) -> back.run()));
        if (hasNext) g.setSlot(53, btn(Items.ARROW, "Next page", "", (i, t, a, gg) -> goPage.accept(page + 1)));
    }

    private static ServerLevel plots(ServerPlayer sp) {
        return sp.level().getServer().getLevel(FabricPlots.PLOTS_DIM);
    }

    private static String nameOf(ServerPlayer ctx, UUID id) {
        if (PlotManager.isServer(id)) return "Server";
        ServerPlayer p = ctx.level().getServer().getPlayerList().getPlayer(id);
        return p != null ? p.getName().getString() : id.toString().substring(0, 8);
    }

    private static GuiElement btn(Item icon, String name, String lore, GuiElement.ClickCallback cb) {
        GuiElementBuilder b = new GuiElementBuilder(icon).setName(Component.literal(name));
        if (lore != null && !lore.isBlank()) b.setLore(List.of(Component.literal(lore)));
        return b.setCallback(cb).build();
    }

    private static GuiElement info(Item icon, String name, String lore) {
        return new GuiElementBuilder(icon).setName(Component.literal(name)).setLore(List.of(Component.literal(lore))).build();
    }
}
