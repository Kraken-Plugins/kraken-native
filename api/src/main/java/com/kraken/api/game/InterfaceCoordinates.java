package com.kraken.api.game;

/**
 * Mirrors {@code osrs.IfType.InterfaceCoordinates}: where an open interface group is drawn, in client pixels (the
 * units of {@link Graphics#clientDimensions()}).
 *
 * @param clipX1     left edge of the group's clipping region
 * @param clipY1     top edge of the clipping region
 * @param clipX2     right edge of the clipping region
 * @param clipY2     bottom edge of the clipping region
 * @param offX       x offset applied to every component of the group
 * @param offY       y offset applied to every component of the group
 * @param scriptOffX extra x offset a Lua plugin applied ({@code setRenderOffset})
 * @param scriptOffY extra y offset a Lua plugin applied
 */
public record InterfaceCoordinates(int clipX1, int clipY1, int clipX2, int clipY2, int offX, int offY,
                                   int scriptOffX, int scriptOffY) {

    /**
     * What the client reports for a group that isn't open.
     */
    public static final InterfaceCoordinates NONE = new InterfaceCoordinates(0, 0, 0, 0, 0, 0, 0, 0);
}
