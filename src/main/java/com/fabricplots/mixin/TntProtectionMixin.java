package com.fabricplots.mixin;

import com.fabricplots.FabricPlots;
import com.fabricplots.core.PlotsConfig;

import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1 stand-in for 26.x's {@code tntExplodes=false} gamerule: with {@code protect-explosions} on,
 * lit TNT in the plot world fizzles instead of exploding. The entity already discards itself before
 * calling {@code explode()}, so cancelling the blast is all it takes.
 */
@Mixin(PrimedTnt.class)
public abstract class TntProtectionMixin {
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void fabricplots$fizzleInPlots(CallbackInfo ci) {
        PrimedTnt tnt = (PrimedTnt) (Object) this;
        if (tnt.level().isClientSide()) return;
        if (tnt.level().dimension() != FabricPlots.PLOTS_DIM) return;
        if (PlotsConfig.protectExplosions) ci.cancel();
    }
}
