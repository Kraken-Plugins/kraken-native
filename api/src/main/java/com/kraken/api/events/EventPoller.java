package com.kraken.api.events;

import com.kraken.api.events.Events.GameStateTrigger;
import com.kraken.api.game.Client;
import com.kraken.api.game.ClientState;
import com.kraken.api.game.InvItem;
import com.kraken.api.game.Inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.kraken.api.game.ClientState.GAME_STATE_CONNECTION_LOST;
import static com.kraken.api.game.ClientState.GAME_STATE_HOPPING;
import static com.kraken.api.game.ClientState.GAME_STATE_LOADING;
import static com.kraken.api.game.ClientState.GAME_STATE_LOGGED_IN;
import static com.kraken.api.game.ClientState.GAME_STATE_LOGGING_IN;
import static com.kraken.api.game.ClientState.GAME_STATE_TITLE;

/**
 * Synthesises {@link Events} by comparing what the game looked like on the previous poll with what it looks like
 * now. The API's poll loop calls {@link #poll} about every 20 ms. Each event is only tracked while something is
 * subscribed to it: the first poll after that takes a silent baseline, so subscribing late doesn't replay the
 * current state as changes.
 *
 * <ul>
 *   <li>{@code ON_GAME_TICK}: the tick counter moved.
 *   <li>{@code ON_GAME_STATE_CHANGED}: {@code Client.gameState} moved. The triggers follow where the client fires
 *       them: entering the title screen (10) or the logging-in state (20) fires on that state; leaving the world fires
 *       before the title screen (logout) or the hop state (45); entering the world fires when the game state reaches
 *       30 after a login or hop, not on the map reloads in between.
 *   <li>{@code ON_STAT_TRANSMIT}: a stat's XP, level or base level changed. All of them fire when stats come back
 *       after a logout.
 *   <li>{@code ON_VAR_TRANSMIT}: a varp changed.
 *   <li>{@code ON_INV_TRANSMIT}: an inventory's slots changed, or an inventory appeared (all its slots). An inventory
 *       the client drops (it clears them all at login) is forgotten without an event.
 *   <li>{@code ON_NPC_LOAD} / {@code ON_NPC_UNLOAD}: the set of loaded NPC indices changed.
 * </ul>
 */
public final class EventPoller {

    private final Events events;
    private final Client client;
    private final ClientState state;

    private boolean tickBaselined;
    private int lastTick;

    private boolean gameStateBaselined;
    private int lastGameState;
    private boolean inWorld;

    private boolean statsBaselined;
    private ClientState.StatArrays lastStats;

    private int[] lastVarps;

    private Map<Integer, Inventory> lastInventories;

    private Map<Integer, String> loadedNpcs; // index -> name, so unload events can name the NPC

    public EventPoller(Events events, Client client, ClientState state) {
        this.events = events;
        this.client = client;
        this.state = state;
    }

    /** Reads the game once and fires whatever changed since the previous call. Poll thread only. */
    public void poll() {
        pollGameState();
        pollStats();
        pollVarps();
        pollInventories();
        pollNpcs();
        pollTick();
    }

    private void pollTick() {
        if (!events.hasSubscribers(Events.ON_GAME_TICK)) {
            tickBaselined = false;
            return;
        }
        int tick = state.getTickCount();
        if (tickBaselined && tick != lastTick) {
            events.fire(Events.ON_GAME_TICK, GameTickCallback::onTick);
        }
        lastTick = tick;
        tickBaselined = true;
    }

    private void pollGameState() {
        if (!events.hasSubscribers(Events.ON_GAME_STATE_CHANGED)) {
            gameStateBaselined = false;
            return;
        }
        int now = state.getGameState();
        if (now < 0) {
            return;
        }
        if (!gameStateBaselined) {
            gameStateBaselined = true;
            lastGameState = now;
            inWorld = now == GAME_STATE_LOGGED_IN || now == GAME_STATE_LOADING || now == GAME_STATE_CONNECTION_LOST;
            return;
        }
        if (now == lastGameState) {
            return;
        }
        lastGameState = now;
        List<GameStateTrigger> triggers = new ArrayList<>(2);
        switch (now) {
            case GAME_STATE_TITLE -> {
                if (inWorld) {
                    triggers.add(GameStateTrigger.LEAVE_WORLD_STATE);
                }
                inWorld = false;
                triggers.add(GameStateTrigger.ENTER_TITLE_STATE);
            }
            case GAME_STATE_LOGGING_IN -> triggers.add(GameStateTrigger.ENTER_LOADING_STATE);
            case GAME_STATE_HOPPING -> {
                if (inWorld) {
                    triggers.add(GameStateTrigger.LEAVE_WORLD_STATE);
                }
                inWorld = false;
                triggers.add(GameStateTrigger.ENTER_WORLD_HOP_STATE);
            }
            case GAME_STATE_LOGGED_IN -> {
                if (!inWorld) {
                    inWorld = true;
                    triggers.add(GameStateTrigger.ENTER_WORLD_STATE);
                }
            }
            default -> {
                // Loading and connection-lost states come and go inside a session without a trigger
            }
        }
        for (GameStateTrigger trigger : triggers) {
            events.fire(Events.ON_GAME_STATE_CHANGED, cb -> cb.onGameStateChange(trigger));
        }
    }

    private void pollStats() {
        if (!events.hasSubscribers(Events.ON_STAT_TRANSMIT)) {
            statsBaselined = false;
            lastStats = null;
            return;
        }
        ClientState.StatArrays now = state.getStatsAll();
        if (!statsBaselined) {
            statsBaselined = true;
            lastStats = now;
            return;
        }
        if (now == null) {
            lastStats = null; // logged out: everything counts as sent again when the stats come back
            return;
        }
        List<StatUpdate> changed = new ArrayList<>();
        for (int id = 0; id < now.xp().length; id++) {
            if (lastStats == null || id >= lastStats.xp().length || now.xp()[id] != lastStats.xp()[id]
                    || now.level()[id] != lastStats.level()[id] || now.base()[id] != lastStats.base()[id]) {
                changed.add(new StatUpdate(id, now.xp()[id], now.level()[id], now.base()[id]));
            }
        }
        lastStats = now;
        if (!changed.isEmpty()) {
            List<StatUpdate> update = List.copyOf(changed);
            events.fire(Events.ON_STAT_TRANSMIT, cb -> cb.onStatTransmit(update));
        }
    }

    private void pollVarps() {
        if (!events.hasSubscribers(Events.ON_VAR_TRANSMIT)) {
            lastVarps = null;
            return;
        }
        int[] now = state.getVarps();
        if (lastVarps == null) {
            lastVarps = now;
            return;
        }
        int[] changed = new int[now.length];
        int n = 0;
        for (int id = 0; id < now.length; id++) {
            if (id >= lastVarps.length || now[id] != lastVarps[id]) {
                changed[n++] = id;
            }
        }
        lastVarps = now;
        if (n > 0) {
            int[] ids = Arrays.copyOf(changed, n);
            events.fire(Events.ON_VAR_TRANSMIT, cb -> cb.onVarTransmit(ids.clone()));
        }
    }

    private void pollInventories() {
        if (!events.hasSubscribers(Events.ON_INV_TRANSMIT)) {
            lastInventories = null;
            return;
        }
        Map<Integer, Inventory> now = new HashMap<>();
        for (Inventory inventory : client.getInventories()) {
            now.put(inventory.id(), inventory);
        }
        if (lastInventories == null) {
            lastInventories = now;
            return;
        }
        List<Inventory> updated = new ArrayList<>();
        for (Inventory inventory : now.values()) {
            Inventory before = lastInventories.get(inventory.id());
            List<InvItem> changed = before == null ? inventory.items() : changedSlots(before, inventory);
            if (!changed.isEmpty()) {
                updated.add(new Inventory(inventory.id(), List.copyOf(changed)));
            }
        }
        lastInventories = now;
        if (!updated.isEmpty()) {
            List<Inventory> update = List.copyOf(updated);
            events.fire(Events.ON_INV_TRANSMIT, cb -> cb.onInvTransmit(update));
        }
    }

    /** Slots whose item or count differ; a slot the inventory no longer holds reads as empty. */
    private static List<InvItem> changedSlots(Inventory before, Inventory now) {
        List<InvItem> changed = new ArrayList<>();
        int slots = Math.max(before.items().size(), now.items().size());
        for (int slot = 0; slot < slots; slot++) {
            InvItem was = slot < before.items().size() ? before.items().get(slot) : new InvItem(-1, slot, 0);
            InvItem is = slot < now.items().size() ? now.items().get(slot) : new InvItem(-1, slot, 0);
            if (!was.equals(is)) {
                changed.add(is);
            }
        }
        return changed;
    }

    private void pollNpcs() {
        if (!events.hasSubscribers(Events.ON_NPC_LOAD) && !events.hasSubscribers(Events.ON_NPC_UNLOAD)) {
            loadedNpcs = null;
            return;
        }
        int[] current = client.getNpcIdAll();
        boolean baseline = loadedNpcs == null;
        if (baseline) {
            loadedNpcs = new HashMap<>();
        }
        Set<Integer> currentSet = new HashSet<>(current.length * 2);
        for (int index : current) {
            currentSet.add(index);
            if (!loadedNpcs.containsKey(index)) {
                String name = client.npcName(index);
                loadedNpcs.put(index, name);
                if (!baseline) {
                    events.fire(Events.ON_NPC_LOAD, cb -> cb.onNpcLoadUnload(name, index));
                }
            }
        }
        for (Iterator<Map.Entry<Integer, String>> it = loadedNpcs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, String> entry = it.next();
            if (!currentSet.contains(entry.getKey())) {
                it.remove();
                String name = entry.getValue();
                int index = entry.getKey();
                events.fire(Events.ON_NPC_UNLOAD, cb -> cb.onNpcLoadUnload(name, index));
            }
        }
    }
}
