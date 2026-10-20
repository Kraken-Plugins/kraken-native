package com.kraken.api.events;

import com.kraken.api.actor.Actor;
import lombok.Value;

/**
 * An actor started or stopped interacting with another, or changed target, as RuneLite's {@code InteractingChanged}.
 * Kraken compares every loaded NPC's and player's target once per game tick (and on the poll after, in case the tick's
 * updates landed late). An actor that spawns already interacting posts one after its spawn; when tracking starts as
 * the API attaches, one is posted for every actor already interacting.
 */
@Value
public class InteractingChanged {

    /**
     * The actor whose target changed.
     */
    Actor source;

    /**
     * The new target: null for none, a world entity, or a target not loaded in the top-level world.
     */
    Actor target;
}
