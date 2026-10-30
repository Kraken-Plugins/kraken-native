package com.kraken.client.plugins.npchighlight;

/**
 * Which shapes highlight an NPC: one NPC's tag style if it has one, else the settings'.
 *
 * @param hull       the game fills and outlines the model
 * @param tile       the tiles the NPC is drawn on
 * @param trueTile   the tiles the NPC is on as the server sees it
 * @param swTile     the south-west tile the NPC is drawn on
 * @param swTrueTile the south-west tile the NPC is on as the server sees it
 * @param outline    the game outlines the model
 */
record RenderStyle(boolean hull, boolean tile, boolean trueTile, boolean swTile, boolean swTrueTile, boolean outline) {

    /**
     * The tag style that highlights the model's hull.
     */
    static final String HULL = "hull";

    /**
     * The tag style that outlines the tiles the NPC is drawn on.
     */
    static final String TILE = "tile";

    /**
     * The tag style that outlines the NPC's true tiles.
     */
    static final String TRUE_TILE = "truetile";

    /**
     * The tag style that outlines the south-west tile the NPC is drawn on.
     */
    static final String SW_TILE = "swtile";

    /**
     * The tag style that outlines the NPC's south-west true tile.
     */
    static final String SW_TRUE_TILE = "swtruetile";

    /**
     * The tag style that outlines the model.
     */
    static final String OUTLINE = "outline";

    /**
     * The shapes for an NPC: exactly the one its tag style names, or the settings' when it has none.
     *
     * @param tagStyle the NPC's tag style ({@code hull}, {@code tile}, ...), or null for none
     * @param config   the settings
     * @return the style
     */
    static RenderStyle of(String tagStyle, NpcIndicatorsConfig config) {
        if (tagStyle != null) {
            return new RenderStyle(HULL.equals(tagStyle), TILE.equals(tagStyle), TRUE_TILE.equals(tagStyle),
                    SW_TILE.equals(tagStyle), SW_TRUE_TILE.equals(tagStyle), OUTLINE.equals(tagStyle));
        }
        return new RenderStyle(config.highlightHull(), config.highlightTile(), config.highlightTrueTile(),
                config.highlightSouthWestTile(), config.highlightSouthWestTrueTile(), config.highlightOutline());
    }
}
