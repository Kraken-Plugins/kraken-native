package com.kraken.api.events;

import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import lombok.Value;

/**
 * An NPC was loaded into the top-level world. When tracking starts as the API attaches, every NPC already loaded is
 * posted as spawned.
 */
@Value
public class NpcSpawned {

    /**
     * The spawned NPC: the same handle {@code Client.getNpcs()} returns while it stays loaded.
     */
    NPC npc;

    /**
     * The spawned NPC as an actor.
     *
     * @return {@link #getNpc()}
     */
    public Actor getActor() {
        return npc;
    }
}
