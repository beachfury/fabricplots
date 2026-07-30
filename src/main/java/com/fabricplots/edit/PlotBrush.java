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
    // The vanilla archaeology brush — thematically right, and NOT a stick: Litematica and friends
    // claim the stick as their client-side tool and eat the right-click before it reaches us.
    static final net.minecraft.world.item.Item BRUSH_ITEM = Items.BRUSH;
    private static final String TAG = "fabricplots_brush";
    private static final int REACH = 30;
    private static final java.util.Random RNG = new java.util.Random();

    public enum Type { SPLATTER, ROUND, OVERLAY, SPRAY, ERASE, WALL }

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
        ItemStack s = new ItemStack(BRUSH_ITEM);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(BRUSH_NAME));
        write(s, new Config());
        return s;
    }

    public static boolean isBrush(ItemStack s) {
        if (s.isEmpty() || s.getItem() != BRUSH_ITEM) return false;
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
            if (world.dimension() != FabricPlots.PLOTS_DIM) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!isBrush(held)) return InteractionResult.PASS;
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            if (player.isShiftKeyDown()) PENDING_GUI.add(sp.getUUID()); else paint(sp, held);
            return InteractionResult.SUCCESS;
        });
        // Clicking directly on a block fires UseBlock first — same behavior, and swallow the click
        // so the brush never opens chests / presses buttons mid-stroke.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.dimension() != FabricPlots.PLOTS_DIM) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);
            if (!isBrush(held)) return InteractionResult.PASS;
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            if (player.isShiftKeyDown()) PENDING_GUI.add(sp.getUUID()); else paint(sp, held);
            return InteractionResult.SUCCESS;
        });
        // GUI opens are queued and drained here — end-of-tick, safely after the click packet that
        // requested them (opening inside the interaction gets closed by the client immediately).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerLevel level = server.getLevel(FabricPlots.PLOTS_DIM);
            if (level == null) return;
            if (!PENDING_GUI.isEmpty()) {
                for (ServerPlayer sp : level.players())
                    if (PENDING_GUI.remove(sp.getUUID())) openGui.accept(sp);
                PENDING_GUI.clear(); // anyone who left the dimension mid-click
            }
            if (server.getTickCount() % 4 != 0) return;
            for (ServerPlayer sp : level.players()) {
                ItemStack held = sp.getMainHandItem();
                if (isBrush(held)) previewRing(sp, level, read(held).size);
                else if (PlotEdit.isWand(held)) outlineSelection(sp, level);
            }
        });
    }

    private static final java.util.Set<java.util.UUID> PENDING_GUI = new java.util.HashSet<>();

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

        // Surface-mode Erase = restore the ground, not dig holes: clear everything above the
        // plot floor level, repaint the floor block (respecting custom plot floors), and refill
        // any holes dug below it. Ball-mode Erase still just clears to air.
        if (c.type == Type.ERASE && c.surface) {
            int top = center.getY() + Math.max(6, Math.min(r, 10));
            int groundY = com.fabricplots.core.PlotConfig.GROUND_Y;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r + 0.45) continue;
                double chance = c.fade ? Math.max(0, 1.0 - (dist / (r + 0.5)) * (dist / (r + 0.5))) : 1.0;
                if (RNG.nextDouble() > chance) continue;
                int x = center.getX() + dx, z = center.getZ() + dz;
                com.fabricplots.core.PlotData plot = com.fabricplots.core.PlotManager.owningPlot(x, z);
                BlockState floor = plot != null ? com.fabricplots.world.PlotWorldPainter.surfaceFor(plot) : null;
                for (int y = top; y > groundY; y--) {
                    BlockState cur = level.getBlockState(new BlockPos(x, y, z));
                    if (cur.isAir()) continue;
                    if (maskBlock != null && !cur.is(maskBlock)) continue;
                    if (PlotEdit.canEdit(sp, admin, x, y, z))
                        writes.add(new PlotEdit.Write(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState()));
                }
                if (maskBlock == null && floor != null) {
                    BlockState cur = level.getBlockState(new BlockPos(x, groundY, z));
                    if (!cur.equals(floor) && PlotEdit.canEdit(sp, admin, x, groundY, z))
                        writes.add(new PlotEdit.Write(new BlockPos(x, groundY, z), floor));
                    // refill anything dug out below the floor
                    for (int y = groundY - 1; y >= Math.max(com.fabricplots.core.PlotConfig.DIRT_BOTTOM_Y, groundY - 8); y--) {
                        if (level.getBlockState(new BlockPos(x, y, z)).isAir() && PlotEdit.canEdit(sp, admin, x, y, z))
                            writes.add(new PlotEdit.Write(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState()));
                    }
                }
            }
            PlotEdit.commit(sp, level, writes, "Restored ground —");
            return;
        }

        if (c.type == Type.WALL) {
            net.minecraft.core.Direction face = ((BlockHitResult) hit).getDirection();
            if (!face.getAxis().isHorizontal()) { msg(sp, "Aim at the SIDE of a wall to texture it."); return; }
            net.minecraft.core.Direction inward = face.getOpposite(), tangent = face.getClockWise();
            for (int u = -r; u <= r; u++) for (int v = -r; v <= r; v++) {
                double dist = Math.sqrt(u * u + v * v);
                if (dist > r + 0.45) continue;
                double chance = c.density / 100.0;
                if (c.fade) chance *= Math.max(0, 1.0 - (dist / (r + 0.5)) * (dist / (r + 0.5)));
                if (RNG.nextDouble() > chance) continue;
                // scan into the wall from just in front of it, so bumpy walls still get painted
                BlockPos start = center.relative(face, 2).relative(tangent, u).above(v);
                for (int k = 0; k <= 5; k++) {
                    BlockPos wp = start.relative(inward, k);
                    BlockState cur = level.getBlockState(wp);
                    if (cur.isAir() || !cur.getFluidState().isEmpty()) continue;
                    // a wall face is a face exposed to air on the side you aim at — the lawn in
                    // front of the wall fails this test, so the ground never gets wall texture
                    if (level.getBlockState(wp.relative(face)).isAir())
                        addWrite(writes, sp, level, admin, wp, cur, maskBlock, palette, c, false);
                    break;
                }
            }
            PlotEdit.commit(sp, level, writes, brushVerb(c.type));
            return;
        }

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
                    addWrite(writes, sp, level, admin, p, cur, maskBlock, palette, c, true);
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
                    addWrite(writes, sp, level, admin, p, level.getBlockState(p), maskBlock, palette, c, false);
                }
            }
        }
        PlotEdit.commit(sp, level, writes, brushVerb(c.type));
    }

    private static void addWrite(List<PlotEdit.Write> writes, ServerPlayer sp, ServerLevel level, boolean admin,
                                 BlockPos p, BlockState cur, Block maskBlock, List<BlockState> palette, Config c,
                                 boolean surfaceMode) {
        if (!PlotEdit.canEdit(sp, admin, p.getX(), p.getY(), p.getZ())) return;
        if (maskBlock != null && !cur.is(maskBlock)) return;
        if (c.type == Type.ERASE) {
            if (!cur.isAir()) writes.add(new PlotEdit.Write(p, Blocks.AIR.defaultBlockState()));
            return;
        }
        BlockState chosen = palette.get(RNG.nextInt(palette.size()));
        // Half blocks (slabs, trapdoors, carpets…) go ON TOP of the surface — sinking them into
        // the ground punches trapdoor-holes in the lawn. Full blocks and stairs replace as before.
        if (surfaceMode && sitsOnTop(chosen)) {
            BlockPos top = p.above();
            if (!level.getBlockState(top).isAir()) return;
            if (!PlotEdit.canEdit(sp, admin, top.getX(), top.getY(), top.getZ())) return;
            writes.add(new PlotEdit.Write(top, chosen));
        } else {
            writes.add(new PlotEdit.Write(p, chosen));
        }
    }

    /**
     * Anything that is not a full cube rests ON the surface; full cubes replace it. Asked of the
     * block itself (collision shape), so every block — vanilla or modded — sorts itself with no
     * per-block list. Stairs are the one agreed exception: they sink into the ground.
     */
    private static boolean sitsOnTop(BlockState s) {
        if (s.getBlock() instanceof net.minecraft.world.level.block.StairBlock) return false;
        return !s.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static String brushVerb(Type t) {
        return switch (t) {
            case SPLATTER -> "Splattered"; case ROUND -> "Painted"; case OVERLAY -> "Overlaid";
            case SPRAY -> "Sprayed"; case ERASE -> "Erased"; case WALL -> "Textured";
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
