package com.kraken.api.impl;

import com.kraken.api.GameState;
import com.kraken.api.Skill;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.CanvasSizeChanged;
import com.kraken.api.events.ClientTick;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.events.ItemContainerChanged;
import com.kraken.api.events.NpcDespawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.events.PlayerDespawned;
import com.kraken.api.events.PlayerSpawned;
import com.kraken.api.events.StatChanged;
import com.kraken.api.events.VarbitChanged;
import com.kraken.api.lua.ClientOp;
import com.kraken.api.lua.ClientState;
import com.kraken.api.lua.Graphics;
import com.kraken.api.lua.Inventory;
import com.kraken.api.lua.LuaApi;
import com.kraken.api.lua.MapCoord;
import com.kraken.api.lua.Scene;
import com.kraken.api.lua.Vector2;
import com.kraken.api.lua.types.VarBitType;
import com.kraken.api.memory.MemoryException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Synthesises the RuneLite-shaped game events by comparing successive reads of the Lua mirror, as the Lua
 * {@code EventPoller} does for Lua's events. It has its own small trackers rather than riding the Lua events, because
 * the RuneLite events differ where it matters: spawns carry pinned handles and notice a new actor taking an old one's
 * index, vars follow the client's working copy, game states are every raw change, and tracking that starts as the API
 * attaches replays the current state.
 *
 * <p>Demand-driven, as the Lua poller is: a tracker only reads while the bus has subscribers for its events, and
 * forgets its baseline when they all leave.
 *
 * <p><b>The replay rule.</b> A tracker that starts on the first poll (when the API attaches) posts the current state
 * as events: the game state, every loaded NPC and player as spawned, every skill's stats and every held inventory.
 * From a plugin's point of view the client just logged in. A tracker that starts later takes a silent baseline, as a
 * RuneLite plugin enabled mid-session sees no spawns until the next scene load. Vars and the canvas size aren't
 * replayed.
 *
 * <p>Events in one poll are posted in this order: {@link GameStateChanged}, {@link CanvasSizeChanged},
 * {@link StatChanged}, {@link VarbitChanged}, {@link ItemContainerChanged}, NPC despawns then spawns, player despawns
 * then spawns, {@link GameTick} (if the tick counter moved), {@link ClientTick}.
 */
final class RuneLiteEventSource implements EventSource {

    private static final Logger log = Logger.getLogger("kraken.api");
    private static final GameTick GAME_TICK = new GameTick();
    private static final ClientTick CLIENT_TICK = new ClientTick();
    private static final CanvasSizeChanged CANVAS_SIZE_CHANGED = new CanvasSizeChanged();
    private static final Skill[] SKILLS = Skill.values();

    private final ClientOp op;
    private final ClientState state;
    private final Graphics graphics;
    private final Scene scene;
    private final EventBus bus;
    private final List<Tracker> trackers;
    private final Set<Tracker> failing = new HashSet<>();

    private boolean tickBaselined;
    private int lastTick;

    RuneLiteEventSource(ClientImpl client, LuaApi lua, EventBus bus) {
        this.op = lua.clientOp();
        this.state = lua.clientState();
        this.graphics = lua.graphics();
        this.scene = lua.scene();
        this.bus = bus;
        this.trackers = List.of(new GameStateTracker(), new CanvasTracker(), new StatTracker(), new VarTracker(),
                new ContainerTracker(),
                new ActorTracker<>(op::getNpcIdAll, client::npc, NpcDespawned::new, NpcSpawned::new,
                        NpcSpawned.class, NpcDespawned.class),
                new ActorTracker<>(op::getPlayerIdAll, client::player, PlayerDespawned::new, PlayerSpawned::new,
                        PlayerSpawned.class, PlayerDespawned.class));
    }

    @Override
    public boolean poll() {
        boolean ticked = pollTick();
        for (Tracker tracker : trackers) {
            try {
                tracker.run(ticked);
                failing.remove(tracker);
            } catch (MemoryException e) {
                // A bad read mid-transition: the tracker kept its baseline and compares again next poll
            } catch (RuntimeException e) {
                if (failing.add(tracker)) {
                    log.log(Level.WARNING, tracker.getClass().getSimpleName() + " failed", e);
                }
            }
        }
        if (ticked) {
            bus.post(GAME_TICK);
        }
        bus.post(CLIENT_TICK);
        return ticked;
    }

    /**
     * Reads the tick counter. It's always read, subscribers or not, since tick-end tasks and the trackers' once-a-tick
     * refresh need it.
     *
     * @return true if it moved since the previous poll
     */
    private boolean pollTick() {
        int tick = state.getTickCount();
        boolean ticked = tickBaselined && tick != lastTick;
        lastTick = tick;
        tickBaselined = true;
        return ticked;
    }

    /**
     * One kind of state, compared poll to poll while its events have subscribers.
     */
    private abstract class Tracker {
        private final Class<?>[] events;
        private boolean tracking;
        private boolean replayPending = true;

        Tracker(Class<?>... events) {
            this.events = events;
        }

        /**
         * Starts, updates or stops tracking, depending on whether anyone subscribes to the events.
         *
         * @param ticked whether a game tick happened in this poll
         */
        final void run(boolean ticked) {
            boolean wanted = false;
            for (Class<?> event : events) {
                wanted |= bus.hasSubscribers(event);
            }
            if (!wanted) {
                if (tracking) {
                    tracking = false;
                    forget();
                }
                replayPending = false;
                return;
            }
            if (!tracking) {
                if (baseline(replayPending)) {
                    tracking = true;
                    replayPending = false;
                }
                return;
            }
            update(ticked);
        }

        /**
         * Reads the state to compare later polls with.
         *
         * @param replay true when tracking starts as the API attaches: post the state as events
         * @return false if the state can't be read yet, to try again next poll
         */
        abstract boolean baseline(boolean replay);

        /**
         * Reads the state and posts what changed since the last read.
         *
         * @param ticked whether a game tick happened in this poll
         */
        abstract void update(boolean ticked);

        /**
         * Drops the baseline: nobody listens any more.
         */
        abstract void forget();
    }

    /**
     * {@link GameStateChanged} for every change of the raw state, plus a loading/logged-in pair when the map base moved
     * between two polls that both saw the logged-in state.
     */
    private final class GameStateTracker extends Tracker {
        private int lastState;
        private MapCoord lastBase;

        GameStateTracker() {
            super(GameStateChanged.class);
        }

        @Override
        boolean baseline(boolean replay) {
            int raw = state.getGameState();
            if (raw < 0) {
                return false;
            }
            lastState = raw;
            lastBase = scene.getBase();
            if (replay) {
                post(GameState.of(raw));
            }
            return true;
        }

        @Override
        void update(boolean ticked) {
            int raw = state.getGameState();
            if (raw < 0) {
                return;
            }
            MapCoord base = scene.getBase();
            int before = lastState;
            boolean loadMissed = raw == before && raw == GameState.LOGGED_IN.getState() && base != null
                    && lastBase != null && !base.equals(lastBase);
            lastState = raw;
            if (base != null) {
                lastBase = base;
            }
            if (raw != before) {
                post(GameState.of(raw));
            } else if (loadMissed) {
                post(GameState.LOADING);
                post(GameState.LOGGED_IN);
            }
        }

        @Override
        void forget() {
            lastBase = null;
        }

        private void post(GameState gameState) {
            GameStateChanged event = new GameStateChanged();
            event.setGameState(gameState);
            bus.post(event);
        }
    }

    /**
     * {@link CanvasSizeChanged} when the client's size or the window's changes, as Lua's {@code ON_SCREEN_RESIZE}.
     */
    private final class CanvasTracker extends Tracker {
        private Vector2 lastDimensions;
        private Vector2 lastScreen;

        CanvasTracker() {
            super(CanvasSizeChanged.class);
        }

        @Override
        boolean baseline(boolean replay) {
            Vector2 dimensions = graphics.clientDimensions();
            if (dimensions == null) {
                return false;
            }
            lastDimensions = dimensions;
            lastScreen = graphics.screenSize();
            return true;
        }

        @Override
        void update(boolean ticked) {
            Vector2 dimensions = graphics.clientDimensions();
            if (dimensions == null) {
                return;
            }
            Vector2 screen = graphics.screenSize();
            boolean changed = !dimensions.equals(lastDimensions)
                    || screen != null && lastScreen != null && !screen.equals(lastScreen);
            lastDimensions = dimensions;
            if (screen != null) {
                lastScreen = screen;
            }
            if (changed) {
                bus.post(CANVAS_SIZE_CHANGED);
            }
        }

        @Override
        void forget() {
            lastDimensions = null;
            lastScreen = null;
        }
    }

    /**
     * {@link StatChanged} for each skill whose XP, level or boosted level changed. Every skill fires when the stats come
     * back after a logout.
     */
    private final class StatTracker extends Tracker {
        private ClientState.StatArrays last;

        StatTracker() {
            super(StatChanged.class);
        }

        @Override
        boolean baseline(boolean replay) {
            last = state.getStatsAll();
            if (replay && last != null) {
                postChanged(null, last);
            }
            return true;
        }

        @Override
        void update(boolean ticked) {
            ClientState.StatArrays now = state.getStatsAll();
            ClientState.StatArrays before = last;
            last = now;
            if (now != null) {
                postChanged(before, now);
            }
        }

        @Override
        void forget() {
            last = null;
        }

        private void postChanged(ClientState.StatArrays before, ClientState.StatArrays now) {
            int skills = Math.min(SKILLS.length, now.xp().length);
            for (int id = 0; id < skills; id++) {
                if (before == null || id >= before.xp().length || now.xp()[id] != before.xp()[id]
                        || now.level()[id] != before.level()[id] || now.base()[id] != before.base()[id]) {
                    bus.post(new StatChanged(SKILLS[id], now.xp()[id], now.base()[id], now.level()[id]));
                }
            }
        }
    }

    /**
     * {@link VarbitChanged} for each changed varp of the client's working copy, after one for each varbit on it whose
     * value changed.
     */
    private final class VarTracker extends Tracker {
        private int[] last;
        private Map<Integer, int[]> varbitsByVarp;

        VarTracker() {
            super(VarbitChanged.class);
        }

        @Override
        boolean baseline(boolean replay) {
            int[] now = state.getVarbitVarps();
            if (now.length == 0) {
                return false;
            }
            last = now;
            varbitsByVarp = state.configs().varbitsByVarp(); // builds the index once (a fraction of a second)
            return true;
        }

        @Override
        void update(boolean ticked) {
            int[] now = state.getVarbitVarps();
            if (now.length == 0) {
                return;
            }
            int[] before = last;
            last = now;
            if (before.length != now.length) {
                return;
            }
            for (int varp = 0; varp < now.length; varp++) {
                if (now[varp] != before[varp]) {
                    postVarbits(varp, before[varp], now[varp]);
                    post(varp, -1, now[varp]);
                }
            }
        }

        @Override
        void forget() {
            last = null;
        }

        private void postVarbits(int varp, int before, int now) {
            if (varbitsByVarp == null) {
                varbitsByVarp = state.configs().varbitsByVarp();
            }
            int[] varbits = varbitsByVarp == null ? null : varbitsByVarp.get(varp);
            if (varbits == null) {
                return;
            }
            for (int varbit : varbits) {
                VarBitType type = state.getVarbitType(varbit);
                if (type != null && type.extract(now) != type.extract(before)) {
                    post(varp, varbit, type.extract(now));
                }
            }
        }

        private void post(int varp, int varbit, int value) {
            VarbitChanged event = new VarbitChanged();
            event.setVarpId(varp);
            event.setVarbitId(varbit);
            event.setValue(value);
            bus.post(event);
        }
    }

    /**
     * {@link ItemContainerChanged}, with the whole container, for each inventory whose slots changed or that the client
     * started holding. Posted in inventory id order.
     */
    private final class ContainerTracker extends Tracker {
        private Map<Integer, Inventory> last;

        ContainerTracker() {
            super(ItemContainerChanged.class);
        }

        @Override
        boolean baseline(boolean replay) {
            last = read();
            if (replay) {
                last.values().forEach(this::post);
            }
            return true;
        }

        @Override
        void update(boolean ticked) {
            Map<Integer, Inventory> now = read();
            Map<Integer, Inventory> before = last;
            last = now;
            for (Inventory inventory : now.values()) {
                if (!inventory.equals(before.get(inventory.id()))) {
                    post(inventory);
                }
            }
        }

        @Override
        void forget() {
            last = null;
        }

        private Map<Integer, Inventory> read() {
            Map<Integer, Inventory> inventories = new TreeMap<>();
            for (Inventory inventory : op.getInventories()) {
                inventories.put(inventory.id(), inventory);
            }
            return inventories;
        }

        private void post(Inventory inventory) {
            int id = inventory.id();
            bus.post(new ItemContainerChanged(id, ItemContainerImpl.of(inventory, op.invSize(id))));
        }
    }

    /**
     * Spawn and despawn events for NPCs or players: a diff of the client's index list, compared by handle, so a new
     * actor in an old one's index despawns the old handle and spawns a new one. Despawns are posted before spawns.
     *
     * <p>The pins are checked when the index list changes and once per game tick (the client updates actors on ticks);
     * otherwise a poll costs one read of the list. Every tracked handle {@linkplain ActorImpl#remember remembers} its
     * name, id and location once per tick, so a despawned one reports where it was and what it was.
     *
     * @param <T> the handle type
     */
    private final class ActorTracker<T extends ActorImpl> extends Tracker {
        private final Supplier<int[]> indices;
        private final IntFunction<T> handle;
        private final Function<T, Object> despawned;
        private final Function<T, Object> spawned;
        private Map<Integer, T> tracked;
        private int[] lastIndices;

        ActorTracker(Supplier<int[]> indices, IntFunction<T> handle, Function<T, Object> despawned,
                     Function<T, Object> spawned, Class<?>... events) {
            super(events);
            this.indices = indices;
            this.handle = handle;
            this.despawned = despawned;
            this.spawned = spawned;
        }

        @Override
        boolean baseline(boolean replay) {
            int[] now = indices.get();
            tracked = handles(now, Map.of());
            tracked.values().forEach(ActorImpl::remember);
            if (replay) {
                tracked.values().forEach(actor -> bus.post(spawned.apply(actor)));
            }
            return true;
        }

        @Override
        void update(boolean ticked) {
            int[] now = indices.get();
            if (!ticked && Arrays.equals(now, lastIndices)) {
                return;
            }
            Map<Integer, T> before = tracked;
            Map<Integer, T> current = handles(now, before);
            tracked = current;
            List<T> gone = new ArrayList<>();
            for (Map.Entry<Integer, T> entry : before.entrySet()) {
                if (current.get(entry.getKey()) != entry.getValue()) {
                    gone.add(entry.getValue());
                }
            }
            List<T> come = new ArrayList<>();
            for (Map.Entry<Integer, T> entry : current.entrySet()) {
                if (before.get(entry.getKey()) != entry.getValue()) {
                    come.add(entry.getValue());
                }
            }
            if (ticked) {
                current.values().forEach(ActorImpl::remember);
            } else {
                come.forEach(ActorImpl::remember);
            }
            gone.forEach(actor -> bus.post(despawned.apply(actor)));
            come.forEach(actor -> bus.post(spawned.apply(actor)));
        }

        @Override
        void forget() {
            tracked = null;
            lastIndices = null;
        }

        /**
         * The handle on each listed index. An index whose actor can't be pinned right now keeps the handle it had, so
         * one failed read doesn't despawn it; the next poll then compares again.
         *
         * @param now      the index list
         * @param previous the handles before
         * @return index to handle, in list order
         */
        private Map<Integer, T> handles(int[] now, Map<Integer, T> previous) {
            Map<Integer, T> current = new LinkedHashMap<>();
            boolean complete = true;
            for (int index : now) {
                T actor = handle.apply(index);
                if (actor == null) {
                    actor = previous.get(index);
                    complete = false;
                }
                if (actor != null) {
                    current.put(index, actor);
                }
            }
            lastIndices = complete ? now : null;
            return current;
        }
    }
}
