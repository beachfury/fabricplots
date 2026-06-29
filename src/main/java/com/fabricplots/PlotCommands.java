package com.fabricplots;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /plot auto | claim | home | info | clear | delete | trust | untrust | visit
 * All commands are typed text, so Bedrock players reach them through Geyser.
 */
public final class PlotCommands {
    private PlotCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext bc) {
        d.register(Commands.literal("plot")
                .executes(PlotCommands::help)
                .then(Commands.literal("help").executes(PlotCommands::help))
                .then(Commands.literal("world").executes(PlotCommands::world))
                .then(Commands.literal("auto").executes(PlotCommands::auto))
                .then(Commands.literal("claim").executes(PlotCommands::claim))
                .then(Commands.literal("home").executes(PlotCommands::home))
                .then(Commands.literal("info").executes(PlotCommands::info))
                .then(Commands.literal("clear").executes(PlotCommands::clear))
                .then(Commands.literal("delete").executes(PlotCommands::delete))
                .then(Commands.literal("repaint")
                        .executes(ctx -> repaint(ctx, 64))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                                .executes(ctx -> repaint(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("wand")
                        .executes(PlotCommands::giveWand)
                        .then(Commands.literal("cancel").executes(PlotCommands::cancelWand)))
                .then(Commands.literal("combine")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(PlotCommands::combine)))
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> setTrust(ctx, true))))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> setTrust(ctx, false))))
                .then(Commands.literal("deny")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> setDeny(ctx, true))))
                .then(Commands.literal("undeny")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> setDeny(ctx, false))))
                .then(Commands.literal("uncombine").executes(PlotCommands::uncombine))
                .then(Commands.literal("visit")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(PlotCommands::visit)))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(PlotCommands::namePlot)))
                .then(Commands.literal("leave").executes(PlotCommands::leave))
                .then(Commands.literal("key").executes(PlotCommands::key))
                .then(Commands.literal("removeall")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(PlotCommands::removeAll)))
                .then(Commands.literal("editwand").executes(PlotCommands::editwand))
                .then(Commands.literal("pos1").executes(PlotCommands::pos1))
                .then(Commands.literal("pos2").executes(PlotCommands::pos2))
                .then(Commands.literal("set")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .executes(PlotCommands::setBlocks)))
                .then(Commands.literal("replace")
                        .then(Commands.argument("from", BlockStateArgument.block(bc))
                                .then(Commands.argument("to", BlockStateArgument.block(bc))
                                        .executes(PlotCommands::replaceBlocks))))
                .then(Commands.literal("menu").executes(PlotCommands::menu))
                .then(Commands.literal("list").executes(PlotCommands::listPlots))
                .then(Commands.literal("edit").executes(PlotCommands::editGui))
                .then(Commands.literal("undo").executes(PlotCommands::undoEdit))
                .then(Commands.literal("redo").executes(PlotCommands::redoEdit))
                .then(Commands.literal("sethome").executes(PlotCommands::setHome))
                .then(Commands.literal("copy").executes(PlotCommands::copyEdit))
                .then(Commands.literal("cut").executes(PlotCommands::cutEdit))
                .then(Commands.literal("paste").executes(PlotCommands::pasteEdit))
                .then(Commands.literal("stack")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> stackEdit(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("move")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> moveEdit(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("walls")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .executes(PlotCommands::wallsEdit)))
                .then(Commands.literal("sphere")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                        .executes(ctx -> sphereEdit(ctx, false)))))
                .then(Commands.literal("hsphere")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                        .executes(ctx -> sphereEdit(ctx, true)))))
                .then(Commands.literal("cyl")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                        .executes(ctx -> cylEdit(ctx, 1))
                                        .then(Commands.argument("height", IntegerArgumentType.integer(1, 256))
                                                .executes(ctx -> cylEdit(ctx, IntegerArgumentType.getInteger(ctx, "height")))))))
                .then(Commands.literal("admin").executes(PlotCommands::adminMode))
                .then(Commands.literal("setspawn").executes(PlotCommands::setSpawn))
                .then(Commands.literal("reload").executes(PlotCommands::reload))
                .then(Commands.literal("portals").executes(PlotCommands::portals))
                .then(Commands.literal("setserver").executes(PlotCommands::setServer))
                .then(Commands.literal("setowner")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(PlotCommands::setOwner)))
                .then(Commands.literal("version").executes(PlotCommands::version)));

        // Convenience alias; becomes the clickable sgui menu in v1.1.
        d.register(Commands.literal("plots").executes(PlotCommands::home));
    }

    // ---- subcommands -----------------------------------------------------

    private static int auto(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (atClaimLimit(ctx, p)) { msg(ctx, "You've hit the plot limit (" + PlotsConfig.claimLimit + ")."); return 0; }
            PlotPos free = PlotManager.nextFree();
            if (free == null) { msg(ctx, "No free plots available."); return 0; }
            PlotManager.claim(free, p.getUUID(), p.getName().getString());
            p.addItem(PortalManager.createKey(free));
            PortalManager.buildExitPortal(plotsLevel(ctx), free);
            teleport(p, plotsLevel(ctx), free);
            msg(ctx, "Claimed plot " + free.px() + "," + free.pz() + " — happy building! (Portal Key added: light a calcite frame at your base with it.)");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int claim(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Stand in the plots world first."); return 0; }
            int x = p.getBlockX(), z = p.getBlockZ();
            if (!PlotManager.isInsidePlot(x, z)) { msg(ctx, "Stand inside a plot, not on the road."); return 0; }
            PlotPos pp = PlotManager.plotAt(x, z);
            if (PlotManager.isClaimed(pp)) { msg(ctx, "That plot is already claimed."); return 0; }
            if (atClaimLimit(ctx, p)) { msg(ctx, "You've hit the plot limit (" + PlotsConfig.claimLimit + ")."); return 0; }
            PlotManager.claim(pp, p.getUUID(), p.getName().getString());
            p.addItem(PortalManager.createKey(pp));
            PortalManager.buildExitPortal(plotsLevel(ctx), pp);
            msg(ctx, "Claimed plot " + pp.px() + "," + pp.pz() + ". Take the Portal Key — build a calcite frame at your base and light it with this to link a portal here.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int home(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlotPos pp = PlotManager.firstOwned(p.getUUID());
            if (pp == null) { msg(ctx, "You don't own a plot yet. Use /plot auto."); return 0; }
            teleport(p, plotsLevel(ctx), pp);
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null) { msg(ctx, "Plot " + pp.px() + "," + pp.pz() + " is unclaimed."); return 1; }
            msg(ctx, "Plot " + pp.px() + "," + pp.pz() + (d.name.isBlank() ? "" : " \"" + d.name + "\"")
                    + " — owner " + nameOf(ctx, d.owner)
                    + " · " + d.cells.size() + " cell" + (d.cells.size() == 1 ? "" : "s") + (d.isMerged() ? " (merged)" : "")
                    + " · trusted " + d.trusted.size() + " · denied " + d.denied.size());
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int setSpawn(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (!isOp(ctx, p)) { msg(ctx, "Ops only."); return 0; }
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Stand in the plot world where you want spawn."); return 0; }
            PlotsConfig.spawnX = p.getBlockX();
            PlotsConfig.spawnY = p.getBlockY();
            PlotsConfig.spawnZ = p.getBlockZ();
            PlotsConfig.save();
            msg(ctx, "Plot-world spawn set to " + PlotsConfig.spawnX + ", " + PlotsConfig.spawnY + ", " + PlotsConfig.spawnZ + ".");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int adminMode(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (!isOp(ctx, p)) { msg(ctx, "Ops only."); return 0; }
            boolean on = PlotProtection.toggleBuildAdmin(p);
            msg(ctx, on ? "Admin build mode ON — you can now edit roads and any plot. Be careful! Run /plot admin again to turn it off."
                       : "Admin build mode OFF — you're back to editing only your own plots.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (!isOp(ctx, p)) { msg(ctx, "Ops only."); return 0; }
            PlotsConfig.load();
            FabricPlots.applyWorldRules(ctx.getSource().getServer());
            msg(ctx, "Config reloaded. allow-player-combine=" + PlotsConfig.allowPlayerCombine
                    + ", max-merge-cells=" + PlotsConfig.maxMergeCells
                    + ", claim-limit=" + PlotsConfig.claimLimit
                    + ", portal-street-spacing=" + PlotsConfig.portalStreetSpacing
                    + ", advance-time=" + PlotsConfig.advanceTime
                    + ", advance-weather=" + PlotsConfig.advanceWeather
                    + ", inactivity-expiry=" + PlotsConfig.inactivityExpiry + " (" + PlotsConfig.inactivityDays + "d)"
                    + ". (Run /plot portals to re-apply spacing to existing plots.)");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int editwand(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            p.addItem(PlotEdit.createWand());
            msg(ctx, "Editor wand given. Right-click a block for corner 1, right-click again for corner 2 (or use /plot pos1 · /plot pos2). Then /plot set <block> or /plot replace <from> <to>.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int pos1(CommandContext<CommandSourceStack> ctx) {
        try { PlotEdit.setPos1(ctx.getSource().getPlayerOrException()); return 1; }
        catch (Exception e) { return err(ctx, e); }
    }

    private static int pos2(CommandContext<CommandSourceStack> ctx) {
        try { PlotEdit.setPos2(ctx.getSource().getPlayerOrException()); return 1; }
        catch (Exception e) { return err(ctx, e); }
    }

    private static int setBlocks(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            var block = BlockStateArgument.getBlock(ctx, "block");
            return PlotEdit.set(p, plotsLevel(ctx), block.getState());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int replaceBlocks(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            var from = BlockStateArgument.getBlock(ctx, "from");
            var to = BlockStateArgument.getBlock(ctx, "to");
            return PlotEdit.replace(p, plotsLevel(ctx), from, to.getState());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int undoEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.undo(p, plotsLevel(ctx));
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int redoEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.redo(p, plotsLevel(ctx));
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int setHome(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null || (!d.owner.equals(p.getUUID()) && !isOp(ctx, p))) { msg(ctx, "Stand on your own plot first."); return 0; }
            d.home = p.blockPosition().immutable();
            PlotManager.save();
            msg(ctx, "Plot home set — /plot home will bring you here.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static final int CLR_TEAL = 0x1ABC9C, CLR_YELLOW = 0xFFE066, CLR_PURPLE = 0xC792EA;

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("FabricPlots").withStyle(s -> s.withColor(CLR_TEAL))
                .append(Component.literal(" — commands (click one to fill it in)").withStyle(s -> s.withColor(CLR_PURPLE))), false);

        section(src, "Getting around");
        line(src, "/plot world", "go to the plot world");
        line(src, "/plot menu", "open the menu");
        line(src, "/plot home", "go to your plot");
        line(src, "/plot visit <player>", "visit a player's plot");
        line(src, "/plot leave", "return to the home world");

        section(src, "Claiming");
        line(src, "/plot auto", "claim the next free plot");
        line(src, "/plot claim", "claim the plot you're standing in");
        line(src, "/plot info", "info about this plot");
        line(src, "/plot key", "get your plot's portal key (run it at your base)");
        line(src, "/plot delete", "release this plot");

        section(src, "Your plot");
        line(src, "/plot name <name>", "name this plot");
        line(src, "/plot sethome", "set where /plot home lands");
        line(src, "/plot trust <player>", "let someone build here");
        line(src, "/plot untrust <player>", "remove a trusted player");
        line(src, "/plot deny <player>", "ban a player from this plot");
        line(src, "/plot undeny <player>", "un-ban a player");
        line(src, "/plot clear", "reset this plot to flat ground");

        section(src, "Building");
        line(src, "/plot edit", "open the build GUI");
        line(src, "/plot editwand", "get the selection wand");
        line(src, "/plot pos1", "set selection corner 1");
        line(src, "/plot pos2", "set selection corner 2");
        line(src, "/plot set <block>", "fill the selection");
        line(src, "/plot replace <from> <to>", "replace blocks in the selection");
        line(src, "/plot walls <block>", "build walls around the selection");
        line(src, "/plot sphere <block> <radius>", "build a sphere");
        line(src, "/plot hsphere <block> <radius>", "build a hollow sphere");
        line(src, "/plot cyl <block> <radius> [height]", "build a cylinder");
        line(src, "/plot copy", "copy the selection");
        line(src, "/plot cut", "cut the selection");
        line(src, "/plot paste", "paste here");
        line(src, "/plot stack <count>", "stack the selection");
        line(src, "/plot move <count>", "move the selection");
        line(src, "/plot undo", "undo the last edit");
        line(src, "/plot redo", "redo");

        if (isOpSource(ctx)) {
            section(src, "Admin");
            line(src, "/plot admin", "toggle build-admin mode");
            line(src, "/plot setspawn", "set the plot-world spawn");
            line(src, "/plot wand", "get the combine wand");
            line(src, "/plot combine <player>", "merge selected plots");
            line(src, "/plot uncombine", "split a merged plot");
            line(src, "/plot setowner <player>", "transfer this plot");
            line(src, "/plot setserver", "make this plot server-owned");
            line(src, "/plot removeall <player>", "free all of a player's plots");
            line(src, "/plot repaint", "repaint nearby roads");
            line(src, "/plot portals", "rebuild exit portals");
            line(src, "/plot reload", "reload the config");
        }
        return 1;
    }

    private static void section(CommandSourceStack src, String title) {
        src.sendSuccess(() -> Component.literal(title).withStyle(s -> s.withColor(CLR_PURPLE).applyFormat(ChatFormatting.BOLD)), false);
    }

    /** One help line: yellow, clickable command (clicking fills it in chat) + a purple description. */
    private static void line(CommandSourceStack src, String command, String desc) {
        int cut = command.length();
        int lt = command.indexOf('<'), br = command.indexOf('[');
        if (lt >= 0) cut = lt;
        if (br >= 0 && br < cut) cut = br;
        final String suggest = command.substring(0, cut); // drop <args>/[args] from what gets typed
        src.sendSuccess(() -> Component.literal("  " + command)
                .withStyle(s -> s.withColor(CLR_YELLOW).withClickEvent(new ClickEvent.SuggestCommand(suggest)))
                .append(Component.literal("   " + desc).withStyle(s -> s.withColor(CLR_PURPLE))), false);
    }

    private static boolean isOpSource(CommandContext<CommandSourceStack> ctx) {
        try { return isOp(ctx, ctx.getSource().getPlayerOrException()); }
        catch (Exception e) { return true; } // console / command block sees everything
    }

    private static int menu(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlotMenus.hub(p); // opens from anywhere — the plot hub is your front door from your base
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int listPlots(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlotMenus.myPlots(p, 0); // opens from anywhere
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int editGui(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Open the editor in the plot world."); return 0; }
            PlotEditGui.open(p);
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int wallsEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.walls(p, plotsLevel(ctx), BlockStateArgument.getBlock(ctx, "block").getState());
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int sphereEdit(CommandContext<CommandSourceStack> ctx, boolean hollow) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            int r = IntegerArgumentType.getInteger(ctx, "radius");
            return PlotEdit.sphere(p, plotsLevel(ctx), BlockStateArgument.getBlock(ctx, "block").getState(), r, hollow);
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int cylEdit(CommandContext<CommandSourceStack> ctx, int height) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            int r = IntegerArgumentType.getInteger(ctx, "radius");
            return PlotEdit.cylinder(p, plotsLevel(ctx), BlockStateArgument.getBlock(ctx, "block").getState(), r, height);
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int copyEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.copy(p, plotsLevel(ctx));
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int cutEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.cut(p, plotsLevel(ctx));
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int pasteEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.paste(p, plotsLevel(ctx));
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int stackEdit(CommandContext<CommandSourceStack> ctx, int count) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.stack(p, plotsLevel(ctx), count);
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int moveEdit(CommandContext<CommandSourceStack> ctx, int count) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.move(p, plotsLevel(ctx), count);
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int portals(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (!isOp(ctx, p)) { msg(ctx, "Ops only."); return 0; }
            int n = PortalManager.rebuildAllExitPortals(plotsLevel(ctx));
            msg(ctx, "Rebuilt exit portals at spacing " + PlotsConfig.portalStreetSpacing
                    + " (every " + (PlotsConfig.portalStreetSpacing == 1 ? "" : PlotsConfig.portalStreetSpacing + nd(PlotsConfig.portalStreetSpacing) + " ")
                    + "N-S street) — " + n + " active.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static String nd(int n) { return n == 2 ? "nd" : n == 3 ? "rd" : "th"; }

    private static int setServer(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (!isOp(ctx, p)) { msg(ctx, "Ops only."); return 0; }
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null) { msg(ctx, "Stand on a claimed plot to hand it to the server."); return 0; }
            d.owner = PlotManager.SERVER_UUID;
            d.ownerName = "Server";
            PlotManager.save();
            msg(ctx, "Plot is now server-owned.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int setOwner(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (!isOp(ctx, p)) { msg(ctx, "Ops only."); return 0; }
            var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
            if (profiles.isEmpty()) { msg(ctx, "Unknown player."); return 0; }
            var profile = profiles.iterator().next();
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null) { msg(ctx, "Stand on a claimed plot to transfer it."); return 0; }
            d.owner = profile.id();
            d.ownerName = profile.name();
            d.trusted.remove(profile.id());
            PlotManager.save();
            msg(ctx, "Plot transferred to " + profile.name() + ".");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int version(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            boolean admin = PlotProtection.isAdmin(p);
            boolean buildAdmin = PlotProtection.isBuildAdmin(p);
            msg(ctx, "FabricPlots build 2026-06-26y · you are " + (admin ? "an OP" : "a normal player")
                    + (admin ? (buildAdmin ? " · build-admin mode ON (editing anywhere)" : " · build-admin mode OFF (own plots only — /plot admin to unlock)") : ""));
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int namePlot(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null) { msg(ctx, "Stand on a plot you own."); return 0; }
            if (!d.owner.equals(p.getUUID()) && !isOp(ctx, p)) { msg(ctx, "That isn't your plot."); return 0; }
            d.name = StringArgumentType.getString(ctx, "name");
            PlotManager.save();
            msg(ctx, "Plot named \"" + d.name + "\".");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            // Returns to the portal you came in through, or the home-world spawn if you never used one.
            PortalManager.returnHome(ctx.getSource().getServer(), p);
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int key(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            // In the plot world, standing on a plot you may use: give that one plot's key.
            if (p.level().dimension() == FabricPlots.PLOTS_DIM) {
                PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
                PlotData d = PlotManager.get(pp);
                if (d != null && (d.owner.equals(p.getUUID()) || d.trusted.contains(p.getUUID()) || isOp(ctx, p))) {
                    p.addItem(PortalManager.createKey(pp));
                    msg(ctx, "Here's your Portal Key. Build a calcite frame at your base and right-click inside it with this.");
                    return 1;
                }
            }
            // Anywhere else (e.g. at your base in the main world) hand out a key for every plot
            // you own. Created here, in the destination inventory, so per-world inventory mods
            // (Dimensional Inventories, etc.) can't strip it on the way out of the plot world.
            int n = 0;
            for (PlotData d : PlotManager.allPlots()) {
                if (d.owner.equals(p.getUUID()) && !d.cells.isEmpty()) {
                    p.addItem(PortalManager.createKey(d.cells.iterator().next()));
                    n++;
                }
            }
            if (n == 0) { msg(ctx, "You don't own a plot yet. Use /plot auto to claim one."); return 0; }
            msg(ctx, "Added " + n + " Portal Key" + (n == 1 ? "" : "s") + " for your plot" + (n == 1 ? "" : "s")
                + ". Build a calcite frame at your base and right-click inside it with one.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int removeAll(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer admin = ctx.getSource().getPlayerOrException();
            if (!isOp(ctx, admin)) { msg(ctx, "Ops only."); return 0; }
            var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
            if (profiles.isEmpty()) { msg(ctx, "Unknown player."); return 0; }
            var profile = profiles.iterator().next();
            int n = PlotManager.removeAllOwnedBy(profile.id());
            msg(ctx, "Freed " + n + " plot(s) previously owned by " + profile.name() + ".");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int combine(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer admin = ctx.getSource().getPlayerOrException();
            boolean op = isOp(ctx, admin);
            if (!op && !PlotsConfig.allowPlayerCombine) { msg(ctx, "Combining plots is admin-only on this server."); return 0; }
            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
            ServerLevel plots = plotsLevel(ctx);
            UUID tid = target.getUUID();
            // Non-ops may only merge their OWN plots.
            if (!op && !tid.equals(admin.getUUID())) { msg(ctx, "You can only combine your own plots."); return 0; }

            // Exactly the cells the wand selected — any rectilinear shape (L, T, H, +, …), not a bbox.
            List<PlotPos> cells = new ArrayList<>(CombineWand.cells(admin.getUUID()));
            if (cells.size() < 2) { msg(ctx, "Select at least 2 plots with the combine wand first (/plot wand)."); return 0; }
            if (!op && cells.size() > PlotsConfig.maxMergeCells) {
                msg(ctx, "You can merge at most " + PlotsConfig.maxMergeCells + " plots at once."); return 0;
            }
            if (!isOrthogonallyConnected(cells)) {
                msg(ctx, "Those plots aren't all connected — a merged plot must be one continuous shape.");
                return 0;
            }

            for (PlotPos cell : cells) {
                PlotData d = PlotManager.get(cell);
                if (op) {
                    if (d != null && !d.owner.equals(tid)) {
                        msg(ctx, "Plot " + cell.px() + "," + cell.pz() + " is owned by someone else — remove them first.");
                        return 0;
                    }
                } else if (d == null || !d.owner.equals(tid)) {
                    msg(ctx, "You must own every selected plot — " + cell.px() + "," + cell.pz() + " isn't yours.");
                    return 0;
                }
            }

            CombineWand.restore(plots, admin);        // pull the gold markers
            PlotManager.combine(cells, tid, target.getName().getString()); // merge ownership
            PlotWorldPainter.combineRepaint(plots, cells); // wipe + dissolve interior roads + repaint
            PortalManager.removeInternalMergePortals(plots, cells); // delete portals now inside the merged plot
            for (PlotPos c : cells) PortalManager.buildExitPortal(plots, c); // exit portals on exterior streets
            msg(ctx, "Combined " + cells.size() + " plots into one for " + target.getName().getString() + ".");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    /** True if every selected cell is reachable from the first via orthogonal (N/S/E/W) steps. */
    private static boolean isOrthogonallyConnected(List<PlotPos> cells) {
        if (cells.size() <= 1) return true;
        java.util.Set<PlotPos> remaining = new java.util.HashSet<>(cells);
        java.util.Deque<PlotPos> stack = new java.util.ArrayDeque<>();
        PlotPos start = cells.get(0);
        remaining.remove(start);
        stack.push(start);
        while (!stack.isEmpty()) {
            PlotPos c = stack.pop();
            PlotPos[] adj = {
                    new PlotPos(c.px() + 1, c.pz()), new PlotPos(c.px() - 1, c.pz()),
                    new PlotPos(c.px(), c.pz() + 1), new PlotPos(c.px(), c.pz() - 1) };
            for (PlotPos n : adj) if (remaining.remove(n)) stack.push(n);
        }
        return remaining.isEmpty();
    }

    private static int giveWand(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (!isOp(ctx, p) && !PlotsConfig.allowPlayerCombine) { msg(ctx, "Combining plots is admin-only on this server."); return 0; }
            p.addItem(CombineWand.createWand());
            msg(ctx, "Combine wand given. Right-click a plot to select it; click another in the same row/column to fill the line between; click off-line to start a new arm. Build any shape (L, T, H, +), then /plot combine <player>.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int cancelWand(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            CombineWand.restore(ctx.getSource().getServer().getLevel(FabricPlots.PLOTS_DIM), p);
            msg(ctx, "Combine selection cleared.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int repaint(CommandContext<CommandSourceStack> ctx, int radius) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (!isOp(ctx, p)) { msg(ctx, "Ops only."); return 0; }
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plots world."); return 0; }
            int n = PlotWorldPainter.repaint(plotsLevel(ctx), p.getBlockX(), p.getBlockZ(), radius);
            msg(ctx, "Repainted roads within " + radius + " blocks (" + n + " columns).");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int world(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            ServerLevel plots = plotsLevel(ctx);
            p.teleportTo(plots, PlotsConfig.spawnX + 0.5, PlotsConfig.spawnY, PlotsConfig.spawnZ + 0.5,
                    Set.of(), -45.0f, 0.0f, false);
            msg(ctx, "Welcome to the plot world! Walk into an empty plot and use /plot claim.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plots world."); return 0; }
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null) { msg(ctx, "That plot is unclaimed."); return 0; }
            if (!d.owner.equals(p.getUUID()) && !isOp(ctx, p)) { msg(ctx, "That isn't your plot."); return 0; }
            int n = PlotWorldPainter.clearPlot(plotsLevel(ctx), d);
            msg(ctx, "Cleared " + n + " columns top-to-bottom back to flat ground.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null) { msg(ctx, "That plot is unclaimed."); return 0; }
            if (!d.owner.equals(p.getUUID()) && !isOp(ctx, p)) { msg(ctx, "That isn't your plot."); return 0; }
            List<PlotPos> released = new ArrayList<>(d.cells);
            PlotManager.unclaim(pp);
            for (PlotPos c : released) PortalManager.removeExitPortalIfOrphan(plotsLevel(ctx), c);
            msg(ctx, "Released plot " + pp.px() + "," + pp.pz() + ".");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int setTrust(CommandContext<CommandSourceStack> ctx, boolean add) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null || !d.owner.equals(p.getUUID())) { msg(ctx, "Stand on your own plot first."); return 0; }
            if (add) { d.trusted.add(target.getUUID()); d.denied.remove(target.getUUID()); } // trusting lifts a deny
            else d.trusted.remove(target.getUUID());
            PlotManager.save();
            msg(ctx, (add ? "Trusted " : "Untrusted ") + target.getName().getString() + ".");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int setDeny(CommandContext<CommandSourceStack> ctx, boolean add) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null || (!d.owner.equals(p.getUUID()) && !isOp(ctx, p))) { msg(ctx, "Stand on your own plot first."); return 0; }
            var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
            if (profiles.isEmpty()) { msg(ctx, "Unknown player."); return 0; }
            var profile = profiles.iterator().next();
            if (add) {
                if (profile.id().equals(d.owner)) { msg(ctx, "You can't deny the owner."); return 0; }
                d.denied.add(profile.id());
                d.trusted.remove(profile.id()); // denying revokes trust
            } else {
                d.denied.remove(profile.id());
            }
            PlotManager.save();
            msg(ctx, (add ? "Denied " : "Un-denied ") + profile.name() + " on this plot.");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int uncombine(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { msg(ctx, "Run this in the plot world."); return 0; }
            PlotPos pp = PlotManager.plotAt(p.getBlockX(), p.getBlockZ());
            PlotData d = PlotManager.get(pp);
            if (d == null) { msg(ctx, "That plot is unclaimed."); return 0; }
            if (!d.owner.equals(p.getUUID()) && !isOp(ctx, p)) { msg(ctx, "That isn't your plot."); return 0; }
            if (d.cells.size() <= 1) { msg(ctx, "That plot isn't merged."); return 0; }
            ServerLevel plots = plotsLevel(ctx);
            var cells = PlotManager.uncombine(pp);
            PlotWorldPainter.repaintCells(plots, cells);                 // re-cut the roads between cells
            for (PlotPos c : cells) PortalManager.buildExitPortal(plots, c);
            msg(ctx, "Split the merged plot back into " + cells.size() + " plots (roads restored).");
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    private static int visit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
            PlotPos pp = PlotManager.firstOwned(target.getUUID());
            if (pp == null) { msg(ctx, "That player has no plot yet."); return 0; }
            teleport(p, plotsLevel(ctx), pp);
            return 1;
        } catch (Exception e) { return err(ctx, e); }
    }

    // ---- helpers ---------------------------------------------------------

    private static ServerLevel plotsLevel(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getServer().getLevel(FabricPlots.PLOTS_DIM);
        if (level == null) throw new IllegalStateException("Plots dimension not loaded (fabricplots:plots).");
        return level;
    }

    private static void teleport(ServerPlayer p, ServerLevel level, PlotPos pp) {
        // 26.1.2 teleportTo: (level, x, y, z, relativeFlags, yaw, pitch, setCamera)
        PlotData d = PlotManager.get(pp);
        if (d != null && d.home != null) {
            p.teleportTo(level, d.home.getX() + 0.5, d.home.getY(), d.home.getZ() + 0.5, Set.of(), p.getYRot(), 0.0f, false);
            return;
        }
        int[] xz = PlotManager.homeXZ(pp);
        p.teleportTo(level, xz[0] + 0.5, PlotConfig.FLOOR_Y, xz[1] + 0.5, Set.of(), p.getYRot(), 0.0f, false);
    }

    private static boolean isOp(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
        return PlotProtection.isAdmin(p); // server op OR single-player host
    }

    private static boolean atClaimLimit(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
        if (isOp(ctx, p) || PlotsConfig.claimLimit <= 0) return false;
        return PlotManager.ownedCount(p.getUUID()) >= PlotsConfig.claimLimit;
    }

    private static String nameOf(CommandContext<CommandSourceStack> ctx, UUID id) {
        if (PlotManager.isServer(id)) return "Server";
        ServerPlayer sp = ctx.getSource().getServer().getPlayerList().getPlayer(id);
        return sp != null ? sp.getName().getString() : id.toString().substring(0, 8);
    }

    private static void msg(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal("[Plots] " + text), false);
    }

    private static int err(CommandContext<CommandSourceStack> ctx, Exception e) {
        ctx.getSource().sendFailure(Component.literal("[Plots] Error: " + e.getMessage()));
        return 0;
    }
}
