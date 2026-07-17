package com.fabricplots;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FabricPlots implements ModInitializer {

    /** ResourceKey for our datapack dimension (data/fabricplots/dimension/plots.json). */
    public static final ResourceKey<Level> PLOTS_DIM = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(PlotConfig.DIM_NAMESPACE, PlotConfig.DIM_PATH));

    // Last known dimension per player. This Fabric API version has no
    // world-change event, so we poll once per server tick instead.
    private static final Map<UUID, ResourceKey<Level>> LAST_DIM = new HashMap<>();
    private static final Map<UUID, PlotData> LAST_PLOT = new HashMap<>();
    private static int mobScanTick = 0;

    @Override
    public void onInitialize() {
        // Live, admin-editable settings (config/fabricplots.properties).
        PlotsConfig.load();

        // Load / save plot ownership + the "decorated chunks" record with the world.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PlotManager.load(server);
            PlotWorldPainter.loadDecorated(server);
            PortalManager.load(server);
            PlotExpiry.load(server);
            applyWorldRules(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PlotManager.save();
            PlotWorldPainter.saveDecorated();
            PortalManager.save();
            PlotExpiry.save();
        });

        // Commands (work for Bedrock players via Geyser — they just type them).
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                PlotCommands.register(dispatcher, access));

        // Combine wand must see right-clicks before protection consumes them.
        CombineWand.register();
        // Frame portals (calcite frame + flint&steel / Plot Key) between home world and plots.
        PortalManager.register();
        // Plot edit wand (selection) — before protection so it can swallow its own clicks.
        PlotEdit.register();
        // Grief protection inside the plots world.
        PlotProtection.register();
        // Biome-spawned mobs are confined to the plot they spawned on.
        PlotMobGuard.register();
        // %fabricplots:*% placeholders (only when Patbox's Placeholder API is installed).
        PlotPlaceholders.register();

        // Paint roads / sidewalks as chunks generate (base only — no block-entities mid-gen).
        ServerChunkEvents.CHUNK_GENERATE.register(PlotWorldPainter::onGenerate);
        // Place furniture (lamps etc.) AFTER a chunk loads — block-entities are safe post-gen.
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, newChunk) ->
                PlotWorldPainter.onChunkLoad(world, chunk));

        ServerTickEvents.END_SERVER_TICK.register(FabricPlots::onServerTick);

        // Onboarding: point new arrivals at the plot world.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                handler.player.sendSystemMessage(Component.literal(
                        "Welcome! Step through the portal at spawn, or type /plot world, to start building your plot. /plot help for commands.")));
    }

    private static int portalParticleTick = 0;
    private static int mobGuardTick = 0;
    private static int sweeperTick = 0;

    /** Apply the time/weather config to the plot world's game rules (start-up + /plot reload). */
    public static void applyWorldRules(MinecraftServer server) {
        ServerLevel plots = server.getLevel(PLOTS_DIM);
        if (plots == null) return;
        var rules = plots.getGameRules();
        rules.set(GameRules.ADVANCE_TIME, PlotsConfig.advanceTime, server);
        rules.set(GameRules.ADVANCE_WEATHER, PlotsConfig.advanceWeather, server);
        // Build-protection flags (a flag = true means "protect", so the matching gamerule is turned OFF).
        rules.set(GameRules.TNT_EXPLODES, !PlotsConfig.protectExplosions, server);
        rules.set(GameRules.MOB_GRIEFING, !PlotsConfig.protectMobGriefing, server);
        rules.set(GameRules.PROJECTILES_CAN_BREAK_BLOCKS, !PlotsConfig.protectProjectiles, server);
        rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, PlotsConfig.protectFire ? 0 : 100000, server);
    }

    private static void onServerTick(MinecraftServer server) {
        // Decorate a few freshly-loaded plots chunks per tick (post-gen furniture placement).
        PlotWorldPainter.processPending(server.getLevel(PLOTS_DIM), 32);

        // Portal swirl particles (a few times a second is plenty).
        if (++portalParticleTick >= 5) { portalParticleTick = 0; PortalManager.spawnParticles(server); }

        // Send biome-spawned escapee mobs back to their plot every couple of seconds.
        if (++mobGuardTick >= 40) { mobGuardTick = 0; PlotMobGuard.tick(server.getLevel(PLOTS_DIM)); }

        // Self-healing streets: remove anything foreign (snow, spills, tree canopies) near players.
        if (PlotsConfig.streetSweeper && ++sweeperTick >= 100) {
            sweeperTick = 0;
            StreetSweeper.sweep(server.getLevel(PLOTS_DIM));
        }

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ResourceKey<Level> dim = p.level().dimension();

            // Frame-portal travel (standing in a lit portal warps you).
            PortalManager.onPlayerTick(server, p);

            // Welcome message when stepping onto a (different) plot — and bounce denied players off it.
            if (dim == PLOTS_DIM) {
                PlotData pd = PlotManager.owningPlot(p.getBlockX(), p.getBlockZ());
                PlotData last = LAST_PLOT.put(p.getUUID(), pd);
                if (pd != null && pd.denied.contains(p.getUUID()) && !PlotProtection.isAdmin(p)) {
                    p.teleportTo((ServerLevel) p.level(), PlotsConfig.spawnX + 0.5, PlotsConfig.spawnY, PlotsConfig.spawnZ + 0.5,
                            java.util.Set.of(), p.getYRot(), 0.0f, false);
                    if (pd != last) p.sendOverlayMessage(Component.literal("You're denied from that plot."));
                } else if (pd != null && pd != last && !pd.greeting.isBlank()) {
                    // The owner's custom greeting always wins over the stock welcome line.
                    p.sendOverlayMessage(Component.literal(pd.greeting));
                } else if (PlotsConfig.welcomeMessage && pd != null && pd != last) {
                    String owner = pd.ownerName.isBlank() ? "someone" : pd.ownerName;
                    String label = pd.name.isBlank() ? owner + "'s plot" : owner + "'s " + pd.name;
                    // Action bar (small text above the hotbar), not chat — avoids spam as players move around.
                    p.sendOverlayMessage(Component.literal("Welcome " + p.getName().getString() + " to " + label + "!"));
                }
                PlotAmbience.tick(p, pd); // per-plot sky illusion (client-only time/weather)
            } else {
                LAST_PLOT.remove(p.getUUID());
                PlotAmbience.reset(p); // back to the real sky when leaving the plot world
            }

            // "Own rules": creative inside the plots world, survival outside.
            // Exit is unconditionally survival: a "restore your prior mode" round-trip can't be made
            // reliable when another mod may also set gamemode (the snapshot reads the other mod's
            // value). A creative admin just toggles back with /gamemode creative.
            // manage-gamemode=false hands gamemode over entirely to another mod — set it when e.g.
            // Dimensional Inventories has a gameMode on its dimension pools, so the two never fight.
            if (!PlotsConfig.manageGamemode) { LAST_DIM.put(p.getUUID(), dim); continue; }
            ResourceKey<Level> prev = LAST_DIM.put(p.getUUID(), dim);
            if (dim == prev) continue; // ResourceKeys are interned
            if (dim == PLOTS_DIM) {
                p.setGameMode(GameType.CREATIVE);
            } else if (prev == PLOTS_DIM) {
                p.setGameMode(GameType.SURVIVAL);
            }
        }

        // Mob lifecycle is per-plot now: what lives on a plot is the OWNER's choice (the spawn
        // toggles), named or not. The stale-mob cull only applies to STREETS and unclaimed ground —
        // escapees and plaza strays that nobody owns.
        if (++mobScanTick >= PlotsConfig.mobScanIntervalTicks) {
            mobScanTick = 0;
            ServerLevel plots = server.getLevel(PLOTS_DIM);
            if (plots != null) {
                List<Entity> stale = new ArrayList<>();
                for (Entity e : plots.getAllEntities()) {
                    if (e instanceof Mob mob && !mob.hasCustomName()
                            && mob.tickCount > PlotsConfig.unnamedMobGraceTicks
                            && PlotManager.owningPlot(mob.getBlockX(), mob.getBlockZ()) == null) {
                        stale.add(mob);
                    }
                }
                for (Entity e : stale) e.discard();
            }
            PlotWorldPainter.saveDecorated(); // flush the decorated-chunks record periodically
            PlotExpiry.tick(server, System.currentTimeMillis()); // refresh activity + release inactive plots
            PlotExpiry.save();
            StreetSweeper.cleanLitter(plots); // dropped items / boats / stands left on streets
        }
    }
}
