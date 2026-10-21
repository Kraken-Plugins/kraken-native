package com.kraken.client.plugins.tilemarkers;

import com.kraken.api.coords.WorldPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How {@link TileMarkersPlugin} reads its tile list.
 */
class TileMarkersPluginTest {

    @Test
    void readsTilesSeparatedBySemicolonsOrLines() {
        assertEquals(List.of(new WorldPoint(3222, 3218, 0), new WorldPoint(3223, 3218, 1),
                        new WorldPoint(1, 2, 3)),
                TileMarkersPlugin.parse(" 3222, 3218, 0; 3223,3218,1\n1 2 3 ;"));
    }

    @Test
    void skipsEntriesThatAreNotThreeNumbersOrOffThePlanes() {
        assertEquals(List.of(new WorldPoint(5, 6, 0)),
                TileMarkersPlugin.parse("1, 2; x, y, 0; 1, 2, 4; 5, 6, 0; 5, 6, 0"));
        assertEquals(List.of(), TileMarkersPlugin.parse(""));
    }
}
