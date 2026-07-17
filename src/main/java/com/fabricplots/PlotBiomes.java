package com.fabricplots;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-plot biome painting: the owner picks a biome and the plot's columns (cores, sidewalk ring,
 * and a merge's dissolved interior) are rewritten to it — grass/foliage/sky tint, ambience, the lot.
 * This is REAL biome data (persists in the chunks, everyone sees it), applied through vanilla's
 * {@code /fillbiome} machinery ({@link FillBiomeCommand#fill}), which also resends the affected
 * chunks' biomes to clients.
 *
 * Fills are tiled so each call stays under the MAX_BLOCK_MODIFICATIONS gamerule volume cap.
 */
public final class PlotBiomes {
    /** The plot world's native biome (see data/fabricplots/dimension/plots.json). */
    public static final String DEFAULT_ID = "fabricplots:plot_biome";

    /** XZ tile side per fill call: 8*8*272 blocks ≈ 17k, safely under the default 32768 cap. */
    private static final int TILE = 8;

    private PlotBiomes() {}

    /**
     * Every biome registered in this world — vanilla, datapacks, and worldgen mods alike — sorted
     * with the minecraft namespace first, then other namespaces alphabetically. The plot world's own
     * default biome is excluded (the picker has a dedicated Default button for it). Built from the
     * live dynamic registry, so new biomes (a new MC version, an installed biome mod) appear
     * automatically with zero code changes.
     */
    public static List<String> allBiomeIds(ServerLevel level) {
        List<String> out = new ArrayList<>();
        try {
            for (Identifier key : level.registryAccess().lookupOrThrow(Registries.BIOME).keySet()) {
                String id = key.toString();
                if (id.equals(DEFAULT_ID)) continue;
                if (id.startsWith("terrablender:")) continue; // internal deferred placeholders, not real biomes
                out.add(id);
            }
        } catch (Exception e) {
            System.err.println("[FabricPlots] Failed to list biomes: " + e);
        }
        out.sort((a, b) -> {
            boolean va = a.startsWith("minecraft:"), vb = b.startsWith("minecraft:");
            if (va != vb) return va ? -1 : 1; // vanilla first, modded after
            return a.compareTo(b);
        });
        return out;
    }

    /** "minecraft:frozen_ocean" → "Frozen Ocean". */
    public static String labelOf(String biomeId) {
        if (biomeId == null || biomeId.isBlank() || biomeId.equals(DEFAULT_ID)) return "Default";
        String path = biomeId.contains(":") ? biomeId.substring(biomeId.indexOf(':') + 1) : biomeId;
        StringBuilder sb = new StringBuilder();
        for (String w : path.replace('/', '_').split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Curated icons for well-known biomes (item registry ids — version-safe). */
    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("minecraft:plains", "minecraft:grass_block"),
            Map.entry("minecraft:sunflower_plains", "minecraft:sunflower"),
            Map.entry("minecraft:flower_forest", "minecraft:poppy"),
            Map.entry("minecraft:birch_forest", "minecraft:birch_sapling"),
            Map.entry("minecraft:dark_forest", "minecraft:dark_oak_sapling"),
            Map.entry("minecraft:cherry_grove", "minecraft:cherry_sapling"),
            Map.entry("minecraft:pale_garden", "minecraft:pale_oak_sapling"),
            Map.entry("minecraft:ice_spikes", "minecraft:packed_ice"),
            Map.entry("minecraft:mangrove_swamp", "minecraft:mangrove_propagule"),
            Map.entry("minecraft:bamboo_jungle", "minecraft:bamboo"),
            Map.entry("minecraft:mushroom_fields", "minecraft:red_mushroom"),
            Map.entry("minecraft:lush_caves", "minecraft:moss_block"),
            Map.entry("minecraft:deep_dark", "minecraft:sculk"),
            Map.entry("minecraft:dripstone_caves", "minecraft:pointed_dripstone"),
            Map.entry("minecraft:crimson_forest", "minecraft:crimson_fungus"),
            Map.entry("minecraft:warped_forest", "minecraft:warped_fungus"),
            Map.entry("minecraft:soul_sand_valley", "minecraft:soul_sand"),
            Map.entry("minecraft:basalt_deltas", "minecraft:basalt"),
            Map.entry("minecraft:sulfur_caves", "minecraft:sulfur"),
            Map.entry("minecraft:deep_cold_ocean", "minecraft:ice"));

    /** Icon item id for a biome: curated first, then a keyword guess, then grass. */
    public static String iconFor(String biomeId) {
        String curated = ICONS.get(biomeId);
        if (curated != null) return curated;
        String p = biomeId.contains(":") ? biomeId.substring(biomeId.indexOf(':') + 1) : biomeId;
        if (p.contains("nether") || p.contains("wastes")) return "minecraft:netherrack";
        if (p.contains("end")) return "minecraft:end_stone";
        if (p.contains("mushroom")) return "minecraft:red_mushroom";
        if (p.contains("cherry")) return "minecraft:cherry_sapling";
        if (p.contains("bamboo")) return "minecraft:bamboo";
        if (p.contains("jungle")) return "minecraft:jungle_sapling";
        if (p.contains("birch")) return "minecraft:birch_sapling";
        if (p.contains("taiga") || p.contains("grove")) return "minecraft:spruce_sapling";
        if (p.contains("savanna") || p.contains("acacia")) return "minecraft:acacia_sapling";
        if (p.contains("swamp")) return "minecraft:lily_pad";
        if (p.contains("desert")) return "minecraft:sand";
        if (p.contains("badlands") || p.contains("mesa")) return "minecraft:terracotta";
        if (p.contains("beach") || p.contains("shore")) return "minecraft:sand";
        if (p.contains("frozen") || p.contains("snow") || p.contains("ice") || p.contains("frost")) return "minecraft:snow_block";
        if (p.contains("ocean") || p.contains("river") || p.contains("lake")) return "minecraft:water_bucket";
        if (p.contains("cave") || p.contains("cavern") || p.contains("deep")) return "minecraft:stone";
        if (p.contains("peak") || p.contains("hill") || p.contains("mountain") || p.contains("windswept") || p.contains("meadow")) return "minecraft:stone";
        if (p.contains("forest") || p.contains("wood")) return "minecraft:oak_sapling";
        if (p.contains("sky") || p.contains("void")) return "minecraft:glass";
        return "minecraft:grass_block";
    }

    /**
     * Biome data lives in 4×4×4 cells, so every fill bleeds up to 3 blocks past its rectangle —
     * onto the curb and the first road columns. Scrub passes pad by one full quart so a CHANGE of
     * biome always erases the previous biome's bleed before painting the new one.
     */
    private static final int SCRUB_PAD = 4;

    /** Paint the plot's chosen biome (blank = the default plot biome) across everything it owns. */
    public static int applyBiome(ServerLevel level, PlotData d) {
        String id = (d.biomeId == null || d.biomeId.isBlank()) ? DEFAULT_ID : d.biomeId;
        // Two passes: scrub the plot + margin back to default (kills the old biome's boundary
        // quarts around the curb/street), then paint the new biome over the exact plot area.
        int n = fillRects(level, d, DEFAULT_ID, SCRUB_PAD);
        if (!id.equals(DEFAULT_ID)) n = fillRects(level, d, id, 0);
        // The old biome's mobs (and whatever they dropped) don't belong to the new look.
        PlotMobGuard.purgeMobsAndDrops(level, d);
        return n;
    }

    /** Back to the plot world's native biome (call BEFORE unclaiming — needs the cells registered). */
    public static int resetBiome(ServerLevel level, PlotData d) {
        return fillRects(level, d, DEFAULT_ID, SCRUB_PAD); // padded: also erases the boundary bleed
    }

    private static int fillRects(ServerLevel level, PlotData d, String biomeId, int pad) {
        Holder<Biome> biome;
        try {
            biome = level.registryAccess().lookupOrThrow(Registries.BIOME)
                    .getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biomeId)));
        } catch (Exception e) {
            System.err.println("[FabricPlots] Unknown biome " + biomeId + ": " + e);
            return 0;
        }
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        int tiles = 0;
        for (int[] raw : ownedRects(d)) {                     // raw = xMin, xMax, zMin, zMax
            int[] r = { raw[0] - pad, raw[1] + pad, raw[2] - pad, raw[3] + pad };
            for (int x0 = r[0]; x0 <= r[1]; x0 += TILE) {
                for (int z0 = r[2]; z0 <= r[3]; z0 += TILE) {
                    int x1 = Math.min(r[1], x0 + TILE - 1);
                    int z1 = Math.min(r[3], z0 + TILE - 1);
                    var result = FillBiomeCommand.fill(level,
                            new BlockPos(x0, minY, z0), new BlockPos(x1, maxY, z1), biome);
                    if (result.right().isPresent()) {
                        System.err.println("[FabricPlots] Biome fill failed: " + result.right().get().getMessage());
                        return tiles;
                    }
                    tiles++;
                }
            }
        }
        return tiles;
    }

    /**
     * The rectangles a plot owns: each cell's core+sidewalk square, plus (for merges) the dissolved
     * road gaps between cardinally adjacent cells and the 4-way corner gaps.
     */
    private static List<int[]> ownedRects(PlotData d) {
        List<int[]> rects = new ArrayList<>();
        final int S = PlotConfig.STEP, P = PlotConfig.PLOT_SIZE, W = PlotConfig.SIDEWALK_DEPTH;
        for (PlotPos c : d.cells) {
            int bx = c.px() * S, bz = c.pz() * S;
            rects.add(new int[] { bx - W, bx + P + W - 1, bz - W, bz + P + W - 1 });
        }
        for (PlotPos c : d.cells) {
            int bx = c.px() * S, bz = c.pz() * S;
            boolean east = d.cells.contains(new PlotPos(c.px() + 1, c.pz()));
            boolean south = d.cells.contains(new PlotPos(c.px(), c.pz() + 1));
            boolean southEast = d.cells.contains(new PlotPos(c.px() + 1, c.pz() + 1));
            if (east)   // dissolved road strip between this cell and its eastern neighbour
                rects.add(new int[] { bx + P + W, bx + S - W - 1, bz, bz + P - 1 });
            if (south)
                rects.add(new int[] { bx, bx + P - 1, bz + P + W, bz + S - W - 1 });
            if (east && south && southEast) // the 4-way crossing inside a solid block of cells
                rects.add(new int[] { bx + P + W, bx + S - W - 1, bz + P + W, bz + S - W - 1 });
        }
        return rects;
    }
}
