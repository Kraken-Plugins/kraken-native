package com.kraken.client.plugins.grounditems.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Which value decides a ground item's value tier and whether it is worth notifying about.
 */
@Getter
@RequiredArgsConstructor
public enum ValueCalculationMode {

    /**
     * The high alchemy value.
     */
    HA("High Alchemy"),

    /**
     * The Grand Exchange price.
     */
    GE("Grand Exchange"),

    /**
     * Whichever of the two is higher.
     */
    HIGHEST("Highest");

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
