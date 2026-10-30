package com.kraken.client.plugins.grounditems;

import com.google.inject.Inject;
import com.kraken.client.input.HotkeyListener;

import java.time.Duration;
import java.time.Instant;

/**
 * The Ground Items hotkey. Held, it shows every item, hidden ones included. Pressed twice within the double-tap delay,
 * it hides every item until it is pressed again. Runs on the client thread, like the plugin's state.
 */
class GroundItemHotkeyListener extends HotkeyListener {

    private final GroundItemsPlugin plugin;
    private final GroundItemsConfig config;

    /**
     * When the hotkey last went down, for telling a double tap; null after one.
     */
    private Instant lastPress;

    @Inject
    GroundItemHotkeyListener(GroundItemsPlugin plugin, GroundItemsConfig config) {
        super(config::hotkey);
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void hotkeyPressed() {
        if (plugin.isHideAll()) {
            plugin.setHideAll(false);
            plugin.setHotKeyPressed(true);
            lastPress = null;
        } else if (lastPress != null && !plugin.isHotKeyPressed() && config.doubleTapDelay() > 0
                && Duration.between(lastPress, Instant.now()).compareTo(Duration.ofMillis(config.doubleTapDelay())) < 0) {
            plugin.setHideAll(true);
            lastPress = null;
        } else {
            plugin.setHotKeyPressed(true);
            lastPress = Instant.now();
        }
    }

    @Override
    public void hotkeyReleased() {
        plugin.setHotKeyPressed(false);
    }
}
