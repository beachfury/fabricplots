package com.fabricplots;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plot grid math + ownership registry + flat-file persistence.
 * Persistence is dependency-free (plain text) to avoid any Gson/NBT classpath
 * surprises on a fresh 26.x toolchain.
 */
public final class PlotManager {
    // Concurrent: read by the chunk-gen worker threads (paintBase) while the main thread claims/combines.
    private static final Map<PlotPos, PlotData> PLOTS = new ConcurrentHashMap<>();
    private static Path saveFile;

    /** Sentinel owner for server-owned plots (showcases, spawn builds, community plots). */
    public static final UUID SERVER_UUID = new UUID(0L, 0L);

    public static boolean isServer(UUID owner) { return SERVER_UUID.equals(owner); }

    /** Number of distinct plots (a merge counts once) owned by a player. */
    public static int ownedCount(UUID owner) {
        java.util.Set<PlotData> groups = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (PlotData d : PLOTS.values()) if (owner.equals(d.owner)) groups.add(d);
        return groups.size();
    }

    private PlotManager() {}

    // ---- grid math -------------------------------------------------------

    // Buildable area is the 38-wide plot (grass + sidewalk ring), offset by SIDEWALK_DEPTH so
    // each plot owns its sidewalk on all four sides. Stairs + road fall outside it (locked).

    /** True if a block X/Z is inside a buildable plot (false = stair or road). */
    public static boolean isInsidePlot(int x, int z) {
        int lxx = Math.floorMod(x + PlotConfig.SIDEWALK_DEPTH, PlotConfig.STEP);
        int lzz = Math.floorMod(z + PlotConfig.SIDEWALK_DEPTH, PlotConfig.STEP);
        return lxx < PlotConfig.BUILDABLE && lzz < PlotConfig.BUILDABLE;
    }

    /** The plot a block X/Z belongs to (its sidewalk maps to the adjacent plot). */
    public static PlotPos plotAt(int x, int z) {
        return new PlotPos(Math.floorDiv(x + PlotConfig.SIDEWALK_DEPTH, PlotConfig.STEP),
                Math.floorDiv(z + PlotConfig.SIDEWALK_DEPTH, PlotConfig.STEP));
    }

    /** Center block X/Z of a plot, used as the teleport/home target. */
    public static int[] homeXZ(PlotPos p) {
        int baseX = p.px() * PlotConfig.STEP;
        int baseZ = p.pz() * PlotConfig.STEP;
        return new int[] { baseX + PlotConfig.PLOT_SIZE / 2, baseZ + PlotConfig.PLOT_SIZE / 2 };
    }

    // ---- ownership -------------------------------------------------------

    public static PlotData get(PlotPos p) { return PLOTS.get(p); }

    /** Every claimed cell (a merge contributes each of its cells). */
    public static java.util.Set<PlotPos> allClaimedCells() { return new java.util.HashSet<>(PLOTS.keySet()); }

    /** Every distinct plot (a merge counts once). */
    public static java.util.Set<PlotData> allPlots() {
        java.util.Set<PlotData> s = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        s.addAll(PLOTS.values());
        return s;
    }

    public static boolean isClaimed(PlotPos p) { return PLOTS.containsKey(p); }

    public static void claim(PlotPos p, UUID owner, String ownerName) {
        PlotData d = new PlotData(owner, ownerName);
        d.cells.add(p);
        PLOTS.put(p, d);
        save();
    }

    public static void unclaim(PlotPos p) {
        PlotData d = PLOTS.get(p);
        if (d != null) for (PlotPos c : d.cells) PLOTS.remove(c); // removes the whole merge group
        else PLOTS.remove(p);
        save();
    }

    /** Merge several cells into one plot owned by {@code owner}, dissolving any old groups they were in. */
    public static void combine(java.util.Collection<PlotPos> cells, UUID owner, String ownerName) {
        // Carry the paid amounts of the merged plots into the combined plot (for refund accounting).
        java.util.Set<PlotData> olds = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (PlotPos c : cells) { PlotData o = PLOTS.get(c); if (o != null) olds.add(o); }
        long paidSum = 0;
        for (PlotData o : olds) { paidSum += o.paidAmount; for (PlotPos oc : new ArrayList<>(o.cells)) PLOTS.remove(oc); }
        PlotData d = new PlotData(owner, ownerName);
        d.paidAmount = paidSum;
        d.cells.addAll(cells);
        for (PlotPos c : cells) PLOTS.put(c, d);
        save();
    }

    /** Split a merged plot back into individual single-cell plots (same owner/trust/deny). Returns the cells. */
    public static java.util.List<PlotPos> uncombine(PlotPos any) {
        PlotData d = PLOTS.get(any);
        if (d == null || d.cells.size() <= 1) return new ArrayList<>();
        java.util.List<PlotPos> cells = new ArrayList<>(d.cells);
        long perCellPaid = cells.isEmpty() ? 0 : d.paidAmount / cells.size(); // split the paid amount evenly
        for (PlotPos c : cells) {
            PlotData single = new PlotData(d.owner, d.ownerName);
            single.name = d.name;
            single.floorBlockId = d.floorBlockId;
            single.pvp = d.pvp;
            single.paidAmount = perCellPaid;
            single.trusted.addAll(d.trusted);
            single.denied.addAll(d.denied);
            single.likes.addAll(d.likes);
            single.cells.add(c);
            PLOTS.put(c, single);
        }
        save();
        return cells;
    }

    /** Remove every plot owned by a player (for cleanup when they leave the server). Returns count. */
    public static int removeAllOwnedBy(UUID owner) {
        java.util.Set<PlotData> groups = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (PlotData d : PLOTS.values()) if (owner.equals(d.owner)) groups.add(d);
        for (PlotData d : groups) for (PlotPos c : d.cells) PLOTS.remove(c);
        if (!groups.isEmpty()) save();
        return groups.size();
    }

    /** True if the two cells belong to the same merged plot. */
    public static boolean sameMerge(int px1, int pz1, int px2, int pz2) {
        PlotData a = PLOTS.get(new PlotPos(px1, pz1));
        PlotData b = PLOTS.get(new PlotPos(px2, pz2));
        return a != null && a == b;
    }

    /** True if this block X/Z sits in a gap (stair/road) that is INTERIOR to a merge (cells across it merged). */
    public static boolean isMergedGap(int x, int z) {
        int lxx = Math.floorMod(x + PlotConfig.SIDEWALK_DEPTH, PlotConfig.STEP);
        int lzz = Math.floorMod(z + PlotConfig.SIDEWALK_DEPTH, PlotConfig.STEP);
        boolean gapX = lxx >= PlotConfig.BUILDABLE;
        boolean gapZ = lzz >= PlotConfig.BUILDABLE;
        if (!gapX && !gapZ) return false;
        int px = Math.floorDiv(x + PlotConfig.SIDEWALK_DEPTH, PlotConfig.STEP);
        int pz = Math.floorDiv(z + PlotConfig.SIDEWALK_DEPTH, PlotConfig.STEP);
        if (gapX && gapZ) {
            return sameMerge(px, pz, px + 1, pz) && sameMerge(px, pz, px, pz + 1) && sameMerge(px, pz, px + 1, pz + 1);
        }
        return gapX ? sameMerge(px, pz, px + 1, pz) : sameMerge(px, pz, px, pz + 1);
    }

    /** The plot that owns this block X/Z (a cell's buildable area, or a merge's dissolved interior), else null. */
    public static PlotData owningPlot(int x, int z) {
        if (isInsidePlot(x, z) || isMergedGap(x, z)) return PLOTS.get(plotAt(x, z));
        return null;
    }

    /** True if X/Z is buildable AND within SIDEWALK_DEPTH of the plot's edge (so it's sidewalk, not interior). */
    public static boolean nearMergeEdge(int x, int z) {
        PlotData here = owningPlot(x, z);
        if (here == null) return false;
        for (int d = 1; d <= PlotConfig.SIDEWALK_DEPTH; d++) {
            if (owningPlot(x + d, z) != here || owningPlot(x - d, z) != here
                    || owningPlot(x, z + d) != here || owningPlot(x, z - d) != here) return true;
        }
        return false;
    }

    /** First plot owned by a player (used by /plot home). */
    public static PlotPos firstOwned(UUID owner) {
        for (Map.Entry<PlotPos, PlotData> e : PLOTS.entrySet()) {
            if (owner.equals(e.getValue().owner)) return e.getKey();
        }
        return null;
    }

    /** Next unclaimed plot, spiralling outward from the origin (like /plot auto). */
    public static PlotPos nextFree() {
        for (int radius = 0; radius < 1000; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue; // ring only
                    PlotPos p = new PlotPos(dx, dz);
                    if (!isClaimed(p)) return p;
                }
            }
        }
        return null;
    }

    // ---- persistence -----------------------------------------------------

    public static void load(MinecraftServer server) {
        saveFile = server.getWorldPath(LevelResource.ROOT).resolve("fabricplots-plots.txt");
        PLOTS.clear();
        if (!Files.exists(saveFile)) return;
        // format: ownerUUID;ownerName;trusted1,trusted2;px1:pz1,px2:pz2;plotName  (one line per group)
        for (String line : safeLines()) {
            if (line.isBlank()) continue;
            try {
                String[] parts = line.split(";", -1);
                PlotData d = new PlotData(UUID.fromString(parts[0].trim()), parts.length > 1 ? parts[1] : "");
                if (parts.length > 2 && !parts[2].isBlank())
                    for (String t : parts[2].split(",")) if (!t.isBlank()) d.trusted.add(UUID.fromString(t.trim()));
                if (parts.length > 3 && !parts[3].isBlank())
                    for (String c : parts[3].split(",")) {
                        String[] cc = c.split(":");
                        d.cells.add(new PlotPos(Integer.parseInt(cc[0].trim()), Integer.parseInt(cc[1].trim())));
                    }
                if (parts.length > 4) d.name = parts[4];
                if (parts.length > 5 && !parts[5].isBlank())
                    for (String dn : parts[5].split(",")) if (!dn.isBlank()) d.denied.add(UUID.fromString(dn.trim()));
                if (parts.length > 6 && !parts[6].isBlank()) {
                    String[] h = parts[6].split(":");
                    d.home = new net.minecraft.core.BlockPos(Integer.parseInt(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]));
                }
                if (parts.length > 7) d.floorBlockId = parts[7];
                if (parts.length > 8 && !parts[8].isBlank()) d.pvp = Boolean.parseBoolean(parts[8].trim());
                if (parts.length > 9 && !parts[9].isBlank())
                    for (String u : parts[9].split(",")) if (!u.isBlank()) d.likes.add(UUID.fromString(u.trim()));
                if (parts.length > 10 && !parts[10].isBlank()) { try { d.paidAmount = Long.parseLong(parts[10].trim()); } catch (NumberFormatException ignored) {} }
                for (PlotPos c : d.cells) PLOTS.put(c, d);
            } catch (Exception e) {
                System.err.println("[FabricPlots] Skipped bad plot line: " + line + " (" + e + ")");
            }
        }
    }

    private static List<String> safeLines() {
        try { return Files.readAllLines(saveFile); }
        catch (IOException e) { System.err.println("[FabricPlots] Failed to read plots: " + e); return new ArrayList<>(); }
    }

    public static void save() {
        if (saveFile == null) return;
        List<String> lines = new ArrayList<>();
        java.util.Set<PlotData> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (PlotData d : PLOTS.values()) {
            if (!seen.add(d)) continue; // each PlotData written once even if it spans many cells
            StringJoiner tj = new StringJoiner(",");
            for (UUID t : d.trusted) tj.add(t.toString());
            StringJoiner cj = new StringJoiner(",");
            for (PlotPos c : d.cells) cj.add(c.px() + ":" + c.pz());
            StringJoiner dj = new StringJoiner(",");
            for (UUID dn : d.denied) dj.add(dn.toString());
            StringJoiner lj = new StringJoiner(",");
            for (UUID lk : d.likes) lj.add(lk.toString());
            String home = d.home == null ? "" : d.home.getX() + ":" + d.home.getY() + ":" + d.home.getZ();
            String floor = d.floorBlockId == null ? "" : d.floorBlockId;
            lines.add(d.owner + ";" + d.ownerName + ";" + tj + ";" + cj + ";" + d.name + ";" + dj + ";" + home
                    + ";" + floor + ";" + d.pvp + ";" + lj + ";" + d.paidAmount);
        }
        try {
            Files.write(saveFile, lines);
        } catch (IOException e) {
            System.err.println("[FabricPlots] Failed to save plots: " + e);
        }
    }
}
