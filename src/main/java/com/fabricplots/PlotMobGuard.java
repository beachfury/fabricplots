package com.fabricplots;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.HashSet;
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
 * Named mobs (pets, display mobs) are exempt, matching the unnamed-mob cleanup rule.
 */
public final class PlotMobGuard {
    /** Where each unnamed plot-world mob belongs (not persisted — re-learned from position on load). */
    private static final Map<UUID, BlockPos> HOME = new HashMap<>();
    private static final int HOME_RADIUS = 14;

    private PlotMobGuard() {}

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (world.dimension() != FabricPlots.PLOTS_DIM) return;
            if (!(entity instanceof Mob mob) || mob.hasCustomName()) return;
            BlockPos pos = mob.blockPosition();
            if (PlotManager.owningPlot(pos.getX(), pos.getZ()) != null) {
                HOME.put(mob.getUUID(), pos.immutable());
                mob.setHomeTo(pos, HOME_RADIUS);
            }
        });
    }

    /** Called every couple of seconds: return escapees to their plot, forget unloaded mobs. */
    public static void tick(ServerLevel plots) {
        if (plots == null) return;
        Set<UUID> seen = new HashSet<>();
        for (Entity e : plots.getAllEntities()) {
            if (!(e instanceof Mob mob) || mob.hasCustomName()) continue;
            seen.add(mob.getUUID());
            BlockPos home = HOME.get(mob.getUUID());
            PlotData here = PlotManager.owningPlot(mob.getBlockX(), mob.getBlockZ());
            if (home == null) {
                // First sighting (e.g. loaded from disk): adopt its current plot as home.
                if (here != null) {
                    HOME.put(mob.getUUID(), mob.blockPosition().immutable());
                    mob.setHomeTo(mob.blockPosition(), HOME_RADIUS);
                }
                continue; // road mobs stay untracked; the unnamed-mob cleanup culls them
            }
            PlotData homePlot = PlotManager.owningPlot(home.getX(), home.getZ());
            if (homePlot == null) { HOME.remove(mob.getUUID()); continue; } // plot was deleted
            if (here != homePlot) {
                mob.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                mob.getNavigation().stop();
            }
        }
        HOME.keySet().retainAll(seen);
    }
}
