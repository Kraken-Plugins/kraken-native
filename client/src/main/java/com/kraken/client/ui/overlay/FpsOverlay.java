package com.kraken.client.ui.overlay;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.FrameRates;
import com.kraken.client.KrakenConfig;
import com.kraken.client.events.ConfigChanged;
import com.kraken.client.ui.overlay.components.LineComponent;

import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * The game's and the overlays' frame rates, at the top of the game while {@link KrakenConfig#showFps} is on. It adds
 * itself to the {@link OverlayManager} when the setting turns on and removes itself when it turns off, so nothing is
 * drawn for it while it's off. The client creates it at launch.
 */
@Singleton
public final class FpsOverlay extends OverlayPanel {

    private static final int WIDTH = 104;

    private final FrameRates frameRates;
    private final KrakenConfig config;
    private final OverlayManager overlayManager;

    @Inject
    FpsOverlay(FrameRates frameRates, KrakenConfig config, OverlayManager overlayManager, EventBus eventBus) {
        this.frameRates = frameRates;
        this.config = config;
        this.overlayManager = overlayManager;
        setPosition(OverlayPosition.TOP_CENTER);
        setPriority(PRIORITY_HIGH);
        panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
        eventBus.register(ConfigChanged.class, event -> {
            if (KrakenConfig.GROUP.equals(event.getGroup()) && "showFps".equals(event.getKey())) {
                update();
            }
        }, 0);
        update();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        double game = frameRates.getGameFrameRate();
        panelComponent.getChildren().add(LineComponent.builder().left("Game")
                .right(Double.isNaN(game) ? "–" : fps(game)).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Overlays")
                .right(fps(frameRates.getOverlayFrameRate())).build());
        return super.render(graphics);
    }

    /**
     * A frame rate as the overlay shows it.
     *
     * @param rate frames a second
     * @return the rate, rounded, with its unit
     */
    static String fps(double rate) {
        return Math.round(rate) + " fps";
    }

    private void update() {
        if (config.showFps()) {
            overlayManager.add(this);
        } else {
            overlayManager.remove(this);
        }
    }
}
