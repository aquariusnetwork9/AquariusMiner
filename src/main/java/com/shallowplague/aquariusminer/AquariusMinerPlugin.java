package com.shallowplague.aquariusminer;

import com.shallowplague.aquariusminer.command.AquariusMinerCommand;
import com.shallowplague.aquariusminer.module.AquariusMinerModule;
import com.shallowplague.aquariusminer.module.PacketSnifferModule;
import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Aquarius Miner — AFK bulk single-block miner (quarries a chunk, then spirals to the next) for ZenithProxy",
    url = "https://github.com/aquariusnetwork9/AquariusMiner",
    authors = {"Shallowplague"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class AquariusMinerPlugin implements ZenithProxyPlugin {
    // public static for simple access from the module and command
    public static AquariusMinerConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        LOG.info("Aquarius Miner plugin loading...");
        // config must exist before the module or command reads it
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, AquariusMinerConfig.class);
        pluginAPI.registerModule(new AquariusMinerModule());
        pluginAPI.registerModule(new PacketSnifferModule());
        pluginAPI.registerCommand(new AquariusMinerCommand());
        LOG.info("Aquarius Miner plugin loaded!");
    }
}
