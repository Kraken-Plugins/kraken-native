package com.kraken.api.highlight;

import lombok.With;

import java.awt.Color;
import java.util.Objects;

/**
 * How one highlight channel draws what is lit on it. Mirrors {@code osrs.ClientOp.HighlightConfig}. The client draws
 * highlights inside the 3D scene, so they follow moving actors and the camera and are hidden behind walls unless
 * {@code alwaysOnTop}. Start from {@link #DEFAULT} (the Lua constructor's defaults) or a factory and change fields with
 * the {@code with*} methods.
 *
 * <p>The client keeps the colour in its 16-bit HSL form, so the colour drawn can differ slightly from the one given.
 *
 * @param highlightColour    the outline and fill colour; its alpha is the fill's opacity
 * @param outlineWidth       the outline's width in pixels, 0 to 65535
 * @param enableModelOutline outline the model (NPCs, players, objects, ground items)
 * @param enableModelFill    fill the model
 * @param enableTileOutline  outline the tiles under it
 * @param enableTileFill     fill the tiles under it
 * @param alwaysOnTop        draw through walls and other models
 * @param showInsideEdges    for something bigger than a tile, also draw the edges between its tiles
 * @param snapToGrid         for something bigger than a tile, snap its tile area to the tile grid
 * @param swOnly             for something bigger than a tile, only its south-west tile
 * @param ignoreRebuild      keep the highlight when the map rebuilds
 * @param saveToFile         have the client save the channel's highlights to its own files and load them next time
 * @param disableModel       documented by the client as having no effect yet
 */
@With
public record HighlightConfig(Color highlightColour, int outlineWidth, boolean enableModelOutline,
                              boolean enableModelFill, boolean enableTileOutline, boolean enableTileFill,
                              boolean alwaysOnTop, boolean showInsideEdges, boolean snapToGrid, boolean swOnly,
                              boolean ignoreRebuild, boolean saveToFile, boolean disableModel) {

    /**
     * {@code HighlightConfig.new()}: a solid white model outline, 1 pixel wide.
     */
    public static final HighlightConfig DEFAULT = new HighlightConfig(Color.WHITE, 1, true, false, false, false,
            false, false, false, false, false, false, false);

    /**
     * Checks the colour is set and the width fits the client's 16 bits.
     *
     * @param highlightColour    the colour
     * @param outlineWidth       the width
     * @param enableModelOutline outline the model
     * @param enableModelFill    fill the model
     * @param enableTileOutline  outline the tiles
     * @param enableTileFill     fill the tiles
     * @param alwaysOnTop        draw through walls
     * @param showInsideEdges    draw inner tile edges
     * @param snapToGrid         snap to the tile grid
     * @param swOnly             south-west tile only
     * @param ignoreRebuild      keep across map rebuilds
     * @param saveToFile         save to the client's files
     * @param disableModel       no effect yet
     */
    public HighlightConfig {
        Objects.requireNonNull(highlightColour, "highlightColour");
        if (outlineWidth < 0 || outlineWidth > 0xffff) {
            throw new IllegalArgumentException("outlineWidth must be 0-65535, got " + outlineWidth);
        }
    }

    /**
     * An outline around the model, and nothing on its tiles.
     *
     * @param colour the outline colour
     * @param width  the outline's width in pixels
     * @return the config
     */
    public static HighlightConfig modelOutline(Color colour, int width) {
        return DEFAULT.withHighlightColour(colour).withOutlineWidth(width);
    }

    /**
     * An outline around the tiles under something, optionally filled, and no model outline.
     *
     * @param colour the outline colour; its alpha is the fill's opacity
     * @param width  the outline's width in pixels
     * @param fill   also fill the tiles
     * @return the config
     */
    public static HighlightConfig tileOutline(Color colour, int width, boolean fill) {
        return DEFAULT.withHighlightColour(colour).withOutlineWidth(width).withEnableModelOutline(false)
                .withEnableTileOutline(true).withEnableTileFill(fill);
    }

    /**
     * Whether the client would draw anything with this config: an outline needs a width, a fill needs some alpha.
     *
     * @return true if some effect is visible
     */
    public boolean isVisible() {
        boolean outline = (enableModelOutline || enableTileOutline) && outlineWidth > 0;
        boolean fill = (enableModelFill || enableTileFill) && highlightColour.getAlpha() > 0;
        return outline || fill;
    }
}
