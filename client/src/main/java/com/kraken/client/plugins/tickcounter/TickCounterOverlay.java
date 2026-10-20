package com.kraken.client.plugins.tickcounter;

import com.google.inject.Inject;
import com.kraken.api.Client;
import com.kraken.client.ui.Theme;
import com.kraken.client.ui.overlay.OverlayPanel;
import com.kraken.client.ui.overlay.OverlayPosition;
import com.kraken.client.ui.overlay.components.LineComponent;
import com.kraken.client.ui.overlay.components.TitleComponent;

import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * A panel in the top-left corner with the tick count and how long it lasts, while logged in and the setting is on.
 */
class TickCounterOverlay extends OverlayPanel {

    private final TickCounterPlugin plugin;
    private final TickCounterConfig config;
    private final Client client;

    @Inject
    TickCounterOverlay(TickCounterPlugin plugin, TickCounterConfig config, Client client) {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        this.client = client;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay() || !client.isLoggedIn()) {
            return null;
        }
        int ticks = plugin.getTicks();
        panelComponent.getChildren().add(TitleComponent.builder().text("Tick counter").color(Theme.ACCENT).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Ticks:").right(String.valueOf(ticks)).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Time:")
                .right(TickCounterPlugin.duration(ticks)).build());
        return super.render(graphics);
    }
}
