package com.kraken.client;

import com.kraken.client.plugins.config.Config;
import com.kraken.client.plugins.config.ConfigGroup;
import com.kraken.client.plugins.config.ConfigItem;
import com.kraken.client.plugins.config.ConfigSection;
import com.kraken.client.plugins.config.Range;
import com.kraken.client.plugins.config.Units;

/**
 * Kraken's own settings: the Settings sidebar page is built from it. Group {@value #GROUP}, which also holds the
 * plugins' on/off switches.
 */
@ConfigGroup(KrakenConfig.GROUP)
public interface KrakenConfig extends Config {

    /**
     * The group Kraken's settings are kept in.
     */
    String GROUP = "kraken";

    /**
     * The section for plugin overlays.
     */
    @ConfigSection(name = "Overlays", description = "Plugin overlays drawn over the game", position = 10)
    String overlays = "overlays";

    /**
     * Whether the window stays above other windows.
     *
     * @return true to keep Kraken on top
     */
    @ConfigItem(keyName = "alwaysOnTop", name = "Always on top", description = "Keep Kraken above other windows.",
            position = 0)
    default boolean alwaysOnTop() {
        return false;
    }

    /**
     * Whether opening a sidebar panel widens the window rather than narrowing the game.
     *
     * @return true to grow the window
     */
    @ConfigItem(keyName = "growWindow", name = "Grow window for panels",
            description = "Widen the window when a panel opens instead of shrinking the game.", position = 1)
    default boolean growWindow() {
        return true;
    }

    /**
     * How many overlay frames to draw a second.
     *
     * @return the frame rate
     */
    @Range(min = 1, max = 50)
    @Units(Units.FPS)
    @ConfigItem(keyName = "overlayFrameRate", name = "Frame rate",
            description = "How often plugin overlays are redrawn. Every frame reads the game, so a higher rate costs "
                    + "more. The API polls about 50 times a second, which caps it.",
            position = 11, section = overlays)
    default int overlayFrameRate() {
        return 30;
    }
}
