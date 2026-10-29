package com.kraken.api.events;

import com.kraken.api.actor.Actor;
import lombok.Value;

/**
 * An actor died: its health bar started showing empty ({@code Actor.isDead()} turned true). Posted once per death, for
 * NPCs and players alike, the local player included.
 *
 * <p>The API compares every loaded NPC's and player's head bars on the tick's poll and the one after, and right after
 * the client applies an NPC or player update while the native hook is connected. An actor that comes into view already
 * dead posts one; the actors already dead when tracking starts post none. An actor counts as alive again once its
 * empty health bar has gone (a later update shows health, or the bar times out), so it can die again.
 */
@Value
public class ActorDeath {

    /**
     * The actor that died.
     */
    Actor actor;
}
