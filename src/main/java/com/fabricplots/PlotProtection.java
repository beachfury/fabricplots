package com.fabricplots;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restricts building/breaking/interacting in the plots world to the plot owner and trusted players.
 * Roads and unclaimed plots are read-only. Ops are NOT auto-exempt — an op must opt into "build-admin
 * mode" with /plot admin to edit outside their own plots (prevents accidental edits via the tools).
 */
public final class PlotProtection {
    /** Ops who have toggled build-admin mode ON (can edit anywhere). Cleared on server restart. */
    private static final Set<UUID> BUILD_ADMIN = ConcurrentHashMap.newKeySet();

    private PlotProtection() {}

    public static void register() {
        // IMPORTANT: these callbacks fire on BOTH client and server. On the client the player is a
        // LocalPlayer (not a ServerPlayer), so the admin bypass can't run there and the client would
        // wrongly cancel an op's edit before it ever reaches the server. Enforce on the SERVER only
        // (the authoritative side) and let the client predict/forward normally.

        // Block breaking — return false to cancel (server-side; client predicts, server decides).
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerLevel)) return true;
            if (!isPlots(world)) return true;
            return allowed(player, pos);
        });

        // Block placement / right-click use — return FAIL to cancel.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerLevel)) return InteractionResult.PASS;
            if (!isPlots(world)) return InteractionResult.PASS;
            return allowed(player, hit.getBlockPos()) ? InteractionResult.PASS : InteractionResult.FAIL;
        });

        // Left-click attack on a block (creative instant-break starts here).
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!(world instanceof ServerLevel)) return InteractionResult.PASS;
            if (!isPlots(world)) return InteractionResult.PASS;
            return allowed(player, pos) ? InteractionResult.PASS : InteractionResult.FAIL;
        });

        // PvP: in the plot world, block player-vs-player damage unless the victim is standing on a
        // plot whose PvP flag is ON. Roads and pvp-off plots are safe.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(world instanceof ServerLevel)) return InteractionResult.PASS;
            if (!isPlots(world)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer) || !(entity instanceof ServerPlayer victim)) return InteractionResult.PASS;
            if (player == victim) return InteractionResult.PASS;
            PlotData plot = PlotManager.owningPlot(victim.getBlockX(), victim.getBlockZ());
            return (plot != null && plot.pvp) ? InteractionResult.PASS : InteractionResult.FAIL;
        });
    }

    private static boolean isPlots(Level world) {
        return world.dimension() == FabricPlots.PLOTS_DIM;
    }

    private static boolean allowed(Player player, BlockPos pos) {
        // Only ops who have ENABLED build-admin mode bypass everything (bedrock / roads / other plots).
        if (player instanceof ServerPlayer sp && isBuildAdmin(sp)) return true;
        if (pos.getY() <= PlotConfig.BEDROCK_Y) return false;                         // bedrock floor: never for players
        PlotData data = PlotManager.owningPlot(pos.getX(), pos.getZ());               // cell or merge interior
        if (data == null) return false;                                               // stair/road/unclaimed: locked
        return data.canBuild(player.getUUID());
    }

    /** True if this op is currently in build-admin mode (toggled via /plot admin). */
    public static boolean isBuildAdmin(ServerPlayer sp) {
        return BUILD_ADMIN.contains(sp.getUUID()) && isAdmin(sp);
    }

    /** Toggle build-admin mode for an op. Returns the new state. */
    public static boolean toggleBuildAdmin(ServerPlayer sp) {
        UUID id = sp.getUUID();
        if (BUILD_ADMIN.remove(id)) return false;
        BUILD_ADMIN.add(id);
        return true;
    }

    /**
     * True for any op/admin. Reads the player's EFFECTIVE permission (set for the single-player
     * host, LAN host, and dedicated-server ops alike) rather than ops.json / profile identity,
     * which are unreliable in single-player. Game mode is irrelevant.
     */
    public static boolean isAdmin(ServerPlayer sp) {
        var server = sp.level().getServer();
        if (server == null) return false;
        // In a single-player world the host IS the admin — their permission level isn't reliably
        // exposed (ops.json is empty, isSingleplayerOwner can miss), so just allow it outright.
        // On a dedicated server this is false, so real ops are gated by the permission check below.
        if (server.isSingleplayer()) return true;
        if (sp.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return true;
        return server.isSingleplayerOwner(sp.nameAndId()) || server.getPlayerList().isOp(sp.nameAndId());
    }
}
