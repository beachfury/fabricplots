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

    public record Choice(String biomeId, String iconItemId, String label) {}

    /** Curated, visually distinct biomes (icons are item registry ids — version-safe). */
    public static final List<Choice> CHOICES = List.of(
            new Choice(DEFAULT_ID, "minecraft:grass_block", "Default"),
            new Choice("minecraft:sunflower_plains", "minecraft:sunflower", "Sunflower Plains"),
            new Choice("minecraft:flower_forest", "minecraft:poppy", "Flower Forest"),
            new Choice("minecraft:birch_forest", "minecraft:birch_sapling", "Birch Forest"),
            new Choice("minecraft:dark_forest", "minecraft:dark_oak_sapling", "Dark Forest"),
            new Choice("minecraft:cherry_grove", "minecraft:cherry_sapling", "Cherry Grove"),
            new Choice("minecraft:pale_garden", "minecraft:pale_oak_sapling", "Pale Garden"),
            new Choice("minecraft:taiga", "minecraft:spruce_sapling", "Taiga"),
            new Choice("minecraft:snowy_plains", "minecraft:snow_block", "Snowy Plains"),
            new Choice("minecraft:ice_spikes", "minecraft:packed_ice", "Ice Spikes"),
            new Choice("minecraft:swamp", "minecraft:lily_pad", "Swamp"),
            new Choice("minecraft:mangrove_swamp", "minecraft:mangrove_propagule", "Mangrove Swamp"),
            new Choice("minecraft:jungle", "minecraft:jungle_sapling", "Jungle"),
            new Choice("minecraft:bamboo_jungle", "minecraft:bamboo", "Bamboo Jungle"),
            new Choice("minecraft:desert", "minecraft:sand", "Desert"),
            new Choice("minecraft:badlands", "minecraft:terracotta", "Badlands"),
            new Choice("minecraft:savanna", "minecraft:acacia_sapling", "Savanna"),
            new Choice("minecraft:mushroom_fields", "minecraft:red_mushroom", "Mushroom Fields"),
            new Choice("minecraft:lush_caves", "minecraft:moss_block", "Lush Caves"),
            new Choice("minecraft:deep_dark", "minecraft:sculk", "Deep Dark"),
            new Choice("minecraft:warm_ocean", "minecraft:brain_coral", "Warm Ocean"),
            new Choice("minecraft:crimson_forest", "minecraft:crimson_fungus", "Crimson Forest"),
            new Choice("minecraft:warped_forest", "minecraft:warped_fungus", "Warped Forest"),
            new Choice("minecraft:soul_sand_valley", "minecraft:soul_sand", "Soul Sand Valley"),
            new Choice("minecraft:nether_wastes", "minecraft:netherrack", "Nether Wastes"),
            new Choice("minecraft:sulfur_caves", "minecraft:sulfur", "Sulfur Caves"), // 26.2+ only (registry-filtered)
            new Choice("minecraft:the_end", "minecraft:end_stone", "The End"));

    private PlotBiomes() {}

    /** True if the biome exists in this Minecraft version's registry (filters version-specific entries). */
    public static boolean available(ServerLevel level, String biomeId) {
        try {
            return level.registryAccess().lookupOrThrow(Registries.BIOME)
                    .get(ResourceKey.create(Registries.BIOME, Identifier.parse(biomeId))).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    public static String labelOf(String biomeId) {
        for (Choice c : CHOICES) if (c.biomeId().equals(biomeId)) return c.label();
        return biomeId.isBlank() ? "Default" : biomeId;
    }

    /** Paint the plot's chosen biome (blank = the default plot biome) across everything it owns. */
    public static int applyBiome(ServerLevel level, PlotData d) {
        String id = (d.biomeId == null || d.biomeId.isBlank()) ? DEFAULT_ID : d.biomeId;
        return fillRects(level, d, id);
    }

    /** Back to the plot world's native biome (call BEFORE unclaiming — needs the cells registered). */
    public static int resetBiome(ServerLevel level, PlotData d) {
        return fillRects(level, d, DEFAULT_ID);
    }

    private static int fillRects(ServerLevel level, PlotData d, String biomeId) {
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
        for (int[] r : ownedRects(d)) {                       // r = xMin, xMax, zMin, zMax
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
