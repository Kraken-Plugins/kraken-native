package com.kraken.api.events;

import com.kraken.api.actor.Actor;
import lombok.Data;

/**
 * An actor's spot animations changed: the server set one (a spell's glow, a teleport, breaking a teleport tab), cleared
 * one, or one finished playing. Read them with {@code getActor().getSpotAnims()} or {@code hasSpotAnim(id)}.
 *
 * <p>The API compares every loaded NPC's and player's spot animations on the tick's poll and the one after, and right
 * after the client applies an NPC or player update while the native hook is connected; on the other polls it re-reads
 * the actors showing one, so an end is posted at once. A spot animation set again in its slot posts again, even with
 * the same id. An actor that comes into view showing one posts one; when tracking starts as the API attaches, one is
 * posted for every actor already showing one. One that is set and ends between two reads posts nothing.
 */
@Data
public class GraphicChanged {

    /**
     * The actor whose spot animations changed.
     */
    private Actor actor;
}
