package com.kraken.api.internal.event;

import com.kraken.api.Hitsplat;
import com.kraken.api.actor.Actor;
import com.kraken.api.events.HitsplatApplied;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link HitsplatApplied} for each hitsplat a loaded actor gained. The client keeps an actor's hitsplats in a few
 * slots and writes a new one over a slot (or onto the end), so a slot holding a different hitsplat than at the last
 * read is a new one. Reads every NPC's and player's slots on the tick's poll and the one after, and at once after an
 * NPC or player update the native hook reports, since hitsplats only arrive in those updates. An actor seen for the
 * first time posts the hitsplats it is still showing; tracking that starts posts none.
 */
final class HitsplatTracker extends Tracker {

    private Map<Actor, List<Hitsplat>> last = Map.of();

    HitsplatTracker(TrackerContext context) {
        super(context, HitsplatApplied.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.NPC_INFO_DECODE, NativeEvent.PLAYER_INFO_DECODE);
    }

    @Override
    boolean baseline(boolean replay) {
        last = read(Map.of());
        return true;
    }

    @Override
    void update(boolean ticked) {
        if (!ticked && !afterTick() && !triggered()) {
            return;
        }
        Map<Actor, List<Hitsplat>> before = last;
        Map<Actor, List<Hitsplat>> now = read(before);
        last = now;
        int cycle = client.getGameCycle();
        now.forEach((actor, hitsplats) -> {
            List<Hitsplat> was = before.get(actor);
            for (int slot = 0; slot < hitsplats.size(); slot++) {
                Hitsplat hitsplat = hitsplats.get(slot);
                boolean added = was == null ? hitsplat.getDisappearsOnGameCycle() > cycle
                        : slot >= was.size() || !was.get(slot).equals(hitsplat);
                if (added) {
                    bus.post(new HitsplatApplied(actor, hitsplat));
                }
            }
        });
    }

    @Override
    void forget() {
        last = Map.of();
    }

    /**
     * Every loaded actor's hitsplats. An actor whose read fails keeps what the last read saw, so the next read that
     * works compares with that rather than taking every slot for new.
     *
     * @param before the last read
     * @return the hitsplats by actor, NPCs first
     */
    private Map<Actor, List<Hitsplat>> read(Map<Actor, List<Hitsplat>> before) {
        Map<Actor, List<Hitsplat>> hitsplats = new LinkedHashMap<>();
        for (Actor actor : client.npcList()) {
            put(hitsplats, before, actor);
        }
        for (Actor actor : client.playerList()) {
            put(hitsplats, before, actor);
        }
        return hitsplats;
    }

    private static void put(Map<Actor, List<Hitsplat>> into, Map<Actor, List<Hitsplat>> before, Actor actor) {
        List<Hitsplat> read = actor.hitsplats();
        List<Hitsplat> value = read != null ? read : before.get(actor);
        if (value != null) {
            into.put(actor, value);
        }
    }
}
