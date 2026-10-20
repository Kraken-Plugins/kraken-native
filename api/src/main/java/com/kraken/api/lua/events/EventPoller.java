package com.kraken.api.lua.events;

import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.ClientState;
import com.kraken.api.lua.Graphics;
import com.kraken.api.lua.InvItem;
import com.kraken.api.lua.Inventory;
import com.kraken.api.lua.Loc;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Scene;
import com.kraken.api.lua.Vector2;
import com.kraken.api.lua.events.Events.GameStateTrigger;
import com.kraken.api.lua.types.LocType;
import com.kraken.api.lua.types.VarBitType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.kraken.api.lua.ClientState.GAME_STATE_CONNECTION_LOST;
import static com.kraken.api.lua.ClientState.GAME_STATE_HOPPING;
import static com.kraken.api.lua.ClientState.GAME_STATE_LOADING;
import static com.kraken.api.lua.ClientState.GAME_STATE_LOGGED_IN;
import static com.kraken.api.lua.ClientState.GAME_STATE_LOGGING_IN;
import static com.kraken.api.lua.ClientState.GAME_STATE_TITLE;

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
 *   <li>{@code ON_VARBIT_UPDATE}: like the client, when the server's value of a varp changes, each varbit on that varp
 *       whose value changed fires with its new value. Values come from the varp array {@code getVarbit} reads. The
 *       first subscription builds the varp-to-varbits index (about 20k definitions, a fraction of a second).
 *   <li>{@code ON_INV_TRANSMIT}: an inventory's slots changed, or an inventory appeared (all its slots). An inventory
 *       the client drops (it clears them all at login) is forgotten without an event.
 *   <li>{@code ON_NPC_LOAD} / {@code ON_NPC_UNLOAD}: the set of loaded NPC indices changed.
 *   <li>{@code ON_SCREEN_RESIZE}: the client's size ({@code Graphics.clientDimensions}) or the window's
 *       ({@code osrs.screenSize}) changed.
 *   <li>{@code ON_MAP_REBUILD_PRE} / {@code ON_MAP_REBUILD_POST}: the client raises PRE when it starts loading a new
 *       area while logged in, just before the game state goes to loading (25), and POST when the load finishes, at
 *       login too. So PRE fires when the game state moves from logged in (30) to loading, and POST when it moves from
 *       loading back to logged in. A load short enough to fall between two polls still moves the map's base
 *       ({@code Scene.getBase}); then both fire together.
 *   <li>{@code ON_LOC_LOAD} / {@code ON_LOC_UNLOAD}: the client raises these when the server changes a loc (a door
 *       opens, a tree is felled and becomes a stump), not for the locs a map load builds. So the scene's locs are
 *       compared once per game tick, and a loc that appeared or went fires (unloads first, as the client replaces a
 *       loc); after a map rebuild the next scan is a silent baseline. Like the client, each callback runs with the loc
 *       as the active loc ({@code Client.locCoord()}). A scan reads the whole scene, so only subscribe while needed.
 * </ul>
 */
public final class EventPoller {

    private final Events events;
    private final ClientOp client;
    private final ClientState state;
    private final Graphics graphics;
    private final Scene scene;

    private boolean tickBaselined;
    private int lastTick;

    private boolean gameStateBaselined;
    private int lastGameState;
    private boolean inWorld;

    private boolean statsBaselined;
    private ClientState.StatArrays lastStats;

    private int[] lastVarps;

    private int[] lastServerVarps; // for ON_VARBIT_UPDATE: which varps the server changed
    private int[] lastVarbitVarps; //   and the values varbits are read from

    private Map<Integer, Inventory> lastInventories;

    private Map<Integer, String> loadedNpcs; // index -> name, so unload events can name the NPC

    private boolean screenBaselined;
    private Vector2 lastDimensions;
    private Vector2 lastScreen;

    private Map<Long, Loc> lastLocs;
    private int lastLocTick;
    private MapCoord lastLocBase;

    private boolean mapBaselined;
    private int lastMapState;
    private MapCoord lastBase;

    /**
     * Creates the poller.
     *
     * @param events   the bus to fire on
     * @param client   reads actors and inventories
     * @param state    reads stats, vars, the tick and the game state
     * @param graphics reads the client's size
     * @param scene    reads the map's base
     */
    public EventPoller(Events events, ClientOp client, ClientState state, Graphics graphics, Scene scene) {
        this.events = events;
        this.client = client;
        this.state = state;
        this.graphics = graphics;
        this.scene = scene;
    }

    /**
     * Reads the game once and fires whatever changed since the previous call. Poll thread only.
     */
    public void poll() {
        pollScreen();
        pollMapRebuild();
        pollGameState();
        pollStats();
        pollVarbits(); // before ON_VAR_TRANSMIT, the order the client's varp packet handler fires them in
        pollVarps();
        pollInventories();
        pollNpcs();
        pollLocs();
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

    private void pollScreen() {
        if (!events.hasSubscribers(Events.ON_SCREEN_RESIZE)) {
            screenBaselined = false;
            return;
        }
        Vector2 dimensions = graphics.clientDimensions();
        Vector2 screen = graphics.screenSize();
        if (dimensions == null) {
            return; // can't read the size now; keep the baseline
        }
        boolean changed = screenBaselined && (!dimensions.equals(lastDimensions)
                || (screen != null && lastScreen != null && !screen.equals(lastScreen)));
        lastDimensions = dimensions;
        lastScreen = screen;
        screenBaselined = true;
        if (changed) {
            events.fire(Events.ON_SCREEN_RESIZE, ScreenResizeCallback::onScreenResize);
        }
    }

    private void pollMapRebuild() {
        if (!events.hasSubscribers(Events.ON_MAP_REBUILD_PRE) && !events.hasSubscribers(Events.ON_MAP_REBUILD_POST)) {
            mapBaselined = false;
            return;
        }
        int now = state.getGameState();
        MapCoord base = scene.getBase();
        if (now < 0) {
            return;
        }
        if (!mapBaselined) {
            mapBaselined = true;
            lastMapState = now;
            lastBase = base;
            return;
        }
        boolean pre = false;
        boolean post = false;
        if (now == GAME_STATE_LOADING && lastMapState == GAME_STATE_LOGGED_IN) {
            pre = true;
        } else if (now == GAME_STATE_LOGGED_IN && lastMapState == GAME_STATE_LOADING) {
            post = true;
        } else if (now == GAME_STATE_LOGGED_IN && lastMapState == GAME_STATE_LOGGED_IN && base != null
                && lastBase != null && !Objects.equals(base, lastBase)) {
            pre = true;
            post = true;
        }
        lastMapState = now;
        if (base != null) {
            lastBase = base;
        }
        if (pre) {
            events.fire(Events.ON_MAP_REBUILD_PRE, MapRebuildCallback::onMapRebuild);
        }
        if (post) {
            events.fire(Events.ON_MAP_REBUILD_POST, MapRebuildCallback::onMapRebuild);
        }
    }

    private void pollLocs() {
        if (!events.hasSubscribers(Events.ON_LOC_LOAD) && !events.hasSubscribers(Events.ON_LOC_UNLOAD)) {
            lastLocs = null;
            return;
        }
        if (state.getGameState() != GAME_STATE_LOGGED_IN) {
            lastLocs = null;
            return;
        }
        int tick = state.getTickCount();
        MapCoord base = scene.getBase();
        if (lastLocs != null && tick == lastLocTick && Objects.equals(base, lastLocBase)) {
            return;
        }
        Map<Long, Loc> now = new HashMap<>();
        for (Loc loc : scene.getAllLocs()) {
            now.put(locKey(loc), loc);
        }
        boolean baseline = lastLocs == null || !Objects.equals(base, lastLocBase);
        Map<Long, Loc> before = lastLocs;
        lastLocs = now;
        lastLocTick = tick;
        lastLocBase = base;
        if (baseline) {
            return;
        }
        for (Map.Entry<Long, Loc> entry : before.entrySet()) {
            Loc current = now.get(entry.getKey());
            if (current == null || current.id() != entry.getValue().id()) {
                fireLoc(Events.ON_LOC_UNLOAD, entry.getValue());
            }
        }
        for (Map.Entry<Long, Loc> entry : now.entrySet()) {
            Loc was = before.get(entry.getKey());
            if (was == null || was.id() != entry.getValue().id()) {
                fireLoc(Events.ON_LOC_LOAD, entry.getValue());
            }
        }
    }

    private void fireLoc(Event<LocLoadUnloadCallback> event, Loc loc) {
        LocType type = client.configs().loc(loc.id());
        String name = type == null ? "" : type.getName();
        client.activateLoc(loc);
        events.fire(event, cb -> cb.onLocLoadUnload(loc.id(), name, loc.layer()));
    }

    /**
     * A loc's slot: its tile and layer. A tile holds one loc per layer, plus game objects, which can share a tile;
     * those are told apart by their id.
     *
     * @param loc the loc
     * @return a key unique to the slot (and, for game objects, the id)
     */
    private static long locKey(Loc loc) {
        MapCoord at = loc.coord();
        long key = (long) at.level() << 60 | (long) (at.x() & 0xfff) << 48 | (long) (at.z() & 0xfff) << 36
                | (long) loc.layer() << 32;
        return loc.layer() == Loc.GAME_OBJECT ? key | (loc.id() & 0xffffffffL) : key;
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

    private void pollVarbits() {
        if (!events.hasSubscribers(Events.ON_VARBIT_UPDATE)) {
            lastServerVarps = null;
            lastVarbitVarps = null;
            return;
        }
        Map<Integer, int[]> varbitsByVarp = state.configs().varbitsByVarp();
        int[] server = state.getVarps();
        int[] bits = state.getVarbitVarps();
        if (varbitsByVarp == null || server.length == 0 || bits.length != server.length) {
            return; // nothing to compare yet; keep any baseline
        }
        if (lastServerVarps == null || lastServerVarps.length != server.length) {
            lastServerVarps = server;
            lastVarbitVarps = bits;
            return;
        }
        for (int varp = 0; varp < server.length; varp++) {
            if (server[varp] == lastServerVarps[varp]) {
                continue;
            }
            int[] varbits = varbitsByVarp.get(varp);
            if (varbits == null) {
                continue;
            }
            int before = lastVarbitVarps[varp];
            int after = bits[varp];
            for (int varbit : varbits) {
                VarBitType type = state.getVarbitType(varbit);
                if (type == null) {
                    continue;
                }
                int value = type.extract(after);
                if (value != type.extract(before)) {
                    events.fire(Events.ON_VARBIT_UPDATE, cb -> cb.onVarbitUpdate(varbit, value));
                }
            }
        }
        lastServerVarps = server;
        lastVarbitVarps = bits;
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

    /**
     * Slots whose item or count differ between two reads of an inventory.
     *
     * @param before the inventory at the previous poll
     * @param now    the inventory now
     * @return the slots that changed, as they are now; a slot the inventory no longer holds reads as empty
     */
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
