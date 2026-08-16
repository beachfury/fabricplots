package com.fabricplots.mixin;

import com.fabricplots.FabricPlots;
import com.fabricplots.protect.PlotProtection;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stops ranged attacks from bypassing protection on item frames, vehicles and other entities. */
@Mixin(Entity.class)
public abstract class EntityDamageProtectionMixin {
    @Inject(method = "hurtOrSimulate", at = @At("HEAD"), cancellable = true)
    private void fabricplots$protectNonPlayerEntity(DamageSource source, float amount,
                                                     CallbackInfoReturnable<Boolean> cir) {
        Entity target = (Entity) (Object) this;
        if (target instanceof Player || !(target.level() instanceof ServerLevel level)
                || level.dimension() != FabricPlots.PLOTS_DIM) return;

        Entity cause = source.getEntity();
        if (!(cause instanceof ServerPlayer) && source.getDirectEntity() instanceof Projectile projectile) {
            cause = projectile.getOwner();
        }
        if (cause instanceof ServerPlayer attacker
                && !PlotProtection.canModifyEntity(attacker, target.blockPosition())) {
            cir.setReturnValue(false);
        }
    }
}
