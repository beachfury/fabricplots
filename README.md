# FabricPlots

A **server-side**, PlotSquared-style plot world for **Fabric / Minecraft 26.1.2 & 26.2**, built for **Java + Bedrock
crossplay** (Geyser/Floodgate). Nothing is required on the client — Bedrock players use every command and menu
through Geyser. Drop the jar on the server and you have a full creative plot server.

## Features

- **Plot world** — a bundled flat creative dimension (`fabricplots:plots`) with a gridded street network
  (roads, sidewalks, corner lamp posts). Forces creative inside, survival outside.
- **Claiming** — `/plot claim`, `/plot auto`, `/plot home`, `/plot visit`, per-player claim limits.
- **Grief protection** — only the owner and trusted players build; roads and other plots are locked.
- **Trust & deny** — `/plot trust` / `/plot deny` (deny bounces a player off your plot).
- **Plot merging, any shape** — an op (or players, if enabled) selects plots with a wand and merges them into
  L / T / H / + or solid shapes; roads dissolve and the curb wraps the new outline. `/plot uncombine` splits it back.
- **Frame portals** — build a calcite frame at your base and light it: flint & steel → spawn, or a **Plot Portal
  Key** → that exact plot. Exit portals auto-build by claimed plots. (Uses particle swirls, not the nether block,
  so it never sends anyone to the nether.)
- **Build tools — a "WorldEdit-lite" that's jailed to your plot.** `set`, `replace`, `walls`, `sphere`,
  `hsphere`, `cyl`, `copy`, `cut`, `paste`, `stack`, `move`, `undo`, `redo` — every block written is
  ownership-checked, so it physically cannot edit a road or someone else's plot. No griefing risk, no WorldEdit
  region setup.
- **Clickable menus (sgui, crossplay)** — `/plot menu` (hub), `/plot edit` (build GUI; material = the block in
  your hand), per-plot settings, and trusted/denied management with player heads + name search.
- **Config & protection** — live-reloadable `config/fabricplots.properties`: time/weather toggles, explosion /
  fire / mob-griefing / projectile protection, inactivity expiry, spawn point, and more.
- **Admin safety** — ops are not auto-exempt; an op runs `/plot admin` to *opt in* to editing outside their own
  plots, so the powerful tools can't wreck the map by accident.

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
