package com.kraken.api.internal;

import com.kraken.api.Client;
import com.kraken.api.GameState;
import com.kraken.api.Skill;
import com.kraken.api.actor.Actor;
import com.kraken.api.coords.LocalPoint;
import com.kraken.api.definition.VarbitComposition;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.CanvasSizeChanged;
import com.kraken.api.events.ClientTick;
import com.kraken.api.events.DecorativeObjectDespawned;
import com.kraken.api.events.DecorativeObjectSpawned;
import com.kraken.api.events.GameObjectDespawned;
import com.kraken.api.events.GameObjectSpawned;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.events.GroundObjectDespawned;
import com.kraken.api.events.GroundObjectSpawned;
import com.kraken.api.events.InteractingChanged;
import com.kraken.api.events.ItemContainerChanged;
import com.kraken.api.events.ItemDespawned;
import com.kraken.api.events.ItemQuantityChanged;
import com.kraken.api.events.ItemSpawned;
import com.kraken.api.events.NpcDespawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.events.PlayerDespawned;
import com.kraken.api.events.PlayerSpawned;
import com.kraken.api.events.ProjectileMoved;
import com.kraken.api.events.ProjectileSpawned;
import com.kraken.api.events.StatChanged;
import com.kraken.api.events.VarbitChanged;
import com.kraken.api.events.WallObjectDespawned;
import com.kraken.api.events.WallObjectSpawned;
import com.kraken.api.item.ItemContainer;
import com.kraken.api.memory.MemoryException;
import com.kraken.api.scene.DecorativeObject;
import com.kraken.api.scene.GameObject;
import com.kraken.api.scene.GroundObject;
import com.kraken.api.scene.Projectile;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileItem;
import com.kraken.api.scene.TileObject;
import com.kraken.api.scene.WallObject;
import com.kraken.api.scene.WorldView;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Posts the game events ({@code com.kraken.api.events}) on an {@code EventBus}, by comparing successive reads of the
 * {@link Client}: spawns carry pinned handles and notice a new actor taking an old one's index, vars follow the
 * client's working copy, game states are every raw change, and tracking that starts as the API attaches replays the
 * current state. {@code GameSession}'s poll loop calls {@link #poll()} about every 20 ms on the poll thread.
 *
 * <p>Demand-driven: a tracker only reads while the bus has subscribers for its events, and forgets its baseline when
 * they all leave.
 *
 * <p><b>The replay rule.</b> A tracker that starts on the first poll (when the API attaches) posts the current state
 * as events: the game state, every object and ground item of the scene, every loaded NPC and player as spawned, every
 * actor's target, every skill's stats and every held inventory. From a plugin's point of view the client just logged
 * in. A tracker that starts later takes a silent baseline and sees no spawns until the next scene load. Vars, the
 * canvas size and projectiles already in flight aren't replayed.
 *
 * <p><b>Scene loads.</b> A newly loaded scene posts a spawn for every object and ground item in it, and no despawns
 * for the old scene's: plugins clear on {@code GameStateChanged(LOADING)}. A scene counts as new when the client comes
 * back to logged in from anything but a lost connection, or when the map base has moved. The {@code LOGGED_IN} that
 * ends a load is held back until those spawns are posted, so plugins see loading, spawns, logged in.
 *
 * <p>Events in one poll are posted in this order: {@link GameStateChanged} (a {@code LOGGED_IN} after the scene's
 * events), object despawns then spawns, ground item despawns, quantity changes then spawns, {@link CanvasSizeChanged},
 * {@link StatChanged}, {@link VarbitChanged}, {@link ItemContainerChanged}, NPC despawns then spawns, player despawns
 * then spawns, {@link InteractingChanged}, {@link ProjectileSpawned} then {@link ProjectileMoved} per new projectile,
 * {@link GameTick} (if the tick counter moved), {@link ClientTick}.
 *
 * <p>How often each looks: the scene's objects once per game tick (a whole-scene read, the costly one); ground items
 * and targets on the tick's poll and the one after it, in case the tick's updates land after the counter moves;
 * projectiles every poll.
 */
public final class EventSource {

    private static final Logger log = Logger.getLogger("kraken.api");
    private static final GameTick GAME_TICK = new GameTick();
    private static final ClientTick CLIENT_TICK = new ClientTick();
    private static final CanvasSizeChanged CANVAS_SIZE_CHANGED = new CanvasSizeChanged();
    private static final Skill[] SKILLS = Skill.values();

    private final Client client;
    private final EventBus bus;
    private final GameStateTracker gameState = new GameStateTracker();
    private final List<Tracker> sceneTrackers;
    private final List<Tracker> trackers;
    private final Set<Tracker> failing = new HashSet<>();

    private boolean tickBaselined;
    private int lastTick;
    private boolean lastPollTicked;

    /**
     * Whether the previous poll saw a game tick: the trackers that look on the tick look again on this poll.
     */
    private boolean afterTick;

    /**
     * Creates the event source; it reads nothing until polled.
     *
     * @param client the client to read, whose actor handles the spawn events carry
     * @param bus    the bus to post on; a kind of event is only tracked while the bus has subscribers for it
     */
    public EventSource(Client client, EventBus bus) {
        this.client = client;
        this.bus = bus;
        this.sceneTrackers = List.of(new LocTracker(), new ItemTracker());
        this.trackers = List.of(new CanvasTracker(), new StatTracker(), new VarTracker(), new ContainerTracker(),
                new ActorTracker<>(client::npcIndices, client::npc, NpcDespawned::new, NpcSpawned::new,
                        NpcSpawned.class, NpcDespawned.class),
                new ActorTracker<>(client::playerIndices, client::player, PlayerDespawned::new, PlayerSpawned::new,
                        PlayerSpawned.class, PlayerDespawned.class),
                new InteractingTracker(), new ProjectileTracker());
    }

    /**
     * Reads the game once and posts what changed since the previous call: the tracked events, then {@code GameTick} if
     * the tick counter moved, then {@code ClientTick}. Poll thread only.
     *
     * @return true if a game tick happened since the previous call
     */
    public boolean poll() {
        boolean ticked = pollTick();
        afterTick = lastPollTicked;
        lastPollTicked = ticked;
        run(gameState, ticked);
        for (Tracker tracker : sceneTrackers) {
            run(tracker, ticked);
        }
        gameState.postHeld();
        for (Tracker tracker : trackers) {
            run(tracker, ticked);
        }
        if (ticked) {
            bus.post(GAME_TICK);
        }
        bus.post(CLIENT_TICK);
        return ticked;
    }

    /**
     * Runs one tracker, so a tracker that fails doesn't hold up the others.
     *
     * @param tracker the tracker
     * @param ticked  whether a game tick happened in this poll
     */
    private void run(Tracker tracker, boolean ticked) {
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

    /**
     * Reads the tick counter. It's always read, subscribers or not, since tick-end tasks and the trackers' once-a-tick
     * refresh need it.
     *
     * @return true if it moved since the previous poll
     */
    private boolean pollTick() {
        int tick = client.getTickCount();
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
     * between two polls that both saw the logged-in state. A {@code LOGGED_IN} is held until {@link #postHeld()}, which
     * the poll calls after the scene's spawns.
     */
    private final class GameStateTracker extends Tracker {
        private int lastState;
        private MapCoord lastBase;
        private boolean loggedInHeld;

        GameStateTracker() {
            super(GameStateChanged.class);
        }

        @Override
        boolean baseline(boolean replay) {
            int raw = client.rawGameState();
            if (raw < 0) {
                return false;
            }
            lastState = raw;
            lastBase = client.mapBase();
            if (replay) {
                post(GameState.of(raw));
            }
            return true;
        }

        @Override
        void update(boolean ticked) {
            int raw = client.rawGameState();
            if (raw < 0) {
                return;
            }
            MapCoord base = client.mapBase();
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
            loggedInHeld = false;
        }

        /**
         * Posts the {@code LOGGED_IN} this poll held back, if any.
         */
        void postHeld() {
            if (loggedInHeld) {
                loggedInHeld = false;
                send(GameState.LOGGED_IN);
            }
        }

        private void post(GameState gameState) {
            if (gameState == GameState.LOGGED_IN) {
                loggedInHeld = true;
            } else {
                send(gameState);
            }
        }

        private void send(GameState gameState) {
            GameStateChanged event = new GameStateChanged();
            event.setGameState(gameState);
            bus.post(event);
        }
    }

    /**
     * {@link CanvasSizeChanged} when the canvas's size or the window's changes, as Lua's {@code ON_SCREEN_RESIZE}.
     */
    private final class CanvasTracker extends Tracker {
        private Dimension lastDimensions;
        private Dimension lastScreen;

        CanvasTracker() {
            super(CanvasSizeChanged.class);
        }

        @Override
        boolean baseline(boolean replay) {
            Dimension dimensions = canvas();
            if (dimensions == null) {
                return false;
            }
            lastDimensions = dimensions;
            lastScreen = screen();
            return true;
        }

        @Override
        void update(boolean ticked) {
            Dimension dimensions = canvas();
            if (dimensions == null) {
                return;
            }
            Dimension screen = screen();
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

        private Dimension canvas() {
            int width = client.getCanvasWidth();
            int height = client.getCanvasHeight();
            return width < 0 || height < 0 ? null : new Dimension(width, height);
        }

        private Dimension screen() {
            int width = client.getScreenWidth();
            int height = client.getScreenHeight();
            return width < 0 || height < 0 ? null : new Dimension(width, height);
        }
    }

    /**
     * {@link StatChanged} for each skill whose XP, level or boosted level changed. Every skill fires when the stats
     * come back after a logout.
     */
    private final class StatTracker extends Tracker {
        private Client.Stats last;

        StatTracker() {
            super(StatChanged.class);
        }

        @Override
        boolean baseline(boolean replay) {
            last = client.stats();
            if (replay && last != null) {
                postChanged(null, last);
            }
            return true;
        }

        @Override
        void update(boolean ticked) {
            Client.Stats now = client.stats();
            Client.Stats before = last;
            last = now;
            if (now != null) {
                postChanged(before, now);
            }
        }

        @Override
        void forget() {
            last = null;
        }

        private void postChanged(Client.Stats before, Client.Stats now) {
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
            int[] now = client.getVarps();
            if (now.length == 0) {
                return false;
            }
            last = now;
            varbitsByVarp = client.getConfigTypes().varbitsByVarp(); // builds the index once (a fraction of a second)
            return true;
        }

        @Override
        void update(boolean ticked) {
            int[] now = client.getVarps();
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
                varbitsByVarp = client.getConfigTypes().varbitsByVarp();
            }
            int[] varbits = varbitsByVarp == null ? null : varbitsByVarp.get(varp);
            if (varbits == null) {
                return;
            }
            for (int varbit : varbits) {
                VarbitComposition type = client.getVarbit(varbit);
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
        private Map<Integer, ItemContainer> last;

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
            Map<Integer, ItemContainer> now = read();
            Map<Integer, ItemContainer> before = last;
            last = now;
            for (ItemContainer inventory : now.values()) {
                if (!inventory.equals(before.get(inventory.getId()))) {
                    post(inventory);
                }
            }
        }

        @Override
        void forget() {
            last = null;
        }

        private Map<Integer, ItemContainer> read() {
            Map<Integer, ItemContainer> inventories = new TreeMap<>();
            for (ItemContainer inventory : client.getItemContainers()) {
                inventories.put(inventory.getId(), inventory);
            }
            return inventories;
        }

        private void post(ItemContainer inventory) {
            bus.post(new ItemContainerChanged(inventory.getId(), inventory));
        }
    }

    /**
     * Spawn and despawn events for NPCs or players: a diff of the client's index list, compared by handle, so a new
     * actor in an old one's index despawns the old handle and spawns a new one. Despawns are posted before spawns.
     *
     * <p>The pins are checked when the index list changes and once per game tick (the client updates actors on ticks);
     * otherwise a poll costs one read of the list. Every tracked handle {@linkplain Actor#remember remembers} its
     * name, id and location once per tick, so a despawned one reports where it was and what it was.
     *
     * @param <T> the handle type
     */
    private final class ActorTracker<T extends Actor> extends Tracker {
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
            tracked.values().forEach(Actor::remember);
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
                current.values().forEach(Actor::remember);
            } else {
                come.forEach(Actor::remember);
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

    /**
     * What the object and ground item trackers share: a scene is read in full when it is new (the replay at attach, a
     * login, a map base that moved), posting every spawn, and compared with the last read otherwise.
     */
    private abstract class SceneTracker extends Tracker {
        private MapCoord lastBase;
        private boolean loadPending;

        SceneTracker(Class<?>... events) {
            super(events);
        }

        @Override
        final boolean baseline(boolean replay) {
            int raw = client.rawGameState();
            if (raw < 0) {
                return false;
            }
            if (raw != GameState.LOGGED_IN.getState()) {
                // No scene yet: the one that loads next spawns in full
                lastBase = null;
                loadPending = true;
                clear();
                return true;
            }
            MapCoord base = client.mapBase();
            if (base == null) {
                return false;
            }
            lastBase = base;
            loadPending = false;
            load(base, replay);
            return true;
        }

        @Override
        final void update(boolean ticked) {
            int raw = client.rawGameState();
            if (raw < 0) {
                return;
            }
            if (raw != GameState.LOGGED_IN.getState()) {
                // A lost connection keeps the scene; anything else (loading, hopping, logged out) brings a new one
                loadPending |= raw != GameState.CONNECTION_LOST.getState();
                return;
            }
            MapCoord base = client.mapBase();
            if (base == null) {
                return;
            }
            if (loadPending || !base.equals(lastBase)) {
                lastBase = base;
                loadPending = false;
                load(base, true);
            } else if (due(ticked)) {
                compare(base);
            }
        }

        @Override
        final void forget() {
            lastBase = null;
            clear();
        }

        /**
         * Whether to compare this poll, when the scene hasn't changed.
         *
         * @param ticked whether a game tick happened in this poll
         * @return true to read and compare
         */
        abstract boolean due(boolean ticked);

        /**
         * Reads the whole scene as the new baseline, dropping the old one without despawns.
         *
         * @param base the scene's map base
         * @param post whether to post a spawn for everything in it
         */
        abstract void load(MapCoord base, boolean post);

        /**
         * Reads the scene and posts what changed since the last read.
         *
         * @param base the scene's map base, the same as the last read's
         */
        abstract void compare(MapCoord base);

        /**
         * Drops the baseline.
         */
        abstract void clear();
    }

    /**
     * The four object spawn and despawn events, from a read of every loc in the scene once per game tick. Objects are
     * keyed by their slot, as Lua's {@code ON_LOC_LOAD} keys them: a tile and layer, plus the id for game objects (a
     * tile can hold several). A slot whose object changed (a door opening) posts the old one's despawn and the new
     * one's spawn. All despawns go before all spawns, as the client replaces a loc.
     */
    private final class LocTracker extends SceneTracker {
        private Map<Long, Loc> last = Map.of();

        LocTracker() {
            super(GameObjectSpawned.class, GameObjectDespawned.class, WallObjectSpawned.class,
                    WallObjectDespawned.class, DecorativeObjectSpawned.class, DecorativeObjectDespawned.class,
                    GroundObjectSpawned.class, GroundObjectDespawned.class);
        }

        @Override
        boolean due(boolean ticked) {
            return ticked;
        }

        @Override
        void load(MapCoord base, boolean post) {
            last = read();
            if (post) {
                last.values().forEach(loc -> post(loc, base, true));
            }
        }

        @Override
        void compare(MapCoord base) {
            Map<Long, Loc> now = read();
            if (now.isEmpty() && !last.isEmpty()) {
                return; // a scene doesn't empty without a reload: a failed read, so keep the baseline
            }
            Map<Long, Loc> before = last;
            last = now;
            for (Map.Entry<Long, Loc> entry : before.entrySet()) {
                if (!entry.getValue().equals(now.get(entry.getKey()))) {
                    post(entry.getValue(), base, false);
                }
            }
            for (Map.Entry<Long, Loc> entry : now.entrySet()) {
                if (!entry.getValue().equals(before.get(entry.getKey()))) {
                    post(entry.getValue(), base, true);
                }
            }
        }

        @Override
        void clear() {
            last = Map.of();
        }

        private Map<Long, Loc> read() {
            Map<Long, Loc> locs = new LinkedHashMap<>();
            SceneReader scene = client.getSceneReader();
            for (Loc loc : scene.allLocs(scene.sceneOf(scene.topWorld()))) {
                locs.put(slot(loc), loc);
            }
            return locs;
        }

        /**
         * Posts one object's spawn or despawn, if anyone listens to that kind.
         *
         * @param loc     the object
         * @param base    the map base it was read with
         * @param spawned true for a spawn, false for a despawn
         */
        private void post(Loc loc, MapCoord base, boolean spawned) {
            Class<?> type = eventType(loc.layer(), spawned);
            if (!bus.hasSubscribers(type)) {
                return;
            }
            Tile tile = Tile.at(client, loc.coord(), base.x(), base.z());
            TileObject object = TileObject.of(client, loc, base.x(), base.z());
            bus.post(switch (loc.layer()) {
                case Loc.WALL -> spawned ? wallSpawned(tile, object) : wallDespawned(tile, object);
                case Loc.WALL_DECOR -> spawned ? decorSpawned(tile, object) : decorDespawned(tile, object);
                case Loc.GAME_OBJECT -> spawned ? gameSpawned(tile, object) : gameDespawned(tile, object);
                default -> spawned ? groundSpawned(tile, object) : groundDespawned(tile, object);
            });
        }

        private Class<?> eventType(int layer, boolean spawned) {
            return switch (layer) {
                case Loc.WALL -> spawned ? WallObjectSpawned.class : WallObjectDespawned.class;
                case Loc.WALL_DECOR -> spawned ? DecorativeObjectSpawned.class : DecorativeObjectDespawned.class;
                case Loc.GAME_OBJECT -> spawned ? GameObjectSpawned.class : GameObjectDespawned.class;
                default -> spawned ? GroundObjectSpawned.class : GroundObjectDespawned.class;
            };
        }

        private Object wallSpawned(Tile tile, TileObject object) {
            WallObjectSpawned event = new WallObjectSpawned();
            event.setTile(tile);
            event.setWallObject((WallObject) object);
            return event;
        }

        private Object wallDespawned(Tile tile, TileObject object) {
            WallObjectDespawned event = new WallObjectDespawned();
            event.setTile(tile);
            event.setWallObject((WallObject) object);
            return event;
        }

        private Object decorSpawned(Tile tile, TileObject object) {
            DecorativeObjectSpawned event = new DecorativeObjectSpawned();
            event.setTile(tile);
            event.setDecorativeObject((DecorativeObject) object);
            return event;
        }

        private Object decorDespawned(Tile tile, TileObject object) {
            DecorativeObjectDespawned event = new DecorativeObjectDespawned();
            event.setTile(tile);
            event.setDecorativeObject((DecorativeObject) object);
            return event;
        }

        private Object gameSpawned(Tile tile, TileObject object) {
            GameObjectSpawned event = new GameObjectSpawned();
            event.setTile(tile);
            event.setGameObject((GameObject) object);
            return event;
        }

        private Object gameDespawned(Tile tile, TileObject object) {
            GameObjectDespawned event = new GameObjectDespawned();
            event.setTile(tile);
            event.setGameObject((GameObject) object);
            return event;
        }

        private Object groundSpawned(Tile tile, TileObject object) {
            GroundObjectSpawned event = new GroundObjectSpawned();
            event.setTile(tile);
            event.setGroundObject((GroundObject) object);
            return event;
        }

        private Object groundDespawned(Tile tile, TileObject object) {
            GroundObjectDespawned event = new GroundObjectDespawned();
            event.setTile(tile);
            event.setGroundObject((GroundObject) object);
            return event;
        }
    }

    /**
     * A loc's slot: its tile and layer, plus its id for a game object, since a tile can hold several.
     *
     * @param loc the loc
     * @return a key unique per slot
     */
    private static long slot(Loc loc) {
        MapCoord at = loc.coord();
        long key = (long) (at.level() & 3) << 62 | (long) (at.x() & 0x3ff) << 52 | (long) (at.z() & 0x3ff) << 42
                | (long) (loc.layer() & 3) << 40;
        return loc.layer() == Loc.GAME_OBJECT ? key | (loc.id() & 0xffffffffL) : key;
    }

    /**
     * {@link ItemSpawned}, {@link ItemDespawned} and {@link ItemQuantityChanged}, from a read of every pile. Each
     * tile's pile is matched against the last read: a stack with the same item and quantity is the same stack (it keeps
     * its handle); one with the same item and another quantity changed size; what is left over went or came.
     */
    private final class ItemTracker extends SceneTracker {
        private Map<MapCoord, List<TileItem>> last = Map.of();

        ItemTracker() {
            super(ItemSpawned.class, ItemDespawned.class, ItemQuantityChanged.class);
        }

        @Override
        boolean due(boolean ticked) {
            return ticked || afterTick;
        }

        @Override
        void load(MapCoord base, boolean post) {
            Map<MapCoord, List<TileItem>> loaded = new LinkedHashMap<>();
            read().forEach((tile, objs) -> {
                Tile at = Tile.at(client, tile, base.x(), base.z());
                List<TileItem> items = new ArrayList<>(objs.size());
                for (GroundObj obj : objs) {
                    items.add(new TileItem(obj.id(), obj.count(), at));
                }
                loaded.put(tile, items);
            });
            last = loaded;
            if (post) {
                loaded.forEach((tile, items) -> items.forEach(item -> bus.post(new ItemSpawned(item.getTile(), item))));
            }
        }

        @Override
        void compare(MapCoord base) {
            Map<MapCoord, List<GroundObj>> now = read();
            Map<MapCoord, List<TileItem>> before = last;
            Map<MapCoord, List<TileItem>> next = new LinkedHashMap<>();
            List<Object> despawns = new ArrayList<>();
            List<Object> changes = new ArrayList<>();
            List<Object> spawns = new ArrayList<>();
            Set<MapCoord> tiles = new LinkedHashSet<>(before.keySet());
            tiles.addAll(now.keySet());
            for (MapCoord tile : tiles) {
                List<TileItem> old = new ArrayList<>(before.getOrDefault(tile, List.of()));
                List<GroundObj> current = now.getOrDefault(tile, List.of());
                TileItem[] kept = new TileItem[current.size()];
                for (int i = 0; i < kept.length; i++) {
                    kept[i] = take(old, current.get(i), true);
                }
                Tile at = Tile.at(client, tile, base.x(), base.z());
                for (int i = 0; i < kept.length; i++) {
                    if (kept[i] == null) {
                        kept[i] = take(old, current.get(i), false);
                        if (kept[i] != null) {
                            int was = kept[i].getQuantity();
                            kept[i].setQuantity(current.get(i).count());
                            changes.add(new ItemQuantityChanged(kept[i], at, was, current.get(i).count()));
                        }
                    }
                }
                old.forEach(item -> despawns.add(new ItemDespawned(at, item)));
                for (int i = 0; i < kept.length; i++) {
                    if (kept[i] == null) {
                        kept[i] = new TileItem(current.get(i).id(), current.get(i).count(), at);
                        spawns.add(new ItemSpawned(at, kept[i]));
                    }
                }
                if (kept.length > 0) {
                    next.put(tile, List.of(kept));
                }
            }
            last = next;
            despawns.forEach(bus::post);
            changes.forEach(bus::post);
            spawns.forEach(bus::post);
        }

        @Override
        void clear() {
            last = Map.of();
        }

        /**
         * Takes the first stack from the last read that matches one in this read.
         *
         * @param old       the last read's stacks on the tile not matched yet; the match is removed
         * @param obj       a stack in this read
         * @param sameCount whether the quantity must match too
         * @return the matching handle, or null
         */
        private TileItem take(List<TileItem> old, GroundObj obj, boolean sameCount) {
            for (int i = 0; i < old.size(); i++) {
                TileItem item = old.get(i);
                if (item.getId() == obj.id() && (!sameCount || item.getQuantity() == obj.count())) {
                    return old.remove(i);
                }
            }
            return null;
        }

        private Map<MapCoord, List<GroundObj>> read() {
            Map<MapCoord, List<GroundObj>> piles = new LinkedHashMap<>();
            for (GroundObj obj : client.getSceneReader().allObjs(client.getSceneReader().topWorld())) {
                piles.computeIfAbsent(obj.coord(), tile -> new ArrayList<>()).add(obj);
            }
            return piles;
        }
    }

    /**
     * {@link InteractingChanged} for each loaded actor whose target changed, on the tick's poll and the one after.
     * An actor seen for the first time posts one if it has a target.
     */
    private final class InteractingTracker extends Tracker {
        private static final long NONE = -1;
        private Map<Actor, Long> last = Map.of();

        InteractingTracker() {
            super(InteractingChanged.class);
        }

        @Override
        boolean baseline(boolean replay) {
            last = read();
            if (replay) {
                last.forEach((actor, target) -> {
                    if (target != NONE) {
                        post(actor, target);
                    }
                });
            }
            return true;
        }

        @Override
        void update(boolean ticked) {
            if (!ticked && !afterTick) {
                return;
            }
            Map<Actor, Long> now = read();
            Map<Actor, Long> before = last;
            last = now;
            now.forEach((actor, target) -> {
                Long was = before.get(actor);
                if (was == null ? target != NONE : !was.equals(target)) {
                    post(actor, target);
                }
            });
        }

        @Override
        void forget() {
            last = Map.of();
        }

        private Map<Actor, Long> read() {
            Map<Actor, Long> targets = new LinkedHashMap<>();
            for (Actor actor : client.npcList()) {
                targets.put(actor, target(actor));
            }
            for (Actor actor : client.playerList()) {
                targets.put(actor, target(actor));
            }
            return targets;
        }

        private long target(Actor actor) {
            int index = actor.getInteractingIndex();
            return index == -1 ? NONE : (long) actor.getInteractingType() << 32 | (index & 0xffffffffL);
        }

        private void post(Actor source, long target) {
            Actor to = target == NONE ? null : client.actor((int) (target >>> 32), (int) target);
            bus.post(new InteractingChanged(source, to));
        }
    }

    /**
     * {@link ProjectileMoved} once per projectile, the first poll it is in the client's list. A projectile is known by
     * what the server sent for it (spot anim, endpoints, cycles, slope, start distance); identical ones are counted.
     */
    private final class ProjectileTracker extends Tracker {
        private Map<Flight, Integer> last = Map.of();

        ProjectileTracker() {
            super(ProjectileSpawned.class, ProjectileMoved.class);
        }

        @Override
        boolean baseline(boolean replay) {
            last = count(client.getProjectiles(), null);
            return true;
        }

        @Override
        void update(boolean ticked) {
            List<Projectile> fresh = new ArrayList<>();
            last = count(client.getProjectiles(), fresh);
            fresh.forEach(this::post);
        }

        @Override
        void forget() {
            last = Map.of();
        }

        /**
         * Counts the projectiles by flight, noting those beyond what the last read counted.
         *
         * @param projectiles this read's projectiles
         * @param fresh       where to add the new ones, or null
         * @return the counts
         */
        private Map<Flight, Integer> count(Collection<Projectile> projectiles, List<Projectile> fresh) {
            Map<Flight, Integer> counts = new HashMap<>();
            for (Projectile projectile : projectiles) {
                int seen = counts.merge(Flight.of(projectile), 1, Integer::sum);
                if (fresh != null && seen > last.getOrDefault(Flight.of(projectile), 0)) {
                    fresh.add(projectile);
                }
            }
            return counts;
        }

        /**
         * Posts a new projectile: {@link ProjectileSpawned}, then {@link ProjectileMoved} with where its target is
         * now, the target actor's location if it is loaded, else the centre of the target tile.
         *
         * @param projectile the projectile
         */
        private void post(Projectile projectile) {
            ProjectileSpawned spawned = new ProjectileSpawned();
            spawned.setProjectile(projectile);
            bus.post(spawned);
            ProjectileEndpoint target = projectile.getTarget();
            LocalPoint position = null;
            Actor actor = client.actor(target.actorType(), target.actorIndex());
            if (actor != null) {
                position = actor.getLocalLocation();
            }
            WorldView view = client.getTopLevelWorldView();
            if (position == null) {
                position = LocalPoint.fromWorld(view, target.tile().x(), target.tile().z());
            }
            ProjectileMoved event = new ProjectileMoved();
            event.setProjectile(projectile);
            event.setPosition(position);
            if (position != null) {
                event.setZ(view.getTileHeight(position.getX(), position.getY(), target.tile().level())
                        - target.height());
            }
            bus.post(event);
        }
    }

    /**
     * What identifies a projectile from one read to the next: everything the server sent for it.
     *
     * @param id            the spot anim
     * @param source        where it comes from
     * @param target        where it goes
     * @param startCycle    its start cycle
     * @param endCycle      its end cycle
     * @param slope         its slope
     * @param startDistance its start distance
     */
    private record Flight(int id, ProjectileEndpoint source, ProjectileEndpoint target, int startCycle, int endCycle,
                          int slope, int startDistance) {

        static Flight of(Projectile p) {
            return new Flight(p.getId(), p.getSource(), p.getTarget(), p.getStartCycle(), p.getEndCycle(),
                    p.getSlope(), p.getStartPos());
        }
    }
}
