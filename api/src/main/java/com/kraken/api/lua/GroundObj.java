package com.kraken.api.lua;

/**
 * One item stack on the ground (an entry of a tile's obj pile). Not a Lua type: the Java API's own listing of what
 * {@code ClientOp.objFind} searches.
 *
 * @param id    the item id ({@code ObjType.getId}, the id {@code objFind} takes)
 * @param count the stack size
 * @param coord the tile, in top-level scene coordinates
 */
public record GroundObj(int id, int count, MapCoord coord) {
}
