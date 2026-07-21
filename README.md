# FabricPlots

A **server-side**, PlotSquared-style plot world for **Fabric / Minecraft 26.1.2 & 26.2**, built for **Java + Bedrock
crossplay** (Geyser/Floodgate). Nothing is required on the client — Bedrock players use every command and menu
through Geyser. Drop the jar on the server and you have a full creative plot server.

## Features

- **Plot world** — a bundled flat creative dimension (`fabricplots:plots`) with a gridded street network
  (roads, sidewalks, corner lamp posts). Forces creative inside, survival outside.
- **Claiming** — `/plot claim`, `/plot auto`, `/plot home`, `/plot visit`, per-player claim limits.
- **Grief protection** — only the owner and trusted players build; roads and other plots are locked.
  **Full containment:** fluids can't flow off a plot, pistons/flying machines can't cross the boundary,
  respawn anchors & end crystals are banned (their blasts bypass TNT rules), and **self-healing streets**
  automatically remove anything foreign from roads (tree overhang, snow, spills, litter).
- **Trust & deny** — `/plot trust` / `/plot deny` (deny bounces a player off your plot).
- **Custom plot floor** — pick your plot's ground block from a paginated GUI palette of **every full-cube block**
  (all the natural blocks, stones, woods, 16 concretes, ores — and version-specific blocks like 26.2's sulfur and
  cinnabar). It recolors the surface instantly and survives `/plot clear`.
- **Per-plot PvP toggle** — the plot world is safe by default; an owner can allow PvP on their own plot from the
  settings menu (roads and other plots stay safe).
- **Sidewalk & wall designers** — chest-GUI pattern editors: place any blocks into a repeating 9-wide template
  (locked marker rows show your plot, the street, and the sky) and the design tiles along every edge of your
  plot. Sidewalks are 3 strips + a curb (curb stairs always face the street); walls build up to 3 tall on your
  plot's edge ring. Designs persist, survive `/plot clear`, and follow merges.
- **Sky & weather per plot** — the owner picks "always sunset", "always rain", etc.; visitors standing on the
  plot see it (client-side illusion — the server's real time/weather never changes). Works for Bedrock too.
- **Per-plot biome** — a paginated picker of **every biome in the world's registry** (vanilla, datapacks,
  and worldgen mods appear automatically) repaints your plot — grass, foliage and sky tint — via vanilla's
  `/fillbiome` machinery. Persists in the world, covers merged shapes, and resets automatically when a plot
  is deleted or expires. Mobs the biome spawns are **confined to the plot** (tethered AI + a
  teleport-back sweep), so a Nether Wastes plot keeps its piglins to itself — and per-plot **spawn
  toggles** let the owner allow/block hostile and passive spawns independently. Changing biome (or
  `/plot clear`) cleans up the previous mobs and their drops.
- **Placeholders** — with [Placeholder API](https://placeholders.pb4.eu/) installed, `%fabricplots:owned%`,
  `%fabricplots:total%`, `%fabricplots:my_likes%`, `%fabricplots:plot_name%`, `%fabricplots:plot_owner%`,
  `%fabricplots:plot_likes%`, `%fabricplots:plot_biome%` work in tab lists, chat formats and holograms
  (soft dependency — nothing required).
- **Custom greeting** — an owner-set welcome line shown to visitors stepping onto the plot.
- **Transfer & kick** — hand a plot to another player (`/plot transfer` or the menu), and send unwanted
  visitors back to spawn (`/plot kick` or the menu).
- **Likes & Top Plots** — players `/plot like` a plot they're standing on (or use the GUI), and the **Top Plots**
  gallery in `/plot menu` ranks every plot by likes so people can browse and visit the best builds.
- **Plot merging, any shape** — an op (or players, if enabled) selects plots with a wand and merges them into
  L / T / H / + or solid shapes; roads dissolve and the curb wraps the new outline. `/plot uncombine` splits it back.
- **Frame portals** — build a calcite frame at your base and light it: flint & steel → spawn, or a **Plot Portal
  Key** → that exact plot. Exit portals auto-build by claimed plots. (Uses particle swirls, not the nether block,
  so it never sends anyone to the nether.)
- **Build tools — a "WorldEdit-lite" that's jailed to your plot.** `set`, `replace`, `walls`, `sphere`,
  `hsphere`, `cyl`, `disc`, `ring`, `line`, `copy`, `cut`, `paste`, `stack`, `move`, `undo`, `redo` — every
  block written is ownership-checked, so it physically cannot edit a road or someone else's plot. No griefing
  risk, no WorldEdit region setup.
- **Editor hub with Shapes & Measure screens** — `/plot edit` opens a quick-bar hub (corners, clipboard,
  fill/walls, stack/move, undo/redo) with doors into two dedicated screens. **Shapes**: circle, square,
  sphere, cylinder, pyramid, and a 3D diagonal **line tool**, each filled or hollow (ring / frame /
  shell / tube), with size (up to 256), height, thickness, and repeat×spacing dials, built on a
  self-cleaning **gold center marker** that shows exactly where the shape will land. **Measure**: a live
  *W × H × L* selection readout, **find the center** of any corner-to-corner line (`/plot center`), and a
  temporary yellow/black **measuring tape** with glowing numbered signs (`/plot tape`).
- **Random texture mode** — toggle it on and every edit *and every block you place by hand* mixes all
  the blocks in your hotbar randomly: lay natural-looking paths without ever scrolling. Duplicate slots
  weight the mix; in survival the cost comes from the stack that actually got placed.
- **Clickable menus (sgui, crossplay)** — `/plot menu` (hub with **Claim a Plot**, My Plots, Build Editor, Portal
  Keys, **Browse Plots**, Top Plots), `/plot edit` (build GUI; material = the block in your hand), and per-plot
  settings: rename, floor-block picker, sidewalk & wall designers, sky & weather, greeting, PvP toggle, transfer,
  kick visitors, and trusted/denied management with player heads + name search. Claiming works from the menu
  too — no commands required.
- **Config & protection** — live-reloadable `config/fabricplots.properties`: time/weather toggles, explosion /
  fire / mob-griefing / projectile protection, inactivity expiry, spawn point, and more.
- **Admin safety** — ops are not auto-exempt; an op runs `/plot admin` to *opt in* to editing outside their own
  plots, so the powerful tools can't wreck the map by accident.
- **Optional economy** — off by default. Turn it on in the config to charge for claiming plots (with optional
  first-plot-free, admin exemption, and refunds on delete). Integrates with the
  [Common Economy API](https://github.com/Patbox/common-economy-api), so it works with any compliant economy mod
  (e.g. [Savs Common Economy](https://modrinth.com/mod/savs-common-economy)) — and it's a soft dependency, so
  FabricPlots runs fine without one.

## Requirements

A Fabric server on **Minecraft 26.1.2 or 26.2** (see [Versions](#versions)) with, in the `mods/` folder:

| Mod | Notes |
|-----|-------|
| `fabricplots-x.x.x.jar` | this mod |
| **sgui** | **required** — the clickable menus ([maven.nucleoid.xyz](https://maven.nucleoid.xyz)). Use the build that matches your MC version (see Versions) |
| Fabric API | the build for your MC version |

For Bedrock players: **Geyser** + **Floodgate** (Geyser connects directly to 26.1.x / 26.2 — no ViaProxy).

See **[SERVER-SETUP.md](SERVER-SETUP.md)** for the full deployment guide (config table, first-run notes, admin tips).

## Quick start

1. Put both jars + Fabric API in `mods/`, start the server (**use a fresh world** the first time).
2. Players spawn in the overworld and reach the plot world via `/plot world` or a portal.
3. Walk into an empty plot → `/plot claim`. Type `/plot help` in-game for the full command list, or `/plot menu`
   for the GUI.

## Keeping plot items separate (recommended)

FabricPlots does **not** isolate inventories on its own — by default, items carry between the plot world and your
other worlds, so players could bring creative items out of the plot world. To prevent that, pair it with a
per-dimension inventory mod such as **[Dimensional Inventories](https://modrinth.com/mod/dimensional-inventories)**
and put the plot dimension in its own pool:

```
/diminv pool plots create
/diminv pool plots dimension fabricplots:plots assign
/diminv pool plots gamemode creative
```

Now the plot world keeps its own inventory (and gamemode) — creative items stay in the plot world, and your main
world keeps its own items.

> **Important:** if you give Dimensional Inventories a `gameMode` on its pools (as above), also set
> **`manage-gamemode=false`** in `config/fabricplots.properties` so only one mod manages gamemode. With both
> active, the two can fight over the mode on dimension change (e.g. players getting stuck in creative).

> **Portal Keys & separated inventories:** because the key would otherwise be stripped when you leave the plot
> world, run **`/plot key` at your base in the main world** (or use the **Portal Keys** button in `/plot menu`) — it
> gives you a key for every plot you own, created right in your main-world inventory. `/plot menu` and `/plot list`
> also open from any world.

## Building from source

Requires **JDK 25**. Minecraft 26.x ships unobfuscated (official Mojang names, no `mappings` line; uses the `jar`
task, not `remapJar`).

```bash
./gradlew build      # or: gradle build
```

Output: `build/libs/fabricplots-<version>.jar`.

## Versions

Both supported Minecraft versions are tracked as branches — **identical features**, differing only in version
numbers and a few renamed vanilla blocks:

| Branch | Minecraft | Fabric API | sgui |
|--------|-----------|------------|------|
| [`main`](../../tree/main) | 26.1.2 | `0.145.4+26.1.2` | `2.0.0+26.1` |
| [`26.2`](../../tree/26.2) | 26.2 | `0.153.0+26.2` | `2.1.0+26.2` |

## License

MIT — see [LICENSE](LICENSE).
