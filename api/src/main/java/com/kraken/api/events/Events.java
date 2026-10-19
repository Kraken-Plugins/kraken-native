package com.kraken.api.events;

import com.kraken.api.mapping.Mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The event bus, mirroring the Lua {@code osrs.Events}: {@link #subscribe} a callback to an event and get a
 * subscription id back; {@link #unsubscribe} with that id. Each event constant carries its Lua id and the callback
 * type it calls, so the compiler checks the callback's shape:
 *
 * <pre>{@code
 * int id = events.subscribe(Events.ON_STAT_TRANSMIT, stats -> stats.forEach(s -> log(s.id() + " " + s.xp())));
 * events.unsubscribe(id);
 * }</pre>
 *
 * <p>In this external-reader build, events are <em>synthesised by diffing snapshots</em> on the API's poll thread
 * (see {@link EventPoller}), and only while something is subscribed: the first poll after a subscription takes a
 * baseline, and later polls fire for what changed. So a change that starts and ends between two polls (about 20 ms)
 * is not seen, and a value the server re-sends unchanged doesn't fire. Callbacks run on the poll thread, so UI
 * handlers must marshal to their own thread.
 */
public final class Events {

    /**
     * Event id for a game tick. Calls a {@link GameTickCallback}.
     */
    public static final Event<GameTickCallback> ON_GAME_TICK = new Event<>(44, "ON_GAME_TICK");

    /**
     * Event id for an NPC loading. Calls an {@link NpcLoadUnloadCallback}.
     */
    public static final Event<NpcLoadUnloadCallback> ON_NPC_LOAD = new Event<>(42, "ON_NPC_LOAD");

    /**
     * Event id for an NPC unloading. Calls an {@link NpcLoadUnloadCallback}.
     */
    public static final Event<NpcLoadUnloadCallback> ON_NPC_UNLOAD = new Event<>(43, "ON_NPC_UNLOAD");

    /**
     * Event id for varps changing. Calls a {@link VarTransmitCallback} with the changed ids.
     */
    public static final Event<VarTransmitCallback> ON_VAR_TRANSMIT = new Event<>(11, "ON_VAR_TRANSMIT");

    /**
     * Event id for inventory slots changing. Calls an {@link InvTransmitCallback}.
     */
    public static final Event<InvTransmitCallback> ON_INV_TRANSMIT = new Event<>(12, "ON_INV_TRANSMIT");

    /**
     * Event id for stats changing. Calls a {@link StatTransmitCallback}.
     */
    public static final Event<StatTransmitCallback> ON_STAT_TRANSMIT = new Event<>(13, "ON_STAT_TRANSMIT");

    /**
     * Event id for a game state change ({@link GameStateTrigger}). Calls a {@link GameStateChangeCallback}.
     */
    public static final Event<GameStateChangeCallback> ON_GAME_STATE_CHANGED = new Event<>(39, "ON_GAME_STATE_CHANGED");

    /**
     * Event id for a varbit's value changing. Calls a {@link VarbitUpdateCallback} once per changed varbit.
     */
    public static final Event<VarbitUpdateCallback> ON_VARBIT_UPDATE = new Event<>(46, "ON_VARBIT_UPDATE");

    /**
     * Event id for the client being resized. Calls a {@link ScreenResizeCallback}.
     */
    public static final Event<ScreenResizeCallback> ON_SCREEN_RESIZE = new Event<>(45, "ON_SCREEN_RESIZE");

    /**
     * Event id for the map starting to load a new area. Calls a {@link MapRebuildCallback}.
     */
    public static final Event<MapRebuildCallback> ON_MAP_REBUILD_PRE = new Event<>(34, "ON_MAP_REBUILD_PRE");

    /**
     * Event id for the map having loaded a new area. Calls a {@link MapRebuildCallback}.
     */
    public static final Event<MapRebuildCallback> ON_MAP_REBUILD_POST = new Event<>(35, "ON_MAP_REBUILD_POST");

    /**
     * Event id for a loc appearing in the scene (a door opening, a tree growing back…). Calls a
     * {@link LocLoadUnloadCallback}.
     */
    public static final Event<LocLoadUnloadCallback> ON_LOC_LOAD = new Event<>(40, "ON_LOC_LOAD");

    /**
     * Event id for a loc leaving the scene. Calls a {@link LocLoadUnloadCallback}.
     */
    public static final Event<LocLoadUnloadCallback> ON_LOC_UNLOAD = new Event<>(41, "ON_LOC_UNLOAD");

    /**
     * Every event this API can deliver so far.
     */
    public static final List<Event<?>> SUPPORTED = List.of(ON_GAME_TICK, ON_NPC_LOAD, ON_NPC_UNLOAD, ON_VAR_TRANSMIT,
            ON_INV_TRANSMIT, ON_STAT_TRANSMIT, ON_GAME_STATE_CHANGED, ON_VARBIT_UPDATE, ON_SCREEN_RESIZE,
            ON_MAP_REBUILD_PRE, ON_MAP_REBUILD_POST, ON_LOC_LOAD, ON_LOC_UNLOAD);

    /**
     * Mirrors {@code osrs.Events.GameStateTrigger}: the transitions {@link #ON_GAME_STATE_CHANGED} reports.
     */
    public enum GameStateTrigger {
        /**
         * Loading into the title screen.
         */
        ENTER_TITLE_STATE(0),

        /**
         * Transitioning from the title screen to the world.
         */
        ENTER_LOADING_STATE(1),

        /**
         * Loaded into the world.
         */
        ENTER_WORLD_STATE(2),

        /**
         * Leaving the world, to log out or hop.
         */
        LEAVE_WORLD_STATE(3),

        /**
         * Hopping from one world to another.
         */
        ENTER_WORLD_HOP_STATE(4);

        private final int value;

        GameStateTrigger(int value) {
            this.value = value;
        }

        /**
         * The trigger's value in the Lua API.
         *
         * @return the number Lua's {@code GameStateTrigger} uses for this trigger
         */
        public int value() {
            return value;
        }
    }

    private static final Logger log = Logger.getLogger("kraken.api");

    private record Subscription(Event<?> event, Object callback) {
    }

    private final Map<Integer, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();

    /**
     * Subscribes {@code callback} to {@code event}. As in Lua, a callback can only be registered once per event:
     * subscribing it again returns the existing id.
     *
     * @param event    the event to listen to, e.g. {@link #ON_GAME_TICK}
     * @param callback what to call when it fires (on the poll thread)
     * @param <C>      the event's callback type
     * @return a subscription id for {@link #unsubscribe}
     */
    public synchronized <C> int subscribe(Event<C> event, C callback) {
        for (Map.Entry<Integer, Subscription> entry : subscriptions.entrySet()) {
            if (entry.getValue().event() == event && entry.getValue().callback() == callback) {
                return entry.getKey();
            }
        }
        int id = nextId.incrementAndGet();
        subscriptions.put(id, new Subscription(event, callback));
        return id;
    }

    /**
     * Removes a subscription. Unknown ids are ignored.
     *
     * @param subscriptionId the id {@link #subscribe} returned
     */
    public void unsubscribe(int subscriptionId) {
        subscriptions.remove(subscriptionId);
    }

    /**
     * Whether anything is subscribed to {@code event}. The poller only synthesises events someone listens to.
     *
     * @param event the event to check
     * @return true if at least one callback is subscribed to it
     */
    public boolean hasSubscribers(Event<?> event) {
        for (Subscription sub : subscriptions.values()) {
            if (sub.event() == event) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares this API's event ids and trigger values with the client's own enums in the mapping.
     *
     * @param mapping the build's mapping, whose {@code enums.events} and {@code enums.gameStateTrigger} tables are read
     * @return a line for each value that differs; empty when they all match, or when the mapping doesn't have the tables
     */
    public static List<String> verify(Mapping mapping) {
        List<String> problems = new ArrayList<>();
        Map<String, Integer> ids = mapping.enumTable("events");
        for (Event<?> event : SUPPORTED) {
            Integer native_ = ids.get(event.name());
            if (native_ != null && native_ != event.id()) {
                problems.add(event.name() + " is " + native_ + " in the client, " + event.id() + " in the API");
            }
        }
        Map<String, Integer> triggers = mapping.enumTable("gameStateTrigger");
        for (GameStateTrigger trigger : GameStateTrigger.values()) {
            Integer native_ = triggers.get(trigger.name());
            if (native_ != null && native_ != trigger.value()) {
                problems.add(trigger.name() + " is " + native_ + " in the client, " + trigger.value() + " in the API");
            }
        }
        return problems;
    }

    /**
     * Calls every subscriber of {@code event}; used by the poller. A subscriber that throws is logged and skipped.
     *
     * @param event the event that fired
     * @param call  invokes one subscriber's callback with the event's arguments
     * @param <C>   the event's callback type
     */
    @SuppressWarnings("unchecked")
    <C> void fire(Event<C> event, Consumer<C> call) {
        for (Subscription sub : subscriptions.values()) {
            if (sub.event() == event) {
                C callback = (C) sub.callback();
                try {
                    call.accept(callback);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Subscriber to " + event.name() + " threw", e);
                }
            }
        }
    }
}
