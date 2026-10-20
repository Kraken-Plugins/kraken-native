package com.kraken.api.events;

import com.kraken.api.Tile;
import com.kraken.api.TileObject;

/**
 * Any of the four object despawn events ({@link GameObjectDespawned}, {@link WallObjectDespawned},
 * {@link DecorativeObjectDespawned}, {@link GroundObjectDespawned}). Kraken extra, as {@link TileObjectSpawned}.
 */
public interface TileObjectDespawned {

    /**
     * The tile the object was on.
     *
     * @return the tile
     */
    Tile getTile();

    /**
     * The object that went.
     *
     * @return the object
     */
    TileObject getTileObject();
}
