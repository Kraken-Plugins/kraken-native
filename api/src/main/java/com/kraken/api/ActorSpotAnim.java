package com.kraken.api;

import com.kraken.api.gameval.SpotanimID;
import lombok.Value;

/**
 * A spot animation an actor shows (a spell's glow, a teleport, a special attack's effect), as it was read: one of the
 * actor's slots. The server sets each one in a slot; a new one in the same slot takes its place, and the client drops
 * it once its animation has played.
 */
@Value
public class ActorSpotAnim {

    /**
     * The slot the server put it in, the key it is kept under.
     */
    int slot;

    /**
     * The spot animation's id, one of {@link SpotanimID}'s.
     */
    int id;

    /**
     * How high above the actor's feet it plays.
     */
    int height;

    /**
     * The client cycle it starts playing on ({@link Client#getGameCycle()}): the cycle it arrived, plus its delay.
     * Until then it isn't drawn.
     */
    int startCycle;

    /**
     * The frame of its animation it is on; -1 until it starts.
     */
    int frame;
}
