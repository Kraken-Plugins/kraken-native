package com.kraken.client.plugins.objectmarkers;

import com.kraken.client.plugins.config.Alpha;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Units;

import java.awt.Color;

/**
 * The Object Markers plugin's settings: which objects, and how their marks look. The game draws the model and tile
 * outlines while the native hook is connected; without it, the plugin outlines the tiles itself.
 */
@ConfigGroup(ObjectMarkersConfig.GROUP)
public interface ObjectMarkersConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "objectmarkers";

    /**
     * The section for how marks look.
     */
    @ConfigSection(name = "Display", description = "How marks look", position = 1)
    String display = "display";

    /**
     * The objects to mark.
     *
     * @return comma-separated object names or ids
     */
    @ConfigItem(keyName = "objects", name = "Objects",
            description = "Comma-separated names or ids of the objects to mark, e.g. Bank booth, Oak tree, 10355. "
                    + "Case doesn't matter.",
            position = 0)
    default String objects() {
        return "";
    }

    /**
     * The marks' colour.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "colour", name = "Colour", description = "The marks' colour", section = display,
            position = 1)
    default Color colour() {
        return Color.YELLOW;
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
     * Whether to outline the object's model, drawn by the game.
     *
     * @return true to outline the model
     */
    @ConfigItem(keyName = "outlineModel", name = "Outline the model",
            description = "Outline the object itself. The game draws it, so it needs the native hook.",
            section = display, position = 3)
    default boolean outlineModel() {
        return true;
    }

    /**
     * Whether to outline the tiles under the object.
     *
     * @return true to outline the tiles
     */
    @ConfigItem(keyName = "outlineTile", name = "Outline the tiles",
            description = "Outline the tiles the object covers. Without the native hook they are always outlined.",
            section = display, position = 4)
    default boolean outlineTile() {
        return false;
    }

    /**
     * Whether the game draws the outlines through walls and other models.
     *
     * @return true to draw on top
     */
    @ConfigItem(keyName = "alwaysOnTop", name = "Draw through walls",
            description = "Show the outlines through walls and other models", section = display, position = 5)
    default boolean alwaysOnTop() {
        return false;
    }

    /**
     * Whether to write each marked object's name on it.
     *
     * @return true to show names
     */
    @ConfigItem(keyName = "showNames", name = "Show names", description = "Write each marked object's name on it",
            section = display, position = 6)
    default boolean showNames() {
        return false;
    }
}
