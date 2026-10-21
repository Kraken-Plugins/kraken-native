package com.kraken.client.plugins.tilemarkers;

import com.kraken.client.plugins.config.Alpha;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Units;

import java.awt.Color;

/**
 * The Tile Markers plugin's settings: which tiles, and how their marks look.
 */
@ConfigGroup(TileMarkersConfig.GROUP)
public interface TileMarkersConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "tilemarkers";

    /**
     * The section for how marks look.
     */
    @ConfigSection(name = "Display", description = "How marks look", position = 1)
    String display = "display";

    /**
     * The tiles to mark.
     *
     * @return world tiles as {@code x, y, plane}, separated by semicolons
     */
    @ConfigItem(keyName = "tiles", name = "Tiles",
            description = "World tiles to mark, each x, y, plane, separated by semicolons, e.g. 3222, 3218, 0; "
                    + "3223, 3218, 0. The Location dev tool shows the tile you stand on.",
            position = 0)
    default String tiles() {
        return "";
    }

    /**
     * The marks' colour.
     *
     * @return the colour; its alpha is the fill's opacity
     */
    @Alpha
    @ConfigItem(keyName = "colour", name = "Colour", description = "The marks' colour; its opacity is the fill's",
            section = display, position = 1)
    default Color colour() {
        return new Color(255, 255, 0, 50);
    }

    /**
     * The outline's width.
     *
     * @return the width in pixels
     */
    @Range(min = 1, max = 4)
    @Units(Units.PIXELS)
    @ConfigItem(keyName = "width", name = "Outline width", description = "How thick the outline is", section = display,
            position = 2)
    default int width() {
        return 2;
    }

    /**
     * Whether to fill the marked tiles.
     *
     * @return true to fill them
     */
    @ConfigItem(keyName = "fill", name = "Fill", description = "Fill the tiles as well as outlining them",
            section = display, position = 3)
    default boolean fill() {
        return true;
    }
}
