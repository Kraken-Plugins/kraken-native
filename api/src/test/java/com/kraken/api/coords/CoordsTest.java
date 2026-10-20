package com.kraken.api.coords;

import com.kraken.api.Point;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coordinate value types' arithmetic, which needs no game: regions, distances, areas.
 */
class CoordsTest {

    @Test
    void regionsAre64TileSquares() {
        WorldPoint lumbridge = new WorldPoint(3222, 3218, 0);
        assertEquals(12850, lumbridge.getRegionID());
        assertEquals(22, lumbridge.getRegionX());
        assertEquals(18, lumbridge.getRegionY());
        assertEquals(lumbridge, WorldPoint.fromRegion(12850, 22, 18, 0));
    }

    @Test
    void worldDistancesAreChebyshevAndStopAtPlanes() {
        WorldPoint a = new WorldPoint(10, 10, 0);
        assertEquals(5, a.distanceTo(new WorldPoint(13, 15, 0)));
        assertEquals(Integer.MAX_VALUE, a.distanceTo(new WorldPoint(13, 15, 1)));
        assertEquals(5, a.distanceTo2D(new WorldPoint(13, 15, 1)));
        assertEquals(new WorldPoint(12, 7, 1), a.dx(2).dy(-3).dz(1));
    }

    @Test
    void areasMeasureFromTheirNearestTiles() {
        WorldArea big = new WorldArea(10, 10, 3, 3, 0);
        assertEquals(0, big.distanceTo(new WorldPoint(12, 12, 0)));
        assertEquals(2, big.distanceTo(new WorldPoint(14, 11, 0)));
        assertEquals(Integer.MAX_VALUE, big.distanceTo(new WorldPoint(12, 12, 1)));
        assertTrue(big.contains(new WorldPoint(10, 12, 0)));
        assertFalse(big.contains(new WorldPoint(13, 12, 0)));
        assertTrue(big.contains2D(new WorldPoint(10, 12, 2)));
        assertTrue(big.intersectsWith(new WorldArea(12, 12, 2, 2, 0)));
        assertFalse(big.intersectsWith(new WorldArea(13, 12, 2, 2, 0)));
        assertTrue(big.isInMeleeDistance(new WorldArea(13, 11, 1, 1, 0)));
        assertFalse(big.isInMeleeDistance(new WorldArea(13, 13, 1, 1, 0)), "diagonal isn't melee distance");
        assertTrue(new WorldPoint(11, 11, 0).isInArea(big));
        assertEquals(new WorldPoint(10, 10, 0), big.toWorldPoint());
        assertEquals(List.of(new WorldPoint(1, 1, 0), new WorldPoint(2, 1, 0), new WorldPoint(1, 2, 0),
                new WorldPoint(2, 2, 0)), new WorldArea(1, 1, 2, 2, 0).toWorldPointList());
    }

    @Test
    void localPointsAre128PerTile() {
        LocalPoint point = new LocalPoint(2 * 128 + 64, 3 * 128 + 1, -1);
        assertEquals(2, point.getSceneX());
        assertEquals(3, point.getSceneY());
        assertEquals(100, point.distanceTo(point.plus(60, 80)));
        assertEquals(Integer.MAX_VALUE, point.distanceTo(new LocalPoint(0, 0, 4)));
        assertEquals(new LocalPoint(point.getX() + 5, point.getY() - 5, -1), point.dx(5).dy(-5));
    }

    @Test
    void canvasPointsMeasureStraightLines() {
        assertEquals(5, new Point(0, 0).distanceTo(new Point(3, 4)));
    }
}
