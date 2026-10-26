package com.kraken.client;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;

import static com.kraken.client.GameSizeCheck.PATIENCE;
import static com.kraken.client.GameSizeCheck.TRIES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GameSizeCheck}: a resize once the game's recorded size has disagreed with its window for a while, never while
 * they agree or the size can't be read, and only a few tries per window size.
 */
class GameSizeCheckTest {

    private static final Dimension WINDOW = new Dimension(1800, 1200);
    private static final Dimension STALE = new Dimension(2700, 1800);

    private final GameSizeCheck check = new GameSizeCheck();

    /**
     * Checks a disagreement until the check asks for a resize.
     *
     * @return how many checks that took, or -1 if it didn't within twice its patience
     */
    private int checksUntilResize() {
        for (int i = 1; i <= 2 * PATIENCE; i++) {
            if (check.needsResize(STALE, WINDOW)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void agreeingOrUnreadableSizesNeverAskForAResize() {
        for (int i = 0; i < 3 * PATIENCE; i++) {
            assertFalse(check.needsResize(WINDOW, WINDOW));
            assertFalse(check.needsResize(null, WINDOW));
            assertFalse(check.needsResize(new Dimension(-1, -1), WINDOW));
        }
    }

    @Test
    void aDisagreementHasToLastBeforeAResize() {
        assertEquals(PATIENCE, checksUntilResize());
        assertEquals(1, check.getTries());

        for (int i = 1; i < PATIENCE; i++) {
            assertFalse(check.needsResize(STALE, WINDOW));
        }
        assertFalse(check.needsResize(WINDOW, WINDOW), "caught up");
        assertEquals(PATIENCE, checksUntilResize(), "the count started over");
    }

    @Test
    void itGivesUpAfterAFewTriesUntilTheWindowSizeChanges() {
        for (int i = 0; i < TRIES; i++) {
            assertEquals(PATIENCE, checksUntilResize());
        }
        assertEquals(-1, checksUntilResize(), "a resize doesn't fix this disagreement");

        Dimension resized = new Dimension(1801, 1200);
        for (int i = 1; i < PATIENCE; i++) {
            assertFalse(check.needsResize(STALE, resized));
        }
        assertTrue(check.needsResize(STALE, resized), "a new window size gets its own tries");
        assertEquals(1, check.getTries());
    }
}
