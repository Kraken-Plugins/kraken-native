package com.kraken.api.internal;

import com.kraken.api.scene.Scene;
import com.kraken.api.scene.TileObject;

/**
 * An object on a scene tile as {@link SceneReader} lists it, before {@link Scene} turns it into a {@link TileObject}.
 *
 * @param id    the object (loc type) id
 * @param layer which slot of the tile holds it: {@link #WALL}, {@link #WALL_DECOR}, {@link #GAME_OBJECT} or
 *              {@link #GROUND_DECOR}
 * @param coord the scene tile; a game object spanning several tiles is listed once, on its origin tile
 */
public record Loc(int id, int layer, MapCoord coord) {

    /**
     * A wall or fence on the tile's edge.
     */
    public static final int WALL = 0;

    /**
     * A decoration hung on a wall.
     */
    public static final int WALL_DECOR = 1;

    /**
     * A free-standing object such as a tree, rock or table.
     */
    public static final int GAME_OBJECT = 2;

    /**
     * A ground decoration such as flowers or a rug.
     */
    public static final int GROUND_DECOR = 3;
}
