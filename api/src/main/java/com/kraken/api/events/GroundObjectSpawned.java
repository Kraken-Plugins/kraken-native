package com.kraken.api.events;

import com.kraken.api.scene.GroundObject;
import com.kraken.api.scene.Tile;
import com.kraken.api.scene.TileObject;
import lombok.Data;

/**
 * A floor decoration appeared on a tile, as RuneLite's {@code GroundObjectSpawned}.
 *
 * <p>As in RuneLite, it is also posted for every ground object of a scene the client has just loaded (no
 * despawns are posted for the old scene's), and when tracking starts as the API attaches, for every one
 * already there. Plugins clear what they keep on {@code GameStateChanged(LOADING)} and learn the new
 * scene from these. A server change (a door opening) is a despawn, then a spawn.
 */
@Data
public class GroundObjectSpawned implements TileObjectSpawned {

    /**
     * The tile it is on.
     */
    private Tile tile;

    /**
     * The ground object that appeared.
     */
    private GroundObject groundObject;

    /**
     * The object, for handlers taking any {@link TileObjectSpawned}.
     *
     * @return {@code getGroundObject()}
     */
    @Override
    public TileObject getTileObject() {
        return groundObject;
    }
}
