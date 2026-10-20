package com.kraken.api.events;

import com.kraken.api.actor.Actor;
import com.kraken.api.actor.Player;
import lombok.Value;

/**
 * A player was loaded, the local player included, as RuneLite's {@code PlayerSpawned}. Lua has no player events:
 * Kraken diffs the client's player list. When tracking starts as the API attaches, every player already loaded is
 * posted as spawned.
 */
@Value
public class PlayerSpawned {

    /**
     * The spawned player: the same handle {@code Client.getPlayers()} returns while it stays loaded.
     */
    Player player;

    /**
     * The spawned player as an actor.
     *
     * @return {@link #getPlayer()}
     */
    public Actor getActor() {
        return player;
    }
}
