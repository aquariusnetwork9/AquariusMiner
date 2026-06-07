package com.shallowplague.aquariusminer.command;

import com.shallowplague.aquariusminer.module.AquariusMinerModule;
import com.shallowplague.aquariusminer.AquariusMinerConfig.AreaAnchor;
import com.shallowplague.aquariusminer.AquariusMinerConfig.AreaMode;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static com.shallowplague.aquariusminer.AquariusMinerPlugin.PLUGIN_CONFIG;

public class AquariusMinerCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("aquariusminer")
            .category(CommandCategory.MODULE)
            .aliases("aqm")
            .description("""
                AFK quarry miner. Clears one chunk at a time within a Y band, spiralling
                outward from where the bot is when enabled. Short alias: .aqm
                """)
            .usageLines(
                "on/off",
                "minY <y>",
                "maxY <y>",
                "here <length> <width>  (length forward, width right, from bot pos+facing)",
                "area unlimited",
                "area chunks <width> <length>",
                "area anchor <center/corner>",
                "area corners <x1> <z1> <x2> <z2>",
                "keep add <item> | remove <item> | list | clear | reset",
                "cave on/off",
                "fullstacks on/off",
                "badfood on/off",
                "autodc on/off",
                "pauseplayer on/off",
                "restock on/off",
                "shovel on/off",
                "food on/off | food count <n> | food min <n>",
                "clearbox <size>",
                "layer <blocks>",
                "verify on/off | verify retries <n>",
                "collect on/off | collect seconds <n>",
                "scan",
                "deposit on/off",
                "deposit chest add <x> <y> <z> | clear",
                "deposit supply add <x> <y> <z> | clear",
                "deposit refill on/off | empties <n> | maxdist <blocks>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("aquariusminer")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.miner.enabled = getToggle(c, "toggle");
                MODULE.get(AquariusMinerModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Aquarius Miner " + toggleStrCaps(PLUGIN_CONFIG.miner.enabled));
            }))
            .then(literal("minY").then(argument("y", integer()).executes(c -> {
                int y = getInteger(c, "y");
                if (y > PLUGIN_CONFIG.miner.maxY) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("minY must be <= maxY (" + PLUGIN_CONFIG.miner.maxY + ")");
                    return ERROR;
                }
                PLUGIN_CONFIG.miner.minY = y;
                c.getSource().getEmbed().title("Min Y Set");
                return OK;
            })))
            .then(literal("maxY").then(argument("y", integer()).executes(c -> {
                int y = getInteger(c, "y");
                if (y < PLUGIN_CONFIG.miner.minY) {
                    c.getSource().getEmbed()
                        .title("Error")
                        .description("maxY must be >= minY (" + PLUGIN_CONFIG.miner.minY + ")");
                    return ERROR;
                }
                PLUGIN_CONFIG.miner.maxY = y;
                c.getSource().getEmbed().title("Max Y Set");
                return OK;
            })))
            // Set the mining box from the bot's CURRENT position + facing: <length> chunks forward (the way
            // the bot looks, snapped to a cardinal) x <width> chunks to its right. Snapshots pos+yaw NOW into an
            // absolute Corners box, so it stays put even if the bot turns/walks later. Re-anchors live if running.
            .then(literal("here")
                .then(argument("length", integer(1))
                    .then(argument("width", integer(1)).executes(c -> {
                        if (!Proxy.getInstance().isConnected()) {
                            c.getSource().getEmbed().title("Error")
                                .description("Bot isn't connected - can't read its position/facing.");
                            return ERROR;
                        }
                        int len = getInteger(c, "length");
                        int wid = getInteger(c, "width");
                        var pc = CACHE.getPlayerCache();
                        int px = (int) Math.floor(pc.getX());
                        int pz = (int) Math.floor(pc.getZ());
                        double yaw = Math.toRadians(pc.getYaw());
                        double lx = -Math.sin(yaw), lz = Math.cos(yaw);
                        int fdx, fdz;                                  // forward unit vector, snapped to a cardinal
                        if (Math.abs(lx) >= Math.abs(lz)) { fdx = lx >= 0 ? 1 : -1; fdz = 0; }
                        else { fdx = 0; fdz = lz >= 0 ? 1 : -1; }
                        int rdx = -fdz, rdz = fdx;                     // right = forward rotated 90 deg clockwise
                        int cx0 = px >> 4, cz0 = pz >> 4;              // bot's chunk = the near corner
                        int cxB = cx0 + fdx * (len - 1) + rdx * (wid - 1);
                        int czB = cz0 + fdz * (len - 1) + rdz * (wid - 1);
                        int cxLo = Math.min(cx0, cxB), cxHi = Math.max(cx0, cxB);
                        int czLo = Math.min(cz0, czB), czHi = Math.max(cz0, czB);
                        var m = PLUGIN_CONFIG.miner;
                        m.areaMode = AreaMode.Corners;
                        m.corner1X = cxLo << 4;          m.corner1Z = czLo << 4;
                        m.corner2X = (cxHi << 4) + 15;   m.corner2Z = (czHi << 4) + 15;
                        boolean live = m.enabled;
                        if (live) MODULE.get(AquariusMinerModule.class).requestReanchor();
                        c.getSource().getEmbed()
                            .title("Area: " + len + " x " + wid + " chunks from here (" + (len * wid) + " total)")
                            .description(len + " forward (" + cardinal(fdx, fdz) + "), "
                                + wid + " right (" + cardinal(rdx, rdz) + ")\n"
                                + "X[" + (cxLo << 4) + ".." + ((cxHi << 4) + 15) + "] "
                                + "Z[" + (czLo << 4) + ".." + ((czHi << 4) + 15) + "], Y "
                                + m.minY + ".." + m.maxY + "\n"
                                + (live ? "Re-anchored live - mining the new box now." : "Run `.aqm on` to start."));
                        return OK;
                    }))))
            .then(literal("keep")
                .then(literal("add").then(argument("item", word()).executes(c -> {
                    String item = getString(c, "item").toLowerCase();
                    if (PLUGIN_CONFIG.miner.keepItems.contains(item)) {
                        c.getSource().getEmbed().title("Already kept: " + item);
                    } else {
                        PLUGIN_CONFIG.miner.keepItems.add(item);
                        c.getSource().getEmbed().title("Keeping " + item)
                            .description("Now keeping: " + String.join(", ", PLUGIN_CONFIG.miner.keepItems));
                    }
                })))
                .then(literal("remove").then(argument("item", word()).executes(c -> {
                    String item = getString(c, "item").toLowerCase();
                    boolean removed = PLUGIN_CONFIG.miner.keepItems.remove(item);
                    c.getSource().getEmbed().title(removed ? "Stopped keeping " + item : "Not in keep list: " + item)
                        .description("Now keeping: " + String.join(", ", PLUGIN_CONFIG.miner.keepItems));
                })))
                .then(literal("list").executes(c -> {
                    var keep = PLUGIN_CONFIG.miner.keepItems;
                    c.getSource().getEmbed().title("Keep items (" + keep.size() + ")")
                        .description(keep.isEmpty() ? "(none)" : String.join(", ", keep));
                }))
                .then(literal("clear").executes(c -> {
                    PLUGIN_CONFIG.miner.keepItems.clear();
                    c.getSource().getEmbed().title("Keep list cleared")
                        .description("Nothing will be deposited - add ores/blocks with `.aqm keep add <item>`.");
                }))
                .then(literal("reset").executes(c -> {
                    PLUGIN_CONFIG.miner.keepItems.clear();
                    PLUGIN_CONFIG.miner.keepItems.add("deepslate");
                    PLUGIN_CONFIG.miner.keepItems.add("cobbled_deepslate");
                    c.getSource().getEmbed().title("Keep list reset")
                        .description("Now keeping: " + String.join(", ", PLUGIN_CONFIG.miner.keepItems));
                })))
            .then(literal("area")
                .then(literal("unlimited").executes(c -> {
                    PLUGIN_CONFIG.miner.areaMode = AreaMode.Unlimited;
                    c.getSource().getEmbed().title("Area: Unlimited");
                }))
                .then(literal("chunks")
                    .then(argument("width", integer(1))
                        .then(argument("length", integer(1)).executes(c -> {
                            PLUGIN_CONFIG.miner.areaMode = AreaMode.ChunksFromStart;
                            PLUGIN_CONFIG.miner.areaWidthChunks = getInteger(c, "width");
                            PLUGIN_CONFIG.miner.areaLengthChunks = getInteger(c, "length");
                            c.getSource().getEmbed().title("Area: " + getInteger(c, "width")
                                + " x " + getInteger(c, "length") + " chunks from start");
                        }))))
                .then(literal("anchor")
                    .then(literal("center").executes(c -> {
                        PLUGIN_CONFIG.miner.areaAnchor = AreaAnchor.Center;
                        c.getSource().getEmbed().title("Area anchor: Center");
                    }))
                    .then(literal("corner").executes(c -> {
                        PLUGIN_CONFIG.miner.areaAnchor = AreaAnchor.Corner;
                        c.getSource().getEmbed().title("Area anchor: Corner");
                    })))
                .then(literal("corners")
                    .then(argument("x1", integer())
                        .then(argument("z1", integer())
                            .then(argument("x2", integer())
                                .then(argument("z2", integer()).executes(c -> {
                                    var m = PLUGIN_CONFIG.miner;
                                    m.areaMode = AreaMode.Corners;
                                    m.corner1X = getInteger(c, "x1");
                                    m.corner1Z = getInteger(c, "z1");
                                    m.corner2X = getInteger(c, "x2");
                                    m.corner2Z = getInteger(c, "z2");
                                    c.getSource().getEmbed().title("Area: corners ("
                                        + m.corner1X + ", " + m.corner1Z + ") -> ("
                                        + m.corner2X + ", " + m.corner2Z + ")");
                                })))))))
            .then(literal("cave").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.miner.caveHandling = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Cave handling " + toggleStrCaps(PLUGIN_CONFIG.miner.caveHandling));
            })))
            .then(literal("fullstacks").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.miner.requireFullStacks = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Require full stacks " + toggleStrCaps(PLUGIN_CONFIG.miner.requireFullStacks));
            })))
            .then(literal("badfood").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.miner.dropBadFood = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Drop bad food " + toggleStrCaps(PLUGIN_CONFIG.miner.dropBadFood));
            })))
            .then(literal("autodc").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.miner.autoDisconnect = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Auto-disconnect " + toggleStrCaps(PLUGIN_CONFIG.miner.autoDisconnect));
            })))
            .then(literal("pauseplayer").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.miner.pauseOnPlayer = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Pause on player " + toggleStrCaps(PLUGIN_CONFIG.miner.pauseOnPlayer));
            })))
            .then(literal("restock").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.miner.restockTools = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Tool restock " + toggleStrCaps(PLUGIN_CONFIG.miner.restockTools));
            })))
            .then(literal("clearbox").then(argument("size", integer(1)).executes(c -> {
                PLUGIN_CONFIG.miner.clearBoxSize = getInteger(c, "size");
                c.getSource().getEmbed().title("Clear-box size: " + getInteger(c, "size")
                    + " (smaller = better drop collection)");
            })))
            .then(literal("layer").then(argument("blocks", integer(1)).executes(c -> {
                PLUGIN_CONFIG.miner.layerHeight = getInteger(c, "blocks");
                c.getSource().getEmbed().title("Layer height: " + getInteger(c, "blocks")
                    + " (top-down; 1 = peel one level across the whole area before descending)");
            })))
            .then(literal("collect")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.miner.collectDrops = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("Collect drops " + toggleStrCaps(PLUGIN_CONFIG.miner.collectDrops));
                }))
                .then(literal("seconds").then(argument("n", integer(1)).executes(c -> {
                    PLUGIN_CONFIG.miner.collectMaxSeconds = getInteger(c, "n");
                    c.getSource().getEmbed().title("Collect cap: " + getInteger(c, "n") + "s per sub-box");
                }))))
            .then(literal("shovel").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.miner.alsoRestockShovel = getToggle(c, "toggle");
                c.getSource().getEmbed().title("Also restock shovel " + toggleStrCaps(PLUGIN_CONFIG.miner.alsoRestockShovel));
            })))
            .then(literal("food")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.miner.restockFood = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("Food restock " + toggleStrCaps(PLUGIN_CONFIG.miner.restockFood));
                }))
                .then(literal("count").then(argument("n", integer(1)).executes(c -> {
                    PLUGIN_CONFIG.miner.foodRestockCount = getInteger(c, "n");
                    c.getSource().getEmbed().title("Food restock target: " + getInteger(c, "n"));
                })))
                .then(literal("min").then(argument("n", integer(1)).executes(c -> {
                    PLUGIN_CONFIG.miner.minFoodOnHand = getInteger(c, "n");
                    c.getSource().getEmbed().title("Restock food when carried food < " + getInteger(c, "n"));
                }))))
            .then(literal("verify")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.miner.verifyClears = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("Verify clears " + toggleStrCaps(PLUGIN_CONFIG.miner.verifyClears));
                }))
                .then(literal("retries").then(argument("n", integer(0)).executes(c -> {
                    PLUGIN_CONFIG.miner.clearRetries = getInteger(c, "n");
                    c.getSource().getEmbed().title("Sub-box clear retries: " + getInteger(c, "n"));
                }))))
            .then(literal("scan").executes(c -> {
                MODULE.get(AquariusMinerModule.class).printScan();
                c.getSource().getEmbed().title("Resource scan printed to console + in-game alert");
            }))
            .then(literal("deposit")
                .then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.miner.depositToChests = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Deposit to chests " + toggleStrCaps(PLUGIN_CONFIG.miner.depositToChests));
                }))
                .then(literal("chest")
                    .then(literal("add").then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer()).executes(c -> {
                        PLUGIN_CONFIG.miner.depositChests.add(getInteger(c, "x") + " " + getInteger(c, "y") + " " + getInteger(c, "z"));
                        c.getSource().getEmbed().title("Deposit chest added (" + PLUGIN_CONFIG.miner.depositChests.size() + " total)");
                    })))))
                    .then(literal("clear").executes(c -> {
                        PLUGIN_CONFIG.miner.depositChests.clear();
                        c.getSource().getEmbed().title("Deposit chests cleared");
                    })))
                .then(literal("supply")
                    .then(literal("add").then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer()).executes(c -> {
                        PLUGIN_CONFIG.miner.supplyChests.add(getInteger(c, "x") + " " + getInteger(c, "y") + " " + getInteger(c, "z"));
                        c.getSource().getEmbed().title("Supply chest added (" + PLUGIN_CONFIG.miner.supplyChests.size() + " total)");
                    })))))
                    .then(literal("clear").executes(c -> {
                        PLUGIN_CONFIG.miner.supplyChests.clear();
                        c.getSource().getEmbed().title("Supply chests cleared");
                    })))
                .then(literal("refill").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.miner.refillEmpties = getToggle(c, "toggle");
                    c.getSource().getEmbed().title("Refill empties " + toggleStrCaps(PLUGIN_CONFIG.miner.refillEmpties));
                })))
                .then(literal("empties").then(argument("n", integer(1)).executes(c -> {
                    PLUGIN_CONFIG.miner.emptiesPerTrip = getInteger(c, "n");
                    c.getSource().getEmbed().title("Empties per trip: " + getInteger(c, "n"));
                })))
                .then(literal("maxdist").then(argument("blocks", integer(0)).executes(c -> {
                    PLUGIN_CONFIG.miner.maxDepositDistance = getInteger(c, "blocks");
                    c.getSource().getEmbed().title("Max deposit distance: " + getInteger(c, "blocks") + " blocks");
                }))));
    }

    /** Cardinal name for a unit chunk-direction (one axis zero): -Z north, +Z south, +X east, -X west. */
    private static String cardinal(int dx, int dz) {
        if (dz < 0) return "north";
        if (dz > 0) return "south";
        if (dx > 0) return "east";
        if (dx < 0) return "west";
        return "?";
    }

    private static String areaDescription() {
        var m = PLUGIN_CONFIG.miner;
        return switch (m.areaMode) {
            case Unlimited -> "Unlimited";
            case ChunksFromStart -> m.areaWidthChunks + " x " + m.areaLengthChunks + " chunks (" + m.areaAnchor + ")";
            case Corners -> "(" + m.corner1X + ", " + m.corner1Z + ") -> (" + m.corner2X + ", " + m.corner2Z + ")";
        };
    }

    @Override
    public void defaultEmbed(Embed embed) {
        var module = MODULE.get(AquariusMinerModule.class);
        embed
            .primaryColor()
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.miner.enabled))
            .addField("State", module.statusLine())
            .addField("Y Band", PLUGIN_CONFIG.miner.minY + " .. " + PLUGIN_CONFIG.miner.maxY)
            .addField("Area", areaDescription())
            .addField("Keep", String.join(", ", PLUGIN_CONFIG.miner.keepItems))
            .addField("Drop Junk", toggleStr(PLUGIN_CONFIG.miner.dropJunk)
                + (PLUGIN_CONFIG.miner.dropBadFood ? " (+bad food)" : ""))
            .addField("Cave Handling", toggleStr(PLUGIN_CONFIG.miner.caveHandling)
                + (PLUGIN_CONFIG.miner.caveHandling ? " (fall " + PLUGIN_CONFIG.miner.maxFallHeight + ")" : ""))
            .addField("Storage", toggleStr(PLUGIN_CONFIG.miner.storageEnabled)
                + (PLUGIN_CONFIG.miner.requireFullStacks ? " (full stacks)" : " (margin " + PLUGIN_CONFIG.miner.freeSlotsBeforeFull + ")")
                + (PLUGIN_CONFIG.miner.breakAndCollect ? ", break & collect" : ", leave"))
            .addField("Restock", PLUGIN_CONFIG.miner.restockTools
                ? "on (" + PLUGIN_CONFIG.miner.restockToolKeyword + " < " + PLUGIN_CONFIG.miner.restockBelowDurability + ")"
                    + (PLUGIN_CONFIG.miner.alsoRestockShovel ? " +shovel" : "")
                : "off")
            .addField("Food", PLUGIN_CONFIG.miner.restockFood
                ? "on (restock to " + PLUGIN_CONFIG.miner.foodRestockCount + " when < " + PLUGIN_CONFIG.miner.minFoodOnHand + ")"
                : "off")
            .addField("Collection", "clear-box " + PLUGIN_CONFIG.miner.clearBoxSize
                + ", layer " + PLUGIN_CONFIG.miner.layerHeight
                + ", vacuum " + toggleStr(PLUGIN_CONFIG.miner.collectDrops)
                + ", verify " + (PLUGIN_CONFIG.miner.verifyClears ? "on (" + PLUGIN_CONFIG.miner.clearRetries + " retries)" : "off"))
            .addField("Deposit", PLUGIN_CONFIG.miner.depositToChests
                ? "on (" + PLUGIN_CONFIG.miner.depositChests.size() + " deposit, "
                    + PLUGIN_CONFIG.miner.supplyChests.size() + " supply"
                    + (PLUGIN_CONFIG.miner.refillEmpties ? "; refill " + PLUGIN_CONFIG.miner.emptiesPerTrip : "") + ")"
                : "off")
            .addField("Safety", "player-pause " + toggleStr(PLUGIN_CONFIG.miner.pauseOnPlayer)
                + " (" + (int) PLUGIN_CONFIG.miner.playerPauseRange + "b), auto-dc " + toggleStr(PLUGIN_CONFIG.miner.autoDisconnect));
    }
}
