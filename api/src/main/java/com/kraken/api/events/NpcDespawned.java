package com.kraken.api.events;

import com.kraken.api.actor.Actor;
import com.kraken.api.actor.NPC;
import lombok.Value;

/**
 * An NPC left the top-level world, as RuneLite's {@code NpcDespawned}. The handle still answers its id, name and
 * location with the last values read (refreshed every game tick while it was tracked).
 */
@Value
public class NpcDespawned {

    /**
     * The NPC that left: the handle {@link NpcSpawned} carried.
     */
    NPC npc;

    /**
     * The NPC that left, as an actor.
     *
     * @return {@link #getNpc()}
     */
    public Actor getActor() {
        return npc;
    }
}
