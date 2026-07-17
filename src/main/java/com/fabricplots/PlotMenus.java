package com.fabricplots;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;

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
        g.setSlot(4, btn(Items.EMERALD, "Claim a Plot",
                "Claim the plot you're standing on, or grab the next free one and warp there.",
                (i, t, a, gg) -> claimAction(sp)));
        g.setSlot(10, btn(Items.GRASS_BLOCK, "My Plots", "Browse and manage the plots you own.", (i, t, a, gg) -> myPlots(sp, 0)));
        g.setSlot(12, btn(Items.DIAMOND_PICKAXE, "Build Editor", "Set, replace, shapes, copy/paste. (Plot world only.)", (i, t, a, gg) -> {
            if (sp.level().dimension() != FabricPlots.PLOTS_DIM) {
                sp.sendSystemMessage(Component.literal("Go to the plot world to use the Build Editor — try Go to Spawn."));
                g.close();
            } else PlotEditGui.open(sp);
        }));
        g.setSlot(14, btn(Items.RECOVERY_COMPASS, "Portal Keys", "A key for each plot you own — usable at your base.", (i, t, a, gg) -> {
            g.close();
            int n = 0;
            for (PlotData d : PlotManager.allPlots()) {
                if (d.owner.equals(sp.getUUID()) && !d.cells.isEmpty()) {
                    sp.addItem(PortalManager.createKey(d.cells.iterator().next()));
                    n++;
                }
            }
            sp.sendSystemMessage(Component.literal(n == 0
                ? "You don't own a plot yet. Use /plot auto to claim one."
                : "Added " + n + " Portal Key" + (n == 1 ? "" : "s") + " — build a calcite frame at your base and right-click with one."));
        }));
        g.setSlot(16, btn(Items.ENDER_PEARL, "Go to Spawn", "Teleport to the plot-world spawn.", (i, t, a, gg) -> {
            sp.teleportTo(plots(sp), PlotsConfig.spawnX + 0.5, PlotsConfig.spawnY, PlotsConfig.spawnZ + 0.5, Set.of(), sp.getYRot(), 0f, false);
            g.close();
        }));
        g.setSlot(20, btn(Items.SPYGLASS, "Browse Plots", "Every claimed plot — visit any of them.", (i, t, a, gg) -> browseAll(sp, 0)));
        g.setSlot(24, btn(Items.NETHER_STAR, "Top Plots", "Browse the most-liked plots and visit them.", (i, t, a, gg) -> topPlots(sp, 0)));
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
        g.setSlot(47, btn(Items.EMERALD, "+ Claim a plot", "Claim where you stand, or the next free plot.", (i, t, a, gg) -> claimAction(sp)));
        if (owned.isEmpty()) g.setSlot(22, info(Items.BARRIER, "No plots yet", "Click '+ Claim a plot' below, or walk onto an empty plot first."));
        g.open();
    }

    /** GUI claim: claim the unclaimed plot you're standing on, else auto-claim the next free plot. Reuses the commands. */
    private static void claimAction(ServerPlayer sp) {
        sp.closeContainer();
        boolean here = sp.level().dimension() == FabricPlots.PLOTS_DIM
                && PlotManager.isInsidePlot(sp.getBlockX(), sp.getBlockZ())
                && !PlotManager.isClaimed(PlotManager.plotAt(sp.getBlockX(), sp.getBlockZ()));
        var server = sp.level().getServer();
        if (server != null) server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), here ? "plot claim" : "plot auto");
    }

    // ---- one plot's settings ---------------------------------------------

    public static void settings(ServerPlayer sp, PlotPos anchor) {
        PlotData d = PlotManager.get(anchor);
        if (d == null || (!d.owner.equals(sp.getUUID()) && !PlotProtection.isAdmin(sp))) { sp.closeContainer(); return; }
        String title = d.name.isBlank() ? "Plot " + anchor.px() + ", " + anchor.pz() : d.name;
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x4, sp, false);
        g.setTitle(Component.literal(title));
        g.setSlot(10, btn(Items.NAME_TAG, "Rename plot", "Currently: " + (d.name.isBlank() ? "(unnamed)" : d.name), (i, t, a, gg) ->
                anvil(sp, "Plot name", d.name, txt -> { d.name = clean(txt); PlotManager.save(); settings(sp, anchor); })));
        g.setSlot(11, btn(floorItem(d), "Floor block", "Currently: " + floorName(d) + ". Click to recolor your plot's ground.",
                (i, t, a, gg) -> floorPicker(sp, anchor, 0)));
        g.setSlot(12, btn(Items.CHISELED_TUFF_BRICKS, "Sidewalk designer",
                "Design your sidewalk from any blocks — the pattern repeats along every edge.",
                (i, t, a, gg) -> PlotDesignerGui.openSidewalk(sp, anchor, () -> settings(sp, anchor))));
        g.setSlot(13, btn(Items.COBBLESTONE_WALL, "Wall designer",
                "Design a wall (up to 3 tall) around your plot's edge.",
                (i, t, a, gg) -> PlotDesignerGui.openWall(sp, anchor, () -> settings(sp, anchor))));
        g.setSlot(14, btn(Items.PLAYER_HEAD, "Trusted (" + d.trusted.size() + ")", "People who can build here.", (i, t, a, gg) -> members(sp, anchor, false, 0)));
        g.setSlot(15, btn(Items.IRON_BARS, "Denied (" + d.denied.size() + ")", "People banned from this plot.", (i, t, a, gg) -> members(sp, anchor, true, 0)));
        g.setSlot(16, btn(Items.ENDER_PEARL, "Teleport here", "Go to this plot.", (i, t, a, gg) -> {
            int[] xz = PlotManager.homeXZ(anchor);
            sp.teleportTo(plots(sp), xz[0] + 0.5, PlotConfig.FLOOR_Y, xz[1] + 0.5, Set.of(), sp.getYRot(), 0f, false);
            g.close();
        }));
        g.setSlot(19, btn(d.pvp ? Items.DIAMOND_SWORD : Items.SHIELD, "PvP: " + (d.pvp ? "ON" : "OFF"),
                d.pvp ? "Players can fight here. Click to make it safe." : "This plot is safe. Click to allow PvP.",
                (i, t, a, gg) -> { d.pvp = !d.pvp; PlotManager.save(); settings(sp, anchor); }));
        g.setSlot(20, btn(Items.CLOCK, "Sky & weather",
                ambienceLabel(d) + " — what visitors see while on your plot.",
                (i, t, a, gg) -> ambiencePicker(sp, anchor)));
        g.setSlot(21, btn(Items.WRITABLE_BOOK, "Greeting",
                d.greeting.isBlank() ? "Set a custom welcome for visitors." : "Currently: \"" + d.greeting + "\"",
                (i, t, a, gg) -> anvil(sp, "Greeting (visitors see this)", d.greeting, txt -> {
                    d.greeting = clean(txt); PlotManager.save(); settings(sp, anchor);
                })));
        g.setSlot(22, btn(Items.ENDER_EYE, "Transfer plot", "Give this plot to another player.", (i, t, a, gg) -> transferPicker(sp, anchor, 0)));
        g.setSlot(23, btn(Items.FEATHER, "Kick visitors", "Send everyone else on this plot to spawn.", (i, t, a, gg) -> {
            int n = kickVisitors(sp, d);
            sp.sendSystemMessage(Component.literal("[Plots] Sent " + n + " visitor" + (n == 1 ? "" : "s") + " to spawn."));
            settings(sp, anchor);
        }));
        g.setSlot(24, btn(Items.TNT, "Clear plot", "Reset every block to flat ground.", (i, t, a, gg) -> confirmClear(sp, anchor)));
        g.setSlot(25, btn(biomeIcon(d), "Biome",
                "Currently: " + PlotBiomes.labelOf(d.biomeId) + " — recolor grass, leaves and sky on your plot.",
                (i, t, a, gg) -> biomePicker(sp, anchor, 0)));
        g.setSlot(31, btn(Items.ARROW, "Back", "", (i, t, a, gg) -> myPlots(sp, 0)));
        g.open();
    }

    private static Item biomeIcon(PlotData d) {
        if (d.biomeId.isBlank()) return Items.GRASS_BLOCK;
        return itemByRegistryId(PlotBiomes.iconFor(d.biomeId));
    }

    private static Item itemByRegistryId(String id) {
        try {
            Item it = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
            return (it == null || it == Items.AIR) ? Items.GRASS_BLOCK : it;
        } catch (Exception e) { return Items.GRASS_BLOCK; }
    }

    // ---- biome picker ----------------------------------------------------

    public static void biomePicker(ServerPlayer sp, PlotPos anchor, int page) {
        PlotData d = PlotManager.get(anchor);
        if (d == null || (!d.owner.equals(sp.getUUID()) && !PlotProtection.isAdmin(sp))) { sp.closeContainer(); return; }
        String current = d.biomeId.isBlank() ? PlotBiomes.DEFAULT_ID : d.biomeId;
        List<String> biomes = PlotBiomes.allBiomeIds(plots(sp)); // every registered biome — mods included
        int pages = Math.max(1, (biomes.size() + PER_PAGE - 1) / PER_PAGE);
        if (page < 0) page = 0;
        if (page >= pages) page = pages - 1;
        final int pg = page;
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        g.setTitle(Component.literal("Plot biome  (" + (pg + 1) + "/" + pages + ")"));
        int start = pg * PER_PAGE;
        int end = Math.min(biomes.size(), start + PER_PAGE);
        int slot = 0;
        for (int idx = start; idx < end; idx++) {
            final String id = biomes.get(idx);
            boolean chosen = id.equals(current);
            boolean modded = !id.startsWith("minecraft:");
            String lore = chosen ? "Your plot's current biome."
                    : (modded ? "From " + id.substring(0, id.indexOf(':')) + ". Click to paint your plot."
                              : "Click to paint your plot this biome.");
            g.setSlot(slot++, btn(itemByRegistryId(PlotBiomes.iconFor(id)),
                    PlotBiomes.labelOf(id) + (chosen ? "  ✔" : ""), lore,
                    (i, t, a, gg) -> {
                        d.biomeId = id;
                        PlotManager.save();
                        int n = PlotBiomes.applyBiome(plots(sp), d);
                        sp.sendSystemMessage(Component.literal("[Plots] Biome set to " + PlotBiomes.labelOf(id)
                                + (n > 0 ? "." : " (no chunks updated — is the plot world loaded?)")));
                        biomePicker(sp, anchor, pg);
                    }));
        }
        // bottom row: paging + default + back (same layout as the floor picker)
        if (pg > 0) g.setSlot(45, btn(Items.ARROW, "Previous page", "", (i, t, a, gg) -> biomePicker(sp, anchor, pg - 1)));
        boolean isDefault = d.biomeId.isBlank();
        g.setSlot(47, btn(Items.GRASS_BLOCK, "Default" + (isDefault ? "  ✔" : ""),
                "The plot world's normal look.", (i, t, a, gg) -> {
            d.biomeId = "";
            PlotManager.save();
            PlotBiomes.applyBiome(plots(sp), d);
            sp.sendSystemMessage(Component.literal("[Plots] Biome reset to default."));
            biomePicker(sp, anchor, pg);
        }));
        g.setSlot(49, btn(Items.BARRIER, "Back", "", (i, t, a, gg) -> settings(sp, anchor)));
        g.setSlot(51, info(Items.PAPER, "Page " + (pg + 1) + " / " + pages, biomes.size() + " biomes"));
        if (pg < pages - 1) g.setSlot(53, btn(Items.ARROW, "Next page", "", (i, t, a, gg) -> biomePicker(sp, anchor, pg + 1)));
        g.open();
    }

    /** Strip characters that would corrupt the ';'-separated save file, and cap the length. */
    private static String clean(String txt) {
        String s = txt.replace(";", "").replace("|", "").replace(",", "").trim();
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    private static String ambienceLabel(PlotData d) {
        if (d.ambience.isBlank()) return "Real sky";
        String t = PlotAmbience.timeOf(d.ambience);
        String w = PlotAmbience.weatherOf(d.ambience);
        return (PlotAmbience.timeTicks(t) < 0 ? "Real time" : "Always " + t) + ", "
                + ("real".equals(w) ? "real weather" : "always " + w);
    }

    // ---- sky & weather illusion picker -----------------------------------

    public static void ambiencePicker(ServerPlayer sp, PlotPos anchor) {
        PlotData d = PlotManager.get(anchor);
        if (d == null || (!d.owner.equals(sp.getUUID()) && !PlotProtection.isAdmin(sp))) { sp.closeContainer(); return; }
        String curTime = PlotAmbience.timeOf(d.ambience.isBlank() ? "real:real" : d.ambience);
        String curWeather = PlotAmbience.weatherOf(d.ambience.isBlank() ? "real:real" : d.ambience);
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x3, sp, false);
        g.setTitle(Component.literal("Sky & weather (visitors only see it)"));
        Item[] timeIcons = { Items.COMPASS, Items.SUNFLOWER, Items.GLOWSTONE, Items.ORANGE_TULIP, Items.LANTERN, Items.OBSIDIAN };
        for (int i = 0; i < PlotAmbience.TIMES.length; i++) {
            final String time = PlotAmbience.TIMES[i];
            boolean chosen = time.equals(curTime);
            g.setSlot(1 + i, btn(timeIcons[i], niceWord(time) + (chosen ? "  ✔" : ""),
                    "real".equals(time) ? "Use the real time of day." : "Visitors always see " + time + " here.",
                    (x, t, a, gg) -> { d.ambience = PlotAmbience.compose(time, PlotAmbience.weatherOf(d.ambience.isBlank() ? "real:real" : d.ambience)); PlotManager.save(); ambiencePicker(sp, anchor); }));
        }
        Item[] weatherIcons = { Items.COMPASS, Items.GLASS, Items.WATER_BUCKET, Items.TRIDENT };
        for (int i = 0; i < PlotAmbience.WEATHERS.length; i++) {
            final String weather = PlotAmbience.WEATHERS[i];
            boolean chosen = weather.equals(curWeather);
            g.setSlot(10 + i, btn(weatherIcons[i], niceWord(weather) + (chosen ? "  ✔" : ""),
                    "real".equals(weather) ? "Use the real weather." : "Visitors always see " + weather + " here.",
                    (x, t, a, gg) -> { d.ambience = PlotAmbience.compose(PlotAmbience.timeOf(d.ambience.isBlank() ? "real:real" : d.ambience), weather); PlotManager.save(); ambiencePicker(sp, anchor); }));
        }
        g.setSlot(16, btn(Items.TNT, "Reset", "Back to the real sky.", (x, t, a, gg) -> { d.ambience = ""; PlotManager.save(); ambiencePicker(sp, anchor); }));
        g.setSlot(22, btn(Items.ARROW, "Back", "", (x, t, a, gg) -> settings(sp, anchor)));
        g.open();
    }

    private static String niceWord(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- transfer ownership ----------------------------------------------

    public static void transferPicker(ServerPlayer sp, PlotPos anchor, int page) {
        PlotData d = PlotManager.get(anchor);
        if (d == null || (!d.owner.equals(sp.getUUID()) && !PlotProtection.isAdmin(sp))) { sp.closeContainer(); return; }
        List<ServerPlayer> online = new ArrayList<>();
        for (ServerPlayer pl : sp.level().getServer().getPlayerList().getPlayers())
            if (!pl.getUUID().equals(d.owner)) online.add(pl);
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        g.setTitle(Component.literal("Transfer plot — pick the new owner"));
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < online.size(); i++) {
            ServerPlayer pl = online.get(start + i);
            g.setSlot(i, new GuiElementBuilder(Items.PLAYER_HEAD).setProfile(pl.getUUID())
                    .setName(Component.literal(pl.getName().getString()))
                    .setLore(List.of(Component.literal("Click to hand this plot over")))
                    .setCallback((x, t, a, gg) -> confirmTransfer(sp, anchor, pl.getUUID(), pl.getName().getString())).build());
        }
        nav(g, page, start + PER_PAGE < online.size(), p -> transferPicker(sp, anchor, p), () -> settings(sp, anchor));
        if (online.isEmpty()) g.setSlot(22, info(Items.BARRIER, "Nobody online", "The new owner must be online."));
        g.open();
    }

    private static void confirmTransfer(ServerPlayer sp, PlotPos anchor, UUID newOwner, String newName) {
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x3, sp, false);
        g.setTitle(Component.literal("Give this plot to " + newName + "?"));
        g.setSlot(11, btn(itemOf("minecraft:lime_concrete"), "Yes, transfer it",
                "You lose access unless they trust you back.", (i, t, a, gg) -> {
            PlotData d = PlotManager.get(anchor);
            if (d != null) {
                d.owner = newOwner;
                d.ownerName = newName;
                d.trusted.remove(newOwner);
                d.denied.remove(newOwner);
                PlotManager.save();
                sp.sendSystemMessage(Component.literal("[Plots] Plot transferred to " + newName + "."));
                ServerPlayer np = sp.level().getServer().getPlayerList().getPlayer(newOwner);
                if (np != null) np.sendSystemMessage(Component.literal("[Plots] " + sp.getName().getString() + " gave you their plot!"));
            }
            myPlots(sp, 0);
        }));
        g.setSlot(15, btn(itemOf("minecraft:red_concrete"), "No, keep it", "", (i, t, a, gg) -> settings(sp, anchor)));
        g.open();
    }

    /** Teleport every non-trusted visitor standing on the plot to the spawn plaza. */
    public static int kickVisitors(ServerPlayer sp, PlotData d) {
        int n = 0;
        for (ServerPlayer pl : sp.level().getServer().getPlayerList().getPlayers()) {
            if (pl.level().dimension() != FabricPlots.PLOTS_DIM) continue;
            if (PlotManager.owningPlot(pl.getBlockX(), pl.getBlockZ()) != d) continue;
            if (pl.getUUID().equals(d.owner) || d.canBuild(pl.getUUID()) || PlotProtection.isAdmin(pl)) continue;
            pl.teleportTo((ServerLevel) pl.level(), PlotsConfig.spawnX + 0.5, PlotsConfig.spawnY, PlotsConfig.spawnZ + 0.5,
                    Set.of(), pl.getYRot(), 0f, false);
            pl.sendOverlayMessage(Component.literal("The plot owner sent you back to spawn."));
            n++;
        }
        return n;
    }

    // ---- browse every plot -----------------------------------------------

    public static void browseAll(ServerPlayer sp, int page) {
        List<PlotData> all = new ArrayList<>(PlotManager.allPlots());
        all.sort((a, b) -> browseLabel(sp, a).compareToIgnoreCase(browseLabel(sp, b)));
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        g.setTitle(Component.literal("All Plots (" + all.size() + ")"));
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < all.size(); i++) {
            PlotData d = all.get(start + i);
            PlotPos anchor = d.cells.iterator().next();
            String owner = nameOf(sp, d.owner);
            String label = browseLabel(sp, d);
            List<Component> lore = List.of(
                    Component.literal("Owner: " + owner),
                    Component.literal("♥ " + d.likes.size() + " · Cells: " + d.cells.size()),
                    Component.literal("Click to visit or like"));
            g.setSlot(i, new GuiElementBuilder(floorItem(d)).setName(Component.literal(label)).setLore(lore)
                    .setCallback((x, t, a, gg) -> plotView(sp, anchor, () -> browseAll(sp, page))).build());
        }
        nav(g, page, start + PER_PAGE < all.size(), p -> browseAll(sp, p), () -> hub(sp));
        if (all.isEmpty()) g.setSlot(22, info(Items.BARRIER, "No plots yet", "Claim one with /plot auto."));
        g.open();
    }

    private static String browseLabel(ServerPlayer sp, PlotData d) {
        return d.name.isBlank() ? nameOf(sp, d.owner) + "'s plot" : d.name;
    }

    private static void confirmClear(ServerPlayer sp, PlotPos anchor) {
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x3, sp, false);
        g.setTitle(Component.literal("Clear this plot?"));
        g.setSlot(11, btn(Items.LIME_CONCRETE, "Yes, clear it", "Wipes the whole plot to flat ground.", (i, t, a, gg) -> {
            PlotData d = PlotManager.get(anchor);
            if (d != null) { PlotWorldPainter.clearPlot(plots(sp), d); sp.sendSystemMessage(Component.literal("[Plots] Plot cleared.")); }
            settings(sp, anchor);
        }));
        g.setSlot(15, btn(Items.RED_CONCRETE, "No, go back", "", (i, t, a, gg) -> settings(sp, anchor)));
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

    // ---- floor block picker ----------------------------------------------

    // A handful of technical full-cube blocks we don't want offered as floors.
    private static final Set<String> FLOOR_EXCLUDE = Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:barrier", "minecraft:light",
            "minecraft:moving_piston", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault");

    private static volatile List<String> floorPaletteCache = null;

    /**
     * Every full-cube ("square") block in the registry that has an item — built once from the live registry,
     * so it automatically includes version-specific blocks (e.g. 26.2's sulfur / cinnabar). Sorted by id.
     */
    private static List<String> floorPalette() {
        List<String> cached = floorPaletteCache;
        if (cached != null) return cached;
        List<String> out = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            try {
                if (b.asItem() == Items.AIR) continue; // no obtainable item
                if (!b.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) continue;
                String id = BuiltInRegistries.BLOCK.getKey(b).toString();
                if (FLOOR_EXCLUDE.contains(id)) continue;
                out.add(id);
            } catch (Throwable ignored) { /* skip any odd block */ }
        }
        out.sort(null); // alphabetical by id
        floorPaletteCache = out;
        return out;
    }

    public static void floorPicker(ServerPlayer sp, PlotPos anchor, int page) {
        PlotData d = PlotManager.get(anchor);
        if (d == null || (!d.owner.equals(sp.getUUID()) && !PlotProtection.isAdmin(sp))) { sp.closeContainer(); return; }
        List<String> palette = floorPalette();
        int pages = Math.max(1, (palette.size() + PER_PAGE - 1) / PER_PAGE);
        if (page < 0) page = 0;
        if (page >= pages) page = pages - 1;
        final int pg = page;
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        g.setTitle(Component.literal("Floor block  (" + (pg + 1) + "/" + pages + ")"));
        int start = pg * PER_PAGE;
        int end = Math.min(palette.size(), start + PER_PAGE);
        int slot = 0;
        for (int idx = start; idx < end; idx++) {
            final String id = palette.get(idx);
            boolean chosen = id.equals(d.floorBlockId) || (d.floorBlockId.isBlank() && id.equals("minecraft:grass_block"));
            g.setSlot(slot++, new GuiElementBuilder(itemOf(id))
                    .setName(Component.literal(prettyName(id) + (chosen ? "  ✔" : "")))
                    .setCallback((x, t, a, gg) -> {
                        d.floorBlockId = id.equals("minecraft:grass_block") ? "" : id; // grass == default
                        PlotManager.save();
                        PlotWorldPainter.applyFloor(plots(sp), d);
                        floorPicker(sp, anchor, pg);
                    }).build());
        }
        // bottom row: paging + reset + back
        if (pg > 0) g.setSlot(45, btn(Items.ARROW, "Previous page", "", (i, t, a, gg) -> floorPicker(sp, anchor, pg - 1)));
        g.setSlot(47, btn(Items.GRASS_BLOCK, "Default (grass)", "Reset to plain grass.", (i, t, a, gg) -> {
            d.floorBlockId = ""; PlotManager.save(); PlotWorldPainter.applyFloor(plots(sp), d); floorPicker(sp, anchor, pg);
        }));
        g.setSlot(49, btn(Items.BARRIER, "Back", "", (i, t, a, gg) -> settings(sp, anchor)));
        g.setSlot(51, info(Items.PAPER, "Page " + (pg + 1) + " / " + pages, palette.size() + " blocks"));
        if (pg < pages - 1) g.setSlot(53, btn(Items.ARROW, "Next page", "", (i, t, a, gg) -> floorPicker(sp, anchor, pg + 1)));
        g.open();
    }

    // ---- top plots gallery -----------------------------------------------

    public static void topPlots(ServerPlayer sp, int page) {
        List<PlotData> all = new ArrayList<>(PlotManager.allPlots());
        all.sort((a, b) -> Integer.compare(b.likes.size(), a.likes.size()));
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x6, sp, false);
        g.setTitle(Component.literal("Top Plots"));
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < all.size(); i++) {
            PlotData d = all.get(start + i);
            PlotPos anchor = d.cells.iterator().next();
            String owner = nameOf(sp, d.owner);
            String label = d.name.isBlank() ? owner + "'s plot" : d.name;
            List<Component> lore = List.of(
                    Component.literal("Owner: " + owner),
                    Component.literal("♥ " + d.likes.size() + " like" + (d.likes.size() == 1 ? "" : "s")),
                    Component.literal("Click to visit or like"));
            g.setSlot(i, new GuiElementBuilder(floorItem(d)).setName(Component.literal(label)).setLore(lore)
                    .setCallback((x, t, a, gg) -> plotView(sp, anchor, () -> topPlots(sp, page))).build());
        }
        nav(g, page, start + PER_PAGE < all.size(), p -> topPlots(sp, p), () -> hub(sp));
        if (all.isEmpty()) g.setSlot(22, info(Items.BARRIER, "No plots yet", "Claim one with /plot auto."));
        g.open();
    }

    /** A single plot's public card: Visit + Like/Unlike. */
    public static void plotView(ServerPlayer sp, PlotPos anchor, Runnable back) {
        PlotData d = PlotManager.get(anchor);
        if (d == null) { back.run(); return; }
        String owner = nameOf(sp, d.owner);
        String label = d.name.isBlank() ? owner + "'s plot" : d.name;
        SimpleGui g = new SimpleGui(MenuType.GENERIC_9x3, sp, false);
        g.setTitle(Component.literal(label));
        g.setSlot(11, btn(Items.ENDER_PEARL, "Visit", "Teleport to this plot.", (i, t, a, gg) -> { teleportVisit(sp, anchor); gg.close(); }));
        boolean own = d.owner.equals(sp.getUUID());
        boolean liked = d.likes.contains(sp.getUUID());
        if (own) {
            g.setSlot(15, info(Items.NETHER_STAR, "♥ " + d.likes.size() + " likes", "This is your plot."));
        } else {
            g.setSlot(15, btn(liked ? Items.POPPY : Items.NETHER_STAR,
                    (liked ? "Unlike" : "Like") + "  (♥ " + d.likes.size() + ")",
                    liked ? "Remove your like." : "Like this plot.",
                    (i, t, a, gg) -> {
                        if (!d.likes.remove(sp.getUUID())) d.likes.add(sp.getUUID());
                        PlotManager.save();
                        plotView(sp, anchor, back);
                    }));
        }
        g.setSlot(22, btn(Items.ARROW, "Back", "", (i, t, a, gg) -> back.run()));
        g.open();
    }

    private static void teleportVisit(ServerPlayer sp, PlotPos anchor) {
        ServerLevel level = plots(sp);
        PlotData d = PlotManager.get(anchor);
        if (d != null && d.home != null) {
            sp.teleportTo(level, d.home.getX() + 0.5, d.home.getY(), d.home.getZ() + 0.5, Set.of(), sp.getYRot(), 0f, false);
            return;
        }
        int[] xz = PlotManager.homeXZ(anchor);
        sp.teleportTo(level, xz[0] + 0.5, PlotConfig.FLOOR_Y, xz[1] + 0.5, Set.of(), sp.getYRot(), 0f, false);
    }

    // ---- helpers ---------------------------------------------------------

    private static Item itemOf(String blockId) {
        try {
            Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId));
            Item it = b.asItem();
            return it == Items.AIR ? Items.GRASS_BLOCK : it;
        } catch (Exception e) { return Items.GRASS_BLOCK; }
    }

    private static Item floorItem(PlotData d) {
        return (d.floorBlockId == null || d.floorBlockId.isBlank()) ? Items.GRASS_BLOCK : itemOf(d.floorBlockId);
    }

    private static String floorName(PlotData d) {
        return (d.floorBlockId == null || d.floorBlockId.isBlank()) ? "Grass" : prettyName(d.floorBlockId);
    }

    /** "minecraft:smooth_quartz" -> "Smooth Quartz". */
    private static String prettyName(String id) {
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] words = s.split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

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
