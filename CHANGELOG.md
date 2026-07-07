# Changelog

All notable changes to FabricPlots. Versions during early development were iterated as dated dev builds
(`/plot version` reports the current build stamp).

## [0.1.6] — 2026-07-06

### Added
- **Optional plot economy — charge money to claim plots.** Off by default; enable it in
  `config/fabricplots.properties`. When on, `/plot claim` and `/plot auto` charge a configurable cost, and
  `/plot delete` can refund part of what was paid. Merging is free (you already paid to claim each plot).
  Integrates with **[Patbox's Common Economy API](https://github.com/Patbox/common-economy-api)**, so it works
  with any compliant economy mod (e.g. [Savs Common Economy](https://modrinth.com/mod/savs-common-economy)). It's a
  soft dependency — if no economy mod is installed, FabricPlots runs exactly as before and claims stay free.

  New config keys (all under `economy-`): `enabled` (default **false**), `claim-cost` (100), `first-plot-free`
  (false), `charge-admins` (false — ops claim free), `refund-on-delete` (false), `refund-percent` (50),
  `currency-id` (blank = the provider's default currency). Free claims (first-plot-free / admins) record a paid
  amount of 0, so they can't be delete-refunded for profit.

## [0.1.5] — 2026-07-06

### Changed
- **Floor picker now offers every full-cube block.** Instead of a fixed 44-block list, the floor palette is built
  from the live block registry — every "square" block that has an item is selectable, across paginated screens
  (Previous/Next). Because it reads the registry at runtime, version-specific blocks appear automatically — e.g.
  **26.2's sulfur and cinnabar blocks** show up on the 26.2 build with no code changes. A few technical blocks
  (command/structure/jigsaw/barrier/spawner/etc.) are excluded.

## [0.1.4] — 2026-07-06

### Added
- **Claim a plot from the menu.** A new **Claim a Plot** button in the `/plot menu` hub (and a **+ Claim a plot**
  button on the My Plots screen) — no commands needed. If you're standing on an unclaimed plot it claims that one;
  otherwise it grabs the next free plot and warps you there, hands you the Portal Key, and builds the exit portal
  (same as `/plot claim` / `/plot auto`). Fully crossplay.

## [0.1.3] — 2026-07-06

New plot-customization and community features.

### Added
- **Custom plot floor.** A new **Floor block** button in the plot settings menu opens a 44-block palette (grass,
  dirt, sand, stone/quartz/prismarine variants, and all 16 concrete colors). Picking one recolors your plot's
  surface immediately, and the choice persists through `/plot clear`. The palette is addressed by block id, so it's
  identical on 26.1.2 and 26.2.
- **Per-plot PvP toggle.** The plot world is now PvP-safe by default. An owner can allow PvP on their own plot from
  the settings menu (**PvP: ON/OFF**); roads and other plots stay safe. Enforced server-side via the attack event.
- **Likes & Top Plots gallery.** Players can `/plot like` the plot they're standing on (you can't like your own),
  and a new **Top Plots** button in `/plot menu` opens a gallery ranked by likes — click a plot to view it, then
  **Visit** or **Like/Unlike**. `/plot info` now shows the like count and PvP state.

## [0.1.2] — 2026-07-06

Portal travel fixes.

### Fixed
- **No fall damage when returning to your home world.** Stepping back out of a plot-world portal now drops you at the
  **base** of the frame (previously it could place you partway up a tall portal) and clears any leftover fall speed,
  so you never take arrival damage. Fall speed/momentum is also cleared when entering the plot world.

### Changed
- **Your game mode now carries across the portal.** Entering the plot world still switches you to creative to build;
  **leaving restores the mode you arrived with** — a creative admin returns to creative, everyone else returns to
  survival — instead of forcing survival on everyone. (Note: if you log out *inside* the plot world, the server can't
  know your prior mode, so on your next exit it defaults to survival.)

## [0.1.1] — 2026-06-29

Quality-of-life fixes for servers that separate inventories per world.

### Changed
- **`/plot key` now works from anywhere.** Standing on a plot in the plot world still gives that plot's key; run it
  **outside the plot world (e.g. at your base)** and it hands you a key for **every plot you own**, created directly
  in your current inventory. This fixes the key being stripped by per-world inventory mods (e.g. **Dimensional
  Inventories**) when leaving the plot world.
- **`/plot menu` and `/plot list` open from any world** — the plot hub is your front door from your base now, not
  just inside the plot world. Location/build commands (claim, setspawn, the build tools, the in-plot editor) stay
  plot-world-only, as they act on where you stand.

### Added
- **Portal Keys** button in the `/plot menu` hub — grabs a key for each plot you own (handy from your base). The
  **Build Editor** hub button now redirects politely if you open it outside the plot world.

## [0.1.0] — initial release (Minecraft 26.1.2 & 26.2)

The first complete, feature-rich release.

**Minecraft versions** — tracked as two branches with identical features: **26.1.2** on `main`, **26.2** on the
`26.2` branch. They differ only in version numbers and a handful of renamed vanilla blocks (26.2 collapsed the
per-colour blocks into `ColorCollection`, e.g. `BLACK_CONCRETE` → `CONCRETE.black()`).

Grouped by area:

### Plot world & claiming
- Bundled flat creative dimension `fabricplots:plots` with a gridded street network (roads, sidewalks, corner
  lamp posts), raised above the void horizon. Ground is solid dirt down to y0 with bedrock at y-1.
- Forces creative inside the plot world, survival outside.
- `/plot world`, `/plot claim`, `/plot auto`, `/plot home`, `/plot visit`, `/plot info`, `/plot delete`,
  `/plot clear` (full-column reset), `/plot leave`, `/plot name`, `/plot sethome`.
- Per-player claim limits; configurable spawn (`/plot setspawn`).
- Action-bar welcome message when stepping onto a plot.

### Permissions & membership
- Grief protection: owner + trusted only; roads/other plots locked. Enforced server-side.
- `/plot trust` / `/plot untrust`, `/plot deny` / `/plot undeny` (denied players bounced off the plot).
- Server-owned plots (`/plot setserver`), ownership transfer (`/plot setowner`), `/plot removeall`.
- **Build-admin mode**: ops are not auto-exempt — `/plot admin` toggles editing outside your own plots, so the
  tools can't damage the map by accident.

### Merging
- Combine wand selects any rectilinear shape (L / T / H / + / solid); `/plot combine <player>` merges, dissolving
  interior roads and wrapping the curb around the new outline. `/plot uncombine` splits it back and re-cuts roads.

### Portals
- Nether-style **calcite frame portals** between the home world and the plot world — flint & steel → spawn, or a
  **Plot Portal Key** → that plot. Auto-built **exit portals** beside claimed plots, spacing configurable.
- Rendered with particle swirls (not the vanilla nether block), so portals never trigger nether travel.

### Build tools (plot-jailed WorldEdit-lite)
- Selection via `/plot pos1` `/plot pos2` or the editor wand.
- `set`, `replace`, `walls`, `sphere`, `hsphere`, `cyl`, `copy`, `cut`, `paste`, `stack`, `move`, `undo`, `redo`.
- Every write is ownership-checked and clamped to your plot; portals are never overwritten.

### Menus (sgui, crossplay)
- `/plot menu` hub, `/plot edit` build GUI (material = held block), `/plot list`.
- Per-plot settings (rename, teleport, clear) and trusted/denied management with player heads + name search.

### Config & protection
- Live-reloadable `config/fabricplots.properties` (`/plot reload`): player-combine toggle, max merge size, claim
  limit, welcome message, portal spacing, time/weather cycle, explosion/fire/mob-griefing/projectile protection,
  inactivity expiry, spawn point.
- Inactivity expiry auto-releases plots of long-absent owners (off by default).

### First-run
- One-line onboarding hint on join, pointing new players to `/plot world`.
