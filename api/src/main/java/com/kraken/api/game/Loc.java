package com.kraken.api.game;

/**
 * A loc (scenery: a wall, a door, a tree, a ground decoration…) on a scene tile. Not a Lua type: the Java API's own
 * listing of what {@code ClientOp.locFind} searches.
 *
 * @param id    the loc type id ({@code LocType.getId}, the id {@code locFind} takes)
 * @param layer which slot of the tile holds it: {@link #WALL}, {@link #WALL_DECOR}, {@link #GAME_OBJECT} or
 *              {@link #GROUND_DECOR}
 * @param coord the tile, in top-level scene coordinates; a game object spanning several tiles is listed once, on its
 *              origin tile
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

    /**
     * The layer's name.
     *
     * @return "wall", "wall decor", "game object" or "ground decor"
     */
    public String layerName() {
        return switch (layer) {
            case WALL -> "wall";
            case WALL_DECOR -> "wall decor";
            case GAME_OBJECT -> "game object";
            case GROUND_DECOR -> "ground decor";
            default -> "layer " + layer;
        };
    }
}
