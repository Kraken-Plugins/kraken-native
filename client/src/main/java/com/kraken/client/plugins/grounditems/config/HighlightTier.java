package com.kraken.client.plugins.grounditems.config;

import com.kraken.client.plugins.grounditems.GroundItemsConfig;

/**
 * A value tier, from the low, medium, high and insane value prices in the settings.
 */
public enum HighlightTier {

    /**
     * No tier.
     */
    OFF,

    /**
     * From the low value price.
     */
    LOW,

    /**
     * From the medium value price.
     */
    MEDIUM,

    /**
     * From the high value price.
     */
    HIGH,

    /**
     * From the insane value price.
     */
    INSANE;

    /**
     * The price the tier starts at.
     *
     * @param config the settings holding the prices
     * @return the price
     * @throws UnsupportedOperationException for {@link #OFF}, which has no price
     */
    public int getValueFromTier(GroundItemsConfig config) {
        return switch (this) {
            case LOW -> config.lowValuePrice();
            case MEDIUM -> config.mediumValuePrice();
            case HIGH -> config.highValuePrice();
            case INSANE -> config.insaneValuePrice();
            default -> throw new UnsupportedOperationException("OFF has no price");
        };
    }
}
