# FabricPlots

A **server-side**, PlotSquared-style plot world for **Fabric / Minecraft 26.1.2**, built for **Java + Bedrock
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

A Fabric server on **Minecraft 26.1.2** with, in the `mods/` folder:

| Mod | Notes |
|-----|-------|
| `fabricplots-x.x.x.jar` | this mod |
| `sgui-2.0.0+26.1.jar` | **required** — the clickable menus ([maven.nucleoid.xyz](https://maven.nucleoid.xyz)) |
| Fabric API | `0.145.4+26.1.2` or newer |

For Bedrock players: **Geyser** + **Floodgate** (Geyser connects directly to 26.1.x — no ViaProxy).

See **[SERVER-SETUP.md](SERVER-SETUP.md)** for the full deployment guide (config table, first-run notes, admin tips).

## Quick start

1. Put both jars + Fabric API in `mods/`, start the server (**use a fresh world** the first time).
2. Players spawn in the overworld and reach the plot world via `/plot world` or a portal.
3. Walk into an empty plot → `/plot claim`. Type `/plot help` in-game for the full command list, or `/plot menu`
   for the GUI.

## Building from source

Requires **JDK 25**. Minecraft 26.x ships unobfuscated (official Mojang names, no `mappings` line; uses the `jar`
task, not `remapJar`).

```bash
./gradlew build      # or: gradle build
```

Output: `build/libs/fabricplots-<version>.jar`.

## Versions

- **26.1.2** — current.
- **26.2** — planned (a tiny port; only a few renamed decorative blocks differ).

## License

MIT — see [LICENSE](LICENSE).
