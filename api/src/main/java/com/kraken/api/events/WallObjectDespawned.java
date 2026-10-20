package com.kraken.api.events;

import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileObject;
import com.kraken.api.scene.WallObject;
import lombok.Data;

/**
 * A wall, fence or door went from a tile. Not posted for the objects of a scene the client unloads: plugins clear on
 * {@code GameStateChanged(LOADING)}.
 */
@Data
public class WallObjectDespawned implements TileObjectDespawned {

    /**
     * The tile it was on.
     */
    private Tile tile;

    /**
     * The wall object that went: equal to the one its spawn event carried.
     */
    private WallObject wallObject;

    /**
     * The object, for handlers taking any {@link TileObjectDespawned}.
     *
     * @return {@code getWallObject()}
     */
    @Override
    public TileObject getTileObject() {
        return wallObject;
    }
}
