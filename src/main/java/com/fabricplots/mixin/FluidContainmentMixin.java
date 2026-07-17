package com.fabricplots.mixin;

import com.fabricplots.FabricPlots;
import com.fabricplots.PlotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps water/lava on the plot it belongs to. In the plot world, a fluid may only spread into a
 * column that is OWNED by a plot — never onto streets, curbs, or unclaimed ground. This blocks the
 * classic "water staircase off the plot edge" and also stops dispenser-placed street sources from
 * running down the road (the street sweeper removes the stranded source block itself).
 */
@Mixin(FlowingFluid.class)
public abstract class FluidContainmentMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void fabricplots_containFluid(LevelAccessor levelAccessor, BlockPos targetPos, BlockState blockState,
                                          Direction direction, FluidState fluidState, CallbackInfo ci) {
        if (!(levelAccessor instanceof ServerLevel level)) return;
        if (level.dimension() != FabricPlots.PLOTS_DIM) return;
        if (PlotManager.owningPlot(targetPos.getX(), targetPos.getZ()) == null) ci.cancel();
    }
}
