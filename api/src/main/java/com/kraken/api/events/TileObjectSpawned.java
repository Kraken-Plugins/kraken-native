package com.kraken.api.events;

import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileObject;

/**
 * Any of the four object spawn events ({@link GameObjectSpawned}, {@link WallObjectSpawned},
 * {@link DecorativeObjectSpawned}, {@link GroundObjectSpawned}). Kraken extra: the event bus also delivers an event to
 * subscribers of the interfaces it implements, so one handler taking this sees all four.
 */
public interface TileObjectSpawned {

    /**
     * The tile the object is on.
     *
     * @return the tile
     */
    Tile getTile();

    /**
     * The object that appeared.
     *
     * @return the object
     */
    TileObject getTileObject();
}
