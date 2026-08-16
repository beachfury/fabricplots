package com.fabricplots.mixin;

import com.draftsmith.edit.DraftEdit;
import com.draftsmith.edit.DraftShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects shapes before their write lists can grow beyond DraftSmith's 65,536-block history cap. */
@Mixin(value = DraftShapes.class, remap = false)
public abstract class DraftShapesSafetyMixin {
    private static final long MAX_WORK = 65_536L;

    @Inject(method = "buildShape", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$boundShape(ServerPlayer player, ServerLevel level, BlockState state,
                                                DraftShapes.Shape shape, boolean hollow, int size, int height,
                                                int thickness, int repeat, int spacing,
                                                CallbackInfoReturnable<Integer> cir) {
        long perShape = estimate(shape, hollow, size, height, thickness);
        if (perShape > MAX_WORK || repeat > MAX_WORK / Math.max(1L, perShape)) reject(player, cir);
    }

    @Inject(method = "line", at = @At("HEAD"), cancellable = true)
    private static void fabricplots$boundLine(ServerPlayer player, ServerLevel level, BlockState state,
                                               int thickness, CallbackInfoReturnable<Integer> cir) {
        BlockPos a = DraftEditAccessor.fabricplots$getPos1().get(player.getUUID());
        BlockPos b = DraftEditAccessor.fabricplots$getPos2().get(player.getUUID());
        if (a == null || b == null) return;
        long steps = Math.max(Math.max(Math.abs((long) b.getX() - a.getX()), Math.abs((long) b.getY() - a.getY())),
                Math.abs((long) b.getZ() - a.getZ())) + 1;
        long side = Math.max(1L, 2L * thickness - 1L);
        long perStep = side * side * side;
        if (steps > MAX_WORK / Math.max(1L, perStep)) reject(player, cir);
    }

    private static void reject(ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        DraftEdit.msg(player, "That shape is too large to run safely; reduce its size, height, thickness, or repeats.");
        cir.setReturnValue(0);
    }

    private static long estimate(DraftShapes.Shape shape, boolean hollow, int size, int height, int thickness) {
        double outer = size / 2.0;
        double inner = Math.max(0, outer - Math.max(1, thickness));
        return switch (shape) {
            case CIRCLE, CYLINDER -> (long) Math.ceil(Math.PI * (outer * outer
                    - (hollow ? inner * inner : 0)) * Math.max(1, height));
            case SQUARE -> {
                long outerArea = (long) size * size;
                long innerSide = Math.max(0, size - 2L * Math.max(1, thickness));
                yield (outerArea - (hollow ? innerSide * innerSide : 0)) * Math.max(1, height);
            }
            case SPHERE -> (long) Math.ceil(4.0 / 3.0 * Math.PI * (outer * outer * outer
                    - (hollow ? inner * inner * inner : 0)));
            case PYRAMID -> {
                long blocks = 0;
                for (long side = size; side > 0; side -= 2) {
                    long innerSide = Math.max(0, side - 2L * Math.max(1, thickness));
                    blocks += side * side - (hollow ? innerSide * innerSide : 0);
                    if (blocks > MAX_WORK) break;
                }
                yield blocks;
            }
            case LINE -> 0;
        };
    }
}
