package com.kraken.client.devtools.tools;

import com.kraken.api.KrakenApi;
import com.kraken.api.events.Events;
import com.kraken.api.game.Client;
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

/** A live log of the API's events. Subscribes only while its window is open. */
final class EventLogTool extends DevTool<List<String>> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_PENDING = 1_000;

    private final ToolOption npcs = option("npcs", "NPC load / unload", true);
    private final ToolOption ticks = option("ticks", "Game ticks", false);
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final List<Integer> subscriptions = new ArrayList<>();
    private Events events;

    EventLogTool() {
        super("events", "Event log", Category.DIAGNOSTICS, MaterialDesignL.LIGHTNING_BOLT_OUTLINE,
                "A live log of API events (NPCs loading and unloading, game ticks) as they're seen.");
    }

    @Override
    public Support windowSupport() {
        return Support.READY;
    }

    @Override
    protected void onStart(KrakenApi api) {
        events = api.events();
        Client client = api.client();
        subscriptions.add(events.subscribe(Events.ON_NPC_LOAD, (name, index) -> npc("NPC loaded", name, index)));
        subscriptions.add(events.subscribe(Events.ON_NPC_UNLOAD, (name, index) -> npc("NPC unloaded", name, index)));
        subscriptions.add(events.subscribe(Events.ON_GAME_TICK, () -> {
            if (ticks.isOn()) {
                add("Game tick  " + client.getTickCount());
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
    protected List<String> read(Client client) {
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

    /** Event callbacks run on the poll thread; {@link #read} drains these on the same thread right after. */
    private void add(String line) {
        pending.add(TIME.format(LocalTime.now()) + "  " + line);
        while (pending.size() > MAX_PENDING) {
            pending.poll();
        }
    }
}
