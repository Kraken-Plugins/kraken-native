package com.kraken.api.events;

import com.kraken.api.GameObject;
import com.kraken.api.Tile;
import com.kraken.api.TileObject;
import lombok.Data;

/**
 * A game object (a tree, rock, table…) went from a tile, as RuneLite's {@code GameObjectDespawned}. Not posted for the objects of a
 * scene the client unloads: plugins clear on {@code GameStateChanged(LOADING)}.
 */
@Data
public class GameObjectDespawned implements TileObjectDespawned {

    /**
     * The tile it was on.
     */
    private Tile tile;

    /**
     * The game object that went: equal to the one its spawn event carried.
     */
    private GameObject gameObject;

    /**
     * The object, for handlers taking any {@link TileObjectDespawned}.
     *
     * @return {@code getGameObject()}
     */
    @Override
    public TileObject getTileObject() {
        return gameObject;
    }
}
