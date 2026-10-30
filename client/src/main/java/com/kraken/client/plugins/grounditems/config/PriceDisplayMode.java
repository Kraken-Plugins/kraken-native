package com.kraken.client.plugins.grounditems.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Which prices are written after a ground item's name.
 */
@Getter
@RequiredArgsConstructor
public enum PriceDisplayMode {

    /**
     * The high alchemy value.
     */
    HA("High Alchemy"),

    /**
     * The Grand Exchange price.
     */
    GE("Grand Exchange"),

    /**
     * Both prices.
     */
    BOTH("Both"),

    /**
     * No price.
     */
    OFF("Off");

    /**
     * The name the settings page shows.
     */
    private final String name;

    /**
     * The name the settings page shows.
     *
     * @return the name
     */
    @Override
    public String toString() {
        return name;
    }
}
