package com.kraken.api.events;

import com.kraken.api.actor.Actor;
import lombok.Data;

/**
 * An actor started an animation, switched to another, or its animation ended ({@code Actor.getAnimation()} then
 * returns -1). Read the new animation with {@code getActor().getAnimation()}.
 *
 * <p>The API compares every loaded NPC's and player's animation on the tick's poll and the one after, and right after
 * the client applies an NPC or player update or sets an NPC's animation while the native hook is connected; on the
 * other polls it re-reads the actors playing an animation, so an animation's end is posted at once. An actor that
 * comes into view playing an animation posts one; when tracking starts as the API attaches, one is posted for every
 * actor already playing one. Restarting the animation an actor is playing posts nothing, nor does an animation that
 * starts and ends between two reads.
 */
@Data
public class AnimationChanged {

    /**
     * The actor whose animation changed.
     */
    private Actor actor;
}
