package com.kraken.client.plugins.tickcounter;

import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Units;

/**
 * The Tick Counter plugin's settings.
 */
@ConfigGroup("tickcounter")
public interface TickCounterConfig extends Config {

    /**
     * How often the count goes to the console.
     *
     * @return minutes between log lines; 0 for never
     */
    @Range(max = 60)
    @Units(Units.MINUTES)
    @ConfigItem(keyName = "logInterval", name = "Log every",
            description = "How often to log the count to the console. 0 never logs it.", position = 0)
    default int logInterval() {
        return 1;
    }
}
