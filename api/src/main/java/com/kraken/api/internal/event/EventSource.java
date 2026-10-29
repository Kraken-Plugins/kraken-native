package com.kraken.api.internal.event;

import com.kraken.api.Client;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.CanvasSizeChanged;
import com.kraken.api.events.ClientTick;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.events.GraphicsObjectCreated;
import com.kraken.api.events.GraphicsObjectDestroyed;
import com.kraken.api.events.InteractingChanged;
import com.kraken.api.events.ItemContainerChanged;
import com.kraken.api.events.MenuEntryAdded;
import com.kraken.api.events.MenuEntryRemoved;
import com.kraken.api.events.MenuOpened;
import com.kraken.api.events.MenuOptionClicked;
import com.kraken.api.events.NpcDespawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.events.PlayerDespawned;
import com.kraken.api.events.PlayerSpawned;
import com.kraken.api.events.ProjectileMoved;
import com.kraken.api.events.StatChanged;
import com.kraken.api.events.VarbitChanged;
import com.kraken.api.events.WidgetClosed;
import com.kraken.api.events.WidgetLoaded;
import com.kraken.api.memory.MemoryException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Posts the game events ({@code com.kraken.api.events}) on an {@code EventBus}, by comparing successive reads of the
 * {@link Client}: spawns carry pinned handles and notice a new actor taking an old one's index, vars follow the
 * client's working copy, game states are every raw change, and tracking that starts as the API attaches replays the
 * current state. {@code GameSession}'s poll loop calls {@link #poll()} on the poll thread about every 20 ms, and right
 * after each raise the native hook pushes.
 *
 * <p>Each kind of state is a {@link Tracker}; see that class for how tracking starts, replays and stops. The trackers
 * share a {@link TrackerContext} (the client, the bus and the after-tick flag).
 *
 * <p>Demand-driven: a tracker only reads while the bus has subscribers for its events, and forgets its baseline when
 * they all leave.
 *
 * <p><b>The replay rule.</b> A tracker that starts on the first poll (when the API attaches) posts the current state
 * as events: the game state, every object and ground item of the scene, every loaded NPC and player as spawned, every
 * actor's target, every skill's stats, every held inventory and every open interface as loaded. From a plugin's point
 * of view the client just logged in. A tracker that starts later takes a silent baseline and sees no spawns until the
 * next scene load. Vars, the canvas size, projectiles already in flight and graphics objects already playing aren't
 * replayed.
 *
 * <p><b>Scene loads.</b> A newly loaded scene posts a spawn for every object and ground item in it, and no despawns
 * for the old scene's: plugins clear on {@code GameStateChanged(LOADING)}. A scene counts as new when the client comes
 * back to logged in from anything but a lost connection, or when the map base has moved. The {@code LOGGED_IN} that
 * ends a load is held back until those spawns are posted, so plugins see loading, spawns, logged in.
 *
 * <p>Events in one poll are posted in this order: {@link GameStateChanged} (a {@code LOGGED_IN} after the scene's
 * events), object despawns then spawns, ground item despawns, quantity changes then spawns, {@link CanvasSizeChanged},
 * {@link StatChanged}, {@link VarbitChanged}, {@link ItemContainerChanged}, interfaces closed and loaded
 * ({@link WidgetClosed}, {@link WidgetLoaded}), NPC despawns then spawns, player despawns then spawns,
 * {@link InteractingChanged}, {@link ProjectileMoved} per new or retargeted projectile, graphics objects
 * destroyed then created ({@link GraphicsObjectDestroyed}, {@link GraphicsObjectCreated}), {@link MenuOptionClicked},
 * {@link MenuEntryRemoved} then {@link MenuEntryAdded}, {@link MenuOpened}, a {@link GameTick} per step of the
 * client's tick counter, then a {@link ClientTick} per step of its cycle counter.
 *
 * <p><b>Ticks come from the game's counters.</b> {@code GameTick} and {@code ClientTick} follow the client's own tick
 * and cycle counters, one event per step each counter takes, so they stay in step with the game however the polls are
 * timed: a poll only decides when a step is noticed. The tick counter is read at the start of a poll (the trackers
 * need to know whether a tick happened), the cycle counter at the end. A native raise makes the poll happen sooner; it
 * never counts a tick itself, so a dropped or repeated raise can't lose or double one.
 *
 * <p>How often each looks: the scene's objects once per game tick (a whole-scene read, the costly one); ground items
 * and targets on the tick's poll and the one after it, in case the tick's updates land after the counter moves;
 * projectiles, graphics objects and the menu every poll.
 */
public final class EventSource {

    private static final Logger log = Logger.getLogger("kraken.api");
    private static final GameTick GAME_TICK = new GameTick();
    private static final ClientTick CLIENT_TICK = new ClientTick();

    /**
     * The most {@code ClientTick}s one poll posts, a second of client cycles: after the poll thread stalls for longer,
     * it posts this many rather than a long burst.
     */
    private static final int MAX_CLIENT_TICKS = 50;

    /**
     * The most {@code GameTick}s one poll posts: after the poll thread stalls through more ticks than this, it posts
     * this many rather than a long burst.
     */
    private static final int MAX_GAME_TICKS = 10;

    /**
     * The longest a poll waits for the cycle counter to reach the cycles {@code clientCycle} raises announced, a tenth
     * of a client cycle. The hook fires as the client cycle starts, a little before the client increments the counter
     * (after a task-queue drain), so the read the raise wakes can still see the old value.
     */
    private static final long CYCLE_WAIT_NANOS = 2_000_000;

    private final Client client;
    private final EventBus bus;
    private final TrackerContext context;
    private final GameStateTracker gameState;
    private final List<Tracker> sceneTrackers;
    private final List<Tracker> trackers;
    private final Set<Tracker> failing = new HashSet<>();
    private final Map<String, List<Tracker>> triggerRoutes;
    private final ScriptEvents scripts;

    private boolean tickBaselined;
    private int lastTick;
    private boolean lastPollTicked;
    private boolean cycleBaselined;
    private int lastCycle;

    /**
     * {@code clientCycle} raises since the last poll: each announces one step of the cycle counter.
     */
    private int cycleRaises;

    /**
     * Creates the event source; it reads nothing until polled.
     *
     * @param client the client to read, whose actor handles the spawn events carry
     * @param bus    the bus to post on; a kind of event is only tracked while the bus has subscribers for it
     */
    public EventSource(Client client, EventBus bus) {
        this.client = client;
        this.bus = bus;
        this.context = new TrackerContext(client, bus);
        this.gameState = new GameStateTracker(context);
        this.sceneTrackers = List.of(new LocTracker(context), new ItemTracker(context));
        this.trackers = List.of(new CanvasTracker(context), new StatTracker(context), new VarTracker(context),
                new ContainerTracker(context), new WidgetTracker(context),
                new ActorTracker<>(context, Set.of("ON_NPC_LOAD", "ON_NPC_UNLOAD", NativeEvent.NPC_INFO_DECODE),
                        client::npcIndices, client::npc, NpcDespawned::new, NpcSpawned::new,
                        NpcSpawned.class, NpcDespawned.class),
                new ActorTracker<>(context, Set.of(NativeEvent.PLAYER_ADD, NativeEvent.PLAYER_REMOVE,
                        NativeEvent.PLAYER_INFO_DECODE),
                        client::playerIndices, client::player, PlayerDespawned::new, PlayerSpawned::new,
                        PlayerSpawned.class, PlayerDespawned.class),
                new InteractingTracker(context), new ProjectileTracker(context), new GraphicsObjectTracker(context),
                new MenuClickTracker(context), new MenuTracker(context));

        this.scripts = new ScriptEvents(client, bus);
        this.triggerRoutes = new HashMap<>();
        List<Tracker> all = new ArrayList<>();
        all.add(gameState);
        all.addAll(sceneTrackers);
        all.addAll(trackers);
        for (Tracker tracker : all) {
            for (String name : tracker.triggerNames()) {
                this.triggerRoutes.computeIfAbsent(name, key -> new ArrayList<>()).add(tracker);
            }
        }
    }

    /**
     * Applies a native event raise pushed by the hook: records a trigger on each tracker that wants it, so the next
     * {@link #poll()} reads and posts for it instead of waiting on the timer. Does not itself post events or read the
     * game; the trackers do, in their usual order, on the poll. Poll thread only (the hook server hands these over with
     * {@code GameSession.invoke}, which runs them just before the poll).
     *
     * <p>Names the trackers don't recognise are ignored, so a hook for an event the API doesn't mirror (or that a
     * build raises but no tracker handles) costs nothing. With no hook at all, this is never called and the poll path
     * is unchanged.
     *
     * <p>A {@code clientCycle} raise is counted instead: it tells the next poll that a cycle has started, so the poll
     * waits briefly for the cycle counter to step rather than posting that {@code ClientTick} a cycle late.
     *
     * @param event the parsed raise
     */
    public void onNative(NativeEvent event) {
        if (NativeEvent.CLIENT_CYCLE.equals(event.name())) {
            cycleRaises++;
        }
        List<Tracker> targets = triggerRoutes.get(event.name());
        if (targets == null) {
            return;
        }
        for (Tracker tracker : targets) {
            tracker.onTrigger(event);
        }
    }

    /**
     * Posts the event for a script the native hook reported starting or returning, if it has subscribers, at once and
     * outside any poll: each report is its own event, with the stacks the hook captured. Poll thread only (the hook
     * server hands these over with {@code GameSession.invoke}).
     *
     * @param run the report
     */
    public void onScript(ScriptRun run) {
        scripts.post(run);
    }

    /**
     * Takes the connection to a native hook that reports scripts, or drops it. While one is set, each poll tells it to
     * report scripts starting while {@code ScriptPreFired} has subscribers and returning while
     * {@code ScriptPostFired} does. Poll thread only.
     *
     * @param hook the hook, or null while there is none
     */
    public void setScriptHook(ScriptHook hook) {
        scripts.setHook(hook);
    }

    /**
     * Reads the game once and posts what changed since the previous call: the tracked events, then a {@code GameTick}
     * per game tick and a {@code ClientTick} per client cycle since the previous call. Poll thread only.
     *
     * @return true if a game tick happened since the previous call
     */
    public boolean poll() {
        scripts.sync();
        int ticks = pollTicks();
        boolean ticked = ticks > 0;
        context.setAfterTick(lastPollTicked);
        lastPollTicked = ticked;
        run(gameState, ticked);
        for (Tracker tracker : sceneTrackers) {
            run(tracker, ticked);
        }
        gameState.postHeld();
        for (Tracker tracker : trackers) {
            run(tracker, ticked);
        }
        for (int i = 0; i < ticks; i++) {
            bus.post(GAME_TICK);
        }
        int cycles = pollCycles();
        for (int i = 0; i < cycles; i++) {
            bus.post(CLIENT_TICK);
        }
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
     * Reads the tick counter to count the {@code GameTick}s this poll posts: one per step the counter took since the
     * previous poll, at most {@link #MAX_GAME_TICKS}, and none on the first reading. The client steps the counter just
     * before it raises {@code ON_GAME_TICK} (in the same server-tick handler), so the read that raise wakes already sees
     * the new tick. Logout resets the counter to 0, so a counter that went back counts the ticks since that reset and
     * the logout itself posts none; a failed read posts none and keeps the last reading. Always read, subscribers or
     * not, since tick-end tasks and the trackers' once-a-tick refresh need it.
     *
     * @return the number of game ticks since the previous poll
     */
    private int pollTicks() {
        int tick = client.tickCount();
        if (tick < 0) {
            return 0;
        }
        if (!tickBaselined) {
            tickBaselined = true;
            lastTick = tick;
            return 0;
        }
        int passed = tick >= lastTick ? tick - lastTick : tick;
        lastTick = tick;
        return Math.min(passed, MAX_GAME_TICKS);
    }

    /**
     * Reads the client's cycle counter ({@code Client.getGameCycle}, one step per 20 ms client cycle) to count the
     * {@code ClientTick}s this poll posts: one per step since the previous poll, at most {@link #MAX_CLIENT_TICKS}, and
     * none on the first reading. When {@code clientCycle} raises announced more steps than the counter shows, it waits
     * up to {@link #CYCLE_WAIT_NANOS} for them. A failed read posts none and keeps the last reading; a counter that has
     * never been read (a mapping without it) falls back to one per poll. Always read, subscribers or not, so a
     * subscriber that arrives later gets no burst of old cycles.
     *
     * @return the number of {@code ClientTick}s to post
     */
    private int pollCycles() {
        int raises = cycleRaises;
        cycleRaises = 0;
        int cycle = client.getGameCycle();
        if (cycle < 0) {
            return cycleBaselined ? 0 : 1;
        }
        if (!cycleBaselined) {
            cycleBaselined = true;
            lastCycle = cycle;
            return 0;
        }
        if (cycle - lastCycle < raises) {
            cycle = awaitCycle(lastCycle + raises, cycle);
        }
        int passed = cycle - lastCycle;
        lastCycle = cycle;
        if (passed < 0) {
            // The counter went back, so it was reset: at least one cycle ran, and how many is unknown
            return 1;
        }
        return Math.min(passed, MAX_CLIENT_TICKS);
    }

    /**
     * Re-reads the cycle counter until it reaches the step the {@code clientCycle} raises announced, for at most
     * {@link #CYCLE_WAIT_NANOS}. A raise whose step doesn't come in time (a raise for a step an earlier read already
     * counted, or an unusually long task-queue drain in the client) gives up the wait; the step is counted when it
     * comes, so nothing is lost or posted twice.
     *
     * @param target the counter value the raises announced
     * @param cycle  the value already read
     * @return the last value read
     */
    private int awaitCycle(int target, int cycle) {
        long deadline = System.nanoTime() + CYCLE_WAIT_NANOS;
        while (cycle - target < 0 && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
            int now = client.getGameCycle();
            if (now < 0) {
                break;
            }
            cycle = now;
        }
        return cycle;
    }
}
