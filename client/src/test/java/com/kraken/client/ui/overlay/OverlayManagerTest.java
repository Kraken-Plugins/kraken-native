package com.kraken.client.ui.overlay;

import com.kraken.api.eventbus.EventBus;
import com.kraken.client.plugins.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The placements {@link OverlayManager} keeps: saved under the overlay's name, set again when an overlay of that name
 * is added, and forgotten when the overlay is reset.
 */
class OverlayManagerTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private ConfigManager config;

    /**
     * An overlay named after its class, {@code Counter}.
     */
    private static final class Counter extends Overlay {
        @Override
        public Dimension render(Graphics2D graphics) {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        config = new ConfigManager(dir.resolve("settings.properties"), scheduler, new EventBus());
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void aSavedPlacementComesBackWhenTheOverlayIsAddedAgain() {
        OverlayManager manager = new OverlayManager(config);
        Counter first = new Counter();
        manager.add(first);
        assertNull(first.getPreferredLocation(), "never moved, so nothing to restore");
        first.setPreferredLocation(new Point(40, 60));
        first.setPreferredSize(new Dimension(200, 0));
        manager.saveOverlay(first);
        assertEquals("40:60", config.getConfiguration(OverlayManager.CONFIG_GROUP, "Counter_preferredLocation"));
        assertEquals("200x0", config.getConfiguration(OverlayManager.CONFIG_GROUP, "Counter_preferredSize"));

        Counter next = new Counter();
        new OverlayManager(config).add(next);
        assertEquals(new Point(40, 60), next.getPreferredLocation());
        assertEquals(new Dimension(200, 0), next.getPreferredSize());
    }

    @Test
    void aResetOverlayGoesBackToItsAnchorAndForgetsWhereItWas() {
        OverlayManager manager = new OverlayManager(config);
        Counter overlay = new Counter();
        manager.add(overlay);
        overlay.setPreferredLocation(new Point(40, 60));
        overlay.setPreferredSize(new Dimension(200, 80));
        overlay.setPreferredPosition(OverlayPosition.BOTTOM_LEFT);
        manager.saveOverlay(overlay);

        manager.resetOverlay(overlay);
        assertNull(overlay.getPreferredLocation());
        assertNull(overlay.getPreferredSize());
        assertNull(overlay.getPreferredPosition());
        assertEquals(List.of(), config.getConfigurationKeys(OverlayManager.CONFIG_GROUP + "."));

        Counter next = new Counter();
        new OverlayManager(config).add(next);
        assertNull(next.getPreferredLocation());
        assertNull(next.getPreferredSize());
    }
}
