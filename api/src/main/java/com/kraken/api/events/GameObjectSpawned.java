package com.kraken.api.events;

import com.kraken.api.GameObject;
import com.kraken.api.Tile;
import com.kraken.api.TileObject;
import lombok.Data;

/**
 * A game object (a tree, rock, table…) appeared on a tile, as RuneLite's {@code GameObjectSpawned}.
 *
 * <p>As in RuneLite, it is also posted for every game object of a scene the client has just loaded (no
 * despawns are posted for the old scene's), and when tracking starts as the API attaches, for every one
 * already there. Plugins clear what they keep on {@code GameStateChanged(LOADING)} and learn the new
 * scene from these. A server change (a door opening) is a despawn, then a spawn.
 */
@Data
public class GameObjectSpawned implements TileObjectSpawned {

    /**
     * The tile it is on.
     */
    private Tile tile;

    /**
     * The game object that appeared.
     */
    private GameObject gameObject;

    /**
     * The object, for handlers taking any {@link TileObjectSpawned}.
     *
     * @return {@code getGameObject()}
     */
    @Override
    public TileObject getTileObject() {
        return gameObject;
    }
}
