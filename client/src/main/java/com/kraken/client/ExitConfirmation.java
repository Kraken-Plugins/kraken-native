package com.kraken.client;

import lombok.AllArgsConstructor;

/**
 * When closing the window asks first ({@link KrakenConfig#confirmExit}).
 */
@AllArgsConstructor
public enum ExitConfirmation {

    /**
     * Close without asking.
     */
    NEVER("Never"),

    /**
     * Ask while a player is logged in.
     */
    LOGGED_IN("While logged in"),

    /**
     * Always ask.
     */
    ALWAYS("Always");

    private final String label;

    /**
     * The name the settings page shows.
     *
     * @return the label
     */
    @Override
    public String toString() {
        return label;
    }
}
