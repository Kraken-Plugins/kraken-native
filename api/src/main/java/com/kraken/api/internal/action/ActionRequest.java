package com.kraken.api.internal.action;

import com.kraken.api.MenuAction;
import com.kraken.api.Point;
import com.kraken.api.coords.WorldPoint;

import java.util.Objects;

/**
 * One menu row's arguments, as the client's menu builder would have captured them, and where to click first.
 * Internal: {@link Interactions} builds them and {@code Actions.run} sends them.
 *
 * @param action     the row's action
 * @param param0     the first parameter: a component's slot (-1 for none), or a tile's scene x
 * @param param1     the second parameter: a component's packed id, or a tile's scene y
 * @param identifier an NPC's or player's index, a loc's or item's id, or a component op (1-based)
 * @param itemId     a component's item, or -1 to read it from the widget
 * @param subOp      a component op's sub-option, 1-based, or 0 for none
 * @param tile       the subject's world tile for a loc, ground item or walk row; null for the others
 * @param click      where to send the mouse click packets first, in canvas pixels; null for no click
 */
public record ActionRequest(MenuAction action, int param0, int param1, int identifier, int itemId, int subOp,
                            WorldPoint tile, Point click) {

    /**
     * Checks the action is given.
     *
     * @param action     the row's action
     * @param param0     the first parameter
     * @param param1     the second parameter
     * @param identifier the identifier
     * @param itemId     a component's item, or -1
     * @param subOp      a sub-option, or 0
     * @param tile       the world tile, or null
     * @param click      the click point, or null
     */
    public ActionRequest {
        Objects.requireNonNull(action, "action");
    }

    /**
     * The same row with the click at another point.
     *
     * @param point the click point, or null for no click
     * @return the request with that click
     */
    public ActionRequest withClick(Point point) {
        return new ActionRequest(action, param0, param1, identifier, itemId, subOp, tile, point);
    }
}
