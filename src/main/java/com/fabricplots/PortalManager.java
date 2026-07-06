package com.fabricplots;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nether-style frame portals between the player's home world and the plot world.
 * Build a CALCITE frame (nether-portal dimensions: interior 2..21 wide, 3..21 tall) and light it:
 *   • flint &amp; steel  → a portal to the plot-world spawn plaza
 *   • a Plot Portal Key → a portal straight to that key's plot
 *
 * IMPORTANT: the interior is left as AIR and the swirl is drawn with server-spawned PORTAL particles.
 * We deliberately do NOT use the vanilla nether_portal block — it carries hardcoded "go to the nether"
 * behaviour that hijacked travel (first entry → nether, nearby real nether portals stealing the link).
 * Travel is 100% ours: a per-tick position check against the registered interior cells.
 */
public final class PortalManager {
    /** The item that, when used on a frame, links the portal to a specific plot. */
    public static final net.minecraft.world.item.Item KEY_ITEM = Items.RECOVERY_COMPASS;

    private enum DestType { PLAZA, PLOT, RETURN }

    // Auto-built exit-portal shape (in-ground, faces N-S, centred on the road's middle column).
    private static final BlockState CALCITE = Blocks.CALCITE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int OPENING_HALF = 1;  // 3 wide (centre column ±1) — odd so it centres on the road
    private static final int OPENING_H = 3;     // 3 tall
    private static final int ROAD_CENTER_LX = PlotConfig.PLOT_SIZE + PlotConfig.ROAD_WIDTH / 2; // lx 40

    private static final class Portal {
        final DestType type;
        final PlotPos plot;                 // non-null only for PLOT
        final Set<BlockPos> interior;       // air cells you walk through (travel + particles)
        final Set<BlockPos> frame;          // calcite border cells (breaking one tears it down)
        final ResourceKey<Level> dim;       // the dimension the frame is in (the home world)
        final BlockPos anchor;              // a representative interior cell (return target)
        Portal(DestType type, PlotPos plot, Set<BlockPos> interior, Set<BlockPos> frame, ResourceKey<Level> dim) {
            this.type = type; this.plot = plot; this.interior = interior; this.frame = frame; this.dim = dim;
            this.anchor = lowest(interior); // return to the BASE of the frame, never mid-air in a tall portal
        }
    }

    private record FrameScan(Set<BlockPos> interior, Set<BlockPos> frame) {}

    /** The lowest interior cell (min Y, then X, then Z) — deterministic base of the frame. */
    private static BlockPos lowest(Set<BlockPos> cells) {
        BlockPos best = null;
        for (BlockPos c : cells) {
            if (best == null || c.getY() < best.getY()
                    || (c.getY() == best.getY() && (c.getX() < best.getX()
                    || (c.getX() == best.getX() && c.getZ() < best.getZ())))) {
                best = c;
            }
        }
        return best;
    }

    /** Kill momentum + fall distance after a portal jump so you never take arrival damage. */
    private static void settle(ServerPlayer p) {
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        p.resetFallDistance();
    }

    // Keyed per cell so the per-tick lookups are O(1).
    private static final Map<BlockPos, Portal> ACTIVE = new ConcurrentHashMap<>();  // interior cell -> portal
    private static final Map<BlockPos, Portal> FRAMES = new ConcurrentHashMap<>();  // frame cell -> portal
    private static final Map<UUID, GlobalPos> RETURN_POINT = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    private static Path saveFile;

    private PortalManager() {}

    // ---- Plot Portal Key item -------------------------------------------

    public static ItemStack createKey(PlotPos plot) {
        ItemStack s = new ItemStack(KEY_ITEM);
        CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> {
            tag.putInt("plot_px", plot.px());
            tag.putInt("plot_pz", plot.pz());
        });
        s.set(DataComponents.CUSTOM_NAME,
                Component.literal("Plot Portal Key → (" + plot.px() + ", " + plot.pz() + ")"));
        return s;
    }

    private static PlotPos keyPlot(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != KEY_ITEM) return null;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag tag = cd.copyTag();
        if (!tag.contains("plot_px") || !tag.contains("plot_pz")) return null;
        return new PlotPos(tag.getIntOr("plot_px", 0), tag.getIntOr("plot_pz", 0));
    }

    // ---- activation (lighting a frame) ----------------------------------

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            ItemStack held = player.getItemInHand(hand);
            // CLIENT: the Key has no default right-click action, so force-forward its click to the server.
            if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) {
                return keyPlot(held) != null ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            final boolean flint = held.getItem() == Items.FLINT_AND_STEEL;
            if (level.dimension() == FabricPlots.PLOTS_DIM) {
                // In the plot world, flint & steel lights a RETURN portal (walk through → home world).
                if (!flint) return InteractionResult.PASS;
                if (PlotsConfig.plotWorldPortalAdminOnly && !PlotProtection.isAdmin(sp)) {
                    msg(sp, "Only admins can build portals in the plot world.");
                    return InteractionResult.SUCCESS; // consume so no fire is lit
                }
                boolean made = tryActivate(level, hit.getBlockPos(), hit.getDirection(), DestType.RETURN, null, sp);
                return made ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            // Home world: flint & steel → spawn-plaza portal, a Plot Key → that plot.
            PlotPos keyPlot = keyPlot(held);
            if (!flint && keyPlot == null) return InteractionResult.PASS;
            DestType type = keyPlot == null ? DestType.PLAZA : DestType.PLOT;
            boolean made = tryActivate(level, hit.getBlockPos(), hit.getDirection(), type, keyPlot, sp);
            // Consume so flint & steel doesn't also light a fire; if no frame, let flint behave normally.
            return made ? InteractionResult.SUCCESS : (flint ? InteractionResult.PASS : InteractionResult.SUCCESS);
        });

        // Breaking any frame (or interior) cell tears the whole portal down.
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(world instanceof ServerLevel level)) return;
            Portal portal = FRAMES.get(pos);
            if (portal == null) portal = ACTIVE.get(pos);
            if (portal != null && portal.dim == level.dimension()) deactivate(portal);
        });
    }

    private static boolean tryActivate(ServerLevel level, BlockPos clicked, Direction face, DestType type, PlotPos plot, ServerPlayer sp) {
        BlockPos[] seeds = { clicked.relative(face), clicked.above(), clicked };
        for (BlockPos seed : seeds) {
            if (!level.getBlockState(seed).isAir()) continue;
            if (ACTIVE.containsKey(seed)) continue; // already a live portal here
            for (Direction.Axis axis : new Direction.Axis[]{ Direction.Axis.X, Direction.Axis.Z }) {
                FrameScan scan = scan(level, seed, axis);
                if (scan != null) { activate(scan, type, plot, sp, level.dimension()); return true; }
            }
        }
        return false;
    }

    private static void activate(FrameScan scan, DestType type, PlotPos plot, ServerPlayer sp, ResourceKey<Level> dim) {
        // Replace any overlapping portal (re-lighting to change destination).
        for (BlockPos p : scan.interior()) { Portal old = ACTIVE.get(p); if (old != null) deactivate(old); }
        Portal portal = new Portal(type, plot, scan.interior(), scan.frame(), dim);
        for (BlockPos p : scan.interior()) ACTIVE.put(p, portal);
        for (BlockPos p : scan.frame()) FRAMES.put(p, portal);
        save();
        switch (type) {
            case PLAZA -> msg(sp, "Spawn-plaza portal lit. Walk through to enter the plot world.");
            case PLOT -> msg(sp, "Portal to plot (" + plot.px() + ", " + plot.pz() + ") lit. Walk through to warp there.");
            case RETURN -> msg(sp, "Return portal lit. Walk through to head back to the home world.");
        }
    }

    /** Scan for a rectangular calcite frame around {@code seed}; returns its interior + frame cells. */
    private static FrameScan scan(ServerLevel level, BlockPos seed, Direction.Axis axis) {
        Direction along = (axis == Direction.Axis.X) ? Direction.EAST : Direction.SOUTH;
        int left = countAir(level, seed, along.getOpposite());
        int right = countAir(level, seed, along);
        int width = left + 1 + right;
        if (width < 2 || width > 21) return null;
        int down = countAir(level, seed, Direction.DOWN);
        int up = countAir(level, seed, Direction.UP);
        int height = down + 1 + up;
        if (height < 3 || height > 21) return null;

        BlockPos origin = seed.relative(along.getOpposite(), left).relative(Direction.DOWN, down); // bottom-left interior
        Set<BlockPos> interior = new HashSet<>();
        for (int w = 0; w < width; w++)
            for (int h = 0; h < height; h++) {
                BlockPos cell = origin.relative(along, w).relative(Direction.UP, h);
                if (!level.getBlockState(cell).isAir()) return null;
                interior.add(cell.immutable());
            }
        Set<BlockPos> frame = new HashSet<>();
        for (int w = 0; w < width; w++) {
            BlockPos bottom = origin.relative(along, w).below();
            BlockPos top = origin.relative(along, w).relative(Direction.UP, height);
            if (!isFrame(level, bottom) || !isFrame(level, top)) return null;
            frame.add(bottom.immutable()); frame.add(top.immutable());
        }
        for (int h = 0; h < height; h++) {
            BlockPos lhs = origin.relative(Direction.UP, h).relative(along, -1);
            BlockPos rhs = origin.relative(Direction.UP, h).relative(along, width);
            if (!isFrame(level, lhs) || !isFrame(level, rhs)) return null;
            frame.add(lhs.immutable()); frame.add(rhs.immutable());
        }
        return new FrameScan(interior, frame);
    }

    private static int countAir(ServerLevel level, BlockPos from, Direction dir) {
        int n = 0;
        BlockPos.MutableBlockPos p = from.mutable();
        for (int i = 0; i < 21; i++) {
            p.move(dir);
            if (level.getBlockState(p).isAir()) n++; else break;
        }
        return n;
    }

    private static boolean isFrame(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.CALCITE);
    }

    private static void deactivate(Portal portal) {
        for (BlockPos p : portal.interior) ACTIVE.remove(p);
        for (BlockPos p : portal.frame) FRAMES.remove(p);
        save();
    }

    // ---- per-tick: travel + particles -----------------------------------

    public static void onPlayerTick(MinecraftServer server, ServerPlayer p) {
        long now = p.level().getGameTime();
        Long cd = COOLDOWN.get(p.getUUID());
        if (cd != null && now < cd) return;
        Portal portal = portalAt(p);
        if (portal == null) return;
        COOLDOWN.put(p.getUUID(), now + 60);
        travel(server, p, portal);
    }

    private static Portal portalAt(ServerPlayer p) {
        ResourceKey<Level> dim = p.level().dimension();
        BlockPos feet = p.blockPosition();
        Portal a = ACTIVE.get(feet);
        if (a != null && a.dim == dim) return a;
        Portal b = ACTIVE.get(feet.above());
        return (b != null && b.dim == dim) ? b : null;
    }

    /** Draw the swirl with portal particles (called a few times a second from the server tick). */
    public static void spawnParticles(MinecraftServer server) {
        Set<Portal> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Portal portal : ACTIVE.values()) {
            if (!seen.add(portal)) continue;
            ServerLevel level = server.getLevel(portal.dim);
            if (level == null) continue;
            for (BlockPos c : portal.interior) {
                // Heal leftover nether_portal blocks from older builds back to plain air.
                if (level.hasChunkAt(c) && level.getBlockState(c).is(Blocks.NETHER_PORTAL)) {
                    level.setBlock(c, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                }
                level.sendParticles(ParticleTypes.PORTAL, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5,
                        2, 0.3, 0.4, 0.3, 0.0);
            }
        }
    }

    private static void travel(MinecraftServer server, ServerPlayer p, Portal portal) {
        if (portal.type == DestType.RETURN) { returnHome(server, p); return; }
        ServerLevel plots = server.getLevel(FabricPlots.PLOTS_DIM);
        if (plots == null) { msg(p, "Plot world isn't loaded."); return; }
        RETURN_POINT.put(p.getUUID(), GlobalPos.of(portal.dim, portal.anchor));
        if (portal.type == DestType.PLAZA) {
            p.teleportTo(plots, PlotsConfig.spawnX + 0.5, PlotsConfig.spawnY, PlotsConfig.spawnZ + 0.5,
                    Set.of(), p.getYRot(), 0.0f, false);
            settle(p);
            msg(p, "Welcome to the plot world! /plot leave to return to your portal.");
        } else {
            int[] xz = PlotManager.homeXZ(portal.plot);
            p.teleportTo(plots, xz[0] + 0.5, PlotConfig.FLOOR_Y, xz[1] + 0.5,
                    Set.of(), p.getYRot(), 0.0f, false);
            settle(p);
            msg(p, "Warped to plot (" + portal.plot.px() + ", " + portal.plot.pz() + "). /plot leave to return.");
        }
    }

    /** Send a player back to the portal they came in through (or the home-world spawn). Used by /plot leave. */
    public static void returnHome(MinecraftServer server, ServerPlayer p) {
        COOLDOWN.put(p.getUUID(), p.level().getGameTime() + 60);
        GlobalPos ret = RETURN_POINT.get(p.getUUID());
        ServerLevel dest = ret == null ? server.overworld() : server.getLevel(ret.dimension());
        if (dest == null) dest = server.overworld();
        BlockPos pos = ret == null ? dest.getRespawnData().pos() : ret.pos();
        p.teleportTo(dest, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), p.getYRot(), 0.0f, false);
        settle(p); // no fall damage stepping out of the portal
        msg(p, "Returned to your home world.");
    }

    // ---- auto-built exit portals (in the plot world) --------------------

    /** The N-S street column a plot's exit portal sits on, or MIN_VALUE if the plot has no adjacent portal street. */
    private static int portalStreetFor(int px) {
        int s = PlotsConfig.portalStreetSpacing;
        if (Math.floorMod(px, s) == 0) return px;          // the road on this plot's EAST is a portal street
        if (Math.floorMod(px - 1, s) == 0) return px - 1;  // the road on this plot's WEST is a portal street
        return Integer.MIN_VALUE;                           // sparse middle column — nearest portal street is a plot away
    }

    private static BlockPos exitAnchor(int streetPx, int pz) {
        int cx = streetPx * PlotConfig.STEP + ROAD_CENTER_LX;
        int cz = pz * PlotConfig.STEP + PlotConfig.PLOT_SIZE / 2;
        return new BlockPos(cx, PlotConfig.ROAD_Y + 1, cz); // opening floor = one above the road surface
    }

    /** Build the exit portal for a freshly-claimed plot cell (no-op if it has no portal street or already exists). */
    public static void buildExitPortal(ServerLevel plots, PlotPos cell) {
        if (plots == null) return;
        int streetPx = portalStreetFor(cell.px());
        if (streetPx == Integer.MIN_VALUE) return;
        // Skip streets that are interior to a merge (dissolved to grass — not a real road).
        if (PlotManager.sameMerge(streetPx, cell.pz(), streetPx + 1, cell.pz())) return;
        BlockPos anchor = exitAnchor(streetPx, cell.pz());
        if (ACTIVE.containsKey(anchor)) return; // already built (shared by the two flanking plots)

        final int cx = anchor.getX(), cz = anchor.getZ(), y0 = anchor.getY();
        Set<BlockPos> interior = new HashSet<>();
        Set<BlockPos> frame = new HashSet<>();
        for (int dx = -OPENING_HALF; dx <= OPENING_HALF; dx++)
            for (int dy = 0; dy < OPENING_H; dy++) interior.add(new BlockPos(cx + dx, y0 + dy, cz));
        for (int dx = -OPENING_HALF - 1; dx <= OPENING_HALF + 1; dx++) {
            frame.add(new BlockPos(cx + dx, y0 - 1, cz));            // buried floor (flush with the road)
            frame.add(new BlockPos(cx + dx, y0 + OPENING_H, cz));    // lintel
        }
        for (int dy = 0; dy < OPENING_H; dy++) {
            frame.add(new BlockPos(cx - OPENING_HALF - 1, y0 + dy, cz));
            frame.add(new BlockPos(cx + OPENING_HALF + 1, y0 + dy, cz));
        }
        Portal portal = new Portal(DestType.RETURN, null, interior, frame, plots.dimension());
        for (BlockPos b : frame) plots.setBlock(b, CALCITE, Block.UPDATE_CLIENTS);
        for (BlockPos b : interior) plots.setBlock(b, AIR, Block.UPDATE_CLIENTS);
        for (BlockPos b : interior) ACTIVE.put(b, portal);
        for (BlockPos b : frame) FRAMES.put(b, portal);
        save();
    }

    /** Remove a cell's exit portal when neither flanking plot is claimed any more (called on unclaim). */
    public static void removeExitPortalIfOrphan(ServerLevel plots, PlotPos cell) {
        if (plots == null) return;
        int streetPx = portalStreetFor(cell.px());
        if (streetPx == Integer.MIN_VALUE) return;
        if (PlotManager.isClaimed(new PlotPos(streetPx, cell.pz()))
                || PlotManager.isClaimed(new PlotPos(streetPx + 1, cell.pz()))) return; // a neighbour keeps it alive
        Portal portal = ACTIVE.get(exitAnchor(streetPx, cell.pz()));
        if (portal == null) return;
        destroyPortal(plots, portal);
        save();
    }

    /** After a merge, delete any exit portal that now sits on a dissolved internal road (inside the new plot). */
    public static void removeInternalMergePortals(ServerLevel plots, java.util.Collection<PlotPos> cells) {
        if (plots == null) return;
        Set<Portal> toRemove = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (PlotPos c : cells) {
            for (int streetPx : new int[]{ c.px(), c.px() - 1 }) { // the N-S roads touching this cell
                if (!PlotManager.sameMerge(streetPx, c.pz(), streetPx + 1, c.pz())) continue; // still a real road
                Portal p = ACTIVE.get(exitAnchor(streetPx, c.pz()));
                if (p != null && p.type == DestType.RETURN) toRemove.add(p);
            }
        }
        for (Portal p : toRemove) destroyPortal(plots, p);
        if (!toRemove.isEmpty()) save();
    }

    /** Unregister a portal, clear its blocks, and repaint the road/ground underneath. */
    private static void destroyPortal(ServerLevel plots, Portal portal) {
        for (BlockPos b : portal.frame) FRAMES.remove(b);
        for (BlockPos b : portal.interior) ACTIVE.remove(b);
        for (BlockPos b : portal.frame) plots.setBlock(b, AIR, Block.UPDATE_CLIENTS);
        for (BlockPos b : portal.interior) plots.setBlock(b, AIR, Block.UPDATE_CLIENTS);
        PlotWorldPainter.repaint(plots, portal.anchor.getX(), portal.anchor.getZ(), 3);
    }

    /** Tear down every exit portal and rebuild them for all claimed plots at the current spacing. */
    public static int rebuildAllExitPortals(ServerLevel plots) {
        if (plots == null) return 0;
        Set<Portal> existing = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Portal p : ACTIVE.values()) if (p.type == DestType.RETURN) existing.add(p);
        for (Portal p : existing) destroyPortal(plots, p);
        for (PlotPos cell : PlotManager.allClaimedCells()) buildExitPortal(plots, cell);
        save();
        Set<Portal> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Portal p : ACTIVE.values()) if (p.type == DestType.RETURN) live.add(p);
        return live.size();
    }

    /** True if a block belongs to a live portal in the plot world — the painter must leave these alone. */
    public static boolean isProtected(BlockPos pos) {
        Portal a = ACTIVE.get(pos);
        if (a != null && a.dim == FabricPlots.PLOTS_DIM) return true;
        Portal f = FRAMES.get(pos);
        return f != null && f.dim == FabricPlots.PLOTS_DIM;
    }

    // ---- persistence -----------------------------------------------------

    public static void load(MinecraftServer server) {
        saveFile = server.getWorldPath(LevelResource.ROOT).resolve("fabricplots-portals.txt");
        ACTIVE.clear();
        FRAMES.clear();
        if (!Files.exists(saveFile)) return;
        try {
            for (String line : Files.readAllLines(saveFile)) {
                if (line.isBlank()) continue;
                try {
                    String[] parts = line.split(";", -1);
                    DestType type = DestType.valueOf(parts[0]);
                    ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(parts[1]));
                    PlotPos plot = type == DestType.PLOT ? new PlotPos(Integer.parseInt(parts[2]), Integer.parseInt(parts[3])) : null;
                    Set<BlockPos> interior = parsePositions(parts[4]);
                    Set<BlockPos> frame = parts.length > 5 ? parsePositions(parts[5]) : new HashSet<>();
                    if (interior.isEmpty()) continue;
                    Portal portal = new Portal(type, plot, interior, frame, dim);
                    for (BlockPos p : interior) ACTIVE.put(p, portal);
                    for (BlockPos p : frame) FRAMES.put(p, portal);
                } catch (Exception e) {
                    System.err.println("[FabricPlots] Skipped bad portal line: " + line + " (" + e + ")");
                }
            }
        } catch (Exception e) {
            System.err.println("[FabricPlots] Failed to read portals: " + e);
        }
    }

    private static Set<BlockPos> parsePositions(String s) {
        Set<BlockPos> out = new HashSet<>();
        for (String t : s.split(",")) {
            if (t.isBlank()) continue;
            String[] c = t.split(":");
            out.add(new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
        }
        return out;
    }

    public static void save() {
        if (saveFile == null) return;
        List<String> lines = new ArrayList<>();
        Set<Portal> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Portal portal : ACTIVE.values()) {
            if (!seen.add(portal)) continue;
            String px = portal.plot == null ? "0" : Integer.toString(portal.plot.px());
            String pz = portal.plot == null ? "0" : Integer.toString(portal.plot.pz());
            lines.add(portal.type + ";" + portal.dim.identifier() + ";" + px + ";" + pz + ";"
                    + join(portal.interior) + ";" + join(portal.frame));
        }
        try {
            Files.write(saveFile, lines);
        } catch (Exception e) {
            System.err.println("[FabricPlots] Failed to save portals: " + e);
        }
    }

    private static String join(Set<BlockPos> set) {
        StringJoiner j = new StringJoiner(",");
        for (BlockPos b : set) j.add(b.getX() + ":" + b.getY() + ":" + b.getZ());
        return j.toString();
    }

    private static void msg(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal("[Plots] " + text));
    }
}
