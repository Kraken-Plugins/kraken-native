package com.kraken.api.events;

import com.kraken.api.actor.NPC;
import com.kraken.api.definition.NPCComposition;
import lombok.Value;

/**
 * An NPC's own definition changed: the server transformed it into another NPC type in place, as when a boss enters
 * its next phase. The NPC keeps its handle, and {@code getNpc().getComposition()} gives the new definition. A
 * multi-NPC that shows another child type because a varbit or varp changed posts nothing, since its base type stays
 * the same.
 *
 * <p>The API compares every loaded NPC's base type id on the tick's poll and the one after, and right after the client
 * applies an NPC update while the native hook is connected.
 */
@Value
public class NpcChanged {

    /**
     * The NPC whose definition changed.
     */
    NPC npc;

    /**
     * The NPC's definition before the change; null if it can't be read.
     */
    NPCComposition old;
}
