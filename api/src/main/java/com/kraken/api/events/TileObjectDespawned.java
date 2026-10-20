package com.kraken.api.events;

import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileObject;

/**
 * Any of the four object despawn events ({@link GameObjectDespawned}, {@link WallObjectDespawned},
 * {@link DecorativeObjectDespawned}, {@link GroundObjectDespawned}). The counterpart of {@link TileObjectSpawned}.
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
