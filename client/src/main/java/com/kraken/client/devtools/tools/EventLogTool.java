package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.ClientState;
import com.kraken.api.lua.InvItem;
import com.kraken.api.lua.Inventory;
import com.kraken.api.lua.events.Events;
import com.kraken.api.lua.events.StatUpdate;
import com.kraken.client.devtools.Category;
import com.kraken.client.devtools.DevTool;
import com.kraken.client.devtools.ToolOption;
import com.kraken.client.devtools.view.LogView;
import com.kraken.client.devtools.view.ToolView;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * A live log of the API's events. Subscribes only while its window is open.
 */
final class EventLogTool extends DevTool<List<String>> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_PENDING = 1_000;
    private static final int MAX_ITEMS = 8;

    private final ToolOption gameState = option("gameState", "Game state changes", true);
    private final ToolOption npcs = option("npcs", "NPC load / unload", true);
    private final ToolOption inventories = option("inventories", "Inventory transmits", true);
    private final ToolOption stats = option("stats", "Stat transmits", true);
    private final ToolOption vars = option("vars", "Var transmits", false);
    private final ToolOption varbits = option("varbits", "Varbit updates", false);
    private final ToolOption ticks = option("ticks", "Game ticks", false);
    private final ToolOption map = option("map", "Map rebuilds", true);
    private final ToolOption locs = option("locs", "Loc load / unload", false);
    private final ToolOption resize = option("resize", "Screen resizes", true);
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final List<Integer> subscriptions = new ArrayList<>();
    private final List<Integer> locSubscriptions = new ArrayList<>();
    private Events events;

    EventLogTool() {
        super("events", "Event log", Category.DIAGNOSTICS, MaterialDesignL.LIGHTNING_BOLT_OUTLINE,
                "A live log of API events (game state, NPCs, inventories, stats, vars, varbits, ticks, map rebuilds, locs, resizes) as they're seen.");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected void onStart(KrakenApi api) {
        events = api.lua().events();
        ClientState state = api.lua().clientState();
        subscriptions.add(events.subscribe(Events.ON_GAME_STATE_CHANGED, trigger -> {
            if (gameState.isOn()) {
                add("Game state  " + trigger + " (" + trigger.value() + ")");
            }
        }));
        subscriptions.add(events.subscribe(Events.ON_NPC_LOAD, (name, index) -> npc("NPC loaded", name, index)));
        subscriptions.add(events.subscribe(Events.ON_NPC_UNLOAD, (name, index) -> npc("NPC unloaded", name, index)));
        subscriptions.add(events.subscribe(Events.ON_INV_TRANSMIT, updated -> {
            if (inventories.isOn()) {
                updated.forEach(inventory -> add(inventory(inventory)));
            }
        }));
        subscriptions.add(events.subscribe(Events.ON_STAT_TRANSMIT, updated -> {
            if (stats.isOn()) {
                add("Stats  " + updated.stream().map(EventLogTool::stat).collect(Collectors.joining(" · ")));
            }
        }));
        subscriptions.add(events.subscribe(Events.ON_VAR_TRANSMIT, ids -> {
            if (vars.isOn()) {
                StringBuilder line = new StringBuilder("Varps  ");
                for (int i = 0; i < ids.length; i++) {
                    line.append(i == 0 ? "" : ", ").append(ids[i]).append(" = ").append(state.getVarp(ids[i]));
                }
                add(line.toString());
            }
        }));
        subscriptions.add(events.subscribe(Events.ON_VARBIT_UPDATE, (id, value) -> {
            if (varbits.isOn()) {
                add("Varbit  " + id + " = " + value);
            }
        }));
        subscriptions.add(events.subscribe(Events.ON_GAME_TICK, () -> {
            if (ticks.isOn()) {
                add("Game tick  " + state.getTickCount());
            }
        }));
        subscriptions.add(events.subscribe(Events.ON_MAP_REBUILD_PRE, () -> {
            if (map.isOn()) {
                add("Map rebuild started");
            }
        }));
        subscriptions.add(events.subscribe(Events.ON_MAP_REBUILD_POST, () -> {
            if (map.isOn()) {
                add("Map rebuilt  base " + api.lua().scene().getBase());
            }
        }));
        subscriptions.add(events.subscribe(Events.ON_SCREEN_RESIZE, () -> {
            if (resize.isOn()) {
                add("Screen resized  client " + api.lua().graphics().clientDimensions() + ", window "
                        + api.lua().graphics().screenSize());
            }
        }));
    }

    @Override
    protected void onStop() {
        subscriptions.forEach(events::unsubscribe);
        subscriptions.clear();
        locSubscriptions.forEach(events::unsubscribe);
        locSubscriptions.clear();
        pending.clear();
    }

    @Override
    protected List<String> read(KrakenApi api) {
        syncLocSubscriptions(api);
        if (pending.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String line; (line = pending.poll()) != null; ) {
            lines.add(line);
        }
        return lines;
    }

    /**
     * Subscribes to loc loads and unloads only while their option is on: each subscription makes the poller compare
     * the whole scene every tick. Poll thread.
     *
     * @param api the attached API
     */
    private void syncLocSubscriptions(KrakenApi api) {
        if (events == null) {
            return;
        }
        if (locs.isOn() && locSubscriptions.isEmpty()) {
            ClientOp client = api.lua().clientOp();
            locSubscriptions.add(events.subscribe(Events.ON_LOC_LOAD, (id, name, layer) ->
                    add("Loc loaded  " + id + " " + name + " (layer " + layer + ") at " + client.locCoord())));
            locSubscriptions.add(events.subscribe(Events.ON_LOC_UNLOAD, (id, name, layer) ->
                    add("Loc unloaded  " + id + " " + name + " (layer " + layer + ") at " + client.locCoord())));
        } else if (!locs.isOn() && !locSubscriptions.isEmpty()) {
            locSubscriptions.forEach(events::unsubscribe);
            locSubscriptions.clear();
        }
    }

    @Override
    protected ToolView<List<String>> createView() {
        return new LogView();
    }

    private void npc(String what, String name, int index) {
        if (npcs.isOn()) {
            add(what + "  #" + index + (name == null || name.isEmpty() ? "" : "  " + name));
        }
    }

    private static String inventory(Inventory inventory) {
        List<InvItem> items = inventory.items();
        String slots = items.stream().limit(MAX_ITEMS)
                .map(i -> "slot " + i.slot() + ": " + (i.isEmpty() ? "empty" : i.id() + " ×" + i.count()))
                .collect(Collectors.joining(" · "));
        String more = items.size() > MAX_ITEMS ? " · +" + (items.size() - MAX_ITEMS) + " more" : "";
        return "Inventory " + inventory.id() + "  " + slots + more;
    }

    private static String stat(StatUpdate update) {
        ClientState.Stats stat = ClientState.Stats.of(update.id());
        String name = stat == null ? "#" + update.id() : stat.displayName();
        return name + " " + update.value() + "/" + update.max() + " " + update.xp() + "xp";
    }

    /**
     * Event callbacks run on the poll thread; {@link #read} drains these on the same thread right after.
     *
     * @param line the log line, without the timestamp
     */
    private void add(String line) {
        pending.add(TIME.format(LocalTime.now()) + "  " + line);
        while (pending.size() > MAX_PENDING) {
            pending.poll();
        }
    }
}
