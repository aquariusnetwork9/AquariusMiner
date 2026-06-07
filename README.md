# Aquarius Miner (ZenithProxy plugin)

An AFK bulk quarry miner for [ZenithProxy](https://github.com/rfresh2/ZenithProxy), by Shallowplague. It clears one
chunk at a time within a configurable Y band, then spirals outward to the next chunk — designed for
bulk deepslate collection on anarchy servers (2b2t / 6b6t).

This is a **standalone plugin**: it loads into a stock, unmodified ZenithProxy `java` instance. No
fork and no patched Baritone are required — it drives ZenithProxy's built-in `clearArea` pathfinder
process, which is already a box-confined, bottom-up, nearest-first quarry.

## How it works

- On enable, it anchors an outward square chunk-spiral at the bot's current chunk.
- For each chunk it calls `BARITONE.clearArea(min, max)` over the box
  `[chunkX*16, minY, chunkZ*16] → [chunkX*16+15, maxY, chunkZ*16+15]`.
- When the box is clear (the pathing future completes) it advances the spiral to the next chunk.
- A safety timeout force-advances if a chunk can't finish (e.g. it reaches into an unloaded chunk).
- Each chunk is cleared in `clearBoxSize` sub-boxes (default 8) rather than all at once, so the bot
  repositions between cells and walks back over the ground it just dug — picking up the drops instead
  of leaving them on the floor to despawn.
- A bounded area is mined **top-down in whole-area layers**: each `clearArea` box is a thin Y-slice
  (`layer-height`, default 1) and the bot sweeps that slice across the entire area before dropping to the
  next — so it never tunnels one cell to `minY` while the rest stands untouched. (The infinite spiral
  still clears full-height per chunk — there's no top of an infinite area to pre-sweep.)
- After each sub-box clears, a **vacuum pass** (`collect-drops`, default on) walks the bot over every
  dropped keep-item still on the floor in that box and picks it up before moving on. It only chases your
  `keep-items` (not junk), skips anything unreachable, and is capped by `collect-max-seconds`, so it can't
  hang.
- On enable it runs a one-time **resource scan** of the area and logs the 3 most abundant blocks and 5
  most abundant ores (with counts, 6-digit-capped) — headless, so there's no HUD; it's logged, flashed as
  an in-game alert, and replayable with `/aquariusminer scan`.
- Junk blocks (an explicit denylist — never tools/food/shulkers) are dropped periodically so the
  inventory fills slower.
- When the main inventory is (nearly) full, a **storage cycle** runs. Whenever the bot carries an **ender
  chest** it is the field buffer (mirroring the Foreman mod): the bot pulls an empty shulker out of the
  echest, fills it, and stores the FILLED shulker back **into** the echest — so filled shulkers are never
  left on the ground or left to clog the mining inventory. Without deposit chests the run simply ends once
  the ender chest is packed full of filled shulkers. (No ender chest? It falls back to placing a shulker
  beside the bot, optionally `breakAndCollect`.)
- A **deposit trip** (only when **deposit chests** are set) fires once the echest runs out of empties (detected the moment the last empty is
  used, with a clean inventory). The bot walks to the nearest **deposit chest** first with an empty
  inventory — the filled shulkers stay in the global echest until it's there, so a death en route can't
  strand them — then at the chest it pulls them out and drops them in, visits a separate **supply chest**
  for only as many empty shulkers as fit in the echest, and stocks those straight back into the echest.
  Chest locations are set by command (the proxy is headless, so there's no in-world crosshair to mark with).

## Build

Requires JDK 25 (the Gradle toolchain auto-provisions it). From the project root:

```
./gradlew build
```

The plugin jar is produced under `build/libs/`. Drop it into your ZenithProxy `plugins/` folder and
restart the proxy. Plugins are only supported on the `java` release channel.

## Commands

`/aquariusminer` (category: MODULE). Short alias: **`.aqm`** (e.g. `.aqm on`, `.aqm here 2 4`).

| Usage | Description |
| --- | --- |
| `aquariusminer on` / `off` | Enable / disable the miner |
| `aquariusminer minY <y>` | Lowest Y level to mine (inclusive) |
| `aquariusminer maxY <y>` | Highest Y level to mine (inclusive) |
| `aquariusminer here <length> <width>` | Set the box from the bot's **position + facing**: `<length>` chunks forward, `<width>` chunks right (re-anchors live if running) |
| `aquariusminer area unlimited` | Infinite outward spiral (no bound) |
| `aquariusminer area chunks <w> <l>` | Finite W×L chunk box from the start chunk |
| `aquariusminer area anchor center/corner` | Box centred on you, or grown from a corner toward your facing |
| `aquariusminer area corners <x1> <z1> <x2> <z2>` | Finite box between two X/Z coords (Y = the band) |
| `aquariusminer keep add/remove <item>` / `list` / `clear` / `reset` | Edit the kept-block list at runtime (mine other ores/blocks); matches **item** names |
| `aquariusminer legit on` / `off` | Break only blocks in line of sight (no reaching through walls); off = faster batch engine |
| `aquariusminer cave on` / `off` | Relax pathfinder fall/jump limits to mine through caves |
| `aquariusminer clearbox <size>` | Drop-collection sub-box size (smaller = more thorough, slower) |
| `aquariusminer layer <blocks>` | Top-down layer thickness for a bounded area (1 = peel one level across the whole area) |
| `aquariusminer collect on/off` / `seconds <n>` | Vacuum dropped keep-items after each sub-box; per-box time cap |
| `aquariusminer verify on/off` / `retries <n>` | Re-run a sub-box that still has blocks after a clear/stall (lag gaps) |
| `aquariusminer shovel on/off` | Also keep a fresh shovel stocked (alongside the main tool) for gravel/sand |
| `aquariusminer restock on/off` | Restock a fresh tool from a tool-shulker in the ender chest |
| `aquariusminer food on/off` / `count <n>` / `min <n>` | Restock food from a food-shulker in the ender chest when carried food runs low |
| `aquariusminer scan` | Re-print the last pre-mine resource scan (top blocks + ores) |
| `aquariusminer deposit on` / `off` | Haul filled shulkers to base chests instead of leaving them behind |
| `aquariusminer deposit chest add <x> <y> <z>` / `clear` | DEPOSIT chest(s) — where FILLED shulkers go |
| `aquariusminer deposit supply add <x> <y> <z>` / `clear` | SUPPLY chest(s) — where EMPTY shulkers come from |
| `aquariusminer deposit refill on/off` / `empties <n>` / `maxdist <b>` | Empty refills (capped to echest room), distance cap |
| `aquariusminer` | Show status (state, Y band, area, cave, collection, deposit, current chunk) |

Position the bot inside (or above) the target layer, set the `minY`/`maxY` band (default `-59 .. -50`,
just above bedrock in the 1.21 deepslate layer), then `/aquariusminer on` (or `.aqm on`).

## Configuration

Stored as JSON under ZenithProxy's plugin config dir (key `aquarius-miner`). Fields under `miner`:

- `enabled`, `minY`, `maxY`, `delayTicks`
- `areaMode` (`Unlimited` / `ChunksFromStart` / `Corners`), `areaAnchor` (`Center` / `Corner`),
  `areaWidthChunks`, `areaLengthChunks`, `corner1X/Z`, `corner2X/Z`
- `caveHandling`, `maxFallHeight`, `allowParkourPlace`, `allowDiagonalDescend`, `allowParkour`,
  `allowDiagonalAscend` (pushed to the pathfinder while active, restored on disable)
- `dropJunk`, `junkDropDelayTicks`, `junkItems` (denylist of item names to drop)
- `keepItems` (items to keep / deposit during the storage cycle)
- `storageEnabled`, `breakAndCollect`, `storageItems`, `freeSlotsBeforeFull`, `maxClearTicks`
- `clearBoxSize` (drop-collection sub-box size; 16 = whole chunk at once)
- `depositToChests`, `depositChests` / `supplyChests` (`"x y z"` lists),
  `refillEmpties`, `emptiesPerTrip`, `maxDepositDistance`
- `legitMine` (break only line-of-sight blocks), `verifyClears` / `clearRetries`
- `restockTools` / `restockToolKeyword` / `restockBelowDurability` / `alsoRestockShovel`,
  `restockFood` / `minFoodOnHand` / `foodRestockCount`

## Status / roadmap

- [x] Per-chunk quarry via stock `clearArea`
- [x] Outward chunk spiral
- [x] Area limit — `ChunksFromStart` (W×L + Center/Corner anchor) or `Corners` (coord box); stores the
      remainder and completes when the area is cleared
- [x] Cave handling — relax pathfinder fall/jump limits while active, restore on disable
- [x] Junk dropping (safe denylist)
- [x] Inventory-full detection
- [x] Storage cycle — place/open shulker (or ender chest), deposit `keepItems`, close, resume
      (optional break & collect)
- [x] Full-stacks storage trigger + risky-food filtering
- [x] Auto-disconnect on run end (area cleared / storage exhausted)
- [x] Hazard pause — soft-pause while a non-self player is within range (auto-resumes)
- [x] Drop collection — clear each chunk in `clearBoxSize` sub-boxes so the bot treads back over and
      picks up its drops instead of leaving them to despawn
- [x] Deposit chests — the ender chest is the field buffer (empties out, filled back in); a trip walks to
      the nearest deposit chest first with a clean inventory, extracts the filled shulkers there, and
      refills empties (capped to the echest's free space) straight back into the echest for a near-
      unlimited run; nearest-with-next-chest fallback, chest locations set by command (`pathTo` a
      `GoalNear`, headless-friendly)
- [x] Tool restock — full cycle: place the ender chest, pull out the tool-shulker, place it, take a
      fresh tool, break & recover it, put it back in the chest, recover the chest. The tool-shulker
      (a shulker holding spare pickaxes) must be a UNIQUE colour vs any empty loot shulkers, since the
      bot places shulkers by item type. Enable with `restock on` + `restockSourceItem` = `ender_chest`.
- [ ] Verify end-to-end in a live ZenithProxy instance (`placeBlock` is marked experimental upstream)
- [ ] Full-shulker detection so `breakAndCollect` can swap in a fresh empty shulker each cycle
