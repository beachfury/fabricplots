package com.fabricplots.player;

import com.fabricplots.FabricPlots;
import com.fabricplots.core.PlotData;
import com.fabricplots.core.PlotManager;
import com.fabricplots.world.PlotBiomes;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optional Patbox Placeholder API integration (same soft-dependency pattern as the economy bridge):
 * if the placeholder-api mod is present at runtime, FabricPlots registers %fabricplots:*%
 * placeholders for tab lists, chat formats, holograms, etc. Without it, nothing happens.
 *
 *   %fabricplots:owned%        — how many plots the player owns
 *   %fabricplots:total%        — total claimed plots on the server
 *   %fabricplots:plot_name%    — name of the plot the player is standing on ("" off-plot)
 *   %fabricplots:plot_owner%   — owner of the plot the player is standing on
 *   %fabricplots:plot_likes%   — like count of the plot the player is standing on
 *   %fabricplots:plot_biome%   — biome label of the plot the player is standing on
 *   %fabricplots:my_likes%     — total likes across all the player's plots
 */
public final class PlotPlaceholders {
    private PlotPlaceholders() {}

    public static void register() {
        try {
            Bridge.register();
            System.out.println("[FabricPlots] Placeholder API detected — %fabricplots:*% placeholders registered");
        } catch (NoClassDefFoundError e) {
            // placeholder-api not installed — fine, it's optional.
        }
    }

    /** Isolated so the Placeholder API classes only load when the mod is actually present. */
    // Placeholder API 2.x (1.21.1 era): Placeholders.register + PlaceholderContext.hasPlayer/player
    // (3.x renamed these to registerServer + ServerPlaceholderContext.hasServerPlayer/serverPlayer).
    private static final class Bridge {
        static void register() {
            eu.pb4.placeholders.api.Placeholders.register(id("owned"), (ctx, arg) -> {
                if (!ctx.hasPlayer()) return eu.pb4.placeholders.api.PlaceholderResult.invalid("No player");
                return eu.pb4.placeholders.api.PlaceholderResult.value(
                        String.valueOf(PlotManager.ownedCount(ctx.player().getUUID())));
            });
            eu.pb4.placeholders.api.Placeholders.register(id("total"), (ctx, arg) ->
                    eu.pb4.placeholders.api.PlaceholderResult.value(String.valueOf(PlotManager.allPlots().size())));
            eu.pb4.placeholders.api.Placeholders.register(id("plot_name"), (ctx, arg) -> {
                PlotData d = standingPlot(ctx);
                return eu.pb4.placeholders.api.PlaceholderResult.value(d == null ? "" : (d.name.isBlank() ? "unnamed" : d.name));
            });
            eu.pb4.placeholders.api.Placeholders.register(id("plot_owner"), (ctx, arg) -> {
                PlotData d = standingPlot(ctx);
                return eu.pb4.placeholders.api.PlaceholderResult.value(d == null ? "" : (d.ownerName.isBlank() ? "someone" : d.ownerName));
            });
            eu.pb4.placeholders.api.Placeholders.register(id("plot_likes"), (ctx, arg) -> {
                PlotData d = standingPlot(ctx);
                return eu.pb4.placeholders.api.PlaceholderResult.value(d == null ? "" : String.valueOf(d.likes.size()));
            });
            eu.pb4.placeholders.api.Placeholders.register(id("plot_biome"), (ctx, arg) -> {
                PlotData d = standingPlot(ctx);
                return eu.pb4.placeholders.api.PlaceholderResult.value(d == null ? "" : PlotBiomes.labelOf(d.biomeId));
            });
            eu.pb4.placeholders.api.Placeholders.register(id("my_likes"), (ctx, arg) -> {
                if (!ctx.hasPlayer()) return eu.pb4.placeholders.api.PlaceholderResult.invalid("No player");
                int likes = 0;
                for (PlotData d : PlotManager.allPlots())
                    if (ctx.player().getUUID().equals(d.owner)) likes += d.likes.size();
                return eu.pb4.placeholders.api.PlaceholderResult.value(String.valueOf(likes));
            });
        }

        private static PlotData standingPlot(eu.pb4.placeholders.api.PlaceholderContext ctx) {
            if (!ctx.hasPlayer()) return null;
            ServerPlayer p = ctx.player();
            if (p.level().dimension() != FabricPlots.PLOTS_DIM) return null;
            return PlotManager.owningPlot(p.getBlockX(), p.getBlockZ());
        }

        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath("fabricplots", path);
        }
    }
}
