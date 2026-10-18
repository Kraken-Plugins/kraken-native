package com.kraken.api.events;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The event bus, mirroring the Lua {@code osrs.Events}: {@link #subscribe} a callback to an event id and
 * get a subscription id back; {@link #unsubscribe} with that id. Event ids match the client's own enum
 * ({@code enums.events} in the mapping).
 *
 * <p>In this external-reader build, events are <em>synthesised by diffing snapshots</em> on the API's poll
 * thread (see {@code KrakenApi}): {@link #ON_GAME_TICK} fires when the tick counter advances, and
 * {@link #ON_NPC_LOAD}/{@link #ON_NPC_UNLOAD} fire when the set of loaded NPC indices changes. A change that
 * both starts and ends between two polls is not observed. Callbacks run on the poll thread, so UI handlers
 * must marshal to their own thread.
 */
public final class Events {

    /** Event id for a game tick. Calls a {@link GameTickCallback}. */
    public static final int ON_GAME_TICK = 45;
    /** Event id for an NPC loading. Calls an {@link NpcLoadUnloadCallback}. */
    public static final int ON_NPC_LOAD = 43;
    /** Event id for an NPC unloading. Calls an {@link NpcLoadUnloadCallback}. */
    public static final int ON_NPC_UNLOAD = 44;

    private static final Logger log = Logger.getLogger("kraken.api");

    private record Subscription(int eventId, Object callback) {
    }

    private final Map<Integer, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();

    /** Subscribes to {@link #ON_GAME_TICK}. Returns a subscription id for {@link #unsubscribe}. */
    public int subscribe(int eventId, GameTickCallback callback) {
        return add(eventId, callback);
    }

    /** Subscribes to {@link #ON_NPC_LOAD} or {@link #ON_NPC_UNLOAD}. Returns a subscription id. */
    public int subscribe(int eventId, NpcLoadUnloadCallback callback) {
        return add(eventId, callback);
    }

    public void unsubscribe(int subscriptionId) {
        subscriptions.remove(subscriptionId);
    }

    private int add(int eventId, Object callback) {
        int id = nextId.incrementAndGet();
        subscriptions.put(id, new Subscription(eventId, callback));
        return id;
    }

    // ---- dispatch (called by the poll loop) ----------------------------------------------------

    public void fireTick() {
        for (Subscription sub : subscriptions.values()) {
            if (sub.eventId() == ON_GAME_TICK && sub.callback() instanceof GameTickCallback cb) {
                safely(() -> cb.onTick());
            }
        }
    }

    public void fireNpc(int eventId, String name, int npcId) {
        for (Subscription sub : subscriptions.values()) {
            if (sub.eventId() == eventId && sub.callback() instanceof NpcLoadUnloadCallback cb) {
                safely(() -> cb.onNpcLoadUnload(name, npcId));
            }
        }
    }

    private static void safely(Runnable dispatch) {
        try {
            dispatch.run();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Event subscriber threw", e);
        }
    }
}
