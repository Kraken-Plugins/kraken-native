package com.kraken.client.plugins.groundmarkers;

import lombok.EqualsAndHashCode;
import lombok.Value;

import java.awt.Color;

/**
 * A marked tile as it is saved and exported: its region, its place in the region and its plane, with the mark's
 * colour and label. Two points on the same tile are equal whatever their colour and label.
 */
@Value
@EqualsAndHashCode(exclude = {"color", "label"})
class GroundMarkerPoint {

    /**
     * The region's id: {@code (x >> 6) << 8 | (y >> 6)} of a tile in it.
     */
    int regionId;

    /**
     * The tile's x within the region, 0-63.
     */
    int regionX;

    /**
     * The tile's y within the region, 0-63.
     */
    int regionY;

    /**
     * The tile's plane, 0-3.
     */
    int z;

    /**
     * The mark's colour, or null to use the plugin's tile colour.
     */
    Color color;

    /**
     * Text written on the tile, or null for none.
     */
    String label;
}
