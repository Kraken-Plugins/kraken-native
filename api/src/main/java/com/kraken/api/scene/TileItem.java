package com.kraken.api.scene;

import com.kraken.api.action.ActionResult;
import lombok.Getter;
import lombok.Setter;

/**
 * An item stack lying on the ground. The whole pile on a tile is read at once ({@link Tile#getGroundItems()}). The
 * despawn time, ownership and privacy aren't read.
 *
 * <p>A handle with identity: the item spawn events carry one handle per stack, the same in its spawn, quantity change
 * and despawn events, and its quantity follows {@code ItemQuantityChanged}. {@link Tile#getGroundItems()} and
 * {@link Scene#getGroundItems()} make new handles each call, so compare those by id and tile.
 */
@Getter
public final class TileItem {

    /**
     * The item's id, as in {@code gameval.ItemID}.
     */
    private final int id;

    /**
     * The stack's size, as last read. Internal: the event source sets it as it follows the stack.
     */
    @Setter
    private volatile int quantity;

    /**
     * The tile the stack lies on.
     */
    private final Tile tile;

    /**
     * A stack as one read of its pile found it.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param id       the item id
     * @param quantity the stack size
     * @param tile     the tile it lies on
     */
    public TileItem(int id, int quantity, Tile tile) {
        this.id = id;
        this.quantity = quantity;
        this.tile = tile;
    }

    /**
     * The item's name.
     *
     * @return the name, or null if the item's definition can't be read
     */
    public String getName() {
        return tile.client().getInteractions().name(this);
    }

    /**
     * The options the item's menu offers on the ground, which {@link #interact(String)} matches against: its ground
     * actions, with conditional actions resolved against the current vars. Take is op 3.
     *
     * @return the options by op, index 0 being op 1, null entries for empty slots; null if the item's definition can't
     *         be read
     */
    public String[] getActions() {
        return tile.client().getInteractions().actions(this);
    }

    /**
     * Runs one of the item's ground options, as clicking it in the menu does. The option is matched loosely: markup
     * and case are ignored, and spaces, {@code -} and {@code _} count as the same.
     *
     * @param option the option's text, such as "Take"
     * @return {@link ActionResult#SENT}, or why nothing was sent: {@link ActionResult#GONE} once its tile's pile no
     *         longer holds the item, {@link ActionResult#NO_SUCH_OPTION} if it has no such option
     * @throws NullPointerException if the option is null
     */
    public ActionResult interact(String option) {
        return tile.client().getInteractions().interact(this, option);
    }

    /**
     * Runs one of the item's ground options by its number, as the menu numbers them.
     *
     * @param op the op, 1-5: index + 1 in {@link #getActions()}
     * @return {@link ActionResult#SENT}, or why nothing was sent; {@link ActionResult#NO_SUCH_OPTION} for a number
     *         out of range or an empty slot
     */
    public ActionResult interact(int op) {
        return tile.client().getInteractions().interact(this, op);
    }

    /**
     * Picks the item up: short for {@code interact("Take")}.
     *
     * @return {@link ActionResult#SENT}, or why nothing was sent
     */
    public ActionResult take() {
        return interact("Take");
    }

    /**
     * Whether a Lua plugin has hidden the item. No Lua function hides ground items, so this is false.
     *
     * @return false
     */
    public boolean isHidden() {
        return false;
    }

    @Override
    public String toString() {
        return "TileItem[" + id + " x" + quantity + "]";
    }
}
