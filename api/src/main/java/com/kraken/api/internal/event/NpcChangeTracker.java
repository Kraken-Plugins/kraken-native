package com.kraken.api.internal.event;

import com.kraken.api.actor.NPC;
import com.kraken.api.events.NpcChanged;
import com.kraken.api.internal.event.ActorValues.Entry;

import java.util.Map;
import java.util.Set;

/**
 * {@link NpcChanged} for each loaded NPC whose base type changed. The client applies a transform from the server to the
 * NPC in place (the update decoder assigns the new type to the same NPC), so the NPC keeps its handle and only its type
 * id moves. Reads every NPC's type id on the tick's poll and the one after, and at once after an NPC update the native
 * hook reports. A multi-NPC showing another child type isn't a change: its base type stays the same. An NPC seen for
 * the first time, and tracking that starts, post nothing.
 */
final class NpcChangeTracker extends Tracker {

    private Map<Integer, Entry<NPC, Integer>> last = Map.of();

    NpcChangeTracker(TrackerContext context) {
        super(context, NpcChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.NPC_INFO_DECODE);
    }

    @Override
    boolean baseline(boolean replay) {
        last = ActorValues.npcs(client, NPC::readId, Map.of());
        return true;
    }

    @Override
    void update(boolean ticked) {
        if (!ticked && !afterTick() && !triggered()) {
            return;
        }
        Map<Integer, Entry<NPC, Integer>> before = last;
        Map<Integer, Entry<NPC, Integer>> now = ActorValues.npcs(client, NPC::readId, before);
        last = now;
        now.forEach((key, entry) -> {
            Entry<NPC, Integer> was = before.get(key);
            if (entry.sameActor(was) && !was.value().equals(entry.value())) {
                bus.post(new NpcChanged(entry.actor(), client.getNpcDefinition(was.value())));
            }
        });
    }

    @Override
    void forget() {
        last = Map.of();
    }
}
