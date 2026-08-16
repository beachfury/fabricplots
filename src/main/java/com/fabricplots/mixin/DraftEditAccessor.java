package com.fabricplots.mixin;

import com.draftsmith.edit.DraftEdit;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

@Mixin(value = DraftEdit.class, remap = false)
public interface DraftEditAccessor {
    @Accessor("POS1")
    static Map<UUID, BlockPos> fabricplots$getPos1() { throw new AssertionError(); }

    @Accessor("POS2")
    static Map<UUID, BlockPos> fabricplots$getPos2() { throw new AssertionError(); }
}
