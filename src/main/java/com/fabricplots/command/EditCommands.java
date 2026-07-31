package com.fabricplots.command;

import com.fabricplots.FabricPlots;
import com.fabricplots.edit.PlotEdit;
import com.fabricplots.edit.PlotMeasure;
import com.fabricplots.edit.PlotShapes;
import com.fabricplots.gui.PlotEditGui;
import com.fabricplots.gui.PlotMeasureGui;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.server.level.ServerPlayer;

/**
 * The editor half of /plot: wand, brush, selection, set/replace, clipboard, shapes and the
 * measuring tape. Registered onto the /plot root by {@link PlotCommands#register} — split out
 * only to keep the classes readable; behavior is identical.
 */
final class EditCommands {
    private EditCommands() {}

    /** Chain the editor subcommands onto the /plot root builder (call before d.register). */
    static void attach(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext bc) {
        root.then(Commands.literal("editwand").executes(EditCommands::editwand))
                .then(Commands.literal("brush").executes(EditCommands::brush))
                .then(Commands.literal("pos1").executes(EditCommands::pos1))
                .then(Commands.literal("pos2").executes(EditCommands::pos2))
                .then(Commands.literal("set")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .executes(EditCommands::setBlocks)))
                .then(Commands.literal("replace")
                        .then(Commands.argument("from", BlockStateArgument.block(bc))
                                .then(Commands.argument("to", BlockStateArgument.block(bc))
                                        .executes(EditCommands::replaceBlocks))))
                .then(Commands.literal("edit").executes(EditCommands::editGui))
                .then(Commands.literal("measure").executes(EditCommands::measureGui))
                .then(Commands.literal("undo").executes(EditCommands::undoEdit))
                .then(Commands.literal("redo").executes(EditCommands::redoEdit))
                .then(Commands.literal("copy").executes(EditCommands::copyEdit))
                .then(Commands.literal("cut").executes(EditCommands::cutEdit))
                .then(Commands.literal("paste").executes(EditCommands::pasteEdit))
                .then(Commands.literal("stack")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> stackEdit(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("move")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> moveEdit(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("walls")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .executes(EditCommands::wallsEdit)))
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
                .then(Commands.literal("disc")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .then(Commands.argument("size", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> shapeEdit(ctx, PlotShapes.Shape.CIRCLE, false, 1))
                                        .then(Commands.argument("height", IntegerArgumentType.integer(1, 128))
                                                .executes(ctx -> shapeEdit(ctx, PlotShapes.Shape.CIRCLE, false,
                                                        IntegerArgumentType.getInteger(ctx, "height")))))))
                .then(Commands.literal("ring")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .then(Commands.argument("size", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> shapeEdit(ctx, PlotShapes.Shape.CIRCLE, true, 1))
                                        .then(Commands.argument("height", IntegerArgumentType.integer(1, 128))
                                                .executes(ctx -> shapeEdit(ctx, PlotShapes.Shape.CIRCLE, true,
                                                        IntegerArgumentType.getInteger(ctx, "height")))))))
                .then(Commands.literal("line")
                        .then(Commands.argument("block", BlockStateArgument.block(bc))
                                .executes(ctx -> lineEdit(ctx, 1))
                                .then(Commands.argument("thickness", IntegerArgumentType.integer(1, 8))
                                        .executes(ctx -> lineEdit(ctx, IntegerArgumentType.getInteger(ctx, "thickness"))))))
                .then(Commands.literal("center").executes(EditCommands::centerEdit))
                .then(Commands.literal("tape")
                        .executes(EditCommands::tapeEdit)
                        .then(Commands.literal("clear").executes(EditCommands::tapeClear)));
    }

    // ---- handlers --------------------------------------------------------

    private static int brush(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            p.getInventory().placeItemBackInInventory(com.fabricplots.edit.PlotBrush.createBrush());
            PlotCommands.msg(ctx, "Paint brush added. Sneak + right-click to configure it, right-click to paint.");
            return 1;
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int editwand(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            p.addItem(PlotEdit.createWand());
            PlotCommands.msg(ctx, "Editor wand given. Right-click a block for corner 1, right-click again for corner 2 (or use /plot pos1 · /plot pos2). Then /plot set <block> or /plot replace <from> <to>.");
            return 1;
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int pos1(CommandContext<CommandSourceStack> ctx) {
        try { PlotEdit.setPos1(ctx.getSource().getPlayerOrException()); return 1; }
        catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int pos2(CommandContext<CommandSourceStack> ctx) {
        try { PlotEdit.setPos2(ctx.getSource().getPlayerOrException()); return 1; }
        catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int setBlocks(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            var block = BlockStateArgument.getBlock(ctx, "block");
            return PlotEdit.set(p, PlotCommands.plotsLevel(ctx), block.getState());
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int replaceBlocks(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            var from = BlockStateArgument.getBlock(ctx, "from");
            var to = BlockStateArgument.getBlock(ctx, "to");
            return PlotEdit.replace(p, PlotCommands.plotsLevel(ctx), from, to.getState());
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int undoEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.undo(p, PlotCommands.plotsLevel(ctx));
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int redoEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.redo(p, PlotCommands.plotsLevel(ctx));
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int editGui(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Open the editor in the plot world."); return 0; }
            PlotEditGui.open(p);
            return 1;
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int measureGui(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Open the measuring tools in the plot world."); return 0; }
            PlotMeasureGui.open(p);
            return 1;
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int wallsEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.walls(p, PlotCommands.plotsLevel(ctx), BlockStateArgument.getBlock(ctx, "block").getState());
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int sphereEdit(CommandContext<CommandSourceStack> ctx, boolean hollow) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            int r = IntegerArgumentType.getInteger(ctx, "radius");
            return PlotEdit.sphere(p, PlotCommands.plotsLevel(ctx), BlockStateArgument.getBlock(ctx, "block").getState(), r, hollow);
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int cylEdit(CommandContext<CommandSourceStack> ctx, int height) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            int r = IntegerArgumentType.getInteger(ctx, "radius");
            return PlotEdit.cylinder(p, PlotCommands.plotsLevel(ctx), BlockStateArgument.getBlock(ctx, "block").getState(), r, height);
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int shapeEdit(CommandContext<CommandSourceStack> ctx, PlotShapes.Shape shape, boolean hollow, int height) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            int size = IntegerArgumentType.getInteger(ctx, "size");
            return PlotShapes.buildShape(p, PlotCommands.plotsLevel(ctx), BlockStateArgument.getBlock(ctx, "block").getState(),
                    shape, hollow, size, height, 1, 1, 0);
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int lineEdit(CommandContext<CommandSourceStack> ctx, int thickness) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotShapes.line(p, PlotCommands.plotsLevel(ctx), BlockStateArgument.getBlock(ctx, "block").getState(), thickness);
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int centerEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotShapes.findLineCenter(p, PlotCommands.plotsLevel(ctx));
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int tapeEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotMeasure.tape(p, PlotCommands.plotsLevel(ctx));
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int tapeClear(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            PlotMeasure.clearTape(p, PlotCommands.plotsLevel(ctx));
            PlotCommands.msg(ctx, "Measuring tape cleared.");
            return 1;
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int copyEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.copy(p, PlotCommands.plotsLevel(ctx));
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int cutEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.cut(p, PlotCommands.plotsLevel(ctx));
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int pasteEdit(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.paste(p, PlotCommands.plotsLevel(ctx));
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int stackEdit(CommandContext<CommandSourceStack> ctx, int count) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.stack(p, PlotCommands.plotsLevel(ctx), count);
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }

    private static int moveEdit(CommandContext<CommandSourceStack> ctx, int count) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) { PlotCommands.msg(ctx, "Run this in the plot world."); return 0; }
            return PlotEdit.move(p, PlotCommands.plotsLevel(ctx), count);
        } catch (Exception e) { return PlotCommands.err(ctx, e); }
    }
}
