package com.kraken.client.plugins.grounditems.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Where ground items are highlighted.
 */
@Getter
@RequiredArgsConstructor
public enum ItemHighlightMode {

    /**
     * Nowhere.
     */
    NONE("None"),

    /**
     * In the text drawn over the items.
     */
    OVERLAY("Overlay"),

    /**
     * In the right-click menu.
     */
    MENU("Right-click menu"),

    /**
     * In the text over the items and in the right-click menu.
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
