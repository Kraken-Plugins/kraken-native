package com.kraken.client.worldhopper;

import lombok.Getter;

/**
 * Where a world's server is: the world list's location numbers ({@link com.kraken.api.World#getLocation()}).
 */
@Getter
public enum WorldRegion {
    UNITED_STATES(0, "US", "United States"),
    UNITED_KINGDOM(1, "UK", "United Kingdom"),
    AUSTRALIA(3, "AU", "Australia"),
    GERMANY(7, "DE", "Germany"),
    BRAZIL(8, "BR", "Brazil"),
    JAPAN(9, "JP", "Japan"),
    SINGAPORE(10, "SG", "Singapore"),
    SOUTH_AFRICA(12, "ZA", "South Africa");

    /**
     * The world list's number for the location.
     */
    private final int location;

    /**
     * A two-letter code for the world list's region column.
     */
    private final String code;

    /**
     * The region's name.
     */
    private final String displayName;

    WorldRegion(int location, String code, String displayName) {
        this.location = location;
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * The region with a world list location number.
     *
     * @param location the location number
     * @return the region, or null for a number this enum doesn't know
     */
    public static WorldRegion of(int location) {
        for (WorldRegion region : values()) {
            if (region.location == location) {
                return region;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
