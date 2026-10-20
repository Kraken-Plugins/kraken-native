package com.kraken.api.events;

import com.kraken.api.GroundObject;
import com.kraken.api.Tile;
import com.kraken.api.TileObject;
import lombok.Data;

/**
 * A floor decoration went from a tile, as RuneLite's {@code GroundObjectDespawned}. Not posted for the objects of a
 * scene the client unloads: plugins clear on {@code GameStateChanged(LOADING)}.
 */
@Data
public class GroundObjectDespawned implements TileObjectDespawned {

    /**
     * The tile it was on.
     */
    private Tile tile;

    /**
     * The ground object that went: equal to the one its spawn event carried.
     */
    private GroundObject groundObject;

    /**
     * The object, for handlers taking any {@link TileObjectDespawned}.
     *
     * @return {@code getGroundObject()}
     */
    @Override
    public TileObject getTileObject() {
        return groundObject;
    }
}
