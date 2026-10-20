package com.kraken.api.events;

import com.kraken.api.actor.Actor;
import com.kraken.api.actor.Player;
import lombok.Value;

/**
 * A player left. The handle still answers its name and location with the last values read (refreshed every game tick
 * while it was tracked).
 */
@Value
public class PlayerDespawned {

    /**
     * The player that left: the handle {@link PlayerSpawned} carried.
     */
    Player player;

    /**
     * The player that left, as an actor.
     *
     * @return {@link #getPlayer()}
     */
    public Actor getActor() {
        return player;
    }
}
