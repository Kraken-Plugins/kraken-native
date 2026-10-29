package com.kraken.api.events;

import com.kraken.api.actor.Actor;
import lombok.Value;

/**
 * An actor's overhead text was set: an NPC or player said something (an NPC's shout, a player's public chat, a forced
 * line), or a Lua plugin made it say something. Not posted when the text times out and disappears.
 *
 * <p>The API compares every loaded NPC's and player's overhead text and its timer on the tick's poll and the one
 * after, and right after the client applies an NPC or player update while the native hook is connected. The same line
 * said again posts again: its timer then shows more time left than its countdown allows. The lines already showing
 * when tracking starts post nothing; an actor that comes into view saying something posts one.
 */
@Value
public class OverheadTextChanged {

    /**
     * The actor whose overhead text was set.
     */
    Actor actor;

    /**
     * The text it shows.
     */
    String overheadText;
}
