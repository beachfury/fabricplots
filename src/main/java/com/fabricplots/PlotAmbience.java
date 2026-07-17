package com.fabricplots;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-plot sky illusion: the owner picks a time-of-day and/or weather look, and anyone standing on
 * the plot sees it — client-side only, via play packets. The server's real time/weather never
 * changes, so redstone, spawning and every other plot are untouched. Works for Bedrock players too
 * (Geyser translates both packet types).
 *
 * Stored on {@link PlotData#ambience} as "time:weather", e.g. "sunset:clear", "real:rain".
 * The fake clock is sent with rate 0 (frozen) and re-sent every tick so the server's periodic
 * clock sync can't flicker it back; stepping off the plot restores the real sky.
 */
public final class PlotAmbience {
    /** What each online player currently sees (absent = the real sky). */
    private static final Map<UUID, String> ACTIVE = new HashMap<>();

    private PlotAmbience() {}

    public static final String[] TIMES = { "real", "day", "noon", "sunset", "night", "midnight" };
    public static final String[] WEATHERS = { "real", "clear", "rain", "thunder" };

    /** Day-cycle tick for a named time, or -1 for "real". */
    public static long timeTicks(String time) {
        return switch (time) {
            case "day" -> 1000L;
            case "noon" -> 6000L;
            case "sunset" -> 12800L;
            case "night" -> 15000L;
            case "midnight" -> 18000L;
            default -> -1L;
        };
    }

    public static String timeOf(String ambience) {
        int i = ambience.indexOf(':');
        return i < 0 ? ambience : ambience.substring(0, i);
    }

    public static String weatherOf(String ambience) {
        int i = ambience.indexOf(':');
        return i < 0 ? "real" : ambience.substring(i + 1);
    }

    /** Compose the stored value; returns "" when both parts are "real" (illusion off). */
    public static String compose(String time, String weather) {
        return (timeTicks(time) < 0 && "real".equals(weather)) ? "" : time + ":" + weather;
    }

    // ---- per-tick driver (called from the FabricPlots tick loop) ---------

    /** Player is in the plot world, standing on {@code pd} (null on roads/unclaimed). */
    public static void tick(ServerPlayer p, PlotData pd) {
        String amb = (pd == null || pd.ambience == null) ? "" : pd.ambience;
        String prev = ACTIVE.get(p.getUUID());
        if (amb.isBlank()) {
            if (prev != null) restore(p);
            return;
        }
        if (!amb.equals(prev)) {
            ACTIVE.put(p.getUUID(), amb);
            sendFakeWeather(p, weatherOf(amb));
        }
        long ticks = timeTicks(timeOf(amb));
        if (ticks >= 0) sendFakeTime(p, ticks); // every tick: outruns the server's periodic clock sync
    }

    /** Player left the plot world (or disconnected) — drop any illusion state. */
    public static void reset(ServerPlayer p) {
        if (ACTIVE.remove(p.getUUID()) != null && p.level() instanceof ServerLevel) restoreReal(p);
    }

    private static void restore(ServerPlayer p) {
        ACTIVE.remove(p.getUUID());
        restoreReal(p);
    }

    // ---- packets ---------------------------------------------------------

    private static void sendFakeTime(ServerPlayer p, long dayTicks) {
        ServerLevel level = (ServerLevel) p.level();
        Holder<WorldClock> clock = level.registryAccess()
                .lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
        // rate 0 = frozen at that moment on the client.
        p.connection.send(new ClientboundSetTimePacket(level.getGameTime(),
                Map.of(clock, new ClockNetworkState(dayTicks, 0.0f, 0.0f))));
    }

    private static void sendFakeWeather(ServerPlayer p, String weather) {
        switch (weather) {
            case "clear" -> {
                p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0f));
                p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.0f));
                p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0f));
            }
            case "rain" -> {
                p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0f));
                p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 1.0f));
                p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0f));
            }
            case "thunder" -> {
                p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0f));
                p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 1.0f));
                p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 1.0f));
            }
            default -> { /* "real" — leave the client's weather alone */ }
        }
    }

    private static void restoreReal(ServerPlayer p) {
        ServerLevel level = (ServerLevel) p.level();
        p.connection.send(level.clockManager().createFullSyncPacket());
        if (level.isRaining()) {
            p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0f));
            p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1.0f)));
            p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1.0f)));
        } else {
            p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0f));
            p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.0f));
            p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0f));
        }
    }
}
