package com.kraken.client;

import lombok.Getter;

import java.awt.Dimension;

/**
 * Decides when the game needs its window resized again. When its window is resized while it is still starting, the
 * game can keep drawing at the size it started at, so only a corner of the game shows, until it is resized once more.
 * Its renderer's record of its window size then disagrees with the window. Once they have disagreed for a few checks
 * in a row (the game takes a moment to catch up with an ordinary resize), the window should be resized again. After a
 * few tries at one window size it gives up, since a disagreement a resize doesn't fix isn't this one.
 *
 * <p>Not thread-safe: the launcher checks from its own thread.
 */
final class GameSizeCheck {

    /**
     * How many checks in a row the sizes must disagree before the window is resized.
     */
    static final int PATIENCE = 4;

    /**
     * How many times the window is resized for one window size before giving up.
     */
    static final int TRIES = 3;

    /**
     * The window size the tries are counted for.
     */
    private Dimension window;

    /**
     * How many checks in a row the sizes have disagreed.
     */
    private int disagreements;

    /**
     * How many times the window was resized for its current size.
     */
    @Getter
    private int tries;

    /**
     * Compares the size the game records with its window's.
     *
     * @param recorded the window size the game's renderer records, in physical pixels; null or not positive if it
     *                 can't be read
     * @param actual   the size the game window is fitted to, in physical pixels
     * @return true to resize the game window again
     */
    boolean needsResize(Dimension recorded, Dimension actual) {
        if (!actual.equals(window)) {
            window = new Dimension(actual);
            disagreements = 0;
            tries = 0;
        }
        if (recorded == null || recorded.width <= 0 || recorded.height <= 0 || recorded.equals(actual)) {
            disagreements = 0;
            return false;
        }
        if (++disagreements < PATIENCE || tries >= TRIES) {
            return false;
        }
        disagreements = 0;
        tries++;
        return true;
    }
}
