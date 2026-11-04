package com.kraken.client.ui.overlay;

import com.kraken.api.draw.Anchor;
import com.kraken.api.draw.DrawItem;
import com.kraken.api.eventbus.EventBus;
import com.kraken.client.KrakenConfig;
import com.kraken.client.plugins.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldLabels}: label images drawn in the overlay's style, centred on the anchor with the baseline on it, scaled
 * to the framebuffer and handed out again for the same label.
 */
class WorldLabelsTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService scheduler;
    private WorldLabels labels;
    private final Anchor anchor = new Anchor(Anchor.Kind.NPC, 5, 1000, 0, 0, 240);

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        ConfigManager manager = new ConfigManager(dir.resolve("settings.properties"), scheduler, new EventBus());
        labels = new WorldLabels(manager.getConfig(KrakenConfig.class));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void aLabelSitsCentredOnItsAnchorWithTheBaselineThere() {
        DrawItem.Sprite label = labels.label(anchor, "Goblin", Color.CYAN, 1);
        BufferedImage image = label.image();
        assertSame(anchor, label.anchor());
        assertTrue(label.offsetX() < 0 && -label.offsetX() * 2 <= image.getWidth(), "about half the text left of it");
        assertTrue(label.offsetY() < 0 && -label.offsetY() < image.getHeight(), "the ascent above it");
        int below = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = -label.offsetY() + 3; y < image.getHeight(); y++) {
                below += image.getRGB(x, y) >>> 24 > 0 ? 1 : 0;
            }
        }
        assertEquals(0, below, "\"Goblin\" has no descender: nothing but its shadow's edge sits below the baseline");
    }

    @Test
    void theTextIsOpaqueWithABlackShadow() {
        BufferedImage image = labels.label(anchor, "MMMM", new Color(0, 255, 255, 40), 1).image();
        boolean cyan = false;
        boolean black = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                cyan |= argb == 0xff00ffff;
                black |= argb == 0xff000000;
            }
        }
        assertTrue(cyan, "the colour drawn opaque");
        assertTrue(black, "the shadow");
    }

    @Test
    void theImageIsScaledToTheFramebuffer() {
        BufferedImage single = labels.label(anchor, "Goblin", Color.CYAN, 1).image();
        DrawItem.Sprite doubled = labels.label(anchor, "Goblin", Color.CYAN, 2);
        assertTrue(doubled.image().getWidth() >= single.getWidth() * 2 - 2);
        assertTrue(doubled.image().getHeight() >= single.getHeight() * 2 - 2);
    }

    @Test
    void theSameLabelIsTheSameImageSoItIsSentOnce() {
        BufferedImage first = labels.label(anchor, "Goblin", Color.CYAN, 1).image();
        assertSame(first, labels.label(anchor.lift(10), "Goblin", Color.CYAN, 1).image());
        assertNotSame(first, labels.label(anchor, "Goblin", Color.RED, 1).image());
        assertNull(labels.label(anchor, "", Color.CYAN, 1));
        assertNull(labels.label(anchor, null, Color.CYAN, 1));
    }
}
