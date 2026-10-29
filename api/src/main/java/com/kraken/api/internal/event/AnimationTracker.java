package com.kraken.api.internal.event;

import com.kraken.api.actor.Actor;
import com.kraken.api.events.AnimationChanged;
import com.kraken.api.internal.event.ActorValues.Entry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link AnimationChanged} for each loaded actor whose animation changed: one started, another replaced it, or it
 * ended. Reads every NPC's and player's animation on the tick's poll and the one after, and at once after an NPC or
 * player update or an NPC's animation being set, which the native hook reports. The server starts animations in those
 * updates, while the client ends them in its own per-cycle step, so on the other polls only the actors playing an
 * animation are read again. An actor seen for the first time posts one if it is playing an animation; tracking that
 * starts as the API attaches posts one for every actor playing one.
 */
final class AnimationTracker extends Tracker {

    private static final int NONE = -1;

    private Map<Integer, Entry<Actor, Integer>> last = Map.of();

    AnimationTracker(TrackerContext context) {
        super(context, AnimationChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.SET_SEQUENCE, NativeEvent.NPC_INFO_DECODE, NativeEvent.PLAYER_INFO_DECODE);
    }

    @Override
    boolean baseline(boolean replay) {
        last = ActorValues.actors(client, Actor::readAnimation, Map.of());
        if (replay) {
            last.values().forEach(entry -> {
                if (entry.value() != NONE) {
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
            comparePlaying();
        }
    }

    @Override
    void forget() {
        last = Map.of();
    }

    /**
     * Reads every loaded actor's animation and posts for each that changed, or that a new actor is playing.
     */
    private void compareAll() {
        Map<Integer, Entry<Actor, Integer>> before = last;
        Map<Integer, Entry<Actor, Integer>> now = ActorValues.actors(client, Actor::readAnimation, before);
        last = now;
        now.forEach((key, entry) -> {
            Entry<Actor, Integer> was = before.get(key);
            if (entry.sameActor(was) ? !was.value().equals(entry.value()) : entry.value() != NONE) {
                post(entry.actor());
            }
        });
    }

    /**
     * Reads the animation of each actor that was playing one and posts for each that changed: it ended, or another
     * replaced it. An actor that has gone or can't be read keeps its entry until the next full read.
     */
    private void comparePlaying() {
        Map<Integer, Entry<Actor, Integer>> now = null;
        for (Map.Entry<Integer, Entry<Actor, Integer>> slot : last.entrySet()) {
            Entry<Actor, Integer> entry = slot.getValue();
            if (entry.value() == NONE) {
                continue;
            }
            Integer animation = entry.actor().readAnimation();
            if (animation == null || animation.equals(entry.value())) {
                continue;
            }
            if (now == null) {
                now = new LinkedHashMap<>(last);
            }
            now.put(slot.getKey(), new Entry<>(entry.actor(), animation));
            post(entry.actor());
        }
        if (now != null) {
            last = now;
        }
    }

    private void post(Actor actor) {
        AnimationChanged event = new AnimationChanged();
        event.setActor(actor);
        bus.post(event);
    }
}
