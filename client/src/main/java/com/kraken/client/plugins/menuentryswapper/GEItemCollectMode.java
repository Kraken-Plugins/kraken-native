package com.kraken.client.plugins.menuentryswapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Which collect option becomes the left click on a Grand Exchange offer.
 */
@Getter
@RequiredArgsConstructor
public enum GEItemCollectMode {
    DEFAULT("Default"),
    ITEMS("Collect-items"),
    NOTES("Collect-notes"),
    BANK("Bank");

    private final String name;

    @Override
    public String toString() {
        return name;
    }
}
