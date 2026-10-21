package com.kraken.api.internal.event;

import com.kraken.api.actor.Actor;
import com.kraken.api.events.InteractingChanged;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link InteractingChanged} for each loaded actor whose target changed, on the tick's poll and the one after.
 * An actor seen for the first time posts one if it has a target.
 */
final class InteractingTracker extends Tracker {

    private static final long NONE = -1;

    private Map<Actor, Long> last = Map.of();

    InteractingTracker(TrackerContext context) {
        super(context, InteractingChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.NPC_INFO_DECODE, NativeEvent.PLAYER_INFO_DECODE);
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
        if (!ticked && !afterTick() && !triggered()) {
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
