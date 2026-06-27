# FabricPlots — Server Setup

A server-side, **crossplay** (Java + Bedrock) PlotSquared-style plot world for Minecraft **26.1.2** (Fabric).
Players claim plots in a flat creative world, build, and travel in via portals. Nothing is required on the
client — Bedrock players use everything through Geyser by typing commands and clicking the menus.

## Requirements

Put these in the server's `mods/` folder:

| Mod | Why | Version |
|-----|-----|---------|
| **fabricplots-0.1.0.jar** | this mod | — |
| **sgui-2.0.0+26.1.jar** | the clickable menus (`/plot menu`, `/plot edit`) — **required**, the mod won't load without it | `2.0.0+26.1` from `maven.nucleoid.xyz` |
| **Fabric API** | dependency | `0.145.4+26.1.2` (or newer for 26.1.2) |

Plus the Fabric server itself:
- **Fabric Loader** `0.19.3+`, Minecraft **26.1.2**, **JDK 25**.
- For Bedrock players: **Geyser** (standalone or Fabric) + **Floodgate**. Geyser connects directly to 26.1.x — no ViaProxy needed.

## First run — IMPORTANT

- The plot world is a bundled datapack dimension (`fabricplots:plots`). It registers when the server starts.
- **Use a freshly-created world the first time**, and any time you change *geometry* (plot size, road width, or Y-levels — those live in code, not the config). Everything else (the config below, commands, build tools, portals) works on an existing world.
- On first start the mod writes `config/fabricplots.properties` with all settings at their defaults.

## How players get in

1. Players spawn in the **overworld** (their "home world"). They get a one-line hint on join.
2. They reach the plot world by either:
   - **`/plot world`** (drops them at the plot-world spawn), or
   - **a portal**: build a **calcite frame** (nether-portal size) at their base and light it —
     - **flint & steel** → goes to the plot-world spawn,
     - a **Plot Portal Key** (handed out on `/plot claim`/`/plot auto`, or `/plot key`) → goes straight to that plot.
3. Recommended: build one **flint-&-steel portal at overworld spawn** so newcomers have an obvious way in.
4. To leave the plot world: **`/plot leave`** (or walk through an exit portal, which auto-build beside claimed plots).

## Claiming & building

- Walk into an empty plot → **`/plot claim`** (or **`/plot auto`** for the next free one).
- **`/plot menu`** opens the hub; **`/plot edit`** opens the build GUI (the material is the block in your hand).
- Full command list in-game: **`/plot help`**.

## Config (`config/fabricplots.properties`)

Edit the file, then **`/plot reload`** (no restart). Geometry is NOT here (needs a fresh world).

| Key | Default | Meaning |
|-----|---------|---------|
| `allow-player-combine` | `false` | let non-ops merge their own adjacent plots |
| `max-merge-cells` | `8` | cap on plots per player merge |
| `claim-limit` | `0` | max plots per player (0 = unlimited) |
| `welcome-message` | `true` | action-bar greeting when stepping onto a plot |
| `portal-street-spacing` | `2` | exit portals every Nth N-S street (1 = every) |
| `advance-time` | `true` | day/night cycle in the plot world |
| `advance-weather` | `true` | weather in the plot world |
| `protect-explosions` | `true` | block TNT/creeper damage |
| `protect-fire` | `true` | stop fire spreading |
| `protect-mob-griefing` | `true` | stop mobs editing blocks |
| `protect-projectiles` | `true` | stop arrows breaking blocks |
| `inactivity-expiry` | `false` | auto-release plots of long-absent owners |
| `inactivity-days` | `30` | days of absence before release |
| `spawn-x/y/z` | `40/67/40` | plot-world spawn (set in-game with `/plot setspawn`) |

## Admin notes

- **Ops are NOT auto-exempt from plot protection.** An op builds like a normal player (own plots only) until they
  run **`/plot admin`** to enter build-admin mode (and again to leave). This prevents accidental edits — e.g. a
  `/plot edit` sphere spilling onto a road. Admin *commands* (`/plot reload`, `setspawn`, etc.) don't need the toggle.
- Server-owned plots: stand on a plot and **`/plot setserver`** (never expire, protected). **`/plot setowner <player>`** transfers.
- Combine plots for a player: **`/plot wand`** → click the plots (any L/T/H/+ shape) → **`/plot combine <player>`**. Split: **`/plot uncombine`**.

## Save files (in the world folder)

- `fabricplots-plots.txt` — plot ownership/trust/deny/home.
- `fabricplots-portals.txt` — lit portals.
- `fabricplots-lastseen.txt` — activity (for expiry).
- `fabricplots-decorated.txt` — which chunks have had street furniture placed.
