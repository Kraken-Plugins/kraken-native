package com.kraken.api.internal.draw;

import java.util.Arrays;

/**
 * A sprite as the native hook takes it: where it goes in the atlas and its pixels, premultiplied ARGB, top row first.
 * Internal: {@code DrawList} builds these and hands them to the {@link DrawHook}.
 *
 * @param x      its left edge in the atlas
 * @param y      its top edge in the atlas
 * @param width  its width
 * @param height its height
 * @param pixels its pixels, width × height premultiplied ARGB ints
 */
public record WireSprite(int x, int y, int width, int height, int[] pixels) {

    /**
     * Checks that the pixels fill the sprite.
     *
     * @param x      its left edge in the atlas
     * @param y      its top edge in the atlas
     * @param width  its width
     * @param height its height
     * @param pixels its pixels, width × height premultiplied ARGB ints
     */
    public WireSprite {
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("A " + width + "x" + height + " sprite needs " + width * height
                    + " pixels, not " + pixels.length);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WireSprite other && x == other.x && y == other.y && width == other.width
                && height == other.height && Arrays.equals(pixels, other.pixels);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * x + y) + width) + height) + Arrays.hashCode(pixels);
    }

    @Override
    public String toString() {
        return "WireSprite[" + x + "," + y + " " + width + "x" + height + "]";
    }
}
