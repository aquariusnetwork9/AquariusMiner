package com.shallowplague.aquariusminer.module;

import com.github.rfresh2.EventConsumer;
import com.shallowplague.aquariusminer.AquariusMinerPlugin;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import com.zenith.network.codec.PacketHandlerCodec;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.shallowplague.aquariusminer.AquariusMinerPlugin.PLUGIN_CONFIG;

/**
 * Packet sniffer module. A SEPARATE toggle from the miner so you can capture packets regardless of whether
 * the bot is mining. Enabling it registers {@link PacketSniffer} into ZenithProxy's CLIENT registry (bot
 * &lt;-&gt; remote server) via {@link #registerClientPacketHandlerCodec()}; disabling unregisters it (so
 * there's zero per-packet overhead when off). The rolling buffer survives across enable/disable, so a
 * {@link #dump()} still works after the sniffer is turned off.
 */
public class PacketSnifferModule extends Module {

    // The buffer lives on the module (persists across enable cycles); each codec instance writes into it.
    private final PacketSniffer.Buffer buffer = new PacketSniffer.Buffer();

    // >0 while a timed verbose capture is running (epoch millis at which to auto-stop + dump).
    private long captureDeadlineMs = 0;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.miner.sniffEnabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(of(ClientBotTick.class, this::onTick));
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return new PacketSniffer(buffer);
    }

    @Override
    public void onDisable() {
        captureDeadlineMs = 0;
    }

    private void onTick(ClientBotTick event) {
        if (captureDeadlineMs > 0 && System.currentTimeMillis() >= captureDeadlineMs) {
            captureDeadlineMs = 0;
            info("Timed packet capture finished - dumping {} lines.", buffer.size());
            dump();
            PLUGIN_CONFIG.miner.sniffEnabled = false;
            syncEnabledFromConfig();   // disable -> unregister the codec
        }
    }

    /** Start a verbose timed capture: live + body for {@code seconds}, then auto-dump and stop. */
    public void startTimed(int seconds) {
        var cfg = PLUGIN_CONFIG.miner;
        cfg.sniffLive = true;
        cfg.sniffBody = true;
        cfg.sniffEnabled = true;
        buffer.clear();
        captureDeadlineMs = System.currentTimeMillis() + seconds * 1000L;
        syncEnabledFromConfig();       // enable -> register the codec
        info("Capturing packets verbosely for {}s (filter='{}', template='{}').",
            seconds, cfg.sniffFilter, cfg.sniffTemplate);
    }

    /** Print the rolling buffer to the console/terminal log (where ZenithProxy packet logs go). */
    public void dump() {
        List<String> lines = buffer.snapshot();
        if (lines.isEmpty()) { info("Packet sniffer buffer is empty."); return; }
        AquariusMinerPlugin.LOG.info("--- Aquarius packet sniffer dump ({} lines) ---", lines.size());
        for (String l : lines) AquariusMinerPlugin.LOG.info("[sniff] {}", l);
        AquariusMinerPlugin.LOG.info("--- end dump ---");
        info("Dumped {} packet lines to console.", lines.size());
    }

    public void clearBuffer() { buffer.clear(); }
    public int bufferSize() { return buffer.size(); }
}
