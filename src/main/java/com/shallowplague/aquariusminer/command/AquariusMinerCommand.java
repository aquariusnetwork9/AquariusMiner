package com.shallowplague.aquariusminer.command;

import com.shallowplague.aquariusminer.module.AquariusMinerModule;
import com.shallowplague.aquariusminer.AquariusMinerConfig.AreaAnchor;
import com.shallowplague.aquariusminer.AquariusMinerConfig.AreaMode;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
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
            .description("""
                AFK quarry miner. Clears one chunk at a time within a Y band, spiralling
                outward from where the bot is when enabled.
                """)
            .usageLines(
                "on/off",
                "minY <y>",
                "maxY <y>",
                "area unlimited",
                "area chunks <width> <length>",
                "area anchor <center/corner>",
                "area corners <x1> <z1> <x2> <z2>",
                "cave on/off",
                "fullstacks on/off",
                "badfood on/off",
                "autodc on/off",
                "pauseplayer on/off",
                "restock on/off",
                "clearbox <size>",
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
                : "off")
            .addField("Collection", "clear-box " + PLUGIN_CONFIG.miner.clearBoxSize)
            .addField("Deposit", PLUGIN_CONFIG.miner.depositToChests
                ? "on (" + PLUGIN_CONFIG.miner.depositChests.size() + " deposit, "
                    + PLUGIN_CONFIG.miner.supplyChests.size() + " supply"
                    + (PLUGIN_CONFIG.miner.refillEmpties ? "; refill " + PLUGIN_CONFIG.miner.emptiesPerTrip : "") + ")"
                : "off")
            .addField("Safety", "player-pause " + toggleStr(PLUGIN_CONFIG.miner.pauseOnPlayer)
                + " (" + (int) PLUGIN_CONFIG.miner.playerPauseRange + "b), auto-dc " + toggleStr(PLUGIN_CONFIG.miner.autoDisconnect));
    }
}
