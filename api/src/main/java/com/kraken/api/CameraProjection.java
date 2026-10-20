package com.kraken.api;

/**
 * One frame's camera: turns points of the top-level scene into canvas positions. Kraken extra, from
 * {@link Client#getCameraProjection()}. {@link Perspective} reads one per call; code that projects many points for the
 * same frame can read one and reuse it, saving the camera reads each {@code Perspective} call makes.
 *
 * <p>It's a snapshot: once the camera moves, a new one is needed.
 */
public interface CameraProjection {

    /**
     * Projects a point of the top-level scene, as the client's Lua {@code Graphics.worldToScreenCoord} does.
     *
     * @param x the local x (east, 128 per tile)
     * @param y the local y (north)
     * @param z the height (more negative is higher), as {@link Perspective#getTileHeight} returns it
     * @return the canvas position in the client's pixels (it may be off the canvas), or null if the point is at or
     *         behind the camera
     */
    Point project(int x, int y, int z);
}
