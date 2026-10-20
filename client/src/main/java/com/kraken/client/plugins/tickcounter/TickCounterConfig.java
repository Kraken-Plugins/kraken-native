package com.kraken.client.plugins.tickcounter;

import com.kraken.client.config.Config;
import com.kraken.client.config.ConfigGroup;
import com.kraken.client.config.ConfigItem;
import com.kraken.client.config.Range;
import com.kraken.client.config.Units;

/**
 * The Tick Counter plugin's settings.
 */
@ConfigGroup("tickcounter")
public interface TickCounterConfig extends Config {

    /**
     * Whether the count shows over the game.
     *
     * @return true to show the panel
     */
    @ConfigItem(keyName = "showOverlay", name = "Show panel", description = "Show the count in a panel over the game",
            position = 0)
    default boolean showOverlay() {
        return true;
    }

    /**
     * How often the count goes to the console.
     *
     * @return minutes between log lines; 0 for never
     */
    @Range(max = 60)
    @Units(Units.MINUTES)
    @ConfigItem(keyName = "logInterval", name = "Log every",
            description = "How often to log the count to the console. 0 never logs it.", position = 1)
    default int logInterval() {
        return 1;
    }
}
