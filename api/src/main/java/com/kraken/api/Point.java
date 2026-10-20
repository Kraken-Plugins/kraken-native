package com.kraken.api;

import lombok.Value;

/**
 * A two-dimensional integer point: a position on the game canvas in the client's pixels, or a scene tile where an
 * API says so.
 */
@Value
public class Point {

    /**
     * The x coordinate: pixels from the canvas's left edge, or a tile x.
     */
    int x;

    /**
     * The y coordinate: pixels from the canvas's top edge, or a tile y.
     */
    int y;

    /**
     * The straight-line distance to another point.
     *
     * @param other the other point
     * @return the distance, rounded down
     */
    public int distanceTo(Point other) {
        return (int) Math.hypot(x - other.x, y - other.y);
    }
}
