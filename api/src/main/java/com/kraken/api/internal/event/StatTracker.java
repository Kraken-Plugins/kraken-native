package com.kraken.api.internal.event;

import com.kraken.api.Client;
import com.kraken.api.Skill;
import com.kraken.api.events.StatChanged;

import java.util.Set;

/**
 * {@link StatChanged} for each skill whose XP, level or boosted level changed. Every skill fires when the stats
 * come back after a logout.
 */
final class StatTracker extends Tracker {

    private static final Skill[] SKILLS = Skill.values();

    private Client.Stats last;

    StatTracker(TrackerContext context) {
        super(context, StatChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of("ON_STAT_TRANSMIT");
    }

    @Override
    boolean baseline(boolean replay) {
        last = client.stats();
        if (replay && last != null) {
            postChanged(null, last);
        }
        return true;
    }

    @Override
    void update(boolean ticked) {
        Client.Stats now = client.stats();
        Client.Stats before = last;
        last = now;
        if (now != null) {
            postChanged(before, now);
        }
    }

    @Override
    void forget() {
        last = null;
    }

    private void postChanged(Client.Stats before, Client.Stats now) {
        int skills = Math.min(SKILLS.length, now.xp().length);
        for (int id = 0; id < skills; id++) {
            if (before == null || id >= before.xp().length || now.xp()[id] != before.xp()[id]
                    || now.level()[id] != before.level()[id] || now.base()[id] != before.base()[id]) {
                bus.post(new StatChanged(SKILLS[id], now.xp()[id], now.base()[id], now.level()[id]));
            }
        }
    }
}
