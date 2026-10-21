package com.kraken.client.ui.overlay;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The row bookkeeping {@link OverlayRenderer} does for frames that go to the game: finding the rows that hold pixels,
 * and clearing only those.
 */
class OverlayRendererTest {

    @Test
    void findsTheFirstAndLastRowsWithPixels() {
        int width = 5;
        int height = 6;
        int[] pixels = new int[width * height];
        assertEquals(height, OverlayRenderer.firstRow(pixels, width, height), "an empty frame has no rows");

        pixels[2 * width + 4] = 0x01000000;
        pixels[4 * width] = 0xff123456;
        int top = OverlayRenderer.firstRow(pixels, width, height);
        assertEquals(2, top);
        assertEquals(4, OverlayRenderer.lastRow(pixels, width, top, height));

        int[] one = new int[width * height];
        one[3 * width + 1] = 1;
        assertEquals(3, OverlayRenderer.lastRow(one, width, 3, height), "a single row is both ends");
    }

    @Test
    void askingOftenDoesNotPushTheScheduleAhead() {
        OverlayRenderer.FrameSchedule schedule = new OverlayRenderer.FrameSchedule();
        long interval = 33_333_333L;
        assertTrue(schedule.due(0, interval, true), "the first frame is due at once");
        long ms = 1_000_000L;
        for (long t = 8 * ms; t < interval; t += 8 * ms) {
            assertFalse(schedule.due(t, interval, false), "not due at " + t / ms + " ms");
        }
        assertTrue(schedule.due(interval, interval, false), "due one interval on, however often it was asked");
    }

    @Test
    void theRateHoldsOnAPollThatDoesNotDivideIt() {
        OverlayRenderer.FrameSchedule schedule = new OverlayRenderer.FrameSchedule();
        long interval = 33_333_333L;
        int frames = 0;
        for (long t = 0; t < 1_000_000_000L; t += 20_000_000L) {
            if (schedule.due(t, interval, t == 0)) {
                frames++;
            }
        }
        assertEquals(30, frames, "30 a second on a 20 ms poll, not one every other poll (25)");
    }

    @Test
    void aRestartDrawsAtOnce() {
        OverlayRenderer.FrameSchedule schedule = new OverlayRenderer.FrameSchedule();
        long interval = 33_333_333L;
        schedule.due(0, interval, true);
        assertFalse(schedule.due(1_000_000L, interval, false));
        assertTrue(schedule.due(2_000_000L, interval, true), "a new image is drawn at once");
    }

    @Test
    void clearRowsLeavesTheOtherRows() {
        BufferedImage image = new BufferedImage(3, 4, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        java.util.Arrays.fill(pixels, 7);
        OverlayRenderer.clearRows(image, 1, 3);
        assertEquals(7, pixels[2], "row 0 kept");
        assertEquals(0, pixels[3], "row 1 cleared");
        assertEquals(0, pixels[8], "row 2 cleared");
        assertEquals(7, pixels[9], "row 3 kept");
        OverlayRenderer.clearRows(image, 2, 2);
        assertEquals(7, pixels[9], "an empty range clears nothing");
    }
}
