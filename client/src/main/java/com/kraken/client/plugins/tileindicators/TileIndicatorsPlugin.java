package com.kraken.client.plugins.tileindicators;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.kraken.client.plugins.Plugin;
import com.kraken.client.plugins.PluginDescriptor;
import com.kraken.client.plugins.config.ConfigManager;
import com.kraken.client.ui.overlay.OverlayManager;

/**
 * Marks the tile the player is walking to, the tile under the mouse and the tile the server has the player on. The
 * destination is the tile the game flags after a click; the hovered tile is the one its Walk here option would walk
 * to.
 */
@PluginDescriptor(
        name = "Tile Indicators",
        description = "Highlight the tile you are currently moving to",
        tags = {"highlight", "overlay"},
        enabledByDefault = false
)
public class TileIndicatorsPlugin extends Plugin {

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private TileIndicatorsOverlay overlay;

    @Provides
    TileIndicatorsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(TileIndicatorsConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
    }
}
