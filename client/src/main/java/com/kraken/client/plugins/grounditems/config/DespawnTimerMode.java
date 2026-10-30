package com.kraken.client.plugins.grounditems.config;

/**
 * How a ground item's time until it despawns is shown.
 */
public enum DespawnTimerMode {

    /**
     * Not shown.
     */
    OFF,

    /**
     * As a pie that empties.
     */
    PIE,

    /**
     * As game ticks left.
     */
    TICKS,

    /**
     * As seconds left.
     */
    SECONDS
}
