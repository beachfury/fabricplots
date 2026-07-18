# Changelog

All notable changes to FabricPlots. Versions during early development were iterated as dated dev builds
(`/plot version` reports the current build stamp).

## [0.3.0] — 2026-07-18

The shapes update.

### Added
- **Shapes screen.** `/plot edit` → **Shapes…** opens a dedicated builder: pick **circle, square,
  sphere, cylinder, pyramid, or line**, choose **Filled or Hollow** (hollow = ring, frame, shell, tube,
  or stepped frame), and dial in **Size**, **Height** (extrudes flat shapes into towers), **Thickness**
  (hollow walls and line beams), and **Repeat × Spacing** (stack vertical copies with an air gap —
  instant floors). Parameter buttons: left-click +1, right-click −1, shift for ±5.
- **Gold center marker.** *Set center* drops a gold block at your feet — a single block for odd sizes,
  a 2×2 for even sizes — so you can see exactly where the shape will land before you build. The marker
  is self-cleaning: whatever it covered is restored when you build, move it, or clear it. No marker?
  Shapes build centered on where you stand.
- **Line tool.** Draws a straight line from corner 1 to corner 2 at **any angle — diagonals and slopes
  included**, in full 3D. Thickness turns it into a beam. Also available as `/plot line <block> [thickness]`.
- **Random texture toggle.** Flip *Random texture* ON (lime/gray dye button on the editor and Shapes
  screens) and every edit — fill, replace, walls, shapes, lines — **and every block you place by hand**
  mixes **all the blocks in your hotbar** randomly instead of using just the held block: lay a path
  without ever scrolling your hotbar. Put a block in multiple slots to make it more common. In survival
  the cost comes out of the stack that actually got placed. Per-player, resets on server restart.
- **Find center of a line.** With corners 1 and 2 set, a Shapes-screen button (or `/plot center`)
  marks the middle of the line with gold — one block for odd lengths, the middle two for even, same
  rule as shapes. The marker doubles as the shape center, so "mark a wall → find center → build a
  circle" gives you a perfectly centered window.
- **Measuring tape.** Corner 1 → corner 2 becomes a yellow/black caution-stripe ruler (straight
  runs only), counting from 1, with numbered signs every 2, 5, or 10 blocks depending on length —
  start and end are always numbered. Horizontal runs get signs standing on top; vertical runs get
  wall signs facing you. Temporary and self-cleaning: laying a new tape or clearing it (button or
  `/plot tape clear`) puts back exactly what it covered.
- **New commands:** `/plot disc <block> <size> [height]`, `/plot ring <block> <size> [height]`,
  `/plot line <block> [thickness]`, `/plot center`, `/plot tape`, `/plot tape clear`.

### Changed
- The sphere / hollow-sphere / cylinder buttons on the main editor page moved into the new Shapes
  screen (the `/plot sphere`, `/plot hsphere`, `/plot cyl` commands still work as before).

Everything the Shapes screen builds goes through the same editor pipeline as before: plot-jailed to
land you own, counted against the edit budget, and fully undoable with `/plot undo`.

## [0.2.0] — 2026-07-17

The customization update.

### Added
- **Sidewalk designer.** A chest-GUI pattern editor in plot settings: pick any block from your inventory and
  stamp it into a repeating 9-wide template (3 sidewalk strips + the curb, between locked "your plot" and
  "street" marker rows). The design tiles along every edge of your plot — including merged shapes — and any
  stairs in the curb row automatically face the street. Empty cells are air. Applied when you close the
  screen; survives `/plot clear`; Reset returns the standard tuff sidewalk.
- **Wall designer.** Same editor, side-on: up to 3 layers of any blocks built on your plot's edge ring,
  between a locked grass row and sky rows. Stairs face outward; empty cells are gaps (fence-like designs
  welcome). Reset removes the wall.
- **Sky & weather per plot.** Owners pick a look — always day/noon/sunset/night/midnight and/or
  clear/rain/thunder — and visitors standing on the plot see it. Pure client-side illusion via play packets:
  the server's real time and weather never change, and Bedrock players (Geyser) see it too.
- **Per-plot biome.** A paginated picker of **every biome registered in the world** — all the vanilla ones
  (including version-specific additions like 26.2's Sulfur Caves) plus anything added by datapacks or
  worldgen mods, automatically, with no code changes. Known biomes get fitting icons; modded ones get a
  guessed icon and show their source mod. Pick one
  in the settings menu and your whole plot — cores, sidewalk ring, merged interiors — is repainted to it:
  grass, foliage and sky tint change the moment anyone steps on. This one is real biome data (painted through
  vanilla's /fillbiome machinery), so it persists in the world, survives `/plot clear`, and resets to the
  plot-world default when the plot is deleted or expires.
- **Custom greeting.** An owner-set action-bar welcome shown when someone steps onto the plot (replaces the
  stock "Welcome to X's plot" line).
- **Transfer ownership.** Give a plot to another online player — `/plot transfer <player>` or the settings
  menu (with a confirmation screen).
- **Kick visitors.** `/plot kick` (or the settings button) sends every non-trusted visitor on your plot back
  to the spawn plaza.
- **Browse Plots.** A hub gallery of every claimed plot (alphabetical, with owner and like count) — visit or
  like any plot from it.
- **Plot-bound mobs.** A spawn-heavy plot biome (Nether Wastes, Mushroom Fields…) naturally spawns that
  biome's mobs — part of the flavor — but they now stay put: their wander AI is tethered to where they
  spawned, and a periodic sweep teleports back anything that slips onto a road or a neighbour's plot.
  Named mobs are exempt, and the existing unnamed-mob cleanup still prevents build-up.
- **Per-plot mob spawn toggles.** Settings → Mob spawning: allow or block the biome's **hostile** and
  **passive** spawns independently. Turning one off immediately clears that category from the plot, and
  the block also despawns saved mobs as their chunks reload. Named mobs (pets) are never touched.
- **Mob lifecycle is per-plot now.** The old global rule ("unnamed mobs vanish after 10 minutes") no
  longer applies on plots — what lives there is the owner's choice via the toggles, so unnamed farm
  animals, biome mobs and zoo builds persist. The timed cull still cleans **streets and unclaimed
  ground** (escapees and strays nobody owns).
- **Cleanups that actually clean.** Changing a plot's biome removes the old biome's mobs and their dropped
  items; `/plot clear` now removes **every** non-player entity on the plot (mobs, drops, armor stands,
  boats, item frames…) along with the blocks.
- **Placeholder API integration** (optional, auto-detected): `%fabricplots:owned%`, `%fabricplots:total%`,
  `%fabricplots:my_likes%`, and — for the plot you're standing on — `%fabricplots:plot_name%`,
  `%fabricplots:plot_owner%`, `%fabricplots:plot_likes%`, `%fabricplots:plot_biome%`. For tab lists, chat
  formats, holograms, etc.
- **Full plot containment.** Nothing physical escapes a plot any more:
  - *Fluids stay home* — water/lava can no longer flow off a plot onto streets (including
    dispenser-placed fluids; a small mixin blocks the spread at the source).
  - *Pistons stay home* — a piston move is cancelled if the head or any pushed/pulled block would
    start or end outside the piston's plot. Blocks push-out, street-block pull-in, and flying
    machines (they stall at the boundary).
  - *Self-healing streets* — roads/curbs/lamps are deterministic, so a budgeted sweeper near players
    resets anything foreign: tree canopies grown over the road, snow layers from a plot biome's
    unavoidable 4-block bleed, fallen sand, spills. Zero block writes when streets are clean.
    Config: `street-sweeper` (on) and `street-sweeper-spawn-radius` (64 — the spawn plaza is left
    alone so admins can decorate it).
  - *Street litter cleanup* — dropped items, boats/minecarts, and unnamed armor stands sitting on a
    street for 5+ minutes are removed (plots are untouched).
  - *Curb placement exploit fixed* — placing a block now validates where the block actually lands,
    not just the block you clicked, so you can no longer build over the curb from the street. Beds
    are checked for BOTH halves, so a bed at the plot edge can't lay its head over the curb.
  - *Respawn anchors & end crystals banned in the plot world* — their explosions bypass the TNT
    protection, and an anchor can't even set spawn in this dimension; it only explodes.
- `/plot name` input is now sanitized (a stray `;` could corrupt the save file).

### Changed
- Plot settings menu grew a row to fit the new buttons.

## [0.1.12] — 2026-07-17

### Fixed
- **Portal return points now survive server restarts.** The "where you came from" position (used by return
  portals and `/plot leave`) was only held in memory, so after a restart anyone still in the plot world was
  sent to world spawn instead of back to their portal. Return points are now saved to
  `fabricplots-returns.txt` alongside the portal file and reloaded on server start.

## [0.1.11] — 2026-07-13

### Fixed
- **`/plot clear` now fully clears merged plots.** It only reset each cell's 32×32 core, so builds on the
  dissolved roads between a merge's cells — and on any plot's sidewalk ring — survived the clear. It now wipes
  every column the plot owns (cores, sidewalk ring, and merged interior), repainting the sidewalk ring as
  sidewalk and the rest as the plot's floor block. The merge itself is untouched — clearing does not uncombine.

## [0.1.10] — 2026-07-13

### Added
- **`manage-gamemode` config option** (default `true`). When `false`, FabricPlots never touches player
  gamemode — for servers where another mod owns it, e.g. **Dimensional Inventories** with a `gameMode` set on
  its dimension pools. Two mods both forcing gamemode on dimension change was the root cause of players
  getting stuck in creative: the other mod flipped the player first, and FabricPlots's mode handling fought
  it on exit. If DI manages your plot world's gamemode, set `manage-gamemode=false`; otherwise leave it on
  and don't give DI pools a gamemode.

## [0.1.9] — 2026-07-13

### Changed
- **Leaving the plot world now always puts you in survival.** The 0.1.2 "restore the mode you entered with"
  behavior could still strand players in creative when another mod that also manages gamemode (e.g. a
  per-dimension inventory mod with a gamemode override on the plot dimension) or a server restart changed the
  recorded mode. Until that interaction has a proper fix, exit is unconditionally survival — a creative admin
  just runs `/gamemode creative` again after stepping out.

## [0.1.8] — 2026-07-11

### Fixed
- **Stuck in creative after leaving the plot world.** If a player was in the plot world across a server
  restart (or sleep/wake) and then left through a portal, they could be left in creative in the overworld.
  The mod was re-capturing the *forced* creative mode as their "prior mode" when it had no record of the mode
  they actually entered with. It now only records the prior mode on a genuine entry from another dimension;
  when that record is missing (e.g. reconnecting straight into the plot world after a restart), exiting now
  falls back to survival instead of stranding the player in creative.

## [0.1.7] — 2026-07-07

### Changed
- **Combine wand: right-click a selected plot to unselect it.** The merge wand now toggles — re-clicking a plot
  that's already in your selection removes it (and its gold marker), instead of doing nothing. Previously the only
  way to deselect was `/plot wand cancel`, which cleared the whole selection.

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
