package com.kraken.api;

/**
 * A free-standing object, such as a tree, rock, anvil or table, as RuneLite's {@code GameObject}. It can cover more than
 * one tile.
 *
 * <p>Differs from RuneLite's in one way: its size is its definition's ({@link ObjectComposition#getSizeX()} and
 * {@link ObjectComposition#getSizeY()}), before rotation, since the object's rotation isn't read. RuneLite's sizes
 * follow the rotation, so for an object turned a quarter the two are swapped. The footprint, centre and outline follow
 * the same sizes.
 */
public interface GameObject extends TileObject {

    /**
     * The object's size along x.
     *
     * @return the width in tiles, from its definition; 1 if the definition can't be read
     */
    int sizeX();

    /**
     * The object's size along y.
     *
     * @return the length in tiles, from its definition; 1 if the definition can't be read
     */
    int sizeY();

    /**
     * The object's south-west tile in the scene.
     *
     * @return the scene x and y of the tile it was placed on
     */
    Point getSceneMinLocation();

    /**
     * The object's north-east tile in the scene.
     *
     * @return {@link #getSceneMinLocation()} plus the size, less one tile
     */
    Point getSceneMaxLocation();
}
