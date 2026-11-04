package com.kraken.api.internal.draw;

import java.util.List;

/**
 * The draw list as the native hook takes it: every plugin's items in order, each anchored to an NPC, with its sprite's
 * place in the atlas. Internal: {@code DrawList} builds these and hands them to the {@link DrawHook}.
 *
 * @param items the items
 */
public record WireDrawList(List<Item> items) {

    /**
     * The atlas is this many pixels square.
     */
    public static final int ATLAS_SIZE = 1024;

    /**
     * The atlas's top-left corner, this many pixels square, is white and holds no sprite.
     */
    public static final int ATLAS_WHITE = 4;

    /**
     * The most items the hook takes.
     */
    public static final int MAX_ITEMS = 1024;

    /**
     * An item's kind: a tile area.
     */
    public static final int TILE_AREA = 0;

    /**
     * An item's kind: a sprite.
     */
    public static final int SPRITE = 1;

    /**
     * An anchor's kind: the NPC's fine position.
     */
    public static final int ANCHOR_NPC = 0;

    /**
     * An anchor's kind: the centre of the NPC's server tile.
     */
    public static final int ANCHOR_NPC_TILE = 1;

    /**
     * Copies the items.
     *
     * @param items the items
     */
    public WireDrawList {
        items = List.copyOf(items);
    }

    /**
     * One item: its anchor, then the fields of its kind (the others are 0).
     *
     * @param kind        {@link #TILE_AREA} or {@link #SPRITE}
     * @param anchor      {@link #ANCHOR_NPC} or {@link #ANCHOR_NPC_TILE}
     * @param index       the NPC's index
     * @param spawnCycle  the cycle the NPC was created on
     * @param dx          the shift east from the anchor, in local units
     * @param dy          the shift north, in local units
     * @param lift        how far above the ground, in local units
     * @param sizeX       a tile area's width in tiles
     * @param sizeY       its length in tiles
     * @param stroke      its outline colour, ARGB (0 for none)
     * @param fill        its fill colour, ARGB (0 for none)
     * @param strokeWidth its outline width in sixteenths of a client pixel
     * @param atlasX      a sprite's left edge in the atlas
     * @param atlasY      its top edge in the atlas
     * @param width       its width
     * @param height      its height
     * @param offsetX     its left edge relative to the anchor, in framebuffer pixels
     * @param offsetY     its top edge relative to the anchor, in framebuffer pixels
     */
    public record Item(int kind, int anchor, int index, int spawnCycle, int dx, int dy, int lift, int sizeX, int sizeY,
                       int stroke, int fill, int strokeWidth, int atlasX, int atlasY, int width, int height,
                       int offsetX, int offsetY) {

        /**
         * A tile area.
         *
         * @param anchor      the anchor kind
         * @param index       the NPC's index
         * @param spawnCycle  the cycle the NPC was created on
         * @param dx          the shift east, in local units
         * @param dy          the shift north, in local units
         * @param lift        how far above the ground, in local units
         * @param sizeX       the width in tiles
         * @param sizeY       the length in tiles
         * @param stroke      the outline colour, ARGB
         * @param fill        the fill colour, ARGB
         * @param strokeWidth the outline width in sixteenths of a client pixel
         * @return the item
         */
        public static Item tileArea(int anchor, int index, int spawnCycle, int dx, int dy, int lift, int sizeX,
                                    int sizeY, int stroke, int fill, int strokeWidth) {
            return new Item(TILE_AREA, anchor, index, spawnCycle, dx, dy, lift, sizeX, sizeY, stroke, fill,
                    strokeWidth, 0, 0, 0, 0, 0, 0);
        }

        /**
         * A sprite.
         *
         * @param anchor     the anchor kind
         * @param index      the NPC's index
         * @param spawnCycle the cycle the NPC was created on
         * @param dx         the shift east, in local units
         * @param dy         the shift north, in local units
         * @param lift       how far above the ground, in local units
         * @param atlasX     the sprite's left edge in the atlas
         * @param atlasY     its top edge in the atlas
         * @param width      its width
         * @param height     its height
         * @param offsetX    its left edge relative to the anchor, in framebuffer pixels
         * @param offsetY    its top edge relative to the anchor, in framebuffer pixels
         * @return the item
         */
        public static Item sprite(int anchor, int index, int spawnCycle, int dx, int dy, int lift, int atlasX,
                                  int atlasY, int width, int height, int offsetX, int offsetY) {
            return new Item(SPRITE, anchor, index, spawnCycle, dx, dy, lift, 0, 0, 0, 0, 0, atlasX, atlasY, width,
                    height, offsetX, offsetY);
        }
    }
}
