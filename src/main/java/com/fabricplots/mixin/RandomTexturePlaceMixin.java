package com.fabricplots.mixin;

import com.fabricplots.PlotEdit;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the editor's random-texture toggle apply to hand placement too: with the toggle ON in the
 * plot world, placing any block by hand rolls the block from the player's hotbar instead — lay a
 * path without ever scrolling. All the decision logic lives in {@link PlotEdit#randomPlace}.
 */
@Mixin(BlockItem.class)
public abstract class RandomTexturePlaceMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void fabricplots$randomTexturePlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult swapped = PlotEdit.randomPlace((BlockItem) (Object) this, context);
        if (swapped != null) cir.setReturnValue(swapped);
    }
}
