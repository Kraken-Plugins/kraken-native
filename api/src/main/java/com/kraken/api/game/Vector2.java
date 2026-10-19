package com.kraken.api.game;

/**
 * A pair of floats, mirroring the Lua {@code osrs.Vector2}: a screen position, a size, or a pair of angles.
 *
 * @param x the first value (a screen x, a width, or the pitch for {@link Camera#getLookAtAngles})
 * @param y the second value (a screen y, a height, or the yaw for {@link Camera#getLookAtAngles})
 */
public record Vector2(float x, float y) {

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
