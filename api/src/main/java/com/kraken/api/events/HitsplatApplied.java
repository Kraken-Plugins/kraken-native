package com.kraken.api.events;

import com.kraken.api.Hitsplat;
import com.kraken.api.actor.Actor;
import lombok.Value;

/**
 * A hitsplat landed on an actor. The API compares every loaded NPC's and player's hitsplats on the tick's poll and
 * the one after, and right after the client applies an NPC or player update while the native hook is connected. A
 * hitsplat the client doesn't keep (an actor already showing as many as it can, with the new one ranking below them)
 * posts nothing; neither do the ones already showing when tracking starts.
 */
@Value
public class HitsplatApplied {

    /**
     * The actor the hitsplat landed on.
     */
    Actor actor;

    /**
     * The hitsplat.
     */
    Hitsplat hitsplat;
}
