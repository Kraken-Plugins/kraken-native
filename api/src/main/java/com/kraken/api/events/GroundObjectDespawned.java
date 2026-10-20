package com.kraken.api.events;

import com.kraken.api.scene.GroundObject;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileObject;
import lombok.Data;

/**
 * A floor decoration went from a tile. Not posted for the objects of a scene the client unloads: plugins clear on
 * {@code GameStateChanged(LOADING)}.
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
