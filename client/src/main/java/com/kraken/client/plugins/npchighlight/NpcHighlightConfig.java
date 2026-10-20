package com.kraken.client.plugins.npchighlight;

import com.kraken.client.plugins.config.Alpha;
import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Units;

import java.awt.Color;

/**
 * The NPC Highlight plugin's settings: which NPCs, and how their outline looks.
 */
@ConfigGroup(NpcHighlightConfig.GROUP)
public interface NpcHighlightConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "npchighlight";

    /**
     * The section for how highlights look.
     */
    @ConfigSection(name = "Display", description = "How highlights look", position = 1)
    String display = "display";

    /**
     * The names to highlight.
     *
     * @return comma-separated NPC names
     */
    @ConfigItem(keyName = "names", name = "NPC names",
            description = "Comma-separated names of the NPCs to highlight, e.g. Goblin, Guard. Case doesn't matter.",
            position = 0)
    default String npcNames() {
        return "";
    }

    /**
     * The outline's colour.
     *
     * @return the colour
     */
    @Alpha
    @ConfigItem(keyName = "colour", name = "Colour", description = "The outline's colour", section = display,
            position = 1)
    default Color colour() {
        return Color.CYAN;
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
     * Whether to write each highlighted NPC's name over it.
     *
     * @return true to show names
     */
    @ConfigItem(keyName = "showNames", name = "Show names", description = "Write each highlighted NPC's name over it",
            section = display, position = 3)
    default boolean showNames() {
        return false;
    }
}
