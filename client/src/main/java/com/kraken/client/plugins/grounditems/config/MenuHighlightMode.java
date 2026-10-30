package com.kraken.client.plugins.grounditems.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What a highlighted item's right-click menu entries colour.
 */
@Getter
@RequiredArgsConstructor
public enum MenuHighlightMode {

    /**
     * The option, such as Take.
     */
    OPTION("Menu option"),

    /**
     * The item's name.
     */
    NAME("Menu item name"),

    /**
     * The option and the item's name.
     */
    BOTH("Both");

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
