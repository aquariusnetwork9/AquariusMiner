package com.shallowplague.aquariusminer.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.food.FoodData;
import com.zenith.mc.food.FoodRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.util.math.MathHelper;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INVENTORY;
import static com.shallowplague.aquariusminer.AquariusMinerPlugin.PLUGIN_CONFIG;
import com.shallowplague.aquariusminer.AquariusMinerConfig.AreaAnchor;
import com.shallowplague.aquariusminer.AquariusMinerConfig.AreaMode;

/**
 * The mining brain. Drives stock ZenithProxy's {@code BARITONE.clearArea} to quarry one chunk at a
 * time, walking an outward square spiral of chunks starting from wherever the bot is when enabled.
 * When the inventory fills it runs a storage cycle: places a container (shulker box) beside the bot,
 * deposits only the kept items, then resumes mining.
 *
 * {@code clearArea} is itself a box-confined, bottom-up, nearest-first quarry (see
 * {@code ClearAreaProcess}), so it inherently avoids the wandering / whole-world rescans that the
 * type-targeted {@code mine()} process suffers from. Both the mining drive and the storage cycle are
 * polling state machines run from the client tick thread, so no cross-thread state is shared with
 * Baritone's pathing executor.
 */
public class AquariusMinerModule extends Module {
    private static final int ACTION_PRIORITY = 3000;

    private final Timer mineTimer = Timers.tickTimer();
    private final Timer junkTimer = Timers.tickTimer();
    private final Timer depositTimer = Timers.tickTimer();

    // outward-spiral state, expressed as a chunk offset from the spiral-centre chunk
    private int startCX, startCZ;
    private int sx, sz, sdx, sdz, segLen, segRemaining, segsDone;
    private int curCX, curCZ;

    // bounded-area state (resolved at enable / reconnect from the configured area mode). When
    // areaLimited is false the spiral is infinite and the grid/bounds fields are unused.
    private boolean areaLimited = false;
    private int areaMinX, areaMaxX, areaMinZ, areaMaxZ;     // block coords inclusive (X/Z); Y = minY..maxY
    private int gridCxMin, gridCxMax, gridCzMin, gridCzMax; // chunk grid covering the box
    private int areaChunksTotal, areaChunksDone;            // progress (per horizontal layer) + completion detection
    private int curLayerTopY = 0;                          // bounded area: top Y of the layer being swept (descends top-down)
    private boolean finishAfterStore = false;              // area cleared -> store remainder -> complete

    // cave-handling snapshot of CONFIG.client.extra.pathfinder.* (restored on disable)
    private boolean cavePushed = false;
    private int savedMaxFall;
    private boolean savedParkour, savedParkourPlace, savedDiagDescend, savedDiagAscend;

    // mining state machine
    private boolean areaActive = false; // we've asked clearArea to run for (curCX,curCZ)
    private boolean sawActive = false;  // we've observed Baritone pick the clear up (1-tick lag guard)
    private int clearTicks = 0;         // ticks the current clear has been running

    // sub-box subdivision: clear each chunk in clearBoxSize cells so the bot repositions and walks back
    // over (and collects) its drops. subBoxes holds the {x1,z1,x2,z2} cells of the current chunk.
    private final java.util.List<int[]> subBoxes = new java.util.ArrayList<>();
    private int subBoxIdx = 0;
    private int subBoxRetries = 0;      // re-runs of the current sub-box after a verify found leftovers (1.3.2 port)
    private boolean paused = false;     // hard pause (inventory full + can't store) -> needs toggle
    private boolean hazardPaused = false; // soft pause (player nearby) -> auto-resumes when clear
    private boolean complete = false;   // the run finished (finite area fully cleared)

    // vacuum pass: after a sub-box clears, walk over its dropped keep-items before moving on
    private boolean collecting = false;
    private int collectTargetId = -1;                 // entity id of the drop we're walking to (-1 = none)
    private int collectTargetTicks = 0;               // ticks spent reaching the current drop (give-up watch)
    private int collectTotalTicks = 0;                // ticks spent on this whole sub-box's vacuum (overall cap)
    private final java.util.Set<Integer> collectSkip = new java.util.HashSet<>(); // drops we gave up reaching
    private static final int COLLECT_ITEM_TICKS = 20 * 8; // 8s to reach one drop, else skip it

    // restock: tool keyword latched at the start of a cycle (primary, or "shovel" when also-restock-shovel)
    private String restockKeyword = "pickaxe";

    // resource scan (pre-mine): the last scan's formatted lines, replayed by '/aquariusminer scan'
    private java.util.List<String> scanLines = new java.util.ArrayList<>();

    // storage sub state machine
    private enum StorePhase { FIND_SPOT, PLACE, OPEN, DEPOSIT, CLOSE, BREAK, PICKUP, RESUME }
    private boolean storing = false;
    private StorePhase storePhase = StorePhase.FIND_SPOT;
    private int storeStepTicks = 0;
    private int storeStartEmpty = 0;       // empty slots when this store cycle began
    private @Nullable BlockPos storePos = null;
    private @Nullable ItemData storeItem = null;
    private int lastKeepTotal = -1;        // deposit progress tracker
    private int depositStall = 0;

    // restock sub state machine (pull a tool-shulker out of the ender chest, take a tool, put it back)
    private enum RestockPhase {
        PLACE_ECHEST, OPEN_ECHEST, TAKE_SHULKER, CLOSE_ECHEST,
        PLACE_SHULKER, OPEN_SHULKER, TAKE_TOOL, CLOSE_SHULKER, BREAK_SHULKER, PICKUP_SHULKER,
        REOPEN_ECHEST, RETURN_SHULKER, CLOSE_ECHEST2, BREAK_ECHEST, PICKUP_ECHEST, DONE
    }
    private boolean restocking = false;
    private RestockPhase restockPhase = RestockPhase.PLACE_ECHEST;
    private int restockStepTicks = 0;
    private @Nullable BlockPos restockEchestPos = null;
    private @Nullable BlockPos restockShulkerPos = null;
    private @Nullable ItemData restockEchestItem = null;
    private @Nullable ItemData restockShulkerItem = null;

    // food restock sub state machine (crack a FOOD-shulker from the ender chest and top up carried food).
    // Mirrors the tool restock cycle but pulls food by preference; BEST-EFFORT (a missing food-shulker never
    // pauses the run - it latches foodRestockExhausted and keeps mining).
    private enum FoodPhase {
        PLACE_ECHEST, OPEN_ECHEST, TAKE_SHULKER, CLOSE_ECHEST,
        PLACE_SHULKER, OPEN_SHULKER, TAKE_FOOD, CLOSE_SHULKER, BREAK_SHULKER, PICKUP_SHULKER,
        REOPEN_ECHEST, RETURN_SHULKER, CLOSE_ECHEST2, BREAK_ECHEST, PICKUP_ECHEST, DONE
    }
    private boolean foodRestocking = false;
    private FoodPhase foodPhase = FoodPhase.PLACE_ECHEST;
    private int foodStepTicks = 0;
    private boolean foodRestockExhausted = false;   // no food-shulker in the echest -> stop retrying this run
    private int foodTakeStall = 0;                   // food-take stall detector (shulker empty / no room)
    private int lastFoodCount = -1;
    private @Nullable BlockPos foodEchestPos = null;
    private @Nullable BlockPos foodShulkerPos = null;
    private @Nullable ItemData foodEchestItem = null;
    private @Nullable ItemData foodShulkerItem = null;

    // echest-buffer storage cycle (deposit mode): the ender chest is the FIELD buffer. Pull an empty
    // shulker out of it, fill it with the haul, store the FILLED shulker back in - so filled shulkers
    // never clog the mining inventory. A deposit trip fires only once the echest runs out of empties.
    private enum EchestPhase {
        PLACE_ECHEST, OPEN_ECHEST, STOCK_EMPTIES, TAKE_EMPTY, CLOSE_ECHEST,
        PLACE_SHULKER, OPEN_SHULKER, FILL_SHULKER, CLOSE_SHULKER, BREAK_SHULKER, PICKUP_SHULKER,
        REOPEN_ECHEST, STORE_FILLED, CLOSE_ECHEST2, BREAK_ECHEST, PICKUP_ECHEST, DONE
    }
    private boolean echestCycle = false;
    private EchestPhase echestPhase = EchestPhase.PLACE_ECHEST;
    private int echestStepTicks = 0;
    private @Nullable BlockPos echPos = null;       // ender chest placed this cycle
    private @Nullable BlockPos shulkPos = null;     // shulker placed this cycle
    private @Nullable ItemData echItem = null;      // the ender chest item type
    private @Nullable ItemData shulkItem = null;    // the empty shulker item type pulled from the echest
    private boolean echestExhausted = false;        // the echest has no empty shulkers left -> trip / end run
    private boolean finishAfterEchest = false;      // bounded-area final pack into the echest -> complete the run
    private int echFillStall = 0;                   // shulker-full / echest-full stall detector
    private int lastEchCount = -1;

    // deposit trips: the ender chest is the field buffer; a trip walks to the nearest DEPOSIT chest FIRST
    // with a clean inventory (filled shulkers stay in the global echest until the bot is there), EXTRACTs
    // them at the chest, drops them in, then refills empties from a separate SUPPLY chest - only as many as
    // fit in the echest - and STOCKs them straight back into the echest. Pathing via BARITONE.pathTo(GoalNear).
    private enum DepositPhase {
        PATH_TO_DEPOSIT,
        PULL_PLACE, PULL_OPEN, PULL_FILLED, PULL_CLOSE, PULL_BREAK, PULL_PICKUP,
        OPEN_DEPOSIT, DEPOSIT_FILLED, CLOSE_DEPOSIT,
        PATH_TO_SUPPLY, OPEN_SUPPLY, TAKE_EMPTIES, CLOSE_SUPPLY,
        STOCK_PLACE, STOCK_OPEN, STOCK_EMPTIES, STOCK_CLOSE, STOCK_BREAK, STOCK_PICKUP,
        DONE
    }
    private boolean depositing = false;
    private DepositPhase depositPhase = DepositPhase.PATH_TO_DEPOSIT;
    private @Nullable BlockPos depositChest = null;
    private @Nullable GoalNear depositGoal = null;
    private int depositTripTicks = 0;
    private boolean nextDepositLeg = false;      // after CLOSE_DEPOSIT, re-travel to another deposit chest (full)
    private boolean nextSupplyLeg = false;       // after CLOSE_SUPPLY, re-travel to another supply chest (empty)
    private boolean finishAfterDeposit = false;  // bounded-area final drop-off -> complete the run after
    private boolean tripExtracted = false;       // filled shulkers have been pulled out of the echest this trip
    private boolean tripRefilled = false;        // at least one empty was stocked back into the echest this trip
    private int echestFreeAfterExtract = 0;      // echest free slots after the extract = empties that may refill
    private @Nullable BlockPos tripEchPos = null; // ender chest placed at a base chest (extract / stock)
    private @Nullable ItemData tripEchItem = null;
    private @Nullable String pendingDepositPause = null; // pause with this message once the container closes
    private final java.util.List<BlockPos> triedChests = new java.util.ArrayList<>();
    private int depositTripStall = 0;            // deposit/take stall (chest full / empty) detection
    private int lastTripCount = -1;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.miner.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, this::onStarting)
        );
    }

    @Override
    public void onEnable() {
        pushCaveSettings();
        resetToStart();
    }

    @Override
    public void onDisable() {
        if (BARITONE.isActive()) BARITONE.stop();
        areaActive = false;
        sawActive = false;
        storing = false;
        echestCycle = false;
        echestExhausted = false;
        restocking = false;
        foodRestocking = false;
        foodRestockExhausted = false;
        depositing = false;
        collecting = false;
        finishAfterDeposit = false;
        finishAfterEchest = false;
        paused = false;
        hazardPaused = false;
        complete = false;
        finishAfterStore = false;
        restoreCaveSettings();
    }

    // ---- cave handling: relax the pathfinder's fall/jump limits while active, restore on disable ----

    private void pushCaveSettings() {
        var cfg = PLUGIN_CONFIG.miner;
        if (!cfg.caveHandling || cavePushed) return;
        var pf = CONFIG.client.extra.pathfinder;
        savedMaxFall = pf.maxFallHeightNoWater;
        savedParkour = pf.allowParkour;
        savedParkourPlace = pf.allowParkourPlace;
        savedDiagDescend = pf.allowDiagonalDescend;
        savedDiagAscend = pf.allowDiagonalAscend;
        pf.maxFallHeightNoWater = cfg.maxFallHeight;
        pf.allowParkour = cfg.allowParkour;
        pf.allowParkourPlace = cfg.allowParkourPlace;
        pf.allowDiagonalDescend = cfg.allowDiagonalDescend;
        pf.allowDiagonalAscend = cfg.allowDiagonalAscend;
        cavePushed = true;
        info("Cave handling on: maxFall={} parkourPlace={} diagDescend={}",
            cfg.maxFallHeight, cfg.allowParkourPlace, cfg.allowDiagonalDescend);
    }

    private void restoreCaveSettings() {
        if (!cavePushed) return;
        var pf = CONFIG.client.extra.pathfinder;
        pf.maxFallHeightNoWater = savedMaxFall;
        pf.allowParkour = savedParkour;
        pf.allowParkourPlace = savedParkourPlace;
        pf.allowDiagonalDescend = savedDiagDescend;
        pf.allowDiagonalAscend = savedDiagAscend;
        cavePushed = false;
    }

    private void onStarting(ClientBotTick.Starting event) {
        // bot (re)connected / world (re)loaded: re-anchor the spiral to the current position
        resetToStart();
    }

    private void resetToStart() {
        int px = MathHelper.floorI(CACHE.getPlayerCache().getX());
        int pz = MathHelper.floorI(CACHE.getPlayerCache().getZ());
        resolveArea(px >> 4, pz >> 4);
        seedSpiral();
        areaActive = false; sawActive = false; clearTicks = 0; subBoxRetries = 0;
        storing = false; restocking = false; storePos = null; storeItem = null;
        foodRestocking = false; foodRestockExhausted = false; foodEchestPos = null; foodShulkerPos = null;
        echestCycle = false; echestExhausted = false; finishAfterEchest = false; echPos = null; shulkPos = null;
        depositing = false; finishAfterDeposit = false; tripExtracted = false; tripRefilled = false; triedChests.clear();
        collecting = false; collectTargetId = -1; collectSkip.clear();
        restockKeyword = PLUGIN_CONFIG.miner.restockToolKeyword;
        paused = false; hazardPaused = false; complete = false; finishAfterStore = false;
        var cfg = PLUGIN_CONFIG.miner;
        if (areaLimited) {
            info("Starting quarry: {} chunks, X[{}..{}] Z[{}..{}], Y {}..{}",
                areaChunksTotal, areaMinX, areaMaxX, areaMinZ, areaMaxZ, cfg.minY, cfg.maxY);
        } else {
            info("Starting quarry at chunk [{}, {}] (unlimited), Y {}..{}",
                startCX, startCZ, cfg.minY, cfg.maxY);
        }
        runChunkScan();   // one-time pre-mine resource scan (logged + replayable via /aquariusminer scan)
    }

    /** Work out the chunk grid + spiral-centre chunk for this run from the configured area mode. */
    private void resolveArea(int startChunkCX, int startChunkCZ) {
        var cfg = PLUGIN_CONFIG.miner;
        if (cfg.areaMode == AreaMode.ChunksFromStart) {
            areaLimited = true;
            int w = Math.max(1, cfg.areaWidthChunks);
            int l = Math.max(1, cfg.areaLengthChunks);
            if (cfg.areaAnchor == AreaAnchor.Corner) {
                // start chunk is a corner; extend the box in the bot's horizontal facing
                int[] dir = facingExtend();
                if (dir[0] >= 0) { gridCxMin = startChunkCX; gridCxMax = startChunkCX + w - 1; }
                else             { gridCxMax = startChunkCX; gridCxMin = startChunkCX - (w - 1); }
                if (dir[1] >= 0) { gridCzMin = startChunkCZ; gridCzMax = startChunkCZ + l - 1; }
                else             { gridCzMax = startChunkCZ; gridCzMin = startChunkCZ - (l - 1); }
            } else { // Center
                gridCxMin = startChunkCX - (w - 1) / 2; gridCxMax = gridCxMin + w - 1;
                gridCzMin = startChunkCZ - (l - 1) / 2; gridCzMax = gridCzMin + l - 1;
            }
            areaMinX = gridCxMin << 4; areaMaxX = (gridCxMax << 4) + 15;
            areaMinZ = gridCzMin << 4; areaMaxZ = (gridCzMax << 4) + 15;
            startCX = (gridCxMin + gridCxMax) >> 1;
            startCZ = (gridCzMin + gridCzMax) >> 1;
        } else if (cfg.areaMode == AreaMode.Corners) {
            areaLimited = true;
            areaMinX = Math.min(cfg.corner1X, cfg.corner2X);
            areaMaxX = Math.max(cfg.corner1X, cfg.corner2X);
            areaMinZ = Math.min(cfg.corner1Z, cfg.corner2Z);
            areaMaxZ = Math.max(cfg.corner1Z, cfg.corner2Z);
            gridCxMin = areaMinX >> 4; gridCxMax = areaMaxX >> 4;
            gridCzMin = areaMinZ >> 4; gridCzMax = areaMaxZ >> 4;
            startCX = (gridCxMin + gridCxMax) >> 1;
            startCZ = (gridCzMin + gridCzMax) >> 1;
        } else { // Unlimited
            areaLimited = false;
            startCX = startChunkCX; startCZ = startChunkCZ;
        }
    }

    /** Reset the outward-square spiral stepper to the centre and recompute the area chunk count. */
    /** Reset just the spiral cursor (used at run start AND at the start of each new horizontal layer). */
    private void resetSpiralStepper() {
        sx = 0; sz = 0; sdx = 1; sdz = 0;
        segLen = 1; segRemaining = 1; segsDone = 0;
        curCX = startCX; curCZ = startCZ;
        subBoxes.clear(); subBoxIdx = 0;
        areaChunksDone = 0;
    }

    private void seedSpiral() {
        resetSpiralStepper();
        curLayerTopY = PLUGIN_CONFIG.miner.maxY;   // bounded: sweep from the top layer downward
        areaChunksTotal = areaLimited
            ? (gridCxMax - gridCxMin + 1) * (gridCzMax - gridCzMin + 1)
            : 0;
    }

    /** Vertical thickness of each top-down horizontal layer (bounded areas), clamped to >= 1. */
    private int layerThickness() { return Math.max(1, PLUGIN_CONFIG.miner.layerHeight); }

    /** Y floor of the layer currently being swept (bounded), clamped to the area floor (minY). */
    private int curLayerBottomY() { return Math.max(PLUGIN_CONFIG.miner.minY, curLayerTopY - layerThickness() + 1); }

    // ---------------------------------------------------------------- resource scan (pre-mine)

    /**
     * Scan the mining area ONCE before mining and log the most-abundant blocks/ores. Headless, so there's
     * no HUD — the result is logged and shown via in-game alert, and replayable with {@code /aquariusminer
     * scan}. Footprint is EXACTLY the area to be mined: the bounded box, else the start chunk for the
     * infinite spiral. Y span is the mining band (never above maxY). Only loaded chunks are read; a block
     * budget caps the one-time work.
     */
    private void runChunkScan() {
        var cfg = PLUGIN_CONFIG.miner;
        int topY = cfg.maxY, botY = cfg.minY;
        int x1, z1, x2, z2;
        if (areaLimited) { x1 = areaMinX; z1 = areaMinZ; x2 = areaMaxX; z2 = areaMaxZ; }
        else { int bx = startCX << 4, bz = startCZ << 4; x1 = bx; z1 = bz; x2 = bx + 15; z2 = bz + 15; }

        java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        int chunks = 0;
        long budget = 0;
        final long BUDGET_MAX = 16_000_000L;
        for (int cx = (x1 >> 4); cx <= (x2 >> 4); cx++) {
            for (int cz = (z1 >> 4); cz <= (z2 >> 4); cz++) {
                if (!World.isChunkLoadedChunkPos(cx, cz)) continue; // can't read ungenerated chunks
                chunks++;
                int bxMin = Math.max(x1, cx << 4), bxMax = Math.min(x2, (cx << 4) + 15);
                int bzMin = Math.max(z1, cz << 4), bzMax = Math.min(z2, (cz << 4) + 15);
                for (int bx = bxMin; bx <= bxMax; bx++)
                    for (int bz = bzMin; bz <= bzMax; bz++)
                        for (int by = botY; by <= topY; by++) {
                            if (++budget > BUDGET_MAX) { buildScanLines(counts, chunks, botY, topY); return; }
                            var block = World.getBlock(bx, by, bz);
                            if (block.isAir()) continue;            // ignore air (dominates a quarry)
                            counts.merge(block.name(), 1, Integer::sum); // water/lava count like any block
                        }
            }
        }
        buildScanLines(counts, chunks, botY, topY);
    }

    /** Rank the tally into the top 3 blocks + top 5 ores, store the lines, and print them. */
    private void buildScanLines(java.util.Map<String, Integer> counts, int chunks, int botY, int topY) {
        java.util.List<java.util.Map.Entry<String, Integer>> blocks = new java.util.ArrayList<>();
        java.util.List<java.util.Map.Entry<String, Integer>> ores = new java.util.ArrayList<>();
        for (var e : counts.entrySet()) (isOreName(e.getKey()) ? ores : blocks).add(e);
        java.util.Comparator<java.util.Map.Entry<String, Integer>> byDesc = (a, b) -> Integer.compare(b.getValue(), a.getValue());
        blocks.sort(byDesc);
        ores.sort(byDesc);

        scanLines = new java.util.ArrayList<>();
        scanLines.add(String.format("Resource scan: %d chunk%s, y%d..%d", chunks, chunks == 1 ? "" : "s", botY, topY));
        scanLines.add("Top blocks:");
        if (blocks.isEmpty()) scanLines.add("  (none)");
        else for (int i = 0; i < Math.min(3, blocks.size()); i++)
            scanLines.add("  " + blocks.get(i).getKey() + "  " + fmtCount(blocks.get(i).getValue()));
        scanLines.add("Top ores:");
        if (ores.isEmpty()) scanLines.add("  (none)");
        else for (int i = 0; i < Math.min(5, ores.size()); i++)
            scanLines.add("  " + ores.get(i).getKey() + "  " + fmtCount(ores.get(i).getValue()));
        printScan();
    }

    /** Log the last scan and flash it as an in-game alert. Called after a scan and by the 'scan' command. */
    public void printScan() {
        if (scanLines.isEmpty()) { info("No scan yet - enable the miner to scan the area."); return; }
        for (String l : scanLines) info(l);
        inGameAlertActivePlayer("<aqua>" + String.join("  |  ", scanLines));
    }

    private boolean isOreName(String n) { return n.contains("_ore") || n.equals("ancient_debris"); }

    /** Counts run into the 100k+ for deepslate; cap the displayed number to 6 digits. */
    private String fmtCount(int n) { return n > 999999 ? "999999+" : Integer.toString(n); }

    /** Sign (+1/-1) of the bot's horizontal facing per axis {x, z} (yaw only). East=+X, South=+Z. */
    private int[] facingExtend() {
        double yaw = Math.toRadians(CACHE.getPlayerCache().getYaw());
        double lx = -Math.sin(yaw);
        double lz = Math.cos(yaw);
        return new int[] { lx >= 0 ? 1 : -1, lz >= 0 ? 1 : -1 };
    }

    private boolean chunkInGrid(int cx, int cz) {
        return cx >= gridCxMin && cx <= gridCxMax && cz >= gridCzMin && cz <= gridCzMax;
    }

    private void onTick(ClientBotTick event) {
        var cfg = PLUGIN_CONFIG.miner;
        if (!CACHE.getPlayerCache().isAlive()) return;
        if (complete || paused) return;

        // storage / echest / restock / deposit cycles take over the bot entirely while they run
        if (storing) {
            storeTick();
            return;
        }
        if (echestCycle) {
            echestTick();
            return;
        }
        if (restocking) {
            restockTick();
            return;
        }
        if (foodRestocking) {
            foodTick();
            return;
        }
        if (depositing) {
            depositTripTick();
            return;
        }
        if (collecting) {
            collectTick();
            return;
        }

        // hazard: soft-pause mining while a non-self player is within range (auto-resumes when clear)
        if (cfg.pauseOnPlayer && playerNearby(cfg.playerPauseRange)) {
            if (!hazardPaused) {
                hazardPaused = true;
                if (BARITONE.isActive()) BARITONE.stop();
                areaActive = false; sawActive = false;
                warn("Player within {} blocks - pausing mining.", (int) cfg.playerPauseRange);
                inGameAlertActivePlayer("<yellow>Aquarius Miner paused: player nearby");
            }
            return;
        }
        if (hazardPaused) {
            hazardPaused = false;
            info("Clear - resuming mining.");
        }

        // 1) drop junk so the inventory fills with keep-items only
        if (cfg.dropJunk && junkTimer.tick(cfg.junkDropDelayTicks)) dropOneJunk();

        // 2) inventory full -> store, or pause if we can't. Only trigger when there's something to
        //    store (keep items present); a full-of-junk inventory self-resolves as junk is dropped.
        boolean invFull = cfg.requireFullStacks
            ? !canHoldMoreKeep()                                // every keep stack at max, no empty slot
            : emptyMainSlots() <= cfg.freeSlotsBeforeFull;      // looser empty-slot margin
        if (invFull && hasKeepItems()) {
            // Mirror Foreman: the ENDER CHEST is the field buffer whenever the bot carries one - pull an
            // empty shulker out, fill it, and store the FILLED shulker back IN. Filled shulkers are never
            // left on the ground or carried in the mining inventory. Deposit chests just add base trips on
            // top. Only with no ender chest do we fall back to placing a shulker beside the bot.
            if (hasEnderChest()) {
                beginEchestCycle();
            } else if (cfg.depositToChests) {
                pauseInvFull("no ender chest (the deposit buffer)");
            } else if (cfg.storageEnabled && hasStorageItem()) {
                beginStore();
            } else {
                pauseInvFull(!cfg.storageEnabled ? "storage disabled" : "no ender chest or storage item");
            }
            return;
        }

        // 2.5) tool spent -> restock a fresh one from the tool-shulker in the ender chest
        if (cfg.restockTools && toolNeedsRestock() && hasRestockSource()) {
            beginRestock();
            return;
        }

        // 2.6) carried food low -> top up from a FOOD-shulker in the ender chest (best-effort, never pauses)
        if (cfg.restockFood && !foodRestockExhausted && countGoodFood() < cfg.minFoodOnHand && hasRestockSource()) {
            beginFoodRestock();
            return;
        }

        // 3) mining drive
        if (areaActive) {
            if (!sawActive) {
                if (BARITONE.isActive()) {
                    sawActive = true; // clear was picked up by the pathing control manager
                } else if (++clearTicks > 40) {
                    areaActive = false; // never started; fall through to retry (paced)
                }
                return;
            }
            if (BARITONE.isActive()) {
                if (++clearTicks > cfg.maxClearTicks) {
                    warn("Chunk [{}, {}] sub-box {}/{} timed out after {} ticks.",
                        curCX, curCZ, subBoxIdx + 1, subBoxes.size(), clearTicks);
                    BARITONE.stop();
                    if (retrySubBoxIfDirty(true)) return;   // lag left blocks -> re-run the box first
                    afterSubBox();
                }
                return;
            }
            // this sub-box completed naturally (box is clear) -> verify, then vacuum its drops, then advance
            if (retrySubBoxIfDirty(false)) return;
            beginCollect();
            return;
        }

        // not clearing right now: start the next chunk (paced)
        if (!mineTimer.tick(cfg.delayTicks)) return;
        startClear(curCX, curCZ);
    }

    // ---------------------------------------------------------------- mining

    /** A sub-box finished (or timed out): start the next sub-box, or finish the chunk if it was the last. */
    private void afterSubBox() {
        subBoxRetries = 0;   // we're leaving this cell; the next one starts with a fresh retry budget
        if (subBoxIdx + 1 < subBoxes.size()) {
            subBoxIdx++;
            issueSubBox();
        } else {
            finishChunkAndAdvance();
        }
    }

    // ----- COLLECT: vacuum the just-cleared sub-box's drops before moving on -----

    /** Sub-box cleared: if there's a kept drop on the floor (and room to hold it), vacuum it; else advance. */
    private void beginCollect() {
        var cfg = PLUGIN_CONFIG.miner;
        if (!cfg.collectDrops || !canHoldMoreKeep() || nearestDrop() == null) { afterSubBox(); return; }
        collecting = true;
        collectTargetId = -1;
        collectTargetTicks = 0;
        collectTotalTicks = 0;
        collectSkip.clear();
        areaActive = false; sawActive = false;   // the builder is done with this box
        info("Vacuuming drops in sub-box {}/{}.", subBoxIdx + 1, subBoxes.size());
    }

    private void collectTick() {
        var cfg = PLUGIN_CONFIG.miner;
        if (!canHoldMoreKeep()) { finishCollect(); return; }                    // no room -> storage fires next
        if (++collectTotalTicks > cfg.collectMaxSeconds * 20) { finishCollect(); return; } // overall cap

        if (collectTargetId != -1) {
            Entity e = CACHE.getEntityCache().getEntities().get(collectTargetId);
            if (e != null && !e.isRemoved() && e.getEntityType() == EntityType.ITEM) {
                if (++collectTargetTicks > COLLECT_ITEM_TICKS) { collectSkip.add(collectTargetId); collectTargetId = -1; }
                else return;                                                    // keep heading to it (goal set)
            } else collectTargetId = -1;                                        // picked up / gone
        }

        Entity next = nearestDrop();
        if (next == null) { finishCollect(); return; }                          // all drops grabbed
        collectTargetId = next.getEntityId();
        collectTargetTicks = 0;
        BlockPos p = next.blockPos();
        BARITONE.pathTo(new GoalNear(p.x(), p.y(), p.z(), 2));                  // within ~1.4 blocks -> vanilla pickup
    }

    /** Nearest kept-item drop still on the floor in (or just around) the sub-box we're vacuuming. */
    private @Nullable Entity nearestDrop() {
        if (subBoxes.isEmpty()) return null;
        int[] sb = subBoxes.get(subBoxIdx);
        int x1 = sb[0] - 2, x2 = sb[2] + 2, z1 = sb[1] - 2, z2 = sb[3] + 2;
        int yLo = (areaLimited ? curLayerBottomY() : PLUGIN_CONFIG.miner.minY) - 2;
        int yHi = (areaLimited ? curLayerTopY : PLUGIN_CONFIG.miner.maxY) + 2;
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.isRemoved() || e.getEntityType() != EntityType.ITEM) continue;
            if (collectSkip.contains(e.getEntityId())) continue;
            BlockPos p = e.blockPos();
            if (p.x() < x1 || p.x() > x2 || p.z() < z1 || p.z() > z2 || p.y() < yLo || p.y() > yHi) continue;
            if (!isKeepDrop(e)) continue;
            double d = CACHE.getPlayerCache().distanceSqToSelf(e);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** True if the dropped item entity holds one of our keep-items. */
    private boolean isKeepDrop(Entity e) {
        ItemStack stack = e.getMetadataValue(8, MetadataTypes.ITEM, ItemStack.class);
        return stack != null && isKeep(stack);
    }

    /** Done vacuuming this sub-box: stop walking and advance the quarry. */
    private void finishCollect() {
        if (BARITONE.isActive()) BARITONE.stop();
        collecting = false;
        collectTargetId = -1;
        afterSubBox();
    }

    private void finishChunkAndAdvance() {
        areaActive = false;
        sawActive = false;
        clearTicks = 0;
        info("Chunk [{}, {}] cleared.", curCX, curCZ);
        // bounded area: count this chunk toward the CURRENT LAYER. Once every chunk in the layer is done,
        // drop one layer and re-sweep the whole area; finish the run when the layer reaches the floor.
        if (areaLimited && ++areaChunksDone >= areaChunksTotal) {
            if (curLayerBottomY() <= PLUGIN_CONFIG.miner.minY) {
                onAreaComplete();
                return;
            }
            curLayerTopY -= layerThickness();
            resetSpiralStepper();              // re-sweep every chunk at the new, lower layer
            info("Layer cleared - dropping to y[{}..{}] and sweeping the area.", curLayerBottomY(), curLayerTopY);
            return;                            // next tick starts the new layer's first sub-box (paced)
        }
        advanceSpiral();
    }

    /** Every in-box chunk is cleared: store any remaining haul, then complete the run. */
    private void onAreaComplete() {
        if (BARITONE.isActive()) BARITONE.stop();
        var cfg = PLUGIN_CONFIG.miner;
        // deposit mode: deliver the filled shulkers accumulated in the ender chest, then complete
        if (cfg.depositToChests && hasEnderChest() && !depositChestList().isEmpty()) {
            finishAfterDeposit = true;
            info("Area cleared - delivering the last of the haul from the ender chest.");
            beginDepositTrip();
            return;
        }
        // ender-chest buffer: pack the last partial inventory into the echest before finishing
        if (hasEnderChest() && hasKeepItems()) {
            finishAfterEchest = true;      // resumeFromEchest() will route to completeRun()
            info("Area cleared - packing the last of the haul into the ender chest.");
            beginEchestCycle();
            return;
        }
        if (cfg.storageEnabled && hasStorageItem() && hasKeepItems()) {
            finishAfterStore = true;       // resumeFromStore() will route to completeRun()
            info("Area cleared - storing the last of the haul.");
            beginStore();
            return;
        }
        completeRun();
    }

    private void completeRun() {
        complete = true;
        info("Quarry complete: cleared the {}-chunk area.", areaChunksTotal);
        inGameAlertActivePlayer("<green>Aquarius Miner complete");
        endRun("area cleared");
    }

    private boolean hasKeepItems() {
        return InventoryUtil.searchPlayerInventory(this::isKeep) != -1;
    }

    /** End-of-run hook: optionally disconnect the bot from the server (auto-disconnect). */
    private void endRun(String reason) {
        if (PLUGIN_CONFIG.miner.autoDisconnect) {
            info("Auto-disconnect ({}).", reason);
            Proxy.getInstance().disconnect("Aquarius Miner: " + reason);
        }
    }

    /** True if any non-self player is within {@code range} blocks of the bot. */
    private boolean playerNearby(double range) {
        double r2 = range * range;
        return CACHE.getEntityCache().getEntities().values().stream()
            .anyMatch(e -> e instanceof EntityPlayer p && !p.isSelfPlayer()
                && CACHE.getPlayerCache().distanceSqToSelf(p) <= r2);
    }

    private void startClear(int cx, int cz) {
        int x1 = cx << 4, z1 = cz << 4, x2 = x1 + 15, z2 = z1 + 15;
        if (areaLimited) {
            // clamp the chunk box to the area bounds so edge chunks never mine outside the box
            x1 = Math.max(x1, areaMinX); x2 = Math.min(x2, areaMaxX);
            z1 = Math.max(z1, areaMinZ); z2 = Math.min(z2, areaMaxZ);
        }
        buildSubBoxes(x1, z1, x2, z2);
        subBoxIdx = 0;
        issueSubBox();
    }

    /** Divide the chunk's clamped XZ footprint into clearBoxSize x clearBoxSize cells (row-major). */
    private void buildSubBoxes(int x1, int z1, int x2, int z2) {
        subBoxes.clear();
        subBoxRetries = 0;
        int s = Math.max(1, PLUGIN_CONFIG.miner.clearBoxSize);
        for (int bx = x1; bx <= x2; bx += s) {
            for (int bz = z1; bz <= z2; bz += s) {
                subBoxes.add(new int[]{ bx, bz, Math.min(bx + s - 1, x2), Math.min(bz + s - 1, z2) });
            }
        }
        if (subBoxes.isEmpty()) subBoxes.add(new int[]{ x1, z1, x2, z2 }); // safety (degenerate bounds)
    }

    /**
     * Issue clearArea for the current sub-box. A bounded area mines ONE horizontal layer at a time (the
     * [curLayerBottomY..curLayerTopY] slice) so the whole area's top is swept before descending; the
     * unbounded spiral clears the full Y band per chunk.
     */
    private void issueSubBox() {
        var cfg = PLUGIN_CONFIG.miner;
        int[] sb = subBoxes.get(subBoxIdx);
        int y1 = areaLimited ? curLayerBottomY() : cfg.minY;
        int y2 = areaLimited ? curLayerTopY      : cfg.maxY;
        BlockPos a = new BlockPos(sb[0], y1, sb[1]);
        BlockPos b = new BlockPos(sb[2], y2, sb[3]);
        areaActive = true;
        sawActive = false;
        clearTicks = 0;
        info("Clearing chunk [{}, {}] sub-box {}/{} y[{}..{}] {} -> {}", curCX, curCZ, subBoxIdx + 1, subBoxes.size(), y1, y2, a, b);
        BARITONE.clearArea(a, b);
    }

    // ----- verify & retry: a lag spike can leave blocks standing in a "cleared" sub-box; re-run it -----

    /** If verify is on and the current sub-box still has solid blocks, re-issue it (up to clearRetries). */
    private boolean retrySubBoxIfDirty(boolean stalled) {
        var cfg = PLUGIN_CONFIG.miner;
        if (!cfg.verifyClears || subBoxRetries >= cfg.clearRetries) return false;
        if (!subBoxHasLeftovers()) return false;
        subBoxRetries++;
        warn("Sub-box {}/{} of chunk [{}, {}] still has blocks after {} - retry {}/{}.",
            subBoxIdx + 1, subBoxes.size(), curCX, curCZ, stalled ? "a stall" : "clearing",
            subBoxRetries, cfg.clearRetries);
        issueSubBox();   // re-run the SAME cell (clearArea skips the blocks already gone)
        return true;
    }

    /** True if any breakable solid block remains in the current sub-box's active Y band (cost-capped). */
    private boolean subBoxHasLeftovers() {
        if (subBoxes.isEmpty()) return false;
        if (!World.isChunkLoadedChunkPos(curCX, curCZ)) return false;   // can't read it -> assume clean
        int[] sb = subBoxes.get(subBoxIdx);
        int y1 = areaLimited ? curLayerBottomY() : PLUGIN_CONFIG.miner.minY;
        int y2 = areaLimited ? curLayerTopY      : PLUGIN_CONFIG.miner.maxY;
        BlockPos feet = BARITONE.getPlayerContext().playerFeet();
        long budget = 0;
        final long BUDGET = 8192;
        for (int x = sb[0]; x <= sb[2]; x++)
            for (int z = sb[1]; z <= sb[3]; z++)
                for (int y = y1; y <= y2; y++) {
                    if (++budget > BUDGET) return false;                // too big to verify -> don't loop
                    var b = World.getBlock(x, y, z);
                    if (b.isAir() || World.isFluid(b)) continue;
                    if (b.name().equals("bedrock")) continue;            // unbreakable; clearArea skips it
                    // the bot stands on the band floor: ignore its feet + the block under them
                    if (x == feet.x() && z == feet.z() && (y == feet.y() || y == feet.y() - 1)) continue;
                    return true;
                }
        return false;
    }

    /**
     * Steps the outward square spiral one chunk. When the area is bounded it skips any chunk outside
     * the grid (a guard cap stops a runaway). The caller only advances while in-box chunks remain,
     * so an in-grid chunk is always found.
     */
    private void advanceSpiral() {
        if (areaLimited) {
            int guard = 0;
            do { stepSpiral(); } while (!chunkInGrid(startCX + sx, startCZ + sz) && ++guard < 100000);
        } else {
            stepSpiral();
        }
        curCX = startCX + sx;
        curCZ = startCZ + sz;
    }

    private void stepSpiral() {
        sx += sdx;
        sz += sdz;
        if (--segRemaining == 0) {
            int ndx = -sdz; // rotate direction 90 degrees
            int ndz = sdx;
            sdx = ndx;
            sdz = ndz;
            if (++segsDone % 2 == 0) segLen++;
            segRemaining = segLen;
        }
    }

    // -------------------------------------------------------------- storage

    private void beginStore() {
        if (BARITONE.isActive()) BARITONE.stop();
        areaActive = false;
        sawActive = false;
        storing = true;
        storeStartEmpty = emptyMainSlots();
        storePos = null;
        storeItem = null;
        setStorePhase(StorePhase.FIND_SPOT);
        info("Inventory full - starting storage cycle.");
    }

    private void setStorePhase(StorePhase phase) {
        storePhase = phase;
        storeStepTicks = 0;
        switch (phase) {
            case PLACE -> {
                if (storePos != null && storeItem != null) {
                    BARITONE.placeBlock(storePos.x(), storePos.y(), storePos.z(), storeItem);
                }
            }
            case OPEN -> {
                if (storePos != null) {
                    BARITONE.rightClickBlock(storePos.x(), storePos.y(), storePos.z());
                }
            }
            case DEPOSIT -> {
                lastKeepTotal = -1;
                depositStall = 0;
            }
            case CLOSE -> INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(new CloseContainer())
                .priority(ACTION_PRIORITY)
                .build());
            case BREAK -> {
                if (storePos != null) {
                    BARITONE.breakBlock(storePos.x(), storePos.y(), storePos.z(), true);
                }
            }
            case PICKUP -> BARITONE.pickup();
            default -> { /* FIND_SPOT, RESUME: handled in storeTick */ }
        }
    }

    private void storeTick() {
        var cfg = PLUGIN_CONFIG.miner;
        storeStepTicks++;

        switch (storePhase) {
            case FIND_SPOT -> {
                int slot = InventoryUtil.searchPlayerInventory(this::isStorageItem);
                if (slot == -1) {
                    abortStore("ran out of storage items");
                    return;
                }
                ItemStack stack = CACHE.getPlayerCache().getPlayerInventory().get(slot);
                storeItem = ItemRegistry.REGISTRY.get(stack.getId());
                storePos = selectStorageSpot();
                if (storePos == null || storeItem == null) {
                    abortStore("no valid spot to place a container");
                    return;
                }
                info("Placing {} at {}", storeItem.name(), storePos);
                setStorePhase(StorePhase.PLACE);
            }
            case PLACE -> {
                if (storePos != null && !World.getBlock(storePos.x(), storePos.y(), storePos.z()).isAir()) {
                    info("Container placed; opening.");
                    setStorePhase(StorePhase.OPEN);
                } else if (storeStepTicks > cfg.storeStepTimeoutTicks) {
                    abortStore("place timed out");
                }
            }
            case OPEN -> {
                if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0) {
                    info("Container open; depositing kept items.");
                    setStorePhase(StorePhase.DEPOSIT);
                } else if (storeStepTicks > cfg.storeStepTimeoutTicks) {
                    abortStore("open timed out");
                }
            }
            case DEPOSIT -> depositTick();
            case CLOSE -> {
                if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() == 0) {
                    // (deposit mode uses the echest-buffer cycle instead, never this simple storeTick)
                    setStorePhase(cfg.breakAndCollect ? StorePhase.BREAK : StorePhase.RESUME);
                } else if (storeStepTicks > cfg.storeStepTimeoutTicks) {
                    // resend close once, then give up
                    if (storeStepTicks == cfg.storeStepTimeoutTicks + 1) {
                        INVENTORY.submit(InventoryActionRequest.builder()
                            .owner(this).actions(new CloseContainer()).priority(ACTION_PRIORITY).build());
                    } else if (storeStepTicks > cfg.storeStepTimeoutTicks * 2) {
                        abortStore("close timed out");
                    }
                }
            }
            case BREAK -> {
                if (storePos != null && World.getBlock(storePos.x(), storePos.y(), storePos.z()).isAir()) {
                    setStorePhase(StorePhase.PICKUP);
                } else if (storeStepTicks > cfg.storeStepTimeoutTicks) {
                    abortStore("break timed out");
                }
            }
            case PICKUP -> {
                // give the pickup a moment, then resume regardless
                if (!BARITONE.isActive() && storeStepTicks > 20) {
                    setStorePhase(StorePhase.RESUME);
                } else if (storeStepTicks > cfg.storeStepTimeoutTicks) {
                    setStorePhase(StorePhase.RESUME);
                }
            }
            case RESUME -> resumeFromStore();
        }
    }

    private void depositTick() {
        var cfg = PLUGIN_CONFIG.miner;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId == 0) {
            // container closed unexpectedly; treat as done
            setStorePhase(StorePhase.RESUME);
            return;
        }
        if (!depositTimer.tick(cfg.depositDelayTicks)) return;

        Container container = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int size = container.getSize();
        int playerStart = Math.max(0, size - 36); // last 36 window slots are the player's inventory

        // find next kept item in the player portion of the window
        int srcSlot = -1;
        for (int i = playerStart; i < size; i++) {
            if (isKeep(container.getItemStack(i))) {
                srcSlot = i;
                break;
            }
        }
        if (srcSlot == -1) {
            // nothing left to deposit
            setStorePhase(StorePhase.CLOSE);
            return;
        }

        // stall detection: if the kept-item total isn't dropping, the chest is full
        int keepTotal = keepTotalInPlayer(container, playerStart, size);
        if (keepTotal == lastKeepTotal) {
            if (++depositStall > 6) {
                info("Container full (kept items remain); closing.");
                setStorePhase(StorePhase.CLOSE);
                return;
            }
        } else {
            depositStall = 0;
            lastKeepTotal = keepTotal;
        }

        INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this)
            .actions(new ShiftClick(container.getContainerId(), srcSlot, ShiftClickItemAction.LEFT_CLICK))
            .priority(ACTION_PRIORITY)
            .build());
    }

    private void resumeFromStore() {
        var cfg = PLUGIN_CONFIG.miner;
        storing = false;
        if (finishAfterStore) {            // this was the final store after the area was cleared
            finishAfterStore = false;
            completeRun();
            return;
        }
        int nowEmpty = emptyMainSlots();
        if (nowEmpty <= cfg.freeSlotsBeforeFull) {
            // still full after a cycle
            boolean progress = nowEmpty > storeStartEmpty;
            if (progress && hasStorageItem()) {
                // freed some space but more to store -> place another container
                beginStore();
                return;
            }
            paused = true;
            if (BARITONE.isActive()) BARITONE.stop();
            warn("Storage cycle freed no space (out of containers or all full). Mining paused.");
            inGameAlertActivePlayer("<red>Aquarius Miner paused: storage exhausted");
            endRun("storage exhausted");
            return;
        }
        // resume mining: re-clear the current chunk (clearArea skips already-air blocks)
        areaActive = false;
        sawActive = false;
        info("Storage done ({} slots free); resuming mining.", nowEmpty);
    }

    private void abortStore(String reason) {
        storing = false;
        paused = true;
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0) {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this).actions(new CloseContainer()).priority(ACTION_PRIORITY).build());
        }
        if (BARITONE.isActive()) BARITONE.stop();
        warn("Storage cycle aborted: {}. Mining paused - toggle /aquariusminer off/on to retry.", reason);
        inGameAlertActivePlayer("<red>Aquarius Miner storage failed: " + reason);
    }

    /**
     * Picks an air block beside the bot that has a solid floor under it (so a shulker can be placed
     * on the floor face). After a quarry the bot stands on the band floor, whose underside is solid,
     * so a horizontal neighbour at feet level normally qualifies.
     */
    private @Nullable BlockPos selectStorageSpot() {
        BlockPos pf = BARITONE.getPlayerContext().playerFeet();
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int dy = 0; dy <= 1; dy++) {
            for (int[] d : dirs) {
                BlockPos cand = pf.add(d[0], dy, d[1]);
                if (cand.equals(pf) || cand.equals(pf.above())) continue;
                if (!World.getBlock(cand.x(), cand.y(), cand.z()).isAir()) continue;
                var floor = World.getBlock(cand.x(), cand.y() - 1, cand.z());
                if (floor.isAir() || World.isFluid(floor)) continue;
                return cand;
            }
        }
        return null;
    }

    /** Mining-full pause helper for deposit/storage modes. */
    private void pauseInvFull(String why) {
        paused = true;
        if (BARITONE.isActive()) BARITONE.stop();
        areaActive = false; sawActive = false;
        warn("Inventory full and cannot store ({}). Mining paused. Resolve, then toggle /aquariusminer off/on.", why);
        inGameAlertActivePlayer("<red>Aquarius Miner paused: inventory full (" + why + ")");
        if (PLUGIN_CONFIG.miner.storageEnabled) endRun("storage exhausted (" + why + ")");
    }

    // ------------------------------------------------ echest-buffer storage cycle (deposit mode)
    // The ender chest is the FIELD buffer. One cycle: place the echest -> open -> stock any carried empties
    // -> pull ONE empty shulker out -> close -> place + fill that shulker with the haul -> break + collect
    // it (now filled) -> reopen the echest -> store the filled shulker back in -> break the echest. Filled
    // shulkers live in the echest, never in the mining inventory. When the echest runs out of empties
    // (detected from STORE_FILLED with a clean inventory) a deposit trip fires.

    private void beginEchestCycle() {
        if (BARITONE.isActive()) BARITONE.stop();
        areaActive = false; sawActive = false;
        echestCycle = true;
        echestExhausted = false;
        shulkPos = null; shulkItem = null;
        int echSlot = InventoryUtil.searchPlayerInventory(this::isEnderChestItem);
        echItem = echSlot == -1 ? null
            : ItemRegistry.REGISTRY.get(CACHE.getPlayerCache().getPlayerInventory().get(echSlot).getId());
        echPos = selectStorageSpot();
        if (echItem == null || echPos == null) { abortEchest("no ender chest or no spot to place it"); return; }
        info("Inventory full - storing into the ender chest buffer.");
        setEchestPhase(EchestPhase.PLACE_ECHEST);
    }

    private void setEchestPhase(EchestPhase phase) {
        echestPhase = phase;
        echestStepTicks = 0;
        switch (phase) {
            case PLACE_ECHEST -> { if (echPos != null && echItem != null) BARITONE.placeBlock(echPos.x(), echPos.y(), echPos.z(), echItem); }
            case OPEN_ECHEST, REOPEN_ECHEST -> { if (echPos != null) BARITONE.rightClickBlock(echPos.x(), echPos.y(), echPos.z()); }
            case PLACE_SHULKER -> { if (shulkPos != null && shulkItem != null) BARITONE.placeBlock(shulkPos.x(), shulkPos.y(), shulkPos.z(), shulkItem); }
            case OPEN_SHULKER -> { if (shulkPos != null) BARITONE.rightClickBlock(shulkPos.x(), shulkPos.y(), shulkPos.z()); }
            case STOCK_EMPTIES, FILL_SHULKER, STORE_FILLED -> { echFillStall = 0; lastEchCount = -1; }
            case CLOSE_ECHEST, CLOSE_SHULKER, CLOSE_ECHEST2 -> closeContainer();
            case BREAK_SHULKER -> { if (shulkPos != null) BARITONE.breakBlock(shulkPos.x(), shulkPos.y(), shulkPos.z(), true); }
            case BREAK_ECHEST -> { if (echPos != null) BARITONE.breakBlock(echPos.x(), echPos.y(), echPos.z(), true); }
            case PICKUP_SHULKER, PICKUP_ECHEST -> BARITONE.pickup();
            default -> { /* DONE handled in echestTick */ }
        }
    }

    private void echestTick() {
        echestStepTicks++;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        switch (echestPhase) {
            case PLACE_ECHEST -> { if (placed(echPos)) setEchestPhase(EchestPhase.OPEN_ECHEST); else echTimeout("place ender chest"); }
            case OPEN_ECHEST -> { if (openId != 0) setEchestPhase(EchestPhase.STOCK_EMPTIES); else echTimeout("open ender chest"); }
            case STOCK_EMPTIES -> { // push any carried empties into the echest first (so refilled empties live there)
                if (openId == 0) { abortEchest("ender chest closed early"); return; }
                Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                int src = c == null ? -1 : findPlayerWindowSlot(c, this::isEmptyShulker);
                if (src != -1 && containerHasRoom(c)) { shiftClick(c, src); return; } // loop one per tick
                setEchestPhase(EchestPhase.TAKE_EMPTY);
            }
            case TAKE_EMPTY -> {
                if (openId == 0) { abortEchest("ender chest closed early"); return; }
                if (countInInv(this::isEmptyShulker) > 0) { setEchestPhase(EchestPhase.CLOSE_ECHEST); return; } // pulled one (or have a spare)
                Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                int src = c == null ? -1 : findContainerSlot(c, this::isEmptyShulker);
                if (src != -1) { shiftClick(c, src); return; }
                // no empties anywhere with a full load of haul: the proactive trip at STORE_FILLED normally fires
                // first (clean inventory), so this is the cold-start fallback - can't pack the haul.
                abortEchest("out of empty shulkers - load empties or stock a supply chest");
            }
            case CLOSE_ECHEST -> {
                if (openId == 0) {
                    int s = InventoryUtil.searchPlayerInventory(this::isEmptyShulker);
                    if (s == -1) { abortEchest("no empty shulker to place"); return; }
                    shulkItem = ItemRegistry.REGISTRY.get(CACHE.getPlayerCache().getPlayerInventory().get(s).getId());
                    shulkPos = selectStorageSpot();
                    if (shulkPos == null) { abortEchest("no spot to place the shulker"); return; }
                    setEchestPhase(EchestPhase.PLACE_SHULKER);
                } else echTimeout("close ender chest");
            }
            case PLACE_SHULKER -> { if (placed(shulkPos)) setEchestPhase(EchestPhase.OPEN_SHULKER); else echTimeout("place shulker"); }
            case OPEN_SHULKER -> { if (openId != 0) setEchestPhase(EchestPhase.FILL_SHULKER); else echTimeout("open shulker"); }
            case FILL_SHULKER -> echFillTick();
            case CLOSE_SHULKER -> { if (openId == 0) setEchestPhase(EchestPhase.BREAK_SHULKER); else echTimeout("close shulker"); }
            case BREAK_SHULKER -> { if (isAir(shulkPos)) setEchestPhase(EchestPhase.PICKUP_SHULKER); else echTimeout("break shulker"); }
            case PICKUP_SHULKER -> { if (countInInv(this::isFilledShulker) > 0 || echestStepTicks > 60) setEchestPhase(EchestPhase.REOPEN_ECHEST); }
            case REOPEN_ECHEST -> { if (openId != 0) setEchestPhase(EchestPhase.STORE_FILLED); else echTimeout("reopen ender chest"); }
            case STORE_FILLED -> echStoreTick();
            case CLOSE_ECHEST2 -> { if (openId == 0) setEchestPhase(EchestPhase.BREAK_ECHEST); else echTimeout("close ender chest"); }
            case BREAK_ECHEST -> { if (isAir(echPos)) setEchestPhase(EchestPhase.PICKUP_ECHEST); else echTimeout("break ender chest"); }
            case PICKUP_ECHEST -> { if (echestStepTicks > 60 || !BARITONE.isActive()) setEchestPhase(EchestPhase.DONE); }
            case DONE -> { echestCycle = false; resumeFromEchest(); }
        }
    }

    /** Shift keep-items into the open shulker, one per tick; full (or none left) -> close. */
    private void echFillTick() {
        var cfg = PLUGIN_CONFIG.miner;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId == 0) { setEchestPhase(EchestPhase.CLOSE_SHULKER); return; }
        if (!depositTimer.tick(cfg.depositDelayTicks)) return;
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int src = c == null ? -1 : findPlayerWindowSlot(c, this::isKeep);
        if (src == -1) { setEchestPhase(EchestPhase.CLOSE_SHULKER); return; }       // no keep items left
        int total = keepTotalInWindow(c);
        if (total == lastEchCount) {
            if (++echFillStall > 6) { setEchestPhase(EchestPhase.CLOSE_SHULKER); return; } // shulker full
        } else { echFillStall = 0; lastEchCount = total; }
        shiftClick(c, src);
    }

    /** Shift the filled shulker(s) into the open echest, one per tick; detect exhaustion -> close. */
    private void echStoreTick() {
        var cfg = PLUGIN_CONFIG.miner;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId == 0) { setEchestPhase(EchestPhase.CLOSE_ECHEST2); return; }
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int src = c == null ? -1 : findPlayerWindowSlot(c, this::isFilledShulker);
        if (src == -1) {
            // done storing, inventory clean: if the echest now holds no empty shulker (and none carried),
            // it's exhausted -> a deposit trip fires from this clean state (so the trip's extract has room).
            if (c != null && findContainerSlot(c, this::isEmptyShulker) == -1 && countInInv(this::isEmptyShulker) == 0) {
                echestExhausted = true;
                info("Used the last empty shulker - ender chest is full of filled shulkers.");
            }
            setEchestPhase(EchestPhase.CLOSE_ECHEST2);
            return;
        }
        if (c != null && !containerHasRoom(c)) {        // echest full of shulkers -> exhausted
            echestExhausted = true;
            info("Ender chest is full of filled shulkers.");
            setEchestPhase(EchestPhase.CLOSE_ECHEST2);
            return;
        }
        if (!depositTimer.tick(cfg.depositDelayTicks)) return;
        shiftClick(c, src);
    }

    private void resumeFromEchest() {
        echestCycle = false;
        if (finishAfterEchest) {         // bounded-area final pack just finished -> complete the run
            finishAfterEchest = false;
            completeRun();
            return;
        }
        if (echestExhausted) {
            echestExhausted = false;
            // Echest is full of filled shulkers. With deposit chests set, haul them out to base for an
            // unlimited run; otherwise (Foreman's EnderChest mode) the haul is safely packed in the global
            // ender chest - end the run rather than leaving shulkers behind.
            if (PLUGIN_CONFIG.miner.depositToChests && !depositChestList().isEmpty()) {
                beginDepositTrip();
            } else {
                complete = true;
                info("Ender chest is full of filled shulkers - run complete. (Set deposit chests for an unlimited run.)");
                inGameAlertActivePlayer("<green>Aquarius Miner: ender chest packed full - done");
                endRun("ender chest full");
            }
            return;
        }
        areaActive = false; sawActive = false;
        info("Stored into the ender chest; resuming mining.");
    }

    private void abortEchest(String reason) {
        echestCycle = false;
        paused = true;
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0) closeContainer();
        if (BARITONE.isActive()) BARITONE.stop();
        warn("Storage cycle aborted: {}. Mining paused - toggle /aquariusminer off/on to retry.", reason);
        inGameAlertActivePlayer("<red>Aquarius Miner storage failed: " + reason);
    }

    private void echTimeout(String what) {
        if (echestStepTicks > PLUGIN_CONFIG.miner.storeStepTimeoutTicks) abortEchest(what + " timed out");
    }

    /** True if the container half (the chest/shulker) has at least one empty slot. */
    private boolean containerHasRoom(Container c) {
        int chestSlots = Math.max(0, c.getSize() - 36);
        for (int i = 0; i < chestSlots; i++) if (c.getItemStack(i) == Container.EMPTY_STACK) return true;
        return false;
    }

    /** Count the empty slots in the container half (used to size a refill to the echest's free space). */
    private int echestFreeSlots(Container c) {
        int chestSlots = Math.max(0, c.getSize() - 36);
        int free = 0;
        for (int i = 0; i < chestSlots; i++) if (c.getItemStack(i) == Container.EMPTY_STACK) free++;
        return free;
    }

    private int keepTotalInWindow(Container c) {
        return keepTotalInPlayer(c, Math.max(0, c.getSize() - 36), c.getSize());
    }

    // ----------------------------------------------------- deposit trips
    // Haul collected filled shulkers to fixed DEPOSIT chests at a base, then refill empty shulkers from a
    // separate SUPPLY chest. A two-leg FSM: pathTo(GoalNear) the nearest deposit chest -> open -> shift
    // filled shulkers in -> pathTo the nearest supply chest -> open -> pull empties out -> resume mining.
    // Each leg has a travel/step timeout that moves on to the next chest or pauses, so a wrong/blocked/
    // out-of-range chest can't hang the run.

    private int countFilledShulkers() { return countInInv(this::isFilledShulker); }
    private boolean hasEmptyShulker() { return InventoryUtil.searchPlayerInventory(this::isEmptyShulker) != -1; }

    private int countInInv(java.util.function.Predicate<ItemStack> pred) {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int n = 0;
        for (int i = 9; i <= 44; i++) if (pred.test(inv.get(i))) n++;
        return n;
    }

    /** Empties to take this trip: capped to what fits in the echest after the deposit (and empties-per-trip). */
    private int tripEmptiesTarget() {
        return Math.min(PLUGIN_CONFIG.miner.emptiesPerTrip, echestFreeAfterExtract);
    }

    private java.util.List<BlockPos> depositChestList() { return parseChests(PLUGIN_CONFIG.miner.depositChests); }
    private java.util.List<BlockPos> supplyChestList()  { return parseChests(PLUGIN_CONFIG.miner.supplyChests); }

    private java.util.List<BlockPos> parseChests(List<String> raw) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (String s : raw) {
            String[] p = s.trim().split("\\s+");
            if (p.length != 3) continue;
            try { out.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]))); }
            catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** Nearest chest in {@code list} not tried this trip, within maxDepositDistance (or null). */
    private @Nullable BlockPos nearestChest(java.util.List<BlockPos> list) {
        BlockPos feet = BARITONE.getPlayerContext().playerFeet();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos c : list) {
            if (triedChests.contains(c)) continue;
            double dx = c.x() - feet.x(), dy = c.y() - feet.y(), dz = c.z() - feet.z();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) { bestD = d; best = c; }
        }
        int max = PLUGIN_CONFIG.miner.maxDepositDistance;
        if (best != null && max > 0 && bestD > (double) max * max) return null; // every untried chest is out of range
        return best;
    }

    /** Begin a deposit trip: walk to the nearest deposit chest FIRST with a clean inventory - the filled
     *  shulkers stay in the global ender chest until the bot is there (a death en route can't strand them). */
    private void beginDepositTrip() {
        if (BARITONE.isActive()) BARITONE.stop();
        areaActive = false; sawActive = false; storing = false; echestCycle = false;
        triedChests.clear();
        nextDepositLeg = false; nextSupplyLeg = false; pendingDepositPause = null;
        tripExtracted = false; tripRefilled = false; echestFreeAfterExtract = 0;
        tripEchPos = null;
        int echSlot = InventoryUtil.searchPlayerInventory(this::isEnderChestItem);
        if (echSlot == -1) { depositPauseMsg("no ender chest to open the shulker buffer"); return; }
        tripEchItem = ItemRegistry.REGISTRY.get(CACHE.getPlayerCache().getPlayerInventory().get(echSlot).getId());
        BlockPos c = nearestChest(depositChestList());
        if (c == null) { depositPause(depositChestList(), "deposit"); return; }
        depositChest = c;
        depositing = true;
        startTravel(c, DepositPhase.PATH_TO_DEPOSIT);
        info("Deposit trip: heading to deposit chest {} (filled shulkers stay in the ender chest until I'm there).", depositChest);
    }

    private void startTravel(BlockPos chest, DepositPhase phase) {
        depositPhase = phase;
        depositTripTicks = 0;
        depositGoal = new GoalNear(chest.x(), chest.y(), chest.z(), 9); // within ~3 blocks (interact reach)
        BARITONE.pathTo(depositGoal);
    }

    /** Start the supply leg by routing to the nearest supply chest (refill empties). */
    private void startSupplyLeg() {
        triedChests.clear();
        BlockPos c = nearestChest(supplyChestList());
        if (c == null) {
            if (countInInv(this::isEmptyShulker) > 0) gotoStockOrDone();  // nothing reachable, but we grabbed some
            else depositPause(supplyChestList(), "supply");
            return;
        }
        depositChest = c;
        startTravel(c, DepositPhase.PATH_TO_SUPPLY);
    }

    /** At the deposit chest: place an ender chest beside the bot to pull the filled shulkers out of it. */
    private void beginExtract() {
        tripEchPos = selectStorageSpot();
        if (tripEchPos == null || tripEchItem == null) { depositPauseMsg("no spot to place the ender chest at the deposit chest"); return; }
        BARITONE.placeBlock(tripEchPos.x(), tripEchPos.y(), tripEchPos.z(), tripEchItem);
        depositPhase = DepositPhase.PULL_PLACE; depositTripTicks = 0;
    }

    /** After the filled shulkers are dumped: refill empties (if wanted and the echest has room), else stock/finish. */
    private void afterDeposit() {
        var cfg = PLUGIN_CONFIG.miner;
        if (!finishAfterDeposit && cfg.refillEmpties
                && tripEmptiesTarget() > 0
                && countInInv(this::isEmptyShulker) < tripEmptiesTarget()) {
            startSupplyLeg();
        } else {
            gotoStockOrDone();
        }
    }

    /** Place an ender chest and stock the carried empties into it, or finish the trip if there are none. */
    private void gotoStockOrDone() {
        if (countInInv(this::isEmptyShulker) > 0 && isEnderChestInInv()) {
            tripEchPos = selectStorageSpot();
            if (tripEchPos != null && tripEchItem != null) {
                tripRefilled = true;
                BARITONE.placeBlock(tripEchPos.x(), tripEchPos.y(), tripEchPos.z(), tripEchItem);
                depositPhase = DepositPhase.STOCK_PLACE; depositTripTicks = 0;
                return;
            }
        }
        depositPhase = DepositPhase.DONE;
    }

    private void depositPause(java.util.List<BlockPos> list, String kind) {
        depositPauseMsg(list.isEmpty() ? "no " + kind + " chests set" : "no reachable " + kind + " chest");
    }

    private void depositPauseMsg(String msg) {
        depositing = false; paused = true;
        if (BARITONE.isActive()) BARITONE.stop();
        warn("Deposit trip: {}. Mining paused - toggle /aquariusminer off/on to retry.", msg);
        inGameAlertActivePlayer("<red>Aquarius Miner: " + msg);
    }

    private void depositTripTick() {
        var cfg = PLUGIN_CONFIG.miner;
        depositTripTicks++;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        switch (depositPhase) {
            case PATH_TO_DEPOSIT, PATH_TO_SUPPLY -> {
                boolean supply = depositPhase == DepositPhase.PATH_TO_SUPPLY;
                BlockPos feet = BARITONE.getPlayerContext().playerFeet();
                if (depositGoal != null && depositGoal.isInGoal(feet.x(), feet.y(), feet.z())) {
                    if (BARITONE.isActive()) BARITONE.stop();
                    if (supply) { open(depositChest); depositPhase = DepositPhase.OPEN_SUPPLY; depositTripTicks = 0; }
                    else if (!tripExtracted) beginExtract();             // pull the filled shulkers out of the echest here
                    else { open(depositChest); depositPhase = DepositPhase.OPEN_DEPOSIT; depositTripTicks = 0; }
                } else if (!BARITONE.isActive() || depositTripTicks > cfg.maxClearTicks) {
                    warn("Couldn't reach {} chest {} - trying another.", supply ? "supply" : "deposit", depositChest);
                    tryNextChest(supply);
                }
            }
            // --- EXTRACT: place echest at the deposit chest, pull the filled loot shulkers out, break it ---
            case PULL_PLACE -> { if (placed(tripEchPos)) { depositPhase = DepositPhase.PULL_OPEN; depositTripTicks = 0; } else if (depositTripTicks > cfg.storeStepTimeoutTicks) depositPauseMsg("couldn't place the ender chest to collect filled shulkers"); }
            case PULL_OPEN -> { if (openId != 0) { depositPhase = DepositPhase.PULL_FILLED; depositTripTicks = 0; } else if (depositTripTicks > cfg.storeStepTimeoutTicks) depositPauseMsg("ender chest didn't open (collecting filled shulkers)"); }
            case PULL_FILLED -> pullFilledTick();
            case PULL_CLOSE -> { if (openId == 0) { breakAt(tripEchPos); depositPhase = DepositPhase.PULL_BREAK; depositTripTicks = 0; } else if (depositTripTicks > cfg.storeStepTimeoutTicks) closeContainer(); }
            case PULL_BREAK -> { if (isAir(tripEchPos)) { BARITONE.pickup(); depositPhase = DepositPhase.PULL_PICKUP; depositTripTicks = 0; } else if (depositTripTicks > cfg.storeStepTimeoutTicks) breakAt(tripEchPos); }
            case PULL_PICKUP -> {
                if (depositTripTicks > 30 || !BARITONE.isActive()) {
                    tripExtracted = true; tripEchPos = null;
                    if (countInInv(this::isLootFilledShulker) > 0) { open(depositChest); depositPhase = DepositPhase.OPEN_DEPOSIT; depositTripTicks = 0; }
                    else afterDeposit();                                 // echest held no filled shulkers (edge)
                }
            }
            case OPEN_DEPOSIT -> {
                if (openId != 0) { depositPhase = DepositPhase.DEPOSIT_FILLED; depositTripTicks = 0; depositTripStall = 0; lastTripCount = -1; }
                else if (depositTripTicks > cfg.storeStepTimeoutTicks) tryNextChest(false);
            }
            case DEPOSIT_FILLED -> depositFilledTick();
            case CLOSE_DEPOSIT -> {
                if (openId == 0) {
                    if (pendingDepositPause != null) { String m = pendingDepositPause; pendingDepositPause = null; depositPauseMsg(m); }
                    else if (nextDepositLeg) { nextDepositLeg = false; startTravel(depositChest, DepositPhase.PATH_TO_DEPOSIT); }
                    else afterDeposit();
                } else if (depositTripTicks > cfg.storeStepTimeoutTicks) closeContainer();
            }
            case OPEN_SUPPLY -> {
                if (openId != 0) { depositPhase = DepositPhase.TAKE_EMPTIES; depositTripTicks = 0; }
                else if (depositTripTicks > cfg.storeStepTimeoutTicks) tryNextChest(true);
            }
            case TAKE_EMPTIES -> takeEmptiesTick();
            case CLOSE_SUPPLY -> {
                if (openId == 0) {
                    if (nextSupplyLeg) { nextSupplyLeg = false; startTravel(depositChest, DepositPhase.PATH_TO_SUPPLY); }
                    else gotoStockOrDone();
                } else if (depositTripTicks > cfg.storeStepTimeoutTicks) closeContainer();
            }
            // --- STOCK: place echest near the supply chest, push the empties into it, break it ---
            case STOCK_PLACE -> { if (placed(tripEchPos)) { depositPhase = DepositPhase.STOCK_OPEN; depositTripTicks = 0; } else if (depositTripTicks > cfg.storeStepTimeoutTicks) depositPauseMsg("couldn't place the ender chest to stock empties"); }
            case STOCK_OPEN -> { if (openId != 0) { depositPhase = DepositPhase.STOCK_EMPTIES; depositTripTicks = 0; } else if (depositTripTicks > cfg.storeStepTimeoutTicks) depositPauseMsg("ender chest didn't open (stocking empties)"); }
            case STOCK_EMPTIES -> stockEmptiesTick();
            case STOCK_CLOSE -> { if (openId == 0) { breakAt(tripEchPos); depositPhase = DepositPhase.STOCK_BREAK; depositTripTicks = 0; } else if (depositTripTicks > cfg.storeStepTimeoutTicks) closeContainer(); }
            case STOCK_BREAK -> { if (isAir(tripEchPos)) { BARITONE.pickup(); depositPhase = DepositPhase.STOCK_PICKUP; depositTripTicks = 0; } else if (depositTripTicks > cfg.storeStepTimeoutTicks) breakAt(tripEchPos); }
            case STOCK_PICKUP -> { if (depositTripTicks > 30 || !BARITONE.isActive()) { tripEchPos = null; finishTrip(); } }
            case DONE -> finishTrip();
        }
    }

    /** Pull filled loot shulkers (NOT the tool-shulker) out of the open echest, one per tick; record free space. */
    private void pullFilledTick() {
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId == 0) { depositPhase = DepositPhase.PULL_CLOSE; return; }
        if (!depositTimer.tick(PLUGIN_CONFIG.miner.depositDelayTicks)) return;
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int src = c == null ? -1 : findContainerSlot(c, this::isLootFilledShulker);
        if (src == -1 || emptyMainSlots() == 0) {                 // pulled them all (or inventory full, rare)
            echestFreeAfterExtract = c == null ? 0 : echestFreeSlots(c);
            closeContainer(); depositPhase = DepositPhase.PULL_CLOSE; depositTripTicks = 0; return;
        }
        shiftClick(c, src);
    }

    /** Push the carried empty shulkers into the open echest, one per tick. */
    private void stockEmptiesTick() {
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId == 0) { depositPhase = DepositPhase.STOCK_CLOSE; return; }
        if (!depositTimer.tick(PLUGIN_CONFIG.miner.depositDelayTicks)) return;
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int src = c == null ? -1 : findPlayerWindowSlot(c, this::isEmptyShulker);
        if (src == -1 || (c != null && !containerHasRoom(c))) {   // all stocked (or echest full)
            closeContainer(); depositPhase = DepositPhase.STOCK_CLOSE; depositTripTicks = 0; return;
        }
        shiftClick(c, src);
    }

    private void depositFilledTick() {
        var cfg = PLUGIN_CONFIG.miner;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId == 0) { depositPhase = DepositPhase.CLOSE_DEPOSIT; return; }
        if (!depositTimer.tick(cfg.depositDelayTicks)) return;
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int src = findPlayerWindowSlot(c, this::isLootFilledShulker);
        if (src == -1) { closeContainer(); depositPhase = DepositPhase.CLOSE_DEPOSIT; depositTripTicks = 0; return; } // all dropped off
        // chest-full detection: if the filled-shulker count in the player window stops dropping, the chest is full
        int cnt = countLootFilledInWindow(c);
        if (cnt == lastTripCount) {
            if (++depositTripStall > 6) {
                triedChests.add(depositChest);
                BlockPos next = nearestChest(depositChestList());
                closeContainer();
                depositPhase = DepositPhase.CLOSE_DEPOSIT;
                depositTripTicks = 0;
                if (next != null) { depositChest = next; nextDepositLeg = true; }
                else pendingDepositPause = "all deposit chests full";
                return;
            }
        } else { depositTripStall = 0; lastTripCount = cnt; }
        shiftClick(c, src);
    }

    private void takeEmptiesTick() {
        var cfg = PLUGIN_CONFIG.miner;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId == 0) { depositPhase = DepositPhase.CLOSE_SUPPLY; return; }
        if (countInInv(this::isEmptyShulker) >= tripEmptiesTarget() || emptyMainSlots() == 0) {
            closeContainer(); depositPhase = DepositPhase.CLOSE_SUPPLY; depositTripTicks = 0; return;
        }
        if (!depositTimer.tick(cfg.depositDelayTicks)) return;
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int src = findContainerSlot(c, this::isEmptyShulker);
        if (src == -1) {                                    // this supply chest is out of empties
            triedChests.add(depositChest);
            BlockPos next = nearestChest(supplyChestList());
            closeContainer();
            depositPhase = DepositPhase.CLOSE_SUPPLY;
            depositTripTicks = 0;
            if (next != null) { depositChest = next; nextSupplyLeg = true; } // else finish with what we grabbed
            return;
        }
        shiftClick(c, src);
    }

    private int countLootFilledInWindow(Container c) {
        int size = c.getSize();
        int n = 0;
        for (int i = Math.max(0, size - 36); i < size; i++) if (isLootFilledShulker(c.getItemStack(i))) n++;
        return n;
    }

    /** A chest of the current leg was full/unreachable: try the next nearest, else continue or pause. */
    private void tryNextChest(boolean supply) {
        if (BARITONE.isActive()) BARITONE.stop();
        triedChests.add(depositChest);
        BlockPos next = nearestChest(supply ? supplyChestList() : depositChestList());
        if (next == null) {
            if (supply) {
                if (countInInv(this::isEmptyShulker) > 0) gotoStockOrDone();
                else depositPauseMsg("no reachable supply chest");
            } else {
                if (tripExtracted && countInInv(this::isLootFilledShulker) == 0) afterDeposit();
                else depositPauseMsg("no reachable deposit chest");
            }
            return;
        }
        depositChest = next;
        startTravel(next, supply ? DepositPhase.PATH_TO_SUPPLY : DepositPhase.PATH_TO_DEPOSIT);
    }

    private void finishTrip() {
        depositing = false;
        if (BARITONE.isActive()) BARITONE.stop();
        if (finishAfterDeposit) { finishAfterDeposit = false; completeRun(); return; }
        if (!tripRefilled) {
            // stocked no empties into the echest (supply empty / refill off) -> can't keep mining
            depositPauseMsg(PLUGIN_CONFIG.miner.refillEmpties
                ? "out of empty shulkers (supply chests had none)"
                : "out of empty shulkers (refill is off)");
            return;
        }
        areaActive = false; sawActive = false;
        info("Deposit trip done; resuming mining.");
    }

    // ----------------------------------------------------------- inventory

    private int emptyMainSlots() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int empty = 0;
        for (int i = 9; i <= 44; i++) { // main inventory + hotbar (skip armor/offhand/crafting)
            if (inv.get(i) == Container.EMPTY_STACK) empty++;
        }
        return empty;
    }

    /**
     * Can the inventory (main + hotbar, slots 9-44) take even one more keep item? True if any slot is
     * empty or any keep stack is below its max. When false the inventory is completely packed, so a
     * storage cycle fills shulkers with whole stacks (the require-full-stacks trigger).
     */
    private boolean canHoldMoreKeep() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) {
            ItemStack s = inv.get(i);
            if (s == Container.EMPTY_STACK) return true;
            if (isKeep(s) && s.getAmount() < maxStackSize(s)) return true;
        }
        return false;
    }

    private int maxStackSize(ItemStack s) {
        var data = ItemRegistry.REGISTRY.get(s.getId());
        return data == null ? 64 : data.stackSize();
    }

    // ---- tool restock: during a storage cycle, pull a fresh tool from the open container if ours is spent ----

    /**
     * Restock cycle: place the ender chest, pull out the tool-shulker (a shulker holding a fresh tool),
     * place it, take one fresh tool, break + recover the shulker, return it to the ender chest, then
     * recover the chest. Driven as a polling FSM like the storage cycle; every wait phase times out to
     * {@link #abortRestock}. The tool-shulker is placed by item TYPE, so it must be a unique colour
     * among any shulkers the bot carries (see config note).
     */
    private void beginRestock() {
        if (BARITONE.isActive()) BARITONE.stop();
        areaActive = false; sawActive = false;
        restockKeyword = currentToolKeyword();   // latch which tool this cycle restocks (primary or shovel)
        restockShulkerPos = null; restockShulkerItem = null;
        int echestSlot = InventoryUtil.searchPlayerInventory(s -> matchesName(s, PLUGIN_CONFIG.miner.restockSourceItem));
        restockEchestItem = echestSlot == -1 ? null
            : ItemRegistry.REGISTRY.get(CACHE.getPlayerCache().getPlayerInventory().get(echestSlot).getId());
        restockEchestPos = selectStorageSpot();
        if (restockEchestItem == null || restockEchestPos == null) { warn("Cannot restock: no ender chest or no spot."); return; }
        restocking = true;
        info("Tool spent - starting restock cycle.");
        setRestockPhase(RestockPhase.PLACE_ECHEST);
    }

    private void setRestockPhase(RestockPhase phase) {
        restockPhase = phase;
        restockStepTicks = 0;
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        switch (phase) {
            case PLACE_ECHEST -> place(restockEchestPos, restockEchestItem);
            case OPEN_ECHEST, REOPEN_ECHEST -> open(restockEchestPos);
            case PLACE_SHULKER -> place(restockShulkerPos, restockShulkerItem);
            case OPEN_SHULKER -> open(restockShulkerPos);
            case CLOSE_ECHEST, CLOSE_SHULKER, CLOSE_ECHEST2 -> closeContainer();
            case BREAK_SHULKER -> breakAt(restockShulkerPos);
            case BREAK_ECHEST -> breakAt(restockEchestPos);
            case PICKUP_SHULKER, PICKUP_ECHEST -> BARITONE.pickup();
            case TAKE_SHULKER -> { // shift the tool-shulker out of the open ender chest
                int slot = c == null ? -1 : findContainerSlot(c, this::isToolShulker);
                if (slot >= 0) { restockShulkerItem = ItemRegistry.REGISTRY.get(c.getItemStack(slot).getId()); shiftClick(c, slot); }
            }
            case TAKE_TOOL -> { // shift one fresh tool out of the open shulker
                int slot = c == null ? -1 : findContainerSlot(c, this::isFreshTool);
                if (slot >= 0) shiftClick(c, slot);
            }
            case RETURN_SHULKER -> { // shift the tool-shulker back into the open ender chest
                int slot = c == null ? -1 : findPlayerWindowSlot(c, this::isToolShulker);
                if (slot >= 0) shiftClick(c, slot);
            }
            default -> { /* DONE handled in tick */ }
        }
    }

    private void restockTick() {
        restockStepTicks++;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        switch (restockPhase) {
            case PLACE_ECHEST -> { if (placed(restockEchestPos)) setRestockPhase(RestockPhase.OPEN_ECHEST); else timeoutRestock("place ender chest"); }
            case OPEN_ECHEST -> { if (openId != 0) setRestockPhase(RestockPhase.TAKE_SHULKER); else timeoutRestock("open ender chest"); }
            case TAKE_SHULKER -> {
                if (InventoryUtil.searchPlayerInventory(this::isToolShulker) != -1) { setRestockPhase(RestockPhase.CLOSE_ECHEST); return; }
                Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                if (c == null) { abortRestock("ender chest closed early"); return; }
                if (findContainerSlot(c, this::isToolShulker) == -1) { abortRestock("no tool-shulker in the ender chest"); return; }
                timeoutRestock("take tool-shulker");
            }
            case CLOSE_ECHEST -> {
                if (openId == 0) {
                    restockShulkerPos = selectStorageSpot();
                    if (restockShulkerPos == null) { abortRestock("no spot to place the tool-shulker"); return; }
                    setRestockPhase(RestockPhase.PLACE_SHULKER);
                } else timeoutRestock("close ender chest");
            }
            case PLACE_SHULKER -> { if (placed(restockShulkerPos)) setRestockPhase(RestockPhase.OPEN_SHULKER); else timeoutRestock("place tool-shulker"); }
            case OPEN_SHULKER -> { if (openId != 0) setRestockPhase(RestockPhase.TAKE_TOOL); else timeoutRestock("open tool-shulker"); }
            case TAKE_TOOL -> {
                if (!toolNeedsRestock()) { setRestockPhase(RestockPhase.CLOSE_SHULKER); return; }
                Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                if (c == null) { abortRestock("tool-shulker closed early"); return; }
                if (findContainerSlot(c, this::isFreshTool) == -1) { abortRestock("no fresh tool in the tool-shulker"); return; }
                timeoutRestock("take tool");
            }
            case CLOSE_SHULKER -> { if (openId == 0) setRestockPhase(RestockPhase.BREAK_SHULKER); else timeoutRestock("close tool-shulker"); }
            case BREAK_SHULKER -> { if (isAir(restockShulkerPos)) setRestockPhase(RestockPhase.PICKUP_SHULKER); else timeoutRestock("break tool-shulker"); }
            case PICKUP_SHULKER -> { if (hasShulkerType(restockShulkerItem) || restockStepTicks > 60) setRestockPhase(RestockPhase.REOPEN_ECHEST); }
            case REOPEN_ECHEST -> { if (openId != 0) setRestockPhase(RestockPhase.RETURN_SHULKER); else timeoutRestock("reopen ender chest"); }
            case RETURN_SHULKER -> {
                if (InventoryUtil.searchPlayerInventory(this::isToolShulker) == -1
                    && !hasShulkerType(restockShulkerItem)) { setRestockPhase(RestockPhase.CLOSE_ECHEST2); return; }
                Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                if (c == null) { abortRestock("ender chest closed early"); return; }
                timeoutRestock("return tool-shulker");
            }
            case CLOSE_ECHEST2 -> { if (openId == 0) setRestockPhase(RestockPhase.BREAK_ECHEST); else timeoutRestock("close ender chest"); }
            case BREAK_ECHEST -> { if (isAir(restockEchestPos)) setRestockPhase(RestockPhase.PICKUP_ECHEST); else timeoutRestock("break ender chest"); }
            case PICKUP_ECHEST -> { if (restockStepTicks > 60 || !BARITONE.isActive()) setRestockPhase(RestockPhase.DONE); }
            case DONE -> {
                restocking = false;
                areaActive = false; sawActive = false;
                info("Restock done; resuming mining.");
            }
        }
    }

    private void abortRestock(String reason) {
        restocking = false;
        paused = true;
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0) closeContainer();
        if (BARITONE.isActive()) BARITONE.stop();
        warn("Restock cycle aborted: {}. Mining paused - toggle /aquariusminer off/on to retry.", reason);
        inGameAlertActivePlayer("<red>Aquarius Miner restock failed: " + reason);
    }

    private void timeoutRestock(String what) {
        if (restockStepTicks > PLUGIN_CONFIG.miner.storeStepTimeoutTicks) abortRestock(what + " timed out");
    }

    // ------------------------------------------------ food restock cycle (best-effort)
    // Crack a FOOD-shulker kept in the ender chest and top up the carried food. Structurally identical to the
    // tool restock cycle (place echest -> pull the shulker out -> place + open it -> take food by preference ->
    // break + recover the shulker -> return it to the echest -> recover the echest) but pulls FOOD, not a tool.
    // BEST-EFFORT: if there's no food-shulker to crack, it latches foodRestockExhausted, warns, and keeps mining
    // (food is never worth pausing the run). A genuine mechanical timeout still pauses, like the tool cycle.

    private void beginFoodRestock() {
        if (BARITONE.isActive()) BARITONE.stop();
        areaActive = false; sawActive = false;
        foodShulkerPos = null; foodShulkerItem = null;
        int echestSlot = InventoryUtil.searchPlayerInventory(this::isEnderChestItem);
        foodEchestItem = echestSlot == -1 ? null
            : ItemRegistry.REGISTRY.get(CACHE.getPlayerCache().getPlayerInventory().get(echestSlot).getId());
        foodEchestPos = selectStorageSpot();
        if (foodEchestItem == null || foodEchestPos == null) { warn("Cannot restock food: no ender chest or no spot."); return; }
        foodRestocking = true;
        info("Food low - starting food restock cycle.");
        setFoodPhase(FoodPhase.PLACE_ECHEST);
    }

    private void setFoodPhase(FoodPhase phase) {
        foodPhase = phase;
        foodStepTicks = 0;
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        switch (phase) {
            case PLACE_ECHEST -> place(foodEchestPos, foodEchestItem);
            case OPEN_ECHEST, REOPEN_ECHEST -> open(foodEchestPos);
            case PLACE_SHULKER -> place(foodShulkerPos, foodShulkerItem);
            case OPEN_SHULKER -> open(foodShulkerPos);
            case CLOSE_ECHEST, CLOSE_SHULKER, CLOSE_ECHEST2 -> closeContainer();
            case BREAK_SHULKER -> breakAt(foodShulkerPos);
            case BREAK_ECHEST -> breakAt(foodEchestPos);
            case PICKUP_SHULKER, PICKUP_ECHEST -> BARITONE.pickup();
            case TAKE_SHULKER -> { // shift the food-shulker out of the open ender chest
                int slot = c == null ? -1 : findContainerSlot(c, this::isFoodShulker);
                if (slot >= 0) { foodShulkerItem = ItemRegistry.REGISTRY.get(c.getItemStack(slot).getId()); shiftClick(c, slot); }
            }
            case RETURN_SHULKER -> { // shift the (now part-empty) food-shulker back into the open ender chest, by TYPE
                int slot = c == null ? -1 : findPlayerWindowSlot(c, s -> foodShulkerItem != null && matchesName(s, foodShulkerItem.name()));
                if (slot >= 0) shiftClick(c, slot);
            }
            case TAKE_FOOD -> { foodTakeStall = 0; lastFoodCount = -1; } // tick-driven multi-shift below
            default -> { /* DONE handled in foodTick */ }
        }
    }

    private void foodTick() {
        foodStepTicks++;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        switch (foodPhase) {
            case PLACE_ECHEST -> { if (placed(foodEchestPos)) setFoodPhase(FoodPhase.OPEN_ECHEST); else timeoutFood("place ender chest"); }
            case OPEN_ECHEST -> { if (openId != 0) setFoodPhase(FoodPhase.TAKE_SHULKER); else timeoutFood("open ender chest"); }
            case TAKE_SHULKER -> {
                if (InventoryUtil.searchPlayerInventory(this::isFoodShulker) != -1) { setFoodPhase(FoodPhase.CLOSE_ECHEST); return; }
                Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                if (c == null) { abortFood("ender chest closed early"); return; }
                if (findContainerSlot(c, this::isFoodShulker) == -1) {
                    // no food-shulker to crack: best-effort - stop trying this run and recover the placed echest
                    foodRestockExhausted = true;
                    warn("No food-shulker in the ender chest - food restock disabled for this run.");
                    inGameAlertActivePlayer("<yellow>Aquarius Miner: no food-shulker to restock");
                    setFoodPhase(FoodPhase.CLOSE_ECHEST2);   // close + break + pick up the echest, then resume
                    return;
                }
                timeoutFood("take food-shulker");
            }
            case CLOSE_ECHEST -> {
                if (openId == 0) {
                    foodShulkerPos = selectStorageSpot();
                    if (foodShulkerPos == null) { abortFood("no spot to place the food-shulker"); return; }
                    setFoodPhase(FoodPhase.PLACE_SHULKER);
                } else timeoutFood("close ender chest");
            }
            case PLACE_SHULKER -> { if (placed(foodShulkerPos)) setFoodPhase(FoodPhase.OPEN_SHULKER); else timeoutFood("place food-shulker"); }
            case OPEN_SHULKER -> { if (openId != 0) setFoodPhase(FoodPhase.TAKE_FOOD); else timeoutFood("open food-shulker"); }
            case TAKE_FOOD -> takeFoodTick();
            case CLOSE_SHULKER -> { if (openId == 0) setFoodPhase(FoodPhase.BREAK_SHULKER); else timeoutFood("close food-shulker"); }
            case BREAK_SHULKER -> { if (isAir(foodShulkerPos)) setFoodPhase(FoodPhase.PICKUP_SHULKER); else timeoutFood("break food-shulker"); }
            case PICKUP_SHULKER -> { if (hasShulkerType(foodShulkerItem) || foodStepTicks > 60) setFoodPhase(FoodPhase.REOPEN_ECHEST); }
            case REOPEN_ECHEST -> { if (openId != 0) setFoodPhase(FoodPhase.RETURN_SHULKER); else timeoutFood("reopen ender chest"); }
            case RETURN_SHULKER -> {
                if (!hasShulkerType(foodShulkerItem)) { setFoodPhase(FoodPhase.CLOSE_ECHEST2); return; } // shulker is back in the echest
                Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                if (c == null) { abortFood("ender chest closed early"); return; }
                timeoutFood("return food-shulker");
            }
            case CLOSE_ECHEST2 -> { if (openId == 0) setFoodPhase(FoodPhase.BREAK_ECHEST); else timeoutFood("close ender chest"); }
            case BREAK_ECHEST -> { if (isAir(foodEchestPos)) setFoodPhase(FoodPhase.PICKUP_ECHEST); else timeoutFood("break ender chest"); }
            case PICKUP_ECHEST -> { if (foodStepTicks > 60 || !BARITONE.isActive()) setFoodPhase(FoodPhase.DONE); }
            case DONE -> {
                foodRestocking = false;
                areaActive = false; sawActive = false;
                info("Food restock done ({} food on hand); resuming mining.", countGoodFood());
            }
        }
    }

    /** Shift the highest-preference food out of the open food-shulker until we have enough (or it's empty/no room). */
    private void takeFoodTick() {
        var cfg = PLUGIN_CONFIG.miner;
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openId == 0) { setFoodPhase(FoodPhase.CLOSE_SHULKER); return; }
        if (countGoodFood() >= cfg.foodRestockCount) { setFoodPhase(FoodPhase.CLOSE_SHULKER); return; }
        Container c = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        int slot = c == null ? -1 : findBestFoodSlot(c);
        if (slot == -1) { setFoodPhase(FoodPhase.CLOSE_SHULKER); return; }   // shulker out of food
        if (!depositTimer.tick(cfg.depositDelayTicks)) return;
        int cur = countGoodFood();
        if (cur == lastFoodCount) {
            if (++foodTakeStall > 6) { setFoodPhase(FoodPhase.CLOSE_SHULKER); return; } // can't unload (no room) -> stop
        } else { foodTakeStall = 0; lastFoodCount = cur; }
        shiftClick(c, slot);
    }

    private void abortFood(String reason) {
        foodRestocking = false;
        paused = true;
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0) closeContainer();
        if (BARITONE.isActive()) BARITONE.stop();
        warn("Food restock aborted: {}. Mining paused - toggle /aquariusminer off/on to retry.", reason);
        inGameAlertActivePlayer("<red>Aquarius Miner food restock failed: " + reason);
    }

    private void timeoutFood(String what) {
        if (foodStepTicks > PLUGIN_CONFIG.miner.storeStepTimeoutTicks) abortFood(what + " timed out");
    }

    // ---- food predicates (ZenithProxy's FoodRegistry gives safe-food + always-eat flags per item) ----

    private @Nullable FoodData foodData(@Nullable ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return null;
        return FoodRegistry.REGISTRY.get(s.getId());
    }

    /** A safe, edible food (per FoodRegistry) that isn't on the risky-food denylist. */
    private boolean isGoodFood(@Nullable ItemStack s) {
        FoodData f = foodData(s);
        if (f == null || !f.isSafeFood()) return false;
        return !PLUGIN_CONFIG.miner.riskyFoods.contains(f.name());
    }

    /** Total carried good food (summed counts across main inventory + hotbar). */
    private int countGoodFood() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int n = 0;
        for (int i = 9; i <= 44; i++) { ItemStack s = inv.get(i); if (isGoodFood(s)) n += s.getAmount(); }
        return n;
    }

    /** Preference rank (lower = better): golden carrot > ench golden apple > golden apple/always-eat > cooked > other. */
    private int foodPriority(@Nullable ItemStack s) {
        String n = itemName(s);
        if (n == null) return Integer.MAX_VALUE;
        if (n.equals("golden_carrot")) return 1;
        if (n.equals("enchanted_golden_apple")) return 2;
        if (n.equals("golden_apple")) return 3;
        FoodData f = foodData(s);
        if (f != null && f.canAlwaysEat()) return 3;   // other always-eat foods rank with the golden apple
        if (n.startsWith("cooked_") || n.equals("bread") || n.equals("baked_potato")) return 4;
        return 5;                                       // natural / other safe foods
    }

    /** Container (chest-half) slot holding the highest-preference food, or -1. */
    private int findBestFoodSlot(Container c) {
        int chestSlots = Math.max(0, c.getSize() - 36);
        int best = -1, bestPri = Integer.MAX_VALUE;
        for (int i = 0; i < chestSlots; i++) {
            ItemStack s = c.getItemStack(i);
            if (!isGoodFood(s)) continue;
            int pri = foodPriority(s);
            if (pri < bestPri) { bestPri = pri; best = i; }
        }
        return best;
    }

    /** A shulker box item whose CONTAINER contents include good food. */
    private boolean isFoodShulker(@Nullable ItemStack s) {
        String n = itemName(s);
        if (n == null || !n.endsWith("shulker_box")) return false;
        for (ItemStack inner : containerContents(s)) if (isGoodFood(inner)) return true;
        return false;
    }

    // ---- restock primitives + predicates ----

    private void place(@Nullable BlockPos pos, @Nullable ItemData item) {
        if (pos != null && item != null) BARITONE.placeBlock(pos.x(), pos.y(), pos.z(), item);
    }
    private void open(@Nullable BlockPos pos) {
        if (pos != null) BARITONE.rightClickBlock(pos.x(), pos.y(), pos.z());
    }
    private void breakAt(@Nullable BlockPos pos) {
        if (pos != null) BARITONE.breakBlock(pos.x(), pos.y(), pos.z(), true);
    }
    private void closeContainer() {
        INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(new CloseContainer()).priority(ACTION_PRIORITY).build());
    }
    private void shiftClick(Container c, int slot) {
        INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this).actions(new ShiftClick(c.getContainerId(), slot, ShiftClickItemAction.LEFT_CLICK)).priority(ACTION_PRIORITY).build());
    }
    private boolean placed(@Nullable BlockPos p) { return p != null && !World.getBlock(p.x(), p.y(), p.z()).isAir(); }
    private boolean isAir(@Nullable BlockPos p) { return p != null && World.getBlock(p.x(), p.y(), p.z()).isAir(); }

    private int findContainerSlot(Container c, java.util.function.Predicate<ItemStack> pred) {
        int chestSlots = Math.max(0, c.getSize() - 36);
        for (int i = 0; i < chestSlots; i++) if (pred.test(c.getItemStack(i))) return i;
        return -1;
    }
    private int findPlayerWindowSlot(Container c, java.util.function.Predicate<ItemStack> pred) {
        int size = c.getSize();
        for (int i = Math.max(0, size - 36); i < size; i++) if (pred.test(c.getItemStack(i))) return i;
        return -1;
    }

    private boolean matchesName(@Nullable ItemStack s, String name) {
        String n = itemName(s);
        return n != null && n.equals(name);
    }
    private boolean hasShulkerType(@Nullable ItemData type) {
        if (type == null) return false;
        return InventoryUtil.searchPlayerInventory(s -> matchesName(s, type.name())) != -1;
    }
    private boolean hasRestockSource() {
        return InventoryUtil.searchPlayerInventory(s -> matchesName(s, PLUGIN_CONFIG.miner.restockSourceItem)) != -1;
    }

    /** A shulker box item whose CONTAINER contents include a fresh tool. */
    private boolean isToolShulker(@Nullable ItemStack s) {
        String n = itemName(s);
        if (n == null || !n.endsWith("shulker_box")) return false;
        for (ItemStack inner : containerContents(s)) if (isFreshTool(inner)) return true;
        return false;
    }

    /** Items inside a container item (shulker) via its CONTAINER component; empty if none. */
    private List<ItemStack> containerContents(@Nullable ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return List.of();
        List<ItemStack> c = s.getDataComponentsOrEmpty().get(DataComponentTypes.CONTAINER);
        return c == null ? List.of() : c;
    }

    /**
     * True if a tool we keep stocked is spent: no fresh PRIMARY tool, OR (also-restock-shovel on) no fresh
     * shovel AND a fresh shovel actually exists in the tool-shulker to pull (best-effort — a missing shovel
     * never triggers/pauses the run, unlike the primary tool).
     */
    private boolean toolNeedsRestock() {
        var cfg = PLUGIN_CONFIG.miner;
        if (!hasFreshToolKw(cfg.restockToolKeyword)) return true;
        if (cfg.alsoRestockShovel && !cfg.restockToolKeyword.equals("shovel")
            && !hasFreshToolKw("shovel") && hasFreshToolInShulker("shovel")) return true;
        return false;
    }

    /** Which tool the CURRENT restock cycle pulls: the primary if it's missing, else the shovel. */
    private String currentToolKeyword() {
        var cfg = PLUGIN_CONFIG.miner;
        return hasFreshToolKw(cfg.restockToolKeyword) ? "shovel" : cfg.restockToolKeyword;
    }

    /** A fresh tool (the one the FSM is pulling this cycle) — keyed to the latched restock keyword. */
    private boolean isFreshTool(@Nullable ItemStack s) { return isFreshToolKw(s, restockKeyword); }

    /** A tool whose name ends with {@code kw} and still has at least the threshold durability. */
    private boolean isFreshToolKw(@Nullable ItemStack s, String kw) {
        String name = itemName(s);
        if (name == null || !name.endsWith(kw)) return false;
        return remainingDurability(s) >= PLUGIN_CONFIG.miner.restockBelowDurability;
    }

    /** True if the inventory already holds a fresh tool ending with {@code kw}. */
    private boolean hasFreshToolKw(String kw) {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) if (isFreshToolKw(inv.get(i), kw)) return true;
        return false;
    }

    /** True if a carried tool-shulker holds a fresh tool ending with {@code kw} (so we could restock it). */
    private boolean hasFreshToolInShulker(String kw) {
        return InventoryUtil.searchPlayerInventory(s -> {
            String n = itemName(s);
            if (n == null || !n.endsWith("shulker_box")) return false;
            for (ItemStack inner : containerContents(s)) if (isFreshToolKw(inner, kw)) return true;
            return false;
        }) != -1;
    }

    /** Remaining durability of a stack (MAX_DAMAGE - DAMAGE). Non-damageable items count as unlimited. */
    private int remainingDurability(ItemStack s) {
        var data = ItemRegistry.REGISTRY.get(s.getId());
        if (data == null) return 0;
        Integer maxDamage = data.components().get(DataComponentTypes.MAX_DAMAGE);
        if (maxDamage == null) return Integer.MAX_VALUE; // not a damageable item
        Integer damage = s.getDataComponentsOrEmpty().get(DataComponentTypes.DAMAGE);
        return maxDamage - (damage == null ? 0 : damage);
    }

    private int keepTotalInPlayer(Container container, int from, int to) {
        int total = 0;
        for (int i = from; i < to; i++) {
            ItemStack st = container.getItemStack(i);
            if (isKeep(st)) total += st.getAmount();
        }
        return total;
    }

    private boolean hasStorageItem() {
        return InventoryUtil.searchPlayerInventory(this::isStorageItem) != -1;
    }

    private void dropOneJunk() {
        int slot = InventoryUtil.searchPlayerInventory(this::isJunk);
        if (slot == -1) return;
        INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this)
            .actions(new DropItem(slot, DropItemAction.DROP_SELECTED_STACK))
            .priority(ACTION_PRIORITY)
            .build());
    }

    private boolean isJunk(@Nullable ItemStack stack) {
        String name = itemName(stack);
        if (name == null) return false;
        var cfg = PLUGIN_CONFIG.miner;
        if (cfg.junkItems.contains(name)) return true;
        return cfg.dropBadFood && cfg.riskyFoods.contains(name); // risky foods only when enabled
    }

    private boolean isKeep(@Nullable ItemStack stack) {
        String name = itemName(stack);
        return name != null && PLUGIN_CONFIG.miner.keepItems.contains(name);
    }

    private boolean isStorageItem(@Nullable ItemStack stack) {
        String name = itemName(stack);
        if (name == null) return false;
        boolean shulker = name.endsWith("shulker_box");
        // In deposit mode a FILLED shulker is haul to carry off, not a container to place and fill.
        if (shulker && PLUGIN_CONFIG.miner.depositToChests && !containerContents(stack).isEmpty()) return false;
        return shulker || PLUGIN_CONFIG.miner.storageItems.contains(name);
    }

    private boolean isShulkerBox(@Nullable ItemStack s) {
        String n = itemName(s);
        return n != null && n.endsWith("shulker_box");
    }
    private boolean isEmptyShulker(@Nullable ItemStack s) { return isShulkerBox(s) && containerContents(s).isEmpty(); }
    private boolean isFilledShulker(@Nullable ItemStack s) { return isShulkerBox(s) && !containerContents(s).isEmpty(); }

    /** The ender chest item used as the field buffer (and restock source). */
    private boolean isEnderChestItem(@Nullable ItemStack s) { return matchesName(s, PLUGIN_CONFIG.miner.restockSourceItem); }
    private boolean isEnderChestInInv() { return InventoryUtil.searchPlayerInventory(this::isEnderChestItem) != -1; }
    private boolean hasEnderChest() { return isEnderChestInInv(); }

    /** A shulker holding any tool of the restock type (fresh or worn) - excluded from the loot haul. */
    private boolean isToolBearingShulker(@Nullable ItemStack s) {
        String n = itemName(s);
        if (n == null || !n.endsWith("shulker_box")) return false;
        for (ItemStack inner : containerContents(s)) {
            String in = itemName(inner);
            if (in != null && in.endsWith(PLUGIN_CONFIG.miner.restockToolKeyword)) return true;
        }
        return false;
    }

    /** A FILLED loot shulker (has contents) that is NOT the tool- or food-shulker - i.e. mined haul to haul off. */
    private boolean isLootFilledShulker(@Nullable ItemStack s) {
        return isFilledShulker(s) && !isToolBearingShulker(s) && !isFoodShulker(s);
    }

    private @Nullable String itemName(@Nullable ItemStack stack) {
        if (stack == null || stack == Container.EMPTY_STACK) return null;
        var data = ItemRegistry.REGISTRY.get(stack.getId());
        return data == null ? null : data.name();
    }

    // --------------------------------------------------- status accessors

    public boolean isPaused() {
        return paused;
    }

    public boolean isComplete() {
        return complete;
    }

    public boolean isStoring() {
        return storing;
    }

    public String statusLine() {
        if (!PLUGIN_CONFIG.miner.enabled) return "Off";
        if (complete) return "Complete";
        if (paused) return "Paused";
        if (hazardPaused) return "Paused (player nearby)";
        if (storing) return "Storing (" + storePhase + ")";
        if (echestCycle) return "Buffering (" + echestPhase + ")";
        if (restocking) return "Restocking (" + restockPhase + ")";
        if (foodRestocking) return "Food restock (" + foodPhase + ")";
        if (depositing) return "Depositing (" + depositPhase + ")";
        if (areaLimited) return "Mining [" + curCX + ", " + curCZ + "] " + areaChunksDone + "/" + areaChunksTotal;
        return "Mining [" + curCX + ", " + curCZ + "]";
    }
}
