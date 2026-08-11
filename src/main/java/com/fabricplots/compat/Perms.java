package com.fabricplots.compat;

import com.fabricplots.protect.PlotProtection;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * Soft bridge to fabric-permissions-api (the check API LuckPerms and friends implement).
 * When no permissions mod is installed — or a node is left undefined — the given default
 * decides, so servers without one behave exactly as before. The inner holder keeps
 * me.lucko classes from loading unless the API mod is actually present.
 */
public final class Perms {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0");

    /** Highest {@code fabricplots.limit.N} node scanned for per-player claim limits. */
    private static final int MAX_LIMIT_NODE = 64;

    /** Highest {@code fabricplots.mobcap.N} node scanned for per-owner mob-cap ceilings. */
    private static final int MAX_MOBCAP_NODE = 64;

    private Perms() {}

    /** True/false from the permissions mod when it defines {@code node}; otherwise {@code def}. */
    public static boolean check(ServerPlayer p, String node, boolean def) {
        return LOADED ? Impl.check(p, node, def) : def;
    }

    /**
     * Staff gate: the node when a permissions mod defines it; otherwise today's admin rule —
     * FabricPlots' own op check ({@code opLevel} is the conventional fallback level, OP 2; the
     * check also covers the single-player host, whose permission level isn't reliably exposed).
     */
    public static boolean checkOp(ServerPlayer p, String node, int opLevel) {
        boolean fallback = PlotProtection.isAdmin(p); // ops (gamemaster+, >= level 2) + single-player host
        return LOADED ? Impl.check(p, node, fallback) : fallback;
    }

    /**
     * Per-player claim limit: scans the numeric nodes {@code fabricplots.limit.1} …
     * {@code fabricplots.limit.64} and the HIGHEST granted one wins; players with none
     * granted (or servers without a permissions mod) use {@code configDefault}.
     */
    public static int claimLimit(ServerPlayer p, int configDefault) {
        return LOADED ? Impl.claimLimit(p, configDefault) : configDefault;
    }

    /**
     * Per-owner mob-cap ceiling: scans the numeric nodes {@code fabricplots.mobcap.1} …
     * {@code fabricplots.mobcap.64} and the HIGHEST granted one RAISES the config ceiling
     * (a node at or below it changes nothing); owners with none granted (or servers without
     * a permissions mod) use {@code configCeiling}. Permission checks need a live player,
     * so this only resolves while the owner is ONLINE — offline owners keep the config
     * ceiling (a deliberate, documented simplification).
     */
    public static int mobCap(ServerPlayer p, int configCeiling) {
        return LOADED ? Impl.mobCap(p, configCeiling) : configCeiling;
    }

    private static final class Impl {
        static boolean check(ServerPlayer p, String node, boolean def) {
            return me.lucko.fabric.api.permissions.v0.Permissions.check(p, node, def);
        }

        static int claimLimit(ServerPlayer p, int configDefault) {
            for (int i = MAX_LIMIT_NODE; i >= 1; i--) {
                if (me.lucko.fabric.api.permissions.v0.Permissions.check(p, "fabricplots.limit." + i, false)) return i;
            }
            return configDefault;
        }

        static int mobCap(ServerPlayer p, int configCeiling) {
            for (int i = MAX_MOBCAP_NODE; i > configCeiling; i--) { // nodes at/below the config ceiling can't raise it
                if (me.lucko.fabric.api.permissions.v0.Permissions.check(p, "fabricplots.mobcap." + i, false)) return i;
            }
            return configCeiling;
        }
    }
}
