package com.fabricplots.protect;

import com.fabricplots.compat.Compat;
import com.fabricplots.compat.Perms;

import com.fabricplots.FabricPlots;
import com.fabricplots.core.PlotData;
import com.fabricplots.core.PlotManager;
import com.fabricplots.core.PlotsConfig;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps biome-spawned mobs on the plot they spawned on. A plot painted with a spawn-heavy biome
 * (nether wastes, mushroom fields, …) naturally spawns that biome's mobs — that's part of the
 * flavor — but they must not wander onto the roads or a neighbour's plot.
 *
 * Two layers: {@link Mob#setHomeTo} bounds the wander AI to the spawn point, and a periodic sweep
 * teleports back anything that slipped out anyway (fliers, knockback, pathfinding quirks).
 * Tamed pets are exempt. A custom name alone is not an exemption: otherwise named hostile mobs
 * could bypass confinement and players could evade the per-plot cap with name tags.
 */
public final class PlotMobGuard {
    /** Where each tracked plot-world mob belongs (not persisted — re-learned from position on load). */
    private static final Map<UUID, BlockPos> HOME = new HashMap<>();
    private static final int HOME_RADIUS = 14;

    /**
     * Live tracked-mob count per plot, for the mob cap: incremented when a mob is adopted,
     * rebuilt authoritatively by every {@link #tick} sweep (which already iterates all
     * entities, so a purge/unload only leaves the count stale for a couple of seconds —
     * and stale means "too high", which merely delays new spawns, never over-spawns).
     */
    private static final Map<PlotData, Integer> COUNT = new IdentityHashMap<>();

    private PlotMobGuard() {}

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (world.dimension() != FabricPlots.PLOTS_DIM) return;
            if (!(entity instanceof Mob mob) || isExempt(mob)) return;
            BlockPos pos = mob.blockPosition();
            PlotData d = PlotManager.owningPlot(pos.getX(), pos.getZ());
            if (d == null) {
                // Streets are no-spawn land. A plot biome's 4-block bleed reaches road columns,
                // so nether/hostile biomes would otherwise spawn mobs ON the street. Unclaimed
                // plot interiors keep their mobs (isInsidePlot but no owner) — only the street
                // band (roads, curbs, stairs) culls on sight. Same test StreetSweeper uses.
                if (!PlotManager.isInsidePlot(pos.getX(), pos.getZ())) {
                    world.getServer().execute(mob::discard); // next tick — never mid-load
                }
                return;
            }
            // The owner's spawn toggles: cull disallowed categories the moment they appear
            // (covers natural spawns AND mobs re-loaded from disk after the toggle changed).
            if (!allowed(d, mob)) {
                world.getServer().execute(mob::discard); // next tick — never mid-load
                return;
            }
            // The master switch ("mob-spawning=off" — no plot spawns anything, whatever the
            // toggles say) and the per-plot mob cap: a full plot accepts no more tracked mobs.
            if (PlotsConfig.mobSpawningOff || tracked(d) >= effectiveCap(world.getServer(), d)) {
                world.getServer().execute(mob::discard); // next tick — never mid-load
                return;
            }
            track(d, mob, pos);
        });
    }

    /** Adopt a non-pet mob onto a plot: remember its home, tether its AI, count it toward the cap. */
    private static void track(PlotData d, Mob mob, BlockPos pos) {
        HOME.put(mob.getUUID(), pos.immutable());
        Compat.setHome(mob, pos, HOME_RADIUS);
        COUNT.merge(d, 1, Integer::sum);
    }

    /** Live tracked-mob count for a plot (see {@link #COUNT}). */
    private static int tracked(PlotData d) {
        return COUNT.getOrDefault(d, 0);
    }

    /**
     * The plot's effective mob ceiling: the owner's chosen per-cell cap (or the server
     * default when unset), clamped to the server ceiling, times the merge's cell count.
     */
    public static int effectiveCap(MinecraftServer server, PlotData d) {
        int ceiling = capCeiling(server, d);
        int perCell = Math.min(d.mobCap < 0 ? PlotsConfig.mobCapPerPlot : d.mobCap, ceiling);
        long total = (long) perCell * Math.max(1, d.cells.size());
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * The per-cell server ceiling for this plot: {@code mob-cap-per-plot}, raised by the
     * owner's {@code fabricplots.mobcap.N} permission tier. Permission checks need a live
     * player, so the tier only applies while the owner is ONLINE — plots of offline owners
     * fall back to the plain config ceiling (kept simple on purpose).
     */
    public static int capCeiling(MinecraftServer server, PlotData d) {
        ServerPlayer owner = server.getPlayerList().getPlayer(d.owner);
        return owner == null ? PlotsConfig.mobCapPerPlot : Perms.mobCap(owner, PlotsConfig.mobCapPerPlot);
    }

    /** Does this plot's owner allow this non-pet mob's category? */
    private static boolean allowed(PlotData d, Mob mob) {
        boolean hostile = mob.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER;
        return hostile ? d.spawnHostile : d.spawnPassive;
    }

    /** Remove a plot's non-pet mobs and item drops (used when its biome changes). Returns count. */
    public static int purgeMobsAndDrops(ServerLevel plots, PlotData d) {
        return purge(plots, d, false, null);
    }

    /** Remove EVERY non-player entity on a plot (used by /plot clear). Returns count. */
    public static int purgeAllEntities(ServerLevel plots, PlotData d) {
        return purge(plots, d, true, null);
    }

    /** Remove a plot's non-pet mobs of one category (used when a spawn toggle is switched off). */
    public static int purgeCategory(ServerLevel plots, PlotData d, boolean hostile) {
        return purge(plots, d, false, hostile);
    }

    private static int purge(ServerLevel plots, PlotData d, boolean everything, Boolean hostileOnly) {
        if (plots == null || d == null) return 0;
        java.util.List<Entity> doomed = new java.util.ArrayList<>();
        for (Entity e : plots.getAllEntities()) {
            if (e instanceof net.minecraft.server.level.ServerPlayer) continue;
            boolean onPlot = PlotManager.owningPlot(e.getBlockX(), e.getBlockZ()) == d;
            if (everything) { if (onPlot) doomed.add(e); continue; }
            if (e instanceof net.minecraft.world.entity.item.ItemEntity && hostileOnly == null) { if (onPlot) doomed.add(e); continue; }
            if (!(e instanceof Mob mob) || isExempt(mob)) continue;
            // A mob belongs to the purge if it stands on the plot OR its home is there
            // (escapees loitering on the road while the sweep hasn't caught them yet).
            if (!onPlot) {
                BlockPos home = HOME.get(mob.getUUID());
                if (home == null || PlotManager.owningPlot(home.getX(), home.getZ()) != d) continue;
            }
            if (hostileOnly != null) {
                boolean isHostile = mob.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER;
                if (isHostile != hostileOnly) continue;
            }
            doomed.add(e);
        }
        for (Entity e : doomed) { HOME.remove(e.getUUID()); e.discard(); }
        return doomed.size();
    }

    /** Called every couple of seconds: return escapees to their plot, forget unloaded mobs. */
    public static void tick(ServerLevel plots) {
        if (plots == null) return;
        Set<UUID> seen = new HashSet<>();
        Map<PlotData, Integer> counts = new IdentityHashMap<>(); // authoritative rebuild for the mob cap
        for (Entity e : plots.getAllEntities()) {
            if (!(e instanceof Mob mob) || isExempt(mob)) continue;
            seen.add(mob.getUUID());
            BlockPos home = HOME.get(mob.getUUID());
            PlotData here = PlotManager.owningPlot(mob.getBlockX(), mob.getBlockZ());
            if (home == null) {
                // First sighting (e.g. loaded from disk): adopt its current plot as home.
                if (here != null) {
                    HOME.put(mob.getUUID(), mob.blockPosition().immutable());
                    Compat.setHome(mob, mob.blockPosition(), HOME_RADIUS);
                    counts.merge(here, 1, Integer::sum);
                } else if (!PlotManager.isInsidePlot(mob.getBlockX(), mob.getBlockZ())) {
                    // Streets are no-spawn land: an untracked mob standing on the street band
                    // goes immediately instead of waiting minutes for the unnamed-mob cleanup.
                    mob.discard();
                }
                continue; // unclaimed-plot mobs stay untracked; the unnamed-mob cleanup culls them
            }
            PlotData homePlot = PlotManager.owningPlot(home.getX(), home.getZ());
            if (homePlot == null) { HOME.remove(mob.getUUID()); continue; } // plot was deleted
            if (here != homePlot) {
                // mob-escape-action config: teleport escapees home (default) or despawn them at
                // the boundary. Never send one home to a plot whose spawn toggle no longer
                // allows its category. Only genuinely tamed pets are exempt from the sweep.
                if (PlotsConfig.mobEscapeDespawn || !allowed(homePlot, mob)) {
                    mob.discard();
                    continue; // gone — don't count it toward its plot's cap
                }
                mob.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                mob.getNavigation().stop();
            }
            counts.merge(homePlot, 1, Integer::sum);
        }
        HOME.keySet().retainAll(seen);
        COUNT.clear();
        COUNT.putAll(counts);
    }

    /** Only genuinely tamed pets bypass plot spawn controls; a name tag by itself never does. */
    public static boolean isExempt(Mob mob) {
        if (mob instanceof net.minecraft.world.entity.TamableAnimal pet) return pet.isTame();
        if (mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse horse) return horse.isTamed();
        return false;
    }
}
