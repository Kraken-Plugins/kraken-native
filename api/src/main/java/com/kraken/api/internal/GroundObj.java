package com.kraken.api.internal;

import com.kraken.api.scene.TileItem;

/**
 * One item stack on the ground (an entry of a tile's pile) as {@link SceneReader} lists it, before it becomes a
 * {@link TileItem}.
 *
 * @param id    the item id
 * @param count the stack size
 * @param coord the scene tile
 */
public record GroundObj(int id, int count, MapCoord coord) {
}
