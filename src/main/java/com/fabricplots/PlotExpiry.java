package com.fabricplots;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks each owner's last-seen time and (when enabled) auto-releases plots whose owner has been
 * absent longer than the configured day count. Online owners are continually refreshed, so a plot
 * never expires while its owner is around. Releasing FREES the plot (it doesn't wipe the build —
 * the next claimer can /plot clear it).
 */
public final class PlotExpiry {
    private static final long MS_PER_DAY = 86_400_000L;
    private static final Map<UUID, Long> LAST_SEEN = new ConcurrentHashMap<>();
    private static Path file;
    private static volatile boolean dirty = false;

    private PlotExpiry() {}

    /** Mark a player as active right now. */
    public static void touch(UUID id, long now) { LAST_SEEN.put(id, now); dirty = true; }

    public static void load(MinecraftServer server) {
        file = server.getWorldPath(LevelResource.ROOT).resolve("fabricplots-lastseen.txt");
        LAST_SEEN.clear();
        dirty = false;
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                String[] parts = line.split(" ");
                try { LAST_SEEN.put(UUID.fromString(parts[0].trim()), Long.parseLong(parts[1].trim())); }
                catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("[FabricPlots] Failed to read last-seen: " + e);
        }
    }

    public static void save() {
        if (file == null || !dirty) return;
        List<String> lines = new ArrayList<>(LAST_SEEN.size());
        for (Map.Entry<UUID, Long> e : LAST_SEEN.entrySet()) lines.add(e.getKey() + " " + e.getValue());
        try { Files.write(file, lines); dirty = false; }
        catch (Exception e) { System.err.println("[FabricPlots] Failed to save last-seen: " + e); }
    }

    /** Refresh all online owners, then release any plot whose owner has been gone too long. */
    public static void tick(MinecraftServer server, long now) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) { online.add(p.getUUID()); touch(p.getUUID(), now); }
        if (!PlotsConfig.inactivityExpiry || PlotsConfig.inactivityDays <= 0) return;

        final long maxAge = (long) PlotsConfig.inactivityDays * MS_PER_DAY;
        List<PlotData> expire = new ArrayList<>();
        for (PlotData d : PlotManager.allPlots()) {
            if (PlotManager.isServer(d.owner) || online.contains(d.owner)) continue; // server/online owners never expire
            Long seen = LAST_SEEN.get(d.owner);
            if (seen != null && now - seen > maxAge) expire.add(d);
        }
        if (expire.isEmpty()) return;

        ServerLevel plots = server.getLevel(FabricPlots.PLOTS_DIM);
        for (PlotData d : expire) {
            List<PlotPos> cells = new ArrayList<>(d.cells);
            if (plots != null && !d.biomeId.isBlank()) PlotBiomes.resetBiome(plots, d); // before unclaim
            PlotManager.unclaim(cells.get(0)); // removes the whole group
            if (plots != null) for (PlotPos c : cells) PortalManager.removeExitPortalIfOrphan(plots, c);
            System.out.println("[FabricPlots] Released " + (d.ownerName.isBlank() ? d.owner : d.ownerName)
                    + "'s plot (" + cells.size() + " cell(s)) — inactive over " + PlotsConfig.inactivityDays + " days.");
        }
    }
}
