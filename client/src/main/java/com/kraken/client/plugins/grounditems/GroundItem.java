package com.kraken.client.plugins.grounditems;

import com.kraken.api.coords.WorldPoint;
import lombok.Builder;
import lombok.Data;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;

/**
 * The plugin's record of one item on one tile: every stack of that item on the tile, added up. Client thread only.
 */
@Data
@Builder
class GroundItem {

    /**
     * Nobody owns the item: anyone can take it.
     */
    static final int OWNERSHIP_NONE = 0;

    /**
     * The local player owns the item.
     */
    static final int OWNERSHIP_SELF = 1;

    /**
     * Another player owns the item.
     */
    static final int OWNERSHIP_OTHER = 2;

    /**
     * The local player's group owns the item.
     */
    static final int OWNERSHIP_GROUP = 3;

    /**
     * The item id on the ground, noted or not.
     */
    private int id;

    /**
     * The plain item's id: the unnoted item for a noted one, else {@link #id}.
     */
    private int itemId;

    /**
     * The item's name.
     */
    private String name;

    /**
     * How many lie on the tile, over every stack.
     */
    private int quantity;

    /**
     * The tile the item lies on.
     */
    private WorldPoint location;

    /**
     * The high alchemy value of one.
     */
    private int haPrice;

    /**
     * The Grand Exchange price of one, or 0 if unknown.
     */
    private long gePrice;

    /**
     * How many lines up from the tile the item's text was drawn, as of the last frame.
     */
    private int offset;

    /**
     * Whether the item can be traded.
     */
    private boolean tradeable;

    /**
     * Who owns the item: {@link #OWNERSHIP_NONE}, {@link #OWNERSHIP_SELF}, {@link #OWNERSHIP_OTHER} or
     * {@link #OWNERSHIP_GROUP}.
     */
    private int ownership;

    /**
     * Whether only its owner ever sees the item.
     */
    private boolean isPrivate;

    /**
     * When the oldest stack appeared, or null if unknown.
     */
    private Instant spawnTime;

    /**
     * Whether the item stacks.
     */
    private boolean stackable;

    /**
     * How long after {@link #spawnTime} the item despawns.
     */
    private Duration despawnTime;

    /**
     * How long after {@link #spawnTime} other players start to see the item.
     */
    private Duration visibleTime;

    /**
     * Whether the item is highlighted, as of the last {@code updateItemColor} since the last {@link #reset()}.
     */
    boolean highlighted;

    /**
     * Whether the item is hidden, as of the last {@code updateItemColor} since the last {@link #reset()}.
     */
    boolean hidden;

    /**
     * The colour the item is drawn in, or null until worked out again after a {@link #reset()}.
     */
    Color color;

    /**
     * The high alchemy value of the whole pile.
     *
     * @return the value of one times the quantity
     */
    long getHaPrice() {
        return (long) haPrice * quantity;
    }

    /**
     * The Grand Exchange price of the whole pile.
     *
     * @return the price of one times the quantity; 0 if unknown
     */
    long getGePrice() {
        return gePrice * quantity;
    }

    /**
     * Forgets the highlighted and hidden state and the colour, so they are worked out again from the settings.
     */
    void reset() {
        highlighted = false;
        hidden = false;
        color = null;
    }
}
