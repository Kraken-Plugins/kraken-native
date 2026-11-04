package com.kraken.api.internal.draw;

/**
 * Places sprites in the native hook's atlas, shelf by shelf: left to right along a shelf as tall as its tallest
 * sprite, then a new shelf below, with a pixel between sprites so linear filtering never reads a neighbour. Space is
 * not reused until {@link #reset}. Internal: {@code DrawList} uses it.
 *
 * <p>Not thread-safe.
 */
public final class AtlasPacker {

    private static final int GAP = 1;

    private final int size;
    private final int top;
    private int shelfX;
    private int shelfY;
    private int shelfHeight;

    /**
     * Where a sprite was placed.
     *
     * @param x its left edge
     * @param y its top edge
     */
    public record Place(int x, int y) {
    }

    /**
     * An empty atlas.
     *
     * @param size     the atlas's side in pixels
     * @param reserved rows at the top that hold no sprite
     */
    public AtlasPacker(int size, int reserved) {
        this.size = size;
        this.top = reserved + GAP;
        reset();
    }

    /**
     * Finds room for a sprite.
     *
     * @param width  its width
     * @param height its height
     * @return where it goes, or null if the atlas has no room left for it
     */
    public Place place(int width, int height) {
        if (width < 1 || height < 1 || width > size || height > size) {
            return null;
        }
        if (shelfX + width > size) {
            shelfY += shelfHeight + GAP;
            shelfX = 0;
            shelfHeight = 0;
        }
        if (shelfY + height > size) {
            return null;
        }
        Place place = new Place(shelfX, shelfY);
        shelfX += width + GAP;
        shelfHeight = Math.max(shelfHeight, height);
        return place;
    }

    /**
     * Empties the atlas.
     */
    public void reset() {
        shelfX = 0;
        shelfY = top;
        shelfHeight = 0;
    }
}
