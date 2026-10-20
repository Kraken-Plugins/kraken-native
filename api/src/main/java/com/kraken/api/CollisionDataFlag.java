package com.kraken.api;

/**
 * The bits of a tile's collision flags ({@link CollisionData#getFlags()}), named as in RuneLite's
 * {@code CollisionDataFlag}. They are the client's own bits, the same as Lua's {@code CollisionFlag}, which names
 * more of them (its {@code W_*}, {@code V_*} and {@code WR_*} bits are these).
 */
public final class CollisionDataFlag {

    /**
     * Blocks movement across the tile's north-west corner.
     */
    public static final int BLOCK_MOVEMENT_NORTH_WEST = 0x1;

    /**
     * Blocks movement across the tile's north edge.
     */
    public static final int BLOCK_MOVEMENT_NORTH = 0x2;

    /**
     * Blocks movement across the tile's north-east corner.
     */
    public static final int BLOCK_MOVEMENT_NORTH_EAST = 0x4;

    /**
     * Blocks movement across the tile's east edge.
     */
    public static final int BLOCK_MOVEMENT_EAST = 0x8;

    /**
     * Blocks movement across the tile's south-east corner.
     */
    public static final int BLOCK_MOVEMENT_SOUTH_EAST = 0x10;

    /**
     * Blocks movement across the tile's south edge.
     */
    public static final int BLOCK_MOVEMENT_SOUTH = 0x20;

    /**
     * Blocks movement across the tile's south-west corner.
     */
    public static final int BLOCK_MOVEMENT_SOUTH_WEST = 0x40;

    /**
     * Blocks movement across the tile's west edge.
     */
    public static final int BLOCK_MOVEMENT_WEST = 0x80;

    /**
     * An object stands on the tile and blocks it.
     */
    public static final int BLOCK_MOVEMENT_OBJECT = 0x100;

    /**
     * A floor decoration blocks the tile.
     */
    public static final int BLOCK_MOVEMENT_FLOOR_DECORATION = 0x40000;

    /**
     * The floor itself blocks the tile (water, say).
     */
    public static final int BLOCK_MOVEMENT_FLOOR = 0x200000;

    /**
     * Any of the three whole-tile blocks.
     */
    public static final int BLOCK_MOVEMENT_FULL = BLOCK_MOVEMENT_OBJECT | BLOCK_MOVEMENT_FLOOR_DECORATION
            | BLOCK_MOVEMENT_FLOOR;

    /**
     * Blocks line of sight across the tile's north edge.
     */
    public static final int BLOCK_LINE_OF_SIGHT_NORTH = 0x400;

    /**
     * Blocks line of sight across the tile's east edge.
     */
    public static final int BLOCK_LINE_OF_SIGHT_EAST = 0x1000;

    /**
     * Blocks line of sight across the tile's south edge.
     */
    public static final int BLOCK_LINE_OF_SIGHT_SOUTH = 0x4000;

    /**
     * Blocks line of sight across the tile's west edge.
     */
    public static final int BLOCK_LINE_OF_SIGHT_WEST = 0x10000;

    /**
     * Blocks line of sight through the whole tile.
     */
    public static final int BLOCK_LINE_OF_SIGHT_FULL = 0x20000;

    private CollisionDataFlag() {
    }
}
