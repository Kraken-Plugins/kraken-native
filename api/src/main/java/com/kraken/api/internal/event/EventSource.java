package com.kraken.api.internal.event;

import com.kraken.api.Client;
import com.kraken.api.eventbus.EventBus;
import com.kraken.api.events.CanvasSizeChanged;
import com.kraken.api.events.ClientTick;
import com.kraken.api.events.GameStateChanged;
import com.kraken.api.events.GameTick;
import com.kraken.api.events.InteractingChanged;
import com.kraken.api.events.ItemContainerChanged;
import com.kraken.api.events.NpcDespawned;
import com.kraken.api.events.NpcSpawned;
import com.kraken.api.events.PlayerDespawned;
import com.kraken.api.events.PlayerSpawned;
import com.kraken.api.events.ProjectileMoved;
import com.kraken.api.events.StatChanged;
import com.kraken.api.events.VarbitChanged;
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
 * current state. {@code GameSession}'s poll loop calls {@link #poll()} about every 20 ms on the poll thread.
 *
 * <p>Each kind of state is a {@link Tracker}; see that class for how tracking starts, replays and stops. The trackers
 * share a {@link TrackerContext} (the client, the bus and the after-tick flag).
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
 * then spawns, {@link InteractingChanged}, {@link ProjectileMoved} per new projectile,
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

    private final Client client;
    private final EventBus bus;
    private final TrackerContext context;
    private final GameStateTracker gameState;
    private final List<Tracker> sceneTrackers;
    private final List<Tracker> trackers;
    private final Set<Tracker> failing = new HashSet<>();
    private final Map<String, List<Tracker>> triggerRoutes;

    private boolean tickBaselined;
    private int lastTick;
    private boolean lastPollTicked;

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
                new ContainerTracker(context),
                new ActorTracker<>(context, Set.of("ON_NPC_LOAD", "ON_NPC_UNLOAD", NativeEvent.NPC_INFO_DECODE),
                        client::npcIndices, client::npc, NpcDespawned::new, NpcSpawned::new,
                        NpcSpawned.class, NpcDespawned.class),
                new ActorTracker<>(context, Set.of(NativeEvent.PLAYER_ADD, NativeEvent.PLAYER_REMOVE,
                        NativeEvent.PLAYER_INFO_DECODE),
                        client::playerIndices, client::player, PlayerDespawned::new, PlayerSpawned::new,
                        PlayerSpawned.class, PlayerDespawned.class),
                new InteractingTracker(context), new ProjectileTracker(context));

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
     * @param event the parsed raise
     */
    public void onNative(NativeEvent event) {
        List<Tracker> targets = triggerRoutes.get(event.name());
        if (targets == null) {
            return;
        }
        for (Tracker tracker : targets) {
            tracker.onTrigger(event);
        }
    }

    /**
     * Reads the game once and posts what changed since the previous call: the tracked events, then {@code GameTick} if
     * the tick counter moved, then {@code ClientTick}. Poll thread only.
     *
     * @return true if a game tick happened since the previous call
     */
    public boolean poll() {
        boolean ticked = pollTick();
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
}
