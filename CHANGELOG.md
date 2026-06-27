# Changelog

All notable changes to FabricPlots. Versions during early development were iterated as dated dev builds
(`/plot version` reports the current build stamp).

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
