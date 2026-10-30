package com.kraken.client.plugins.tileindicators;

import com.kraken.client.plugins.config.Alpha;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;

import java.awt.Color;

/**
 * The Tile Indicators plugin's settings: for each of the destination, hovered and current tiles, whether to mark it
 * and the mark's outline colour, fill colour and border width.
 */
@ConfigGroup(TileIndicatorsConfig.GROUP)
public interface TileIndicatorsConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "tileindicators";

    /**
     * The section for the tile being walked to.
     */
    @ConfigSection(name = "Destination tile", description = "Destination tile configuration.", position = 0)
    String destinationTile = "destinationTile";

    /**
     * The section for the tile under the mouse.
     */
    @ConfigSection(name = "Hovered tile", description = "Hovered tile configuration.", position = 1)
    String hoveredTile = "hoveredTile";

    /**
     * The section for the tile the player stands on.
     */
    @ConfigSection(name = "Current tile", description = "Current tile configuration.", position = 2)
    String currentTile = "currentTile";

    /**
     * Whether to mark the tile the player is walking to.
     *
     * @return true to mark it
     */
    @ConfigItem(keyName = "highlightDestinationTile", name = "Highlight destination tile",
            description = "Highlights tile player is walking to.", position = 1, section = destinationTile)
    default boolean highlightDestinationTile() {
        return true;
    }

    /**
     * The destination mark's outline colour.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "highlightDestinationColor", name = "Highlight color",
            description = "Configures the highlight color of current destination.", position = 2,
            section = destinationTile)
    default Color highlightDestinationColor() {
        return Color.GRAY;
    }

    /**
     * The destination mark's fill colour.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "destinationTileFillColor", name = "Fill color",
            description = "Configures the fill color of destination tile.", position = 3, section = destinationTile)
    default Color destinationTileFillColor() {
        return new Color(0, 0, 0, 50);
    }

    /**
     * The destination mark's border width.
     *
     * @return the width in pixels
     */
    @ConfigItem(keyName = "destinationTileBorderWidth", name = "Border width",
            description = "Width of the destination tile marker border.", position = 4, section = destinationTile)
    default double destinationTileBorderWidth() {
        return 2;
    }

    /**
     * Whether to mark the tile under the mouse.
     *
     * @return true to mark it
     */
    @ConfigItem(keyName = "highlightHoveredTile", name = "Highlight hovered tile",
            description = "Highlights tile player is hovering with mouse.", position = 1, section = hoveredTile)
    default boolean highlightHoveredTile() {
        return false;
    }

    /**
     * The hovered mark's outline colour.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "highlightHoveredColor", name = "Highlight color",
            description = "Configures the highlight color of hovered tile.", position = 2, section = hoveredTile)
    default Color highlightHoveredColor() {
        return new Color(0, 0, 0, 0);
    }

    /**
     * The hovered mark's fill colour.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "hoveredTileFillColor", name = "Fill color",
            description = "Configures the fill color of hovered tile.", position = 3, section = hoveredTile)
    default Color hoveredTileFillColor() {
        return new Color(0, 0, 0, 50);
    }

    /**
     * The hovered mark's border width.
     *
     * @return the width in pixels
     */
    @ConfigItem(keyName = "hoveredTileBorderWidth", name = "Border width",
            description = "Width of the hovered tile marker border.", position = 4, section = hoveredTile)
    default double hoveredTileBorderWidth() {
        return 2;
    }

    /**
     * Whether to mark the tile the server has the player on.
     *
     * @return true to mark it
     */
    @ConfigItem(keyName = "highlightCurrentTile", name = "Highlight true tile",
            description = "Highlights true tile player is on as seen by server.", position = 1,
            section = currentTile)
    default boolean highlightCurrentTile() {
        return false;
    }

    /**
     * The current tile mark's outline colour.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "highlightCurrentColor", name = "Highlight color",
            description = "Configures the highlight color of current true tile.", position = 2,
            section = currentTile)
    default Color highlightCurrentColor() {
        return Color.CYAN;
    }

    /**
     * The current tile mark's fill colour.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "currentTileFillColor", name = "Fill color",
            description = "Configures the fill color of current true tile.", position = 3, section = currentTile)
    default Color currentTileFillColor() {
        return new Color(0, 0, 0, 50);
    }

    /**
     * The current tile mark's border width.
     *
     * @return the width in pixels
     */
    @ConfigItem(keyName = "currentTileBorderWidth", name = "Border width",
            description = "Width of the true tile marker border.", position = 4, section = currentTile)
    default double currentTileBorderWidth() {
        return 2;
    }
}
