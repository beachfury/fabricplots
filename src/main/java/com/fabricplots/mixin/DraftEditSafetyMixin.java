package com.fabricplots.mixin;

import com.draftsmith.api.DraftSmithApi;
import com.draftsmith.edit.DraftEdit;

import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** FabricPlots-side guardrails for the bundled DraftSmith 1.0 history format. */
@Mixin(value = DraftEdit.class, remap = false)
public abstract class DraftEditSafetyMixin {
    private static final long MAX_WRITES = 65_536L;
    private static final long MAX_SELECTION_SCAN = 1_000_000L;

    @Shadow @Final private static Map<UUID, BlockPos> POS1;
    @Shadow @Final private static Map<UUID, BlockPos> POS2;

    @Inject(
            method = "applyEdit(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerLevel;Ljava/util/function/BiPredicate;Ljava/util/function/Supplier;Ljava/lang/String;)I",
            at = @At("HEAD"), cancellable = true
    )
    private static void fabricplots$boundSelectionScan(ServerPlayer player, ServerLevel level,
                                                        BiPredicate<ServerLevel, BlockPos> include,
                                                        Supplier<BlockState> material, String verb,
                                                        CallbackInfoReturnable<Integer> cir) {
        if (selectionVolume(player, level) > MAX_SELECTION_SCAN) rejectLarge(player, cir);
    }

    @Inject(method = "stack", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$boundStack(ServerPlayer player, ServerLevel level, int count,
                                                CallbackInfoReturnable<Integer> cir) {
        long volume = selectionVolume(player, level);
        if (volume > MAX_SELECTION_SCAN || exceeds(volume, count, MAX_WRITES)) rejectLarge(player, cir);
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$boundMove(ServerPlayer player, ServerLevel level, int count,
                                               CallbackInfoReturnable<Integer> cir) {
        long volume = selectionVolume(player, level);
        if (volume > MAX_SELECTION_SCAN || exceeds(volume, 2, MAX_WRITES)) rejectLarge(player, cir);
    }

    @Inject(method = "walls", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$boundWalls(ServerPlayer player, ServerLevel level, BlockState state,
                                                CallbackInfoReturnable<Integer> cir) {
        if (selectionVolume(player, level) > MAX_SELECTION_SCAN) rejectLarge(player, cir);
    }

    @Inject(method = "sphere", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$boundSphere(ServerPlayer player, ServerLevel level, BlockState state,
                                                 int radius, boolean hollow, CallbackInfoReturnable<Integer> cir) {
        double outer = radius + 0.5;
        double inner = Math.max(0, radius - 0.5);
        double estimate = 4.0 / 3.0 * Math.PI * (outer * outer * outer
                - (hollow ? inner * inner * inner : 0));
        if (estimate > MAX_WRITES) rejectLarge(player, cir);
    }

    @Inject(method = "cylinder", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$boundCylinder(ServerPlayer player, ServerLevel level, BlockState state,
                                                   int radius, int height, CallbackInfoReturnable<Integer> cir) {
        double outer = radius + 0.5;
        if (Math.PI * outer * outer * height > MAX_WRITES) rejectLarge(player, cir);
    }

    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$rejectBlockEntitySet(ServerPlayer player, ServerLevel level, BlockState state,
                                                          CallbackInfoReturnable<Integer> cir) {
        if (state.getBlock() instanceof EntityBlock) {
            DraftEdit.msg(player, "Containers and signs are not supported by editor history; place them by hand.");
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "replace", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$rejectBlockEntityReplace(ServerPlayer player, ServerLevel level,
                                                              BlockInput from, BlockState to,
                                                              CallbackInfoReturnable<Integer> cir) {
        if (to.getBlock() instanceof EntityBlock) {
            DraftEdit.msg(player, "Containers and signs are not supported by editor history; place them by hand.");
            cir.setReturnValue(0);
        }
    }

    /**
     * DraftSmith 1.0 snapshots block states, not block-entity NBT. Refuse edits which would
     * create or replace a block entity, preventing containers/signs from losing their data.
     */
    @Inject(method = "commit", at = @At("HEAD"))
    private static void fabricplots$filterUnsafeWrites(ServerPlayer player, ServerLevel level,
                                                        List<DraftEdit.Write> writes, String verb,
                                                        CallbackInfoReturnable<Integer> cir) {
        boolean admin = DraftSmithApi.access().isAdmin(player);
        writes.removeIf(write -> unsafe(level, write.pos(), write.state())
                || !DraftSmithApi.access().canEdit(player, admin,
                write.pos().getX(), write.pos().getY(), write.pos().getZ()));
    }

    /** The selection set/replace path writes directly rather than through commit. */
    @Redirect(
            method = "applyEdit(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerLevel;Ljava/util/function/BiPredicate;Ljava/util/function/Supplier;Ljava/lang/String;)I",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z")
    )
    private static boolean fabricplots$safeSelectionWrite(ServerLevel level, BlockPos pos, BlockState state, int flags,
                                                            ServerPlayer player, ServerLevel ignoredLevel,
                                                            BiPredicate<ServerLevel, BlockPos> include,
                                                            Supplier<BlockState> material, String verb) {
        boolean admin = DraftSmithApi.access().isAdmin(player);
        if (unsafe(level, pos, state)
                || !DraftSmithApi.access().canEdit(player, admin, pos.getX(), pos.getY(), pos.getZ())) return false;
        return level.setBlock(pos, state, flags);
    }

    /**
     * Undo/redo in DraftSmith 1.0 did not re-check the access provider. Cancel the whole step
     * before its stack is popped if ownership changed or if block-entity data would be involved.
     */
    @Inject(method = "step", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$authorizeHistory(ServerPlayer player, ServerLevel level,
                                                      Map<UUID, Deque<List<DraftEdit.Snapshot>>> from,
                                                      Map<UUID, Deque<List<DraftEdit.Snapshot>>> to,
                                                      String empty, String verb,
                                                      CallbackInfoReturnable<Integer> cir) {
        Deque<List<DraftEdit.Snapshot>> stack = from.get(player.getUUID());
        if (stack == null || stack.isEmpty()) return;
        boolean admin = DraftSmithApi.access().isAdmin(player);
        for (DraftEdit.Snapshot snapshot : stack.peek()) {
            BlockPos pos = snapshot.pos();
            if (unsafe(level, pos, snapshot.old())
                    || !DraftSmithApi.access().canEdit(player, admin, pos.getX(), pos.getY(), pos.getZ())) {
                DraftEdit.msg(player, "Undo/redo stopped: the plot access changed or the edit contains a container/sign.");
                cir.setReturnValue(0);
                return;
            }
        }
    }

    private static boolean unsafe(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        return block instanceof EntityBlock || level.getBlockEntity(pos) != null;
    }

    private static long selectionVolume(ServerPlayer player, ServerLevel level) {
        BlockPos a = POS1.get(player.getUUID()), b = POS2.get(player.getUUID());
        if (a == null || b == null) return 0;
        long dx = Math.abs((long) a.getX() - b.getX()) + 1;
        long dz = Math.abs((long) a.getZ() - b.getZ()) + 1;
        int y1 = Math.max(DraftSmithApi.access().minY(level), Math.min(a.getY(), b.getY()));
        int y2 = Math.min(DraftSmithApi.access().maxY(level), Math.max(a.getY(), b.getY()));
        long dy = Math.max(0L, (long) y2 - y1 + 1L);
        if (exceeds(dx, dz, MAX_SELECTION_SCAN)) return MAX_SELECTION_SCAN + 1;
        long area = dx * dz;
        return exceeds(area, dy, MAX_SELECTION_SCAN) ? MAX_SELECTION_SCAN + 1 : area * dy;
    }

    private static boolean exceeds(long value, long multiplier, long limit) {
        return value > 0 && multiplier > limit / value;
    }

    private static void rejectLarge(ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        DraftEdit.msg(player, "That operation is too large to run safely; use a smaller selection, size, height, or count.");
        cir.setReturnValue(0);
    }
}

