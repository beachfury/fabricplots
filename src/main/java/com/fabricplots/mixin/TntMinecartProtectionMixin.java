package com.fabricplots.mixin;

import com.fabricplots.FabricPlots;
import com.fabricplots.core.PlotsConfig;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TNT minecarts, same rule as {@link TntProtectionMixin}. Unlike primed TNT the cart only removes
 * itself inside {@code explode}, so a cancelled blast must discard it — otherwise a cart whose fuse
 * ran out would retry every tick forever.
 */
@Mixin(MinecartTNT.class)
public abstract class TntMinecartProtectionMixin {
    @Inject(method = "explode(Lnet/minecraft/world/damagesource/DamageSource;D)V", at = @At("HEAD"), cancellable = true)
    private void fabricplots$fizzleInPlots(DamageSource source, double radiusModifier, CallbackInfo ci) {
        MinecartTNT cart = (MinecartTNT) (Object) this;
        if (cart.level().isClientSide()) return;
        if (cart.level().dimension() != FabricPlots.PLOTS_DIM) return;
        if (!PlotsConfig.protectExplosions) return;
        cart.discard();
        ci.cancel();
    }
}
