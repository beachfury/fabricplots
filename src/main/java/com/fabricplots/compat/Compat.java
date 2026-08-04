package com.fabricplots.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/**
 * The version seam. Everything that tends to change between Minecraft versions funnels through
 * this package, so porting to another version means re-implementing THIS class — not hunting the
 * codebase. Known differences are noted per method. THIS BRANCH: 1.21.1 (mojmap).
 */
public final class Compat {
    private Compat() {}

    // ---- registry lookups --------------------------------------------------
    // 1.21.1: ResourceLocation + Registry.get. 26.x: Identifier + Registry.getValue.
    // Always address blocks/items by REGISTRY ID in shared code — the ids never change.

    /** Raw block lookup by registry id (returns the registry default on unknown ids). */
    public static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    /** Raw item lookup by registry id (returns the registry default on unknown ids). */
    public static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    // ---- mob homing ----------------------------------------------------------
    // 1.21.1: Mob.restrictTo / isWithinRestriction. 26.x: Mob.setHomeTo / isWithinHome.

    /** Bind a mob's wander AI to a home position and radius. */
    public static void setHome(Mob mob, BlockPos pos, int radius) {
        mob.restrictTo(pos, radius);
    }

    // ---- sign text (measuring tape numbers) --------------------------------
    // SignText API is stable since 1.20 — identical in 1.21.1 and 26.x.

    /** Write {@code text} on both faces of the sign at pos — black dye + glow ink, waxed. */
    public static void labelSign(ServerLevel level, BlockPos pos, String text) {
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sbe) {
            Component c = Component.literal(text);
            sbe.setText(sbe.getText(true).setMessage(1, c)
                    .setColor(DyeColor.BLACK).setHasGlowingText(true), true);
            sbe.setText(sbe.getText(false).setMessage(1, c)
                    .setColor(DyeColor.BLACK).setHasGlowingText(true), false);
            sbe.setWaxed(true); // nobody should be able to edit the numbers
            sbe.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), Block.UPDATE_CLIENTS);
        }
    }

    // ---- per-player client clock (sky/time illusion) ------------------------
    // 1.21.1: ClientboundSetTimePacket(gameTime, dayTime, doDaylightCycle) — doDaylightCycle=false
    // freezes the client clock. 26.x: WorldClock system — packet takes Map<clock, state>, rate 0.

    /** Show this player a frozen time of day (resend every tick to beat the server sync). */
    public static void sendFrozenTime(ServerPlayer p, long dayTicks) {
        ServerLevel level = (ServerLevel) p.level();
        p.connection.send(new ClientboundSetTimePacket(level.getGameTime(), dayTicks, false));
    }

    /** Put this player back on the level's real clock. */
    public static void sendRealTime(ServerPlayer p) {
        ServerLevel level = (ServerLevel) p.level();
        p.connection.send(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
    }
}
