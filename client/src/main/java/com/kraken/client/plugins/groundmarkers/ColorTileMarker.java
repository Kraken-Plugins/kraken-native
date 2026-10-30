package com.kraken.client.plugins.groundmarkers;

import com.kraken.api.coords.WorldPoint;
import lombok.Value;

import java.awt.Color;

/**
 * A marked tile of the loaded area, as the overlay draws it.
 */
@Value
class ColorTileMarker {

    /**
     * The tile.
     */
    WorldPoint worldPoint;

    /**
     * The mark's colour, or null to use the plugin's tile colour.
     */
    Color color;

    /**
     * Text written on the tile, or null for none.
     */
    String label;
}
