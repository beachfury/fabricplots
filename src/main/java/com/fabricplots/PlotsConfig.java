package com.fabricplots;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Live, admin-editable settings (config/fabricplots.properties), applied with /plot reload.
 * NOTE: this is only the NON-geometry tunables. Plot size, road width, and Y-levels live in
 * {@link PlotConfig} because changing them needs a world regen, so they can't be hot-reloaded.
 */
public final class PlotsConfig {
    // Defaults double as the documented values written to a fresh file.
    public static volatile boolean allowPlayerCombine = false; // can non-ops merge their own plots?
    public static volatile int maxMergeCells = 8;              // cap on plots per player-made merge
    public static volatile int claimLimit = 0;                // max plots per player (0 = unlimited)
    public static volatile boolean welcomeMessage = true;     // show the action-bar welcome on a plot
    public static volatile int unnamedMobGraceTicks = 12000;  // how long an unnamed mob may live
    public static volatile int mobScanIntervalTicks = 600;    // how often to sweep stale mobs
    public static volatile int portalStreetSpacing = 2;       // exit portals on every Nth N-S street (1=every)
    public static volatile boolean advanceTime = true;        // day/night cycle in the plot world
    public static volatile boolean advanceWeather = true;     // weather cycle in the plot world
    public static volatile boolean protectExplosions = true;  // block TNT (and creeper) explosion damage
    public static volatile boolean protectFire = true;        // stop fire spreading/burning builds
    public static volatile boolean protectMobGriefing = true; // stop mobs modifying blocks
    public static volatile boolean protectProjectiles = true; // stop arrows/etc. breaking blocks
    public static volatile boolean inactivityExpiry = false;  // auto-release plots of long-absent owners
    public static volatile int inactivityDays = 30;           // days of inactivity before release
    public static volatile int spawnX = PlotConfig.SPAWN_X;   // plot-world spawn (set with /plot setspawn)
    public static volatile int spawnY = PlotConfig.FLOOR_Y;
    public static volatile int spawnZ = PlotConfig.SPAWN_Z;

    private static Path file;

    private PlotsConfig() {}

    /** Load (creating a default file if absent). Safe to call again for /plot reload. */
    public static void load() {
        if (file == null) file = FabricLoader.getInstance().getConfigDir().resolve("fabricplots.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) { p.load(in); }
            catch (Exception e) { System.err.println("[FabricPlots] Failed to read config: " + e); }
        }
        allowPlayerCombine   = bool(p, "allow-player-combine", allowPlayerCombine);
        maxMergeCells        = inted(p, "max-merge-cells", maxMergeCells);
        claimLimit           = inted(p, "claim-limit", claimLimit);
        welcomeMessage       = bool(p, "welcome-message", welcomeMessage);
        unnamedMobGraceTicks = inted(p, "unnamed-mob-grace-ticks", unnamedMobGraceTicks);
        mobScanIntervalTicks = inted(p, "mob-scan-interval-ticks", mobScanIntervalTicks);
        portalStreetSpacing = Math.max(1, inted(p, "portal-street-spacing", portalStreetSpacing));
        advanceTime = bool(p, "advance-time", advanceTime);
        advanceWeather = bool(p, "advance-weather", advanceWeather);
        protectExplosions = bool(p, "protect-explosions", protectExplosions);
        protectFire = bool(p, "protect-fire", protectFire);
        protectMobGriefing = bool(p, "protect-mob-griefing", protectMobGriefing);
        protectProjectiles = bool(p, "protect-projectiles", protectProjectiles);
        inactivityExpiry = bool(p, "inactivity-expiry", inactivityExpiry);
        inactivityDays = Math.max(1, inted(p, "inactivity-days", inactivityDays));
        spawnX = inted(p, "spawn-x", spawnX);
        spawnY = inted(p, "spawn-y", spawnY);
        spawnZ = inted(p, "spawn-z", spawnZ);
        save(); // always rewrite so keys added in a mod update show up in the file (values are preserved)
    }

    public static void save() {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("allow-player-combine", Boolean.toString(allowPlayerCombine));
        p.setProperty("max-merge-cells", Integer.toString(maxMergeCells));
        p.setProperty("claim-limit", Integer.toString(claimLimit));
        p.setProperty("welcome-message", Boolean.toString(welcomeMessage));
        p.setProperty("unnamed-mob-grace-ticks", Integer.toString(unnamedMobGraceTicks));
        p.setProperty("mob-scan-interval-ticks", Integer.toString(mobScanIntervalTicks));
        p.setProperty("portal-street-spacing", Integer.toString(portalStreetSpacing));
        p.setProperty("advance-time", Boolean.toString(advanceTime));
        p.setProperty("advance-weather", Boolean.toString(advanceWeather));
        p.setProperty("protect-explosions", Boolean.toString(protectExplosions));
        p.setProperty("protect-fire", Boolean.toString(protectFire));
        p.setProperty("protect-mob-griefing", Boolean.toString(protectMobGriefing));
        p.setProperty("protect-projectiles", Boolean.toString(protectProjectiles));
        p.setProperty("inactivity-expiry", Boolean.toString(inactivityExpiry));
        p.setProperty("inactivity-days", Integer.toString(inactivityDays));
        p.setProperty("spawn-x", Integer.toString(spawnX));
        p.setProperty("spawn-y", Integer.toString(spawnY));
        p.setProperty("spawn-z", Integer.toString(spawnZ));
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                p.store(out, "FabricPlots settings — edit then run /plot reload. Geometry (plot/road size, Y) needs a world regen and lives in code.");
            }
        } catch (Exception e) { System.err.println("[FabricPlots] Failed to write config: " + e); }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int inted(Properties p, String k, int def) {
        String v = p.getProperty(k);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }
}
