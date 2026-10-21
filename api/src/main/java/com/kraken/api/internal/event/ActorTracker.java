package com.kraken.api.internal.event;

import com.kraken.api.actor.Actor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Logger;

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
final class ActorTracker<T extends Actor> extends Tracker {

    private static final Logger log = Logger.getLogger("kraken.api");

    private final Set<String> triggers;
    private final Supplier<int[]> indices;
    private final IntFunction<T> handle;
    private final Function<T, Object> despawned;
    private final Function<T, Object> spawned;
    private Map<Integer, T> tracked;
    private int[] lastIndices;
    private boolean loggedSlot;

    ActorTracker(TrackerContext context, Set<String> triggers, Supplier<int[]> indices, IntFunction<T> handle,
                 Function<T, Object> despawned, Function<T, Object> spawned, Class<?>... events) {
        super(context, events);
        this.triggers = triggers;
        this.indices = indices;
        this.handle = handle;
        this.despawned = despawned;
        this.spawned = spawned;
    }

    @Override
    Set<String> triggerNames() {
        return triggers;
    }

    @Override
    void onTrigger(NativeEvent event) {
        super.onTrigger(event); // run the index-list diff this poll; it is cheap and needs no payload
        Integer slot = event.i32(NativeEvent.R8);
        if (slot != null && !loggedSlot) {
            loggedSlot = true;
        }
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
