package com.kraken.client.ui.devtools.tools;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InventorySlotsView}'s text: stack quantities as the game writes them, and item names wrapped to a slot.
 */
class InventorySlotsViewTest {

    @Test
    void quantitiesAbbreviateAndColourLikeTheGame() {
        assertEquals("99999", InventorySlotsView.quantityText(99_999));
        assertEquals("100K", InventorySlotsView.quantityText(100_000));
        assertEquals("9999K", InventorySlotsView.quantityText(9_999_999));
        assertEquals("10M", InventorySlotsView.quantityText(10_000_000));
        assertEquals(new Color(0xffff00), InventorySlotsView.quantityColor(99_999));
        assertEquals(new Color(0xffffff), InventorySlotsView.quantityColor(100_000));
        assertEquals(new Color(0x00ff80), InventorySlotsView.quantityColor(10_000_000));
    }

    @Test
    void namesWrapAtWordsAndEllipsiseOverflow() {
        FontMetrics metrics = metrics();
        int width = metrics.stringWidth("Rune platebody");

        assertEquals(List.of("Rune platebody"), InventorySlotsView.wrap("Rune platebody", metrics, width, 2));
        assertEquals(List.of("Rune", "platebody"),
                InventorySlotsView.wrap("Rune platebody", metrics, metrics.stringWidth("platebody"), 2));

        List<String> lines = InventorySlotsView.wrap("Avernic defender hilt of the ages", metrics,
                metrics.stringWidth("defender"), 2);
        assertEquals(2, lines.size(), "kept to the line limit");
        assertTrue(lines.get(1).endsWith("…"), "the last line shows the name goes on");
    }

    @Test
    void wordsWiderThanASlotBreakMidWord() {
        FontMetrics metrics = metrics();
        int width = metrics.stringWidth("Abyss");
        for (String line : InventorySlotsView.wrap("Abyssalwhipwhip", metrics, width, 5)) {
            assertTrue(metrics.stringWidth(line) <= width, line + " fits");
        }
    }

    /**
     * Metrics for a fixed font, from an offscreen image so the test runs headless.
     *
     * @return the metrics
     */
    private static FontMetrics metrics() {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        FontMetrics metrics = g.getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.dispose();
        return metrics;
    }
}
