package com.kraken.client.plugins.objectmarkers;

import com.kraken.client.config.Alpha;
import com.kraken.client.config.Config;
import com.kraken.client.config.ConfigGroup;
import com.kraken.client.config.ConfigItem;
import com.kraken.client.config.ConfigSection;

import java.awt.Color;

/**
 * The Object Markers plugin's settings: which objects, and how their marks look.
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
     * Whether to write each marked object's name on it.
     *
     * @return true to show names
     */
    @ConfigItem(keyName = "showNames", name = "Show names", description = "Write each marked object's name on it",
            section = display, position = 2)
    default boolean showNames() {
        return false;
    }
}
