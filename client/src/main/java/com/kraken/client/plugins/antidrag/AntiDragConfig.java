package com.kraken.client.plugins.antidrag;

import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;

/**
 * The Anti Drag plugin's settings: how long an item must be held before it drags, and when that delay applies.
 */
@ConfigGroup(AntiDragPlugin.CONFIG_GROUP)
public interface AntiDragConfig extends Config {

    /**
     * How long an item must be held before it drags.
     *
     * @return the delay in client ticks (20 ms each); one game tick by default
     */
    @ConfigItem(keyName = "dragDelay", name = "Drag delay",
            description = "Configures the inventory drag delay in client ticks (20ms).", position = 1)
    default int dragDelay() {
        return 600 / 20;
    }

    /**
     * Whether the delay only applies while Shift is held.
     *
     * @return true for Shift only
     */
    @ConfigItem(keyName = "onShiftOnly", name = "On shift only",
            description = "Configures whether to only adjust the delay while holding shift.", position = 2)
    default boolean onShiftOnly() {
        return true;
    }

    /**
     * Whether holding Control lifts the delay.
     *
     * @return true to lift it while Control is held
     */
    @ConfigItem(keyName = "disableOnCtrl", name = "Disable on control pressed",
            description = "Configures whether to ignore the delay while holding control.", position = 3)
    default boolean disableOnCtrl() {
        return false;
    }
}
