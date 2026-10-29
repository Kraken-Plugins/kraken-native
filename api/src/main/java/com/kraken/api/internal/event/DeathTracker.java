package com.kraken.api.internal.event;

import com.kraken.api.actor.Actor;
import com.kraken.api.events.ActorDeath;
import com.kraken.api.internal.event.ActorValues.Entry;

import java.util.Map;
import java.util.Set;

/**
 * {@link ActorDeath} for each loaded actor that died: {@code Actor.isDead()} (a health bar showing, one of its head
 * bars showing empty) turned true. Reads every NPC's and player's head bars on the tick's poll and the one after, and
 * at once after an NPC or player update the native hook reports, since head bar updates only arrive in those. The read
 * is strict, so head bars that can't be read keep the last answer rather than looking like the actor coming back to
 * life and dying again. An actor seen for the first time posts one if it is dead; tracking that starts posts none.
 */
final class DeathTracker extends Tracker {

    private Map<Integer, Entry<Actor, Boolean>> last = Map.of();

    DeathTracker(TrackerContext context) {
        super(context, ActorDeath.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.NPC_INFO_DECODE, NativeEvent.PLAYER_INFO_DECODE);
    }

    @Override
    boolean baseline(boolean replay) {
        last = ActorValues.actors(client, Actor::readDead, Map.of());
        return true;
    }

    @Override
    void update(boolean ticked) {
        if (!ticked && !afterTick() && !triggered()) {
            return;
        }
        Map<Integer, Entry<Actor, Boolean>> before = last;
        Map<Integer, Entry<Actor, Boolean>> now = ActorValues.actors(client, Actor::readDead, before);
        last = now;
        now.forEach((key, entry) -> {
            Entry<Actor, Boolean> was = before.get(key);
            if (entry.value() && !(entry.sameActor(was) && was.value())) {
                bus.post(new ActorDeath(entry.actor()));
            }
        });
    }

    @Override
    void forget() {
        last = Map.of();
    }
}
