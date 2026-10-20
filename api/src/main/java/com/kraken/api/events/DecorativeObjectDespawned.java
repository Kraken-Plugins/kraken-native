package com.kraken.api.events;

import com.kraken.api.DecorativeObject;
import com.kraken.api.Tile;
import com.kraken.api.TileObject;
import lombok.Data;

/**
 * A wall decoration went from a tile, as RuneLite's {@code DecorativeObjectDespawned}. Not posted for the objects of a
 * scene the client unloads: plugins clear on {@code GameStateChanged(LOADING)}.
 */
@Data
public class DecorativeObjectDespawned implements TileObjectDespawned {

    /**
     * The tile it was on.
     */
    private Tile tile;

    /**
     * The decorative object that went: equal to the one its spawn event carried.
     */
    private DecorativeObject decorativeObject;

    /**
     * The object, for handlers taking any {@link TileObjectDespawned}.
     *
     * @return {@code getDecorativeObject()}
     */
    @Override
    public TileObject getTileObject() {
        return decorativeObject;
    }
}
