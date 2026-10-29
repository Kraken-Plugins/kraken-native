package com.kraken.api.internal.event;

import com.kraken.api.ActorSpotAnim;
import com.kraken.api.actor.Actor;
import com.kraken.api.events.GraphicChanged;
import com.kraken.api.internal.event.ActorValues.Entry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link GraphicChanged} for each loaded actor whose spot animations changed: one was set (even with the id the slot
 * already held), cleared, or finished playing. Every set makes a new spot animation with its own start cycle, so the
 * tracker compares each actor's slots, ids and start cycles. Reads every NPC's and player's spot animations on the
 * tick's poll and the one after, and at once after an NPC or player update the native hook reports. The server sets
 * them in those updates and in its spot animation packets, while the client drops one in its own per-cycle step once
 * it has played, so on the other polls only the actors showing one are read again. An actor seen for the first time
 * posts one if it shows one; tracking that starts as the API attaches posts one for every actor showing one.
 */
final class GraphicTracker extends Tracker {

    /**
     * What tells one set of a spot animation from another: the slot, the id and the cycle it starts on.
     *
     * @param slot       the slot
     * @param id         the spot animation's id
     * @param startCycle the client cycle it starts on
     */
    private record Shown(int slot, int id, int startCycle) {
    }

    private Map<Integer, Entry<Actor, List<Shown>>> last = Map.of();

    GraphicTracker(TrackerContext context) {
        super(context, GraphicChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.NPC_INFO_DECODE, NativeEvent.PLAYER_INFO_DECODE);
    }

    @Override
    boolean baseline(boolean replay) {
        last = ActorValues.actors(client, GraphicTracker::read, Map.of());
        if (replay) {
            last.values().forEach(entry -> {
                if (!entry.value().isEmpty()) {
                    post(entry.actor());
                }
            });
        }
        return true;
    }

    @Override
    void update(boolean ticked) {
        if (ticked || afterTick() || triggered()) {
            compareAll();
        } else {
            compareShowing();
        }
    }

    @Override
    void forget() {
        last = Map.of();
    }

    /**
     * Reads every loaded actor's spot animations and posts for each whose changed, or a new actor showing one.
     */
    private void compareAll() {
        Map<Integer, Entry<Actor, List<Shown>>> before = last;
        Map<Integer, Entry<Actor, List<Shown>>> now = ActorValues.actors(client, GraphicTracker::read, before);
        last = now;
        now.forEach((key, entry) -> {
            Entry<Actor, List<Shown>> was = before.get(key);
            if (entry.sameActor(was) ? !was.value().equals(entry.value()) : !entry.value().isEmpty()) {
                post(entry.actor());
            }
        });
    }

    /**
     * Reads the spot animations of each actor that was showing one and posts for each whose changed. An actor that
     * has gone or can't be read keeps its entry until the next full read.
     */
    private void compareShowing() {
        Map<Integer, Entry<Actor, List<Shown>>> now = null;
        for (Map.Entry<Integer, Entry<Actor, List<Shown>>> slot : last.entrySet()) {
            Entry<Actor, List<Shown>> entry = slot.getValue();
            if (entry.value().isEmpty()) {
                continue;
            }
            List<Shown> shown = read(entry.actor());
            if (shown == null || shown.equals(entry.value())) {
                continue;
            }
            if (now == null) {
                now = new LinkedHashMap<>(last);
            }
            now.put(slot.getKey(), new Entry<>(entry.actor(), shown));
            post(entry.actor());
        }
        if (now != null) {
            last = now;
        }
    }

    /**
     * The spot animations an actor shows, as the tracker compares them.
     *
     * @param actor the actor
     * @return each slot's set, in slot order; null if the actor is gone or they can't be read
     */
    private static List<Shown> read(Actor actor) {
        List<ActorSpotAnim> spotAnims = actor.readSpotAnims();
        if (spotAnims == null) {
            return null;
        }
        List<Shown> shown = new ArrayList<>(spotAnims.size());
        for (ActorSpotAnim spotAnim : spotAnims) {
            shown.add(new Shown(spotAnim.getSlot(), spotAnim.getId(), spotAnim.getStartCycle()));
        }
        return shown;
    }

    private void post(Actor actor) {
        GraphicChanged event = new GraphicChanged();
        event.setActor(actor);
        bus.post(event);
    }
}
