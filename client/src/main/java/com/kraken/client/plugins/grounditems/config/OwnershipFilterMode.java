package com.kraken.client.plugins.grounditems.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Which ground items are shown, by who they belong to.
 */
@Getter
@RequiredArgsConstructor
public enum OwnershipFilterMode {

    /**
     * Every item.
     */
    ALL("All"),

    /**
     * Items the local player can pick up: everything but other players' items for an ironman.
     */
    TAKEABLE("Takeable"),

    /**
     * Only the local player's and their group's items.
     */
    DROPS("Drops");

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
