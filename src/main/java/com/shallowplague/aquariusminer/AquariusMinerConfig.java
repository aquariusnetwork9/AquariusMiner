package com.shallowplague.aquariusminer;

import java.util.ArrayList;
import java.util.List;

/**
 * Aquarius Miner configuration POJO. Saved/loaded to JSON automatically by ZenithProxy
 * (on every command execution, proxy start/stop, etc).
 *
 * All fields are public and mutable; static inner classes generate nested JSON objects.
 */
public class AquariusMinerConfig {

    public final MinerConfig miner = new MinerConfig();

    /** How a finite mining area is defined (or Unlimited = infinite outward spiral). */
    public enum AreaMode { Unlimited, ChunksFromStart, Corners }

    /** Where a ChunksFromStart box sits relative to the chunk the bot is in when enabled. */
    public enum AreaAnchor { Center, Corner }

    public static class MinerConfig {
        /** Whether the module is enabled on startup. */
        public boolean enabled = false;

        /**
         * Lowest Y level the quarry will mine (inclusive). Default sits just above bedrock
         * in the 1.21 deepslate layer.
         */
        public int minY = -59;

        /** Highest Y level the quarry will mine (inclusive). A thin band keeps holes shallow. */
        public int maxY = -50;

        // --- area ---

        /**
         * How the mining area is bounded. Unlimited = infinite outward chunk spiral from where the
         * bot is when enabled. ChunksFromStart = a width x length box of chunks placed by
         * {@link #areaAnchor}. Corners = an explicit box between two X/Z coordinates (Y uses the
         * minY/maxY band). When a finite area is fully cleared the bot stores any remaining haul and
         * the run completes.
         */
        public AreaMode areaMode = AreaMode.Unlimited;

        /**
         * ChunksFromStart only: where the box sits relative to the start chunk. Center = the bot is
         * in the middle. Corner = the start chunk is a corner and the box extends in the direction
         * the bot is FACING (so face the area before enabling). Set width = length for a square.
         */
        public AreaAnchor areaAnchor = AreaAnchor.Center;

        /** ChunksFromStart only: area size along X, in chunks (1 chunk = 16 blocks). */
        public int areaWidthChunks = 3;

        /** ChunksFromStart only: area size along Z, in chunks (1 chunk = 16 blocks). */
        public int areaLengthChunks = 3;

        /** Corners only: first corner X/Z (block coords). The Y span comes from minY/maxY. */
        public int corner1X = 0;
        public int corner1Z = 0;
        /** Corners only: second corner X/Z (block coords). */
        public int corner2X = 0;
        public int corner2Z = 0;

        /** Ticks between mining drive steps / starting the next chunk (pacing, anti-spam). */
        public int delayTicks = 4;

        /** Drop "junk" items (see {@link #junkItems}) while mining so the inventory fills slower. */
        public boolean dropJunk = true;

        /** Ticks between junk-drop attempts. */
        public int junkDropDelayTicks = 10;

        /**
         * Explicit denylist of item names to drop when {@link #dropJunk} is on.
         * A denylist (rather than "drop everything not kept") is used so tools, food,
         * shulkers and ender chests are never dropped by accident.
         */
        public List<String> junkItems = new ArrayList<>(List.of(
            "tuff", "cobblestone", "stone", "diorite", "granite", "andesite",
            "gravel", "dirt", "smooth_basalt"
        ));

        /**
         * Also drop "risky" foods (see {@link #riskyFoods}) as junk. Normal foods (bread, cooked
         * meat, carrots, golden apples) are NOT in that list, so they're kept for an AutoEat module.
         * Only applies when {@link #dropJunk} is on.
         */
        public boolean dropBadFood = true;

        /**
         * Foods treated as junk when {@link #dropBadFood} is on: harmful-effect / teleport foods and
         * the (non-stackable) stews. Kept out of the main {@link #junkItems} list so it can be toggled
         * separately.
         */
        public List<String> riskyFoods = new ArrayList<>(List.of(
            "rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish",
            "chicken", "chorus_fruit",
            "suspicious_stew", "mushroom_stew", "rabbit_stew", "beetroot_soup"
        ));

        /**
         * Items the miner wants to keep / will deposit during the (future) storage cycle.
         * Used today only by the status display.
         */
        public List<String> keepItems = new ArrayList<>(List.of(
            "deepslate", "cobbled_deepslate"
        ));

        /**
         * Only begin a storage cycle once the inventory is COMPLETELY packed — every keep stack at its
         * max count and no empty slots left — so shulkers fill with whole stacks. Turn off to use the
         * looser {@link #freeSlotsBeforeFull} margin instead (which can fire on partial stacks).
         */
        public boolean requireFullStacks = true;

        /**
         * Used only when {@link #requireFullStacks} is false. When the number of empty main-inventory
         * slots drops to this value or below, the inventory is considered full and the storage cycle
         * runs (or mining pauses if storage is disabled / no storage item is available).
         */
        public int freeSlotsBeforeFull = 1;

        // --- storage cycle ---

        /**
         * When the inventory fills, place a container beside the bot and deposit {@link #keepItems}
         * into it. If false, mining simply pauses when full.
         */
        public boolean storageEnabled = true;

        /**
         * After depositing, break the placed container and pick it up again (carry it). Off by
         * default: shulkers are left behind full (the simplest, most reliable behaviour). Turning
         * this on is mainly useful with an ender chest, whose contents are global.
         */
        public boolean breakAndCollect = false;

        /**
         * Extra exact item names to treat as a storage container. Any "*_shulker_box" is detected
         * automatically; add e.g. "ender_chest" here to use one.
         */
        public List<String> storageItems = new ArrayList<>();

        /** Ticks between individual deposit shift-clicks (lets the inventory cache settle). */
        public int depositDelayTicks = 2;

        /**
         * Ticks a single storage step (place / open / close / break / pickup) may wait before the
         * cycle is aborted and mining pauses. 20 ticks/sec.
         */
        public int storeStepTimeoutTicks = 300;

        /**
         * Safety cap: if a single chunk's clear runs this many ticks without finishing
         * (e.g. the box reaches into an unloaded chunk), force-advance to the next chunk.
         * 20 ticks/sec, so 3600 = 3 minutes.
         */
        public int maxClearTicks = 3600;

        // --- drop collection ---

        /**
         * Clear each chunk in clearBoxSize x clearBoxSize sub-boxes (full height) instead of the whole
         * 16x16 chunk in one go. The bot repositions between sub-boxes and walks back over the ground it
         * just dug, so it picks up the drops instead of leaving them on the floor to despawn. Smaller =
         * more thorough collection (more repositioning); 16 = the whole chunk at once (old behaviour).
         */
        public int clearBoxSize = 8;

        /**
         * Mine a bounded area in horizontal LAYERS this many blocks tall, TOP-DOWN: the bot clears this
         * slice across the whole area before dropping to the next, so it never tunnels one cell to minY
         * while the rest stands untouched. 1 = peel one block-level at a time across the area (most even,
         * most travel); larger = fewer full-area passes (faster) but it digs this many deep per spot first.
         * Set it to your full Y band (maxY-minY+1) for the old per-cell full-height behaviour. The
         * unbounded spiral always clears full-height per chunk (no infinite top to pre-sweep).
         */
        public int layerHeight = 1;

        /**
         * After each sub-box clears, do a VACUUM pass: walk over every kept item (in {@link #keepItems})
         * still lying on the ground in that box and pick it up before moving on, so nothing despawns.
         * Time-capped by {@link #collectMaxSeconds} and skips anything unreachable, so it can't hang.
         */
        public boolean collectDrops = true;

        /** Cap (seconds) on the vacuum pass per sub-box; past it, the bot gives up the stragglers and moves on. */
        public int collectMaxSeconds = 20;

        // --- deposit chests (haul filled shulkers to a base) ---
        // When on, the storage cycle collects each filled shulker (instead of leaving it behind) and the
        // bot carries them to fixed DEPOSIT chests at a base to drop them off, then visits a separate
        // SUPPLY chest to restock empty shulkers, for a near-unlimited run. Chest locations are set by
        // command (the proxy is headless, so there is no in-world crosshair to mark with).

        /** Master toggle: collect filled shulkers and haul them to the {@link #depositChests}. */
        public boolean depositToChests = false;

        /** DEPOSIT chest locations ("x y z" each) where FILLED shulkers are dropped off. */
        public List<String> depositChests = new ArrayList<>();

        /**
         * SUPPLY chest locations ("x y z" each) where EMPTY shulkers are taken from. Keep these SEPARATE
         * from the deposit chests. Only used when {@link #refillEmpties} is on.
         */
        public List<String> supplyChests = new ArrayList<>();

        // (No "deposit after N shulkers" knob: the ender chest is the field buffer, so a trip fires once it
        // runs out of empties - the batch size is just how many empty shulkers you keep stocked / refill.)

        /**
         * On a trip, after dropping off filled shulkers, visit a supply chest and pull a fresh batch of
         * empty shulkers. This is what makes a run effectively unlimited. Off = drop-offs only, and the
         * run ends when the empties you started with run out.
         */
        public boolean refillEmpties = true;

        /** How many empty shulkers to grab from a supply chest per trip. */
        public int emptiesPerTrip = 6;

        /** Don't walk to a chest farther than this many blocks (straight-line); past it the bot pauses. 0 = no limit. */
        public int maxDepositDistance = 1024;

        // --- cave handling ---
        // Relax ZenithProxy's pathfinder fall/jump limits while the module is active so the bot drops
        // into caves to reach blocks instead of detouring or stalling. These are pushed into
        // CONFIG.client.extra.pathfinder.* on enable and restored on disable. Lava is still never
        // walked/fallen into (the pathfinder costs it out). Balanced defaults; raise for more reach.

        /** Master toggle for the relaxed cave movement profile below. */
        public boolean caveHandling = true;

        /** Tallest no-water drop the bot will take (pathfinder maxFallHeightNoWater; stock default 3). */
        public int maxFallHeight = 20;

        /** Allow placing a block mid-jump to bridge a parkour gap (pathfinder allowParkourPlace). */
        public boolean allowParkourPlace = true;

        /** Allow stepping diagonally downward into caves (pathfinder allowDiagonalDescend). */
        public boolean allowDiagonalDescend = true;

        /** Allow running parkour jumps across gaps (pathfinder allowParkour). Off for Balanced. */
        public boolean allowParkour = false;

        /** Allow stepping diagonally upward (pathfinder allowDiagonalAscend). Off for Balanced. */
        public boolean allowDiagonalAscend = false;

        // --- safety / lifecycle ---

        /**
         * When the run ends — the finite area is fully cleared, or storage is exhausted (no shulkers
         * left / chest full) — also disconnect the bot from the server. Handy for an AFK run so it
         * leaves cleanly once everything is packed. Off = just stop and stay connected.
         */
        public boolean autoDisconnect = false;

        /** Pause mining while another (non-self) player is within {@link #playerPauseRange} blocks. */
        public boolean pauseOnPlayer = true;

        /** Range in blocks for {@link #pauseOnPlayer}. */
        public double playerPauseRange = 48.0;

        // --- tool restock ---

        /**
         * When the bot runs out of fresh tools, run a restock cycle: place the {@link #restockSourceItem}
         * (an ender chest), pull out the tool-shulker (a shulker that contains a fresh tool), place it,
         * take a fresh tool, break and recover the shulker, put it back in the ender chest, then recover
         * the chest. Lets one ender-chest slot hold a whole shulker of spare pickaxes.
         *
         * SETUP: the tool-shulker must be a DIFFERENT colour from any empty loot shulkers you carry —
         * the bot places shulkers by item type, so a unique colour guarantees it places the right one.
         */
        public boolean restockTools = false;

        /** The source container the tool-shulker lives in (placed/opened during a restock). */
        public String restockSourceItem = "ender_chest";

        /** Remaining-durability threshold below which a tool is considered spent and gets restocked. */
        public int restockBelowDurability = 60;

        /** Item-name suffix identifying the tool to restock (e.g. "pickaxe", "shovel"). */
        public String restockToolKeyword = "pickaxe";

        /**
         * Also keep a fresh SHOVEL alongside the main restock tool, so gravel/sand mines fast instead of
         * being slogged through with a pickaxe. The tool-shulker must also hold spare shovels. Off, or when
         * {@link #restockToolKeyword} is already "shovel", this does nothing.
         */
        public boolean alsoRestockShovel = true;
    }
}
