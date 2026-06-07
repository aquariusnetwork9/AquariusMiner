package com.shallowplague.aquariusminer.module;

import com.shallowplague.aquariusminer.AquariusMinerPlugin;
import com.zenith.network.codec.PacketHandlerCodec;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.shallowplague.aquariusminer.AquariusMinerPlugin.PLUGIN_CONFIG;

/**
 * Packet sniffer codec. Subclasses ZenithProxy's {@link PacketHandlerCodec} (its all-args constructor is
 * {@code protected}, so a subclass can call it) and overrides the inbound/outbound hooks to OBSERVE every
 * packet without modifying it - it always returns the packet unchanged. It is registered into the CLIENT
 * registry (bot &lt;-&gt; remote server) by {@link PacketSnifferModule}, so {@code handleInbound} = packets
 * received FROM the server and {@code handleOutgoing} = packets the bot SENDS to the server.
 *
 * Each matching packet is appended to a {@link Buffer rolling buffer} (most-recent-N lines), and optionally
 * logged live. Matching is by an optional scenario {@link #TEMPLATES template} plus an optional substring
 * filter, both against the packet's class simple name.
 */
public class PacketSniffer extends PacketHandlerCodec {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Scenario templates: name -> lower-case substrings matched against the packet class simple name. */
    static final Map<String, List<String>> TEMPLATES = Map.of(
        "movement",  List.of("playerposition", "moveplayer", "moveentity", "teleport", "rotate", "velocity", "vehiclemove", "setentitymotion"),
        "combat",    List.of("damage", "hurt", "interact", "sethealth", "entityevent", "removeentities", "attack"),
        "inventory", List.of("container", "setslot", "setcontent", "openscreen", "carrieditem", "creativemodeslot", "setheldslot"),
        "blocks",    List.of("blockupdate", "blockdestruction", "playeraction", "useitem", "blockchangedack", "blockevent", "sectionblocks"),
        "chunks",    List.of("levelchunk", "forgetlevelchunk", "chunkcachecenter", "chunkcacheradius", "lightupdate"),
        "chat",      List.of("chat", "systemchat", "disguised", "playerinfo"),
        "entities",  List.of("addentity", "removeentities", "setentitydata", "moveentity", "teleportentity", "entityevent"),
        "keepalive", List.of("keepalive", "ping", "pong")
    );

    public static List<String> templateNames() {
        List<String> names = new ArrayList<>(TEMPLATES.keySet());
        names.sort(String::compareTo);
        return names;
    }

    public static List<String> templateSubs(String name) {
        return TEMPLATES.getOrDefault(name, List.of());
    }

    /**
     * Resolve a user-typed template name to a canonical one, forgivingly: exact match, else a unique
     * singular/prefix match (so "block" -&gt; "blocks", "chunk" -&gt; "chunks", "entit" -&gt; "entities").
     * Returns null if unknown or ambiguous (e.g. "c" matches chat/chunks/combat).
     */
    public static String resolveTemplate(String input) {
        if (input == null) return null;
        String n = input.trim().toLowerCase();
        if (n.isEmpty()) return null;
        if (TEMPLATES.containsKey(n)) return n;
        String match = null;
        for (String key : TEMPLATES.keySet()) {
            if (key.startsWith(n) || n.startsWith(key)) {   // "block" -> "blocks", "blocksX" -> "blocks"
                if (match != null && !match.equals(key)) return null;   // ambiguous
                match = key;
            }
        }
        return match;
    }

    /** Thread-safe rolling buffer: keeps only the most recent {@code cap} lines (oldest evicted). */
    public static final class Buffer {
        private final ArrayDeque<String> lines = new ArrayDeque<>();

        synchronized void add(String line, int cap) {
            lines.addLast(line);
            int max = Math.max(1, cap);
            while (lines.size() > max) lines.pollFirst();
        }

        public synchronized List<String> snapshot() { return new ArrayList<>(lines); }
        public synchronized int size() { return lines.size(); }
        public synchronized void clear() { lines.clear(); }
    }

    private final Buffer buffer;

    public PacketSniffer(Buffer buffer) {
        // priority MAX_VALUE: invoked first for inbound (sees packets before other codecs may drop them).
        super(Integer.MAX_VALUE, "aquarius-sniffer", new java.util.EnumMap<>(ProtocolState.class), s -> true);
        this.buffer = buffer;
    }

    @Override
    public <P extends Packet, S extends Session> P handleInbound(P packet, S session) {
        record("IN ", packet);
        return packet;   // observe only - never alter the pipeline
    }

    @Override
    public <P extends Packet, S extends Session> P handleOutgoing(P packet, S session) {
        record("OUT", packet);
        return packet;
    }

    private void record(String dir, Packet packet) {
        var cfg = PLUGIN_CONFIG.miner;
        if (dir.charAt(0) == 'I' && "out".equalsIgnoreCase(cfg.sniffDir)) return;
        if (dir.charAt(0) == 'O' && "in".equalsIgnoreCase(cfg.sniffDir)) return;
        String name = packet.getClass().getSimpleName();
        if (!passes(name.toLowerCase())) return;
        String line = LocalTime.now().format(TS) + "  " + dir + "  " + (cfg.sniffBody ? packet : name);
        buffer.add(line, cfg.sniffBufferLines);
        if (cfg.sniffLive) AquariusMinerPlugin.LOG.info("[sniff] {}", line);
    }

    /** True if a packet (lower-case class simple name) passes the active template AND substring filter. */
    static boolean passes(String nameLower) {
        var cfg = PLUGIN_CONFIG.miner;
        String tmpl = cfg.sniffTemplate == null ? "" : cfg.sniffTemplate.trim().toLowerCase();
        if (!tmpl.isEmpty() && !tmpl.equals("all")) {
            List<String> subs = TEMPLATES.get(tmpl);
            if (subs != null) {
                boolean any = false;
                for (String s : subs) if (nameLower.contains(s)) { any = true; break; }
                if (!any) return false;
            }
        }
        String f = cfg.sniffFilter == null ? "" : cfg.sniffFilter.trim().toLowerCase();
        return f.isEmpty() || nameLower.contains(f);
    }
}
