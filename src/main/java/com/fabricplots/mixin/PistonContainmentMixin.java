package com.fabricplots.mixin;

import com.fabricplots.FabricPlots;
import com.fabricplots.PlotData;
import com.fabricplots.PlotManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Keeps piston contraptions on their own plot. A piston move is cancelled when the piston head, any
 * pushed/pulled block, or any of their destination positions falls outside the plot the piston sits
 * on. This blocks pushing blocks onto the street, sticky-pulling street blocks into a plot, and
 * flying machines crossing the roads (they simply stall at the boundary).
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonContainmentMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private BlockPos pistonPos;
    @Shadow @Final private Direction pushDirection;
    @Shadow @Final private List<BlockPos> toPush;
    @Shadow @Final private List<BlockPos> toDestroy;

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
    private void fabricplots_containPiston(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;
        if (level.dimension() != FabricPlots.PLOTS_DIM) return;
        PlotData home = PlotManager.owningPlot(pistonPos.getX(), pistonPos.getZ());
        // The extending head occupies the block in front of the piston.
        BlockPos head = pistonPos.relative(pushDirection);
        if (!sameHome(home, head)) { cir.setReturnValue(false); return; }
        for (BlockPos p : toPush) {
            if (!sameHome(home, p) || !sameHome(home, p.relative(pushDirection))) {
                cir.setReturnValue(false);
                return;
            }
        }
        for (BlockPos p : toDestroy) {
            if (!sameHome(home, p)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }

    private static boolean sameHome(PlotData home, BlockPos pos) {
        return home != null && PlotManager.owningPlot(pos.getX(), pos.getZ()) == home;
    }
}
