package com.kraken.client.plugins.groundmarkers;

import com.kraken.api.coords.WorldPoint;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link GroundMarkerPlugin} reads and writes marked tiles: the export JSON, marking and unmarking, merging an
 * import, and the regions a loaded area covers.
 */
class GroundMarkerPluginTest {

    private static final int LUMBRIDGE = 12850;

    @Test
    void readsExportsWithEachColourForm() {
        List<GroundMarkerPoint> points = GroundMarkerPlugin.parse("[{\"regionId\":12850,\"regionX\":20,"
                + "\"regionY\":30,\"z\":0,\"color\":\"#FFFF0000\",\"label\":\"Here\"},"
                + "{\"regionId\":12850,\"regionX\":21,\"regionY\":30,\"z\":0,\"color\":\"#00FF00\"},"
                + "{\"regionId\":12850,\"regionX\":22,\"regionY\":30,\"z\":1,"
                + "\"color\":{\"value\":-16776961,\"falpha\":0.0}},"
                + "{\"regionId\":12850,\"regionX\":23,\"regionY\":30,\"z\":0}]");
        assertEquals(4, points.size());
        assertEquals(Color.RED, points.get(0).getColor());
        assertEquals("Here", points.get(0).getLabel());
        assertEquals(new Color(0, 255, 0), points.get(1).getColor(), "#RRGGBB is opaque");
        assertEquals(Color.BLUE, points.get(2).getColor(), "the older object form");
        assertNull(points.get(3).getColor(), "no colour uses the tile colour");
        assertNull(points.get(3).getLabel());
    }

    @Test
    void writesColoursAsAlphaRedGreenBlue() {
        String json = GroundMarkerPlugin.GSON.toJson(List.of(
                new GroundMarkerPoint(LUMBRIDGE, 1, 2, 0, new Color(255, 255, 0, 128), null)));
        assertTrue(json.contains("\"color\":\"#80FFFF00\""), json);
        assertEquals(List.of(new GroundMarkerPoint(LUMBRIDGE, 1, 2, 0, null, null)), GroundMarkerPlugin.parse(json));
    }

    @Test
    void anythingButAListOfMarksReadsAsNone() {
        assertEquals(List.of(), GroundMarkerPlugin.parse(""));
        assertEquals(List.of(), GroundMarkerPlugin.parse("not json"));
        assertEquals(List.of(), GroundMarkerPlugin.parse("{\"regionId\":1}"));
    }

    @Test
    void markingAMarkedTileUnmarksIt() {
        GroundMarkerPoint red = new GroundMarkerPoint(LUMBRIDGE, 5, 6, 0, Color.RED, "a");
        GroundMarkerPoint other = new GroundMarkerPoint(LUMBRIDGE, 7, 8, 0, Color.RED, null);
        List<GroundMarkerPoint> marked = GroundMarkerPlugin.toggle(List.of(other), red);
        assertEquals(List.of(other, red), marked);
        GroundMarkerPoint sameTileOtherColour = new GroundMarkerPoint(LUMBRIDGE, 5, 6, 0, Color.BLUE, null);
        assertEquals(List.of(other), GroundMarkerPlugin.toggle(marked, sameTileOtherColour),
                "the colour and label don't make it another tile");
    }

    @Test
    void anImportAddsOnlyTilesNotMarkedYet() {
        GroundMarkerPoint mine = new GroundMarkerPoint(LUMBRIDGE, 5, 6, 0, Color.RED, null);
        GroundMarkerPoint theirs = new GroundMarkerPoint(LUMBRIDGE, 5, 6, 0, Color.BLUE, "theirs");
        GroundMarkerPoint fresh = new GroundMarkerPoint(LUMBRIDGE, 9, 9, 2, Color.BLUE, null);
        List<GroundMarkerPoint> merged = GroundMarkerPlugin.merge(List.of(mine), List.of(theirs, fresh));
        assertEquals(List.of(mine, fresh), merged);
        assertEquals(Color.RED, merged.get(0).getColor(), "the existing mark is kept as it was");
    }

    @Test
    void aLoadedAreaCoversTheRegionsItOverlaps() {
        // A 104-tile scene starting 8 tiles into a region spans two regions each way
        int baseX = 49 * 64 + 8;
        int baseY = 49 * 64 + 8;
        int west = baseX >> 6;
        int south = baseY >> 6;
        assertArrayEquals(new int[]{west << 8 | south, west << 8 | south + 1, (west + 1) << 8 | south,
                (west + 1) << 8 | south + 1}, GroundMarkerPlugin.regions(baseX, baseY, 104, 104));
        assertEquals(new WorldPoint(3200, 3200, 0).getRegionID(), (west + 1) << 8 | south + 1);
        assertArrayEquals(new int[0], GroundMarkerPlugin.regions(-1, -1, 104, 104), "an unread scene");
    }
}
