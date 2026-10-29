package com.kraken.api.internal.event;

import com.kraken.api.actor.Actor;
import com.kraken.api.events.OverheadTextChanged;
import com.kraken.api.internal.OverheadText;
import com.kraken.api.internal.event.ActorValues.Entry;

import java.util.Map;
import java.util.Set;

/**
 * {@link OverheadTextChanged} for each loaded actor whose overhead text was set: it shows other text than at the last
 * read, or the same text with more time left than its countdown allows. The client counts every actor's timer down by
 * one per actor step ({@code Client.actorSteps()}) and empties the text when it reaches 0, so between two reads a timer
 * drops by at least the steps the counter took between them unless a new line set it again. Reads every NPC's and
 * player's text and timer on the tick's poll and the one after, and at once after an NPC or player update the native
 * hook reports, since lines arrive in those (a Lua plugin's line is seen on the next tick's poll). The reads are
 * strict, so text that can't be read keeps the last one rather than looking like a new line. An actor seen for the
 * first time posts one if it shows text; tracking that starts posts none.
 */
final class OverheadTextTracker extends Tracker {

    private Map<Integer, Entry<Actor, OverheadText>> last = Map.of();

    /**
     * The step counter read right after the last read of the actors; -1 if it couldn't be read.
     */
    private int lastSteps = -1;

    OverheadTextTracker(TrackerContext context) {
        super(context, OverheadTextChanged.class);
    }

    @Override
    Set<String> triggerNames() {
        return Set.of(NativeEvent.NPC_INFO_DECODE, NativeEvent.PLAYER_INFO_DECODE);
    }

    @Override
    boolean baseline(boolean replay) {
        last = ActorValues.actors(client, Actor::readOverheadText, Map.of());
        lastSteps = client.actorSteps();
        return true;
    }

    @Override
    void update(boolean ticked) {
        if (!ticked && !afterTick() && !triggered()) {
            return;
        }
        int steps = client.actorSteps();
        Map<Integer, Entry<Actor, OverheadText>> before = last;
        Map<Integer, Entry<Actor, OverheadText>> now = ActorValues.actors(client, Actor::readOverheadText, before);
        int stepped = steps < 0 || lastSteps < 0 || steps < lastSteps ? -1 : steps - lastSteps;
        last = now;
        lastSteps = client.actorSteps();
        now.forEach((key, entry) -> {
            OverheadText text = entry.value();
            if (text.text().isEmpty()) {
                return;
            }
            Entry<Actor, OverheadText> was = before.get(key);
            if (!entry.sameActor(was) || said(was.value(), text, stepped)) {
                bus.post(new OverheadTextChanged(entry.actor(), text.text()));
            }
        });
    }

    @Override
    void forget() {
        last = Map.of();
        lastSteps = -1;
    }

    /**
     * Whether an actor's showing text was set since the last read: it is other text, or its timer is higher than it
     * could be had it only counted down.
     *
     * @param was     the last read
     * @param now     this read, showing text
     * @param stepped the actor steps the client certainly took between the two reads; -1 if unknown (the counter
     *                couldn't be read, or went back)
     * @return true if a line was said since the last read
     */
    static boolean said(OverheadText was, OverheadText now, int stepped) {
        if (!now.text().equals(was.text()) || now.cycles() > was.cycles()) {
            return true;
        }
        if (stepped < 0 || was.cycles() <= 0 || now.cycles() <= 0) {
            return false;
        }
        return now.cycles() > was.cycles() - stepped;
    }
}
