package com.kraken.api;

/**
 * Scene geometry constants, as RuneLite's {@code Perspective}. Local units are 1/128 of a tile and relative to the
 * scene's south-west corner.
 */
public final class Perspective {

    /**
     * Bits of a local coordinate below the tile: a local coordinate shifted right by this is a scene tile.
     */
    public static final int LOCAL_COORD_BITS = 7;

    /**
     * Local units per tile.
     */
    public static final int LOCAL_TILE_SIZE = 1 << LOCAL_COORD_BITS;

    /**
     * Local units per half tile: a tile's centre is its corner plus this.
     */
    public static final int LOCAL_HALF_TILE_SIZE = LOCAL_TILE_SIZE / 2;

    /**
     * A normal scene's size in tiles along each axis.
     */
    public static final int SCENE_SIZE = 104;

    private Perspective() {
    }
}
