package com.fabricplots.edit;

import com.fabricplots.compat.Compat;
import com.fabricplots.protect.PlotProtection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Measuring tools: the caution-stripe measuring tape (temporary, self-restoring) and the
 * selection size readout. Tapes snapshot every block they cover, so laying a new one or
 * clearing restores exactly what was there.
 */
public final class PlotMeasure {
    // Up to 4 tapes per player (e.g. boxing in a footprint); laying a 5th retires the oldest.
    private static final int MAX_TAPES = 4;
    private static final Map<UUID, ArrayDeque<List<PlotEdit.Snapshot>>> TAPE = new HashMap<>();

    private PlotMeasure() {}

    /** Current selection as "W x H x L — n blocks", or null if either corner is unset. */
    public static String selectionInfo(ServerPlayer sp) {
        BlockPos p1 = PlotEdit.POS1.get(sp.getUUID()), p2 = PlotEdit.POS2.get(sp.getUUID());
        if (p1 == null || p2 == null) return null;
        int w = Math.abs(p2.getX() - p1.getX()) + 1;
        int h = Math.abs(p2.getY() - p1.getY()) + 1;
        int l = Math.abs(p2.getZ() - p1.getZ()) + 1;
        return w + " x " + h + " x " + l + " — " + String.format("%,d", (long) w * h * l) + " blocks";
    }

    /**
     * Lay a caution-stripe measuring tape from corner 1 to corner 2 — straight runs only (one
     * axis). Yellow/black alternates every block, counting from 1 at corner 1; numbered signs go
     * every 2, 5, or 10 blocks depending on length (start and end are always numbered). Horizontal
     * runs get signs standing on top; vertical runs get wall signs facing you. Temporary: laying a
     * new tape or clearing restores exactly what was there.
     */
    public static int tape(ServerPlayer sp, ServerLevel level) {
        UUID id = sp.getUUID();
        BlockPos p1 = PlotEdit.POS1.get(id), p2 = PlotEdit.POS2.get(id);
        if (p1 == null || p2 == null) { PlotEdit.msg(sp, "Set corner 1 and corner 2 first — the tape runs between them."); return 0; }
        int dx = p2.getX() - p1.getX(), dy = p2.getY() - p1.getY(), dz = p2.getZ() - p1.getZ();
        int nonZero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (nonZero > 1) { PlotEdit.msg(sp, "The tape runs straight only — line corners 1 and 2 up on a single axis."); return 0; }
        ArrayDeque<List<PlotEdit.Snapshot>> tapes = TAPE.computeIfAbsent(id, k -> new ArrayDeque<>());
        while (tapes.size() >= MAX_TAPES) restoreTape(level, tapes.pollFirst()); // retire the oldest

        int n = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) + 1;
        int interval = n <= 20 ? 2 : n <= 50 ? 5 : 10;
        int sx = Integer.signum(dx), sy = Integer.signum(dy), sz = Integer.signum(dz);
        boolean vertical = dy != 0;
        boolean admin = PlotProtection.isBuildAdmin(sp);
        Block yellow = PlotEdit.blockById("minecraft:yellow_concrete"), black = PlotEdit.blockById("minecraft:black_concrete");
        Block standing = PlotEdit.blockById("minecraft:oak_sign"), wall = PlotEdit.blockById("minecraft:oak_wall_sign");
        // Signs face back at wherever the player is standing when the tape is laid.
        Direction toPlayer = sp.getDirection().getOpposite();

        List<PlotEdit.Snapshot> snaps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos pos = p1.offset(sx * i, sy * i, sz * i);
            if (!PlotEdit.canEdit(sp, admin, pos.getX(), pos.getY(), pos.getZ())) continue;
            snaps.add(new PlotEdit.Snapshot(pos, level.getBlockState(pos)));
            level.setBlock(pos, (i % 2 == 0 ? yellow : black).defaultBlockState(), Block.UPDATE_CLIENTS);

            int number = i + 1;
            if (number != 1 && number != n && number % interval != 0) continue;
            if (!vertical) {
                BlockPos signPos = pos.above();
                if (!PlotEdit.canEdit(sp, admin, signPos.getX(), signPos.getY(), signPos.getZ())) continue;
                snaps.add(new PlotEdit.Snapshot(signPos, level.getBlockState(signPos)));
                int rot = (int) (toPlayer.toYRot() / 22.5f) & 15;
                level.setBlock(signPos, standing.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION, rot), Block.UPDATE_CLIENTS);
                labelSign(level, signPos, number);
            } else {
                BlockPos signPos = pos.relative(toPlayer);
                if (!PlotEdit.canEdit(sp, admin, signPos.getX(), signPos.getY(), signPos.getZ())) continue;
                snaps.add(new PlotEdit.Snapshot(signPos, level.getBlockState(signPos)));
                level.setBlock(signPos, wall.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.WallSignBlock.FACING, toPlayer), Block.UPDATE_CLIENTS);
                labelSign(level, signPos, number);
            }
        }
        tapes.addLast(snaps);
        PlotEdit.msg(sp, "Tape laid — " + n + " blocks, numbered every " + interval + " (" + tapes.size() + "/" + MAX_TAPES + " tapes). /plot tape clear removes them all.");
        return 1;
    }

    private static void labelSign(ServerLevel level, BlockPos pos, int number) {
        Compat.labelSign(level, pos, String.valueOf(number)); // version seam — sign API lives in compat
    }

    /** Remove the player's tape, restoring what each stripe/sign covered (skips blocks changed since). */
    public static void clearTape(ServerPlayer sp, ServerLevel level) {
        ArrayDeque<List<PlotEdit.Snapshot>> tapes = TAPE.remove(sp.getUUID());
        if (tapes == null) return;
        while (!tapes.isEmpty()) restoreTape(level, tapes.pollFirst());
    }

    private static void restoreTape(ServerLevel level, List<PlotEdit.Snapshot> snaps) {
        if (snaps == null) return;
        Block yellow = PlotEdit.blockById("minecraft:yellow_concrete"), black = PlotEdit.blockById("minecraft:black_concrete");
        for (PlotEdit.Snapshot s : snaps) {
            BlockState cur = level.getBlockState(s.pos());
            if (cur.is(yellow) || cur.is(black) || cur.getBlock() instanceof net.minecraft.world.level.block.SignBlock)
                level.setBlock(s.pos(), s.old(), Block.UPDATE_CLIENTS);
        }
    }
}
