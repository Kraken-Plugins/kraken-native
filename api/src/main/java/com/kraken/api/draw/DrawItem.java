package com.kraken.api.draw;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Something a {@link DrawLayer} draws in the game's frame, tied to an {@link Anchor}.
 */
public sealed interface DrawItem permits DrawItem.TileArea, DrawItem.Sprite {

    /**
     * The anchor the item follows.
     *
     * @return the anchor
     */
    Anchor anchor();

    /**
     * A rectangle of tiles centred on the anchor, its corners following the ground: an outline, then a fill over it.
     *
     * @param anchor      the area's centre
     * @param sizeX       its width in tiles, 1 to 64
     * @param sizeY       its length in tiles, 1 to 64
     * @param stroke      the outline's colour, or null for no outline
     * @param fill        the fill's colour, or null for no fill
     * @param strokeWidth the outline's width in the client's pixels
     */
    record TileArea(Anchor anchor, int sizeX, int sizeY, Color stroke, Color fill, float strokeWidth)
            implements DrawItem {

        /**
         * The widest a tile area can be, in tiles.
         */
        public static final int MAX_SIZE = 64;

        /**
         * Checks the anchor and the size.
         *
         * @param anchor      the area's centre
         * @param sizeX       its width in tiles, 1 to 64
         * @param sizeY       its length in tiles, 1 to 64
         * @param stroke      the outline's colour, or null for no outline
         * @param fill        the fill's colour, or null for no fill
         * @param strokeWidth the outline's width in the client's pixels
         */
        public TileArea {
            Objects.requireNonNull(anchor, "anchor");
            if (sizeX < 1 || sizeX > MAX_SIZE || sizeY < 1 || sizeY > MAX_SIZE) {
                throw new IllegalArgumentException("Tile area size " + sizeX + "x" + sizeY + " outside 1 to "
                        + MAX_SIZE);
            }
        }
    }

    /**
     * An image drawn at the anchor's place on the screen, pixel for pixel. The image is in framebuffer pixels and is
     * matched by identity: hand over the same image object for the same picture, and don't change it afterwards.
     *
     * @param anchor  where the image is placed
     * @param image   the image
     * @param offsetX the image's left edge relative to the anchor, in framebuffer pixels
     * @param offsetY its top edge relative to the anchor, in framebuffer pixels
     */
    record Sprite(Anchor anchor, BufferedImage image, int offsetX, int offsetY) implements DrawItem {

        /**
         * Checks the anchor and the image.
         *
         * @param anchor  where the image is placed
         * @param image   the image
         * @param offsetX the image's left edge relative to the anchor, in framebuffer pixels
         * @param offsetY its top edge relative to the anchor, in framebuffer pixels
         */
        public Sprite {
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(image, "image");
        }
    }
}
