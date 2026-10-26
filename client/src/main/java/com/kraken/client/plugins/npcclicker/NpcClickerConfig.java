package com.kraken.client.plugins.npcclicker;

import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Units;

/**
 * The NPC Clicker plugin's settings: which NPC to click, which of its options, and how often.
 */
@ConfigGroup(NpcClickerConfig.GROUP)
public interface NpcClickerConfig extends Config {

    /**
     * The settings' group.
     */
    String GROUP = "npcclicker";

    /**
     * The NPC to click.
     *
     * @return the NPC's name, as it shows in the menu; case doesn't matter
     */
    @ConfigItem(keyName = "npcName", name = "NPC name",
            description = "The name of the NPC to click, e.g. Moss giant. Case doesn't matter.", position = 0)
    default String npcName() {
        return "Moss giant";
    }

    /**
     * The menu option to run on it.
     *
     * @return the option's text, as it shows in the menu; case doesn't matter
     */
    @ConfigItem(keyName = "option", name = "Option",
            description = "The menu option to run on the NPC, e.g. Attack or Talk-to. Case doesn't matter.",
            position = 1)
    default String option() {
        return "Attack";
    }

    /**
     * How often to click.
     *
     * @return game ticks between clicks; 0 clicks once, when the plugin is turned on
     */
    @Range(max = 1000)
    @Units(Units.TICKS)
    @ConfigItem(keyName = "interval", name = "Click every",
            description = "Game ticks between clicks. 0 clicks once, when the plugin is turned on.", position = 2)
    default int interval() {
        return 0;
    }

    /**
     * Whether to send the mouse click packet before the action, as a real click does.
     *
     * @return true to send a click at the NPC's position on the canvas first
     */
    @ConfigItem(keyName = "mouseClick", name = "Send mouse click",
            description = "Send the mouse click packet at the NPC's position before the action, as a real click does. "
                    + "The NPC must be on screen.", position = 3)
    default boolean mouseClick() {
        return true;
    }
}
