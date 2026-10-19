package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.events.Events;
import com.kraken.api.events.StatUpdate;
import com.kraken.api.game.ClientState;
import com.kraken.api.game.InvItem;
import com.kraken.api.game.Inventory;
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

/** A live log of the API's events. Subscribes only while its window is open. */
final class EventLogTool extends DevTool<List<String>> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_PENDING = 1_000;
    private static final int MAX_ITEMS = 8;

    private final ToolOption gameState = option("gameState", "Game state changes", true);
    private final ToolOption npcs = option("npcs", "NPC load / unload", true);
    private final ToolOption inventories = option("inventories", "Inventory transmits", true);
    private final ToolOption stats = option("stats", "Stat transmits", true);
    private final ToolOption vars = option("vars", "Var transmits", false);
    private final ToolOption ticks = option("ticks", "Game ticks", false);
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final List<Integer> subscriptions = new ArrayList<>();
    private Events events;

    EventLogTool() {
        super("events", "Event log", Category.DIAGNOSTICS, MaterialDesignL.LIGHTNING_BOLT_OUTLINE,
                "A live log of API events (game state, NPCs, inventories, stats, vars, ticks) as they're seen.");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected void onStart(KrakenApi api) {
        events = api.events();
        ClientState state = api.clientState();
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
        subscriptions.add(events.subscribe(Events.ON_GAME_TICK, () -> {
            if (ticks.isOn()) {
                add("Game tick  " + state.getTickCount());
            }
        }));
    }

    @Override
    protected void onStop() {
        subscriptions.forEach(events::unsubscribe);
        subscriptions.clear();
        pending.clear();
    }

    @Override
    protected List<String> read(KrakenApi api) {
        if (pending.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String line; (line = pending.poll()) != null; ) {
            lines.add(line);
        }
        return lines;
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

    /** Event callbacks run on the poll thread; {@link #read} drains these on the same thread right after. */
    private void add(String line) {
        pending.add(TIME.format(LocalTime.now()) + "  " + line);
        while (pending.size() > MAX_PENDING) {
            pending.poll();
        }
    }
}
