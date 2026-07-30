package com.fabricplots.edit;

import com.fabricplots.FabricPlots;
import com.fabricplots.compat.Compat;
import com.fabricplots.protect.PlotProtection;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Paint brushes — a named stick whose whole configuration lives ON the stick (custom data
 * component), so every stick is its own brush: keep a gravel-path splatter in slot 1 and a leaf
 * blob in slot 2 and swap like a painter. Right-click paints where you aim (30 blocks); sneak +
 * right-click opens the config GUI. Strokes are plot-jailed through the editor pipeline, land as
 * one undo entry each, and a particle ring previews the radius while you aim.
 */
public final class PlotBrush {
    public static final String BRUSH_NAME = "Plot Paint Brush";
    private static final String TAG = "fabricplots_brush";
    private static final int REACH = 30;
    private static final java.util.Random RNG = new java.util.Random();

    public enum Type { SPLATTER, ROUND, OVERLAY, SPRAY, ERASE }

    /** Brush settings, (de)serialized from the stick's custom-data tag. */
    public static final class Config {
        public Type type = Type.SPLATTER;
        public int size = 4;            // radius, 1..15
        public int density = 60;        // % of area a splatter/spray stroke covers, 10..100
        public boolean fade = true;     // edges thin out
        public boolean surface = true;  // paint the top solid block of each column
        public String mask = "";        // only repaint this block id ("" = anything)
        public final List<String> palette = new ArrayList<>();
    }

    private PlotBrush() {}

    // ---- the stick ---------------------------------------------------------

    public static ItemStack createBrush() {
        ItemStack s = new ItemStack(Items.STICK);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(BRUSH_NAME));
        write(s, new Config());
        return s;
    }

    public static boolean isBrush(ItemStack s) {
        if (s.isEmpty() || s.getItem() != Items.STICK) return false;
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.copyTag().contains(TAG);
    }

    public static Config read(ItemStack s) {
        Config c = new Config();
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null) return c;
        CompoundTag t = d.copyTag().getCompoundOrEmpty(TAG);
        try { c.type = Type.valueOf(t.getStringOr("type", "SPLATTER")); } catch (Exception ignored) {}
        c.size = Math.max(1, Math.min(15, t.getIntOr("size", 4)));
        c.density = Math.max(10, Math.min(100, t.getIntOr("density", 60)));
        c.fade = t.getBooleanOr("fade", true);
        c.surface = t.getBooleanOr("surface", true);
        c.mask = t.getStringOr("mask", "");
        String pal = t.getStringOr("palette", "");
        if (!pal.isEmpty()) for (String id : pal.split(",")) if (!id.isBlank()) c.palette.add(id);
        return c;
    }

    public static void write(ItemStack s, Config c) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        CompoundTag root = d != null ? d.copyTag() : new CompoundTag();
        CompoundTag t = new CompoundTag();
        t.putString("type", c.type.name());
        t.putInt("size", c.size);
        t.putInt("density", c.density);
        t.putBoolean("fade", c.fade);
        t.putBoolean("surface", c.surface);
        t.putString("mask", c.mask);
        t.putString("palette", String.join(",", c.palette));
        root.put(TAG, t);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    // ---- events ------------------------------------------------------------

    /** openGui is injected by FabricPlots wiring so edit/ never depends on gui/. */
    public static void register(Consumer<ServerPlayer> openGui) {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.dimension() != FabricPlots.PLOTS_DIM || world.isClientSide()) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!isBrush(held) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) openGui.accept(sp); else paint(sp, held);
            return InteractionResult.SUCCESS;
        });
        // Clicking directly on a block fires UseBlock first — same behavior, and swallow the click
        // so the brush never opens chests / presses buttons mid-stroke.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.dimension() != FabricPlots.PLOTS_DIM || world.isClientSide()) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!isBrush(held) || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) openGui.accept(sp); else paint(sp, held);
            return InteractionResult.SUCCESS;
        });
        // Radius preview while aiming, and selection outlines while holding the editor wand.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 4 != 0) return;
            ServerLevel level = server.getLevel(FabricPlots.PLOTS_DIM);
            if (level == null) return;
            for (ServerPlayer sp : level.players()) {
                ItemStack held = sp.getMainHandItem();
                if (isBrush(held)) previewRing(sp, level, read(held).size);
                else if (PlotEdit.isWand(held)) outlineSelection(sp, level);
            }
        });
    }

    // ---- painting ----------------------------------------------------------

    static void paint(ServerPlayer sp, ItemStack stick) {
        ServerLevel level = (ServerLevel) sp.level();
        HitResult hit = sp.pick(REACH, 0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) { msg(sp, "Aim at a block within " + REACH + " blocks."); return; }
        BlockPos center = ((BlockHitResult) hit).getBlockPos();
        Config c = read(stick);

        List<BlockState> palette = new ArrayList<>();
        for (String id : c.palette) { Block b = Compat.block(id); if (b != Blocks.AIR) palette.add(b.defaultBlockState()); }
        if (c.type != Type.ERASE && palette.isEmpty()) {
            msg(sp, "This brush has no blocks yet — sneak + right-click to open it and fill the palette.");
            return;
        }
        Block maskBlock = c.mask.isEmpty() ? null : Compat.block(c.mask);
        boolean admin = PlotProtection.isBuildAdmin(sp);
        int r = c.size;
        List<PlotEdit.Write> writes = new ArrayList<>();

        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > r + 0.45) continue;
            double chance = switch (c.type) {
                case SPLATTER -> c.density / 100.0;
                case SPRAY -> c.density / 100.0 * 0.2;
                default -> 1.0;
            };
            if (c.fade) chance *= Math.max(0, 1.0 - (dist / (r + 0.5)) * (dist / (r + 0.5)));
            boolean surfaceMode = c.surface || c.type == Type.OVERLAY;

            if (surfaceMode) {
                if (RNG.nextDouble() > chance) continue;
                // walk down from a little above the aim point to the first solid block
                for (int y = center.getY() + Math.min(r, 6) + 1; y >= center.getY() - r - 2; y--) {
                    BlockPos p = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    BlockState cur = level.getBlockState(p);
                    if (cur.isAir() || !cur.getFluidState().isEmpty()) continue;
                    addWrite(writes, sp, level, admin, p, cur, maskBlock, palette, c);
                    break;
                }
            } else {
                for (int dy = -r; dy <= r; dy++) {
                    double d3 = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d3 > r + 0.45) continue;
                    double ballChance = switch (c.type) {
                        case SPLATTER -> c.density / 100.0;
                        case SPRAY -> c.density / 100.0 * 0.2;
                        default -> 1.0;
                    };
                    if (c.fade) ballChance *= Math.max(0, 1.0 - (d3 / (r + 0.5)) * (d3 / (r + 0.5)));
                    if (RNG.nextDouble() > ballChance) continue;
                    BlockPos p = center.offset(dx, dy, dz);
                    addWrite(writes, sp, level, admin, p, level.getBlockState(p), maskBlock, palette, c);
                }
            }
        }
        PlotEdit.commit(sp, level, writes, brushVerb(c.type));
    }

    private static void addWrite(List<PlotEdit.Write> writes, ServerPlayer sp, ServerLevel level, boolean admin,
                                 BlockPos p, BlockState cur, Block maskBlock, List<BlockState> palette, Config c) {
        if (!PlotEdit.canEdit(sp, admin, p.getX(), p.getY(), p.getZ())) return;
        if (maskBlock != null && !cur.is(maskBlock)) return;
        if (c.type == Type.ERASE) {
            if (!cur.isAir()) writes.add(new PlotEdit.Write(p, Blocks.AIR.defaultBlockState()));
        } else {
            writes.add(new PlotEdit.Write(p, palette.get(RNG.nextInt(palette.size()))));
        }
    }

    private static String brushVerb(Type t) {
        return switch (t) {
            case SPLATTER -> "Splattered"; case ROUND -> "Painted"; case OVERLAY -> "Overlaid";
            case SPRAY -> "Sprayed"; case ERASE -> "Erased";
        } + " —";
    }

    // ---- particle previews ---------------------------------------------------

    private static void previewRing(ServerPlayer sp, ServerLevel level, int r) {
        HitResult hit = sp.pick(REACH, 0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos c = ((BlockHitResult) hit).getBlockPos();
        int points = Math.max(12, r * 6);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            level.sendParticles(sp, ParticleTypes.END_ROD, true, false,
                    c.getX() + 0.5 + Math.cos(a) * (r + 0.5), c.getY() + 1.1, c.getZ() + 0.5 + Math.sin(a) * (r + 0.5),
                    1, 0, 0, 0, 0);
        }
    }

    /** Corner 1 → corner 2 box edges while the editor wand is in hand. */
    private static void outlineSelection(ServerPlayer sp, ServerLevel level) {
        BlockPos p1 = PlotEdit.POS1.get(sp.getUUID()), p2 = PlotEdit.POS2.get(sp.getUUID());
        if (p1 == null || p2 == null) return;
        double x1 = Math.min(p1.getX(), p2.getX()), x2 = Math.max(p1.getX(), p2.getX()) + 1;
        double y1 = Math.min(p1.getY(), p2.getY()), y2 = Math.max(p1.getY(), p2.getY()) + 1;
        double z1 = Math.min(p1.getZ(), p2.getZ()), z2 = Math.max(p1.getZ(), p2.getZ()) + 1;
        double step = 1.5;
        for (double x = x1; x <= x2; x += step) for (double[] yz : new double[][]{{y1,z1},{y1,z2},{y2,z1},{y2,z2}})
            level.sendParticles(sp, ParticleTypes.HAPPY_VILLAGER, true, false, x, yz[0], yz[1], 1, 0, 0, 0, 0);
        for (double y = y1; y <= y2; y += step) for (double[] xz : new double[][]{{x1,z1},{x1,z2},{x2,z1},{x2,z2}})
            level.sendParticles(sp, ParticleTypes.HAPPY_VILLAGER, true, false, xz[0], y, xz[1], 1, 0, 0, 0, 0);
        for (double z = z1; z <= z2; z += step) for (double[] xy : new double[][]{{x1,y1},{x1,y2},{x2,y1},{x2,y2}})
            level.sendParticles(sp, ParticleTypes.HAPPY_VILLAGER, true, false, xy[0], xy[1], z, 1, 0, 0, 0, 0);
    }

    private static void msg(ServerPlayer p, String t) { p.sendSystemMessage(Component.literal("[Plots] " + t)); }
}
