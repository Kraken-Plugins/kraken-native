package com.kraken.api.coords;

import com.kraken.api.scene.WorldView;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * A rectangle of world tiles on one plane, as RuneLite's {@code WorldArea}: an actor's footprint, or any area a plugin
 * wants to test against.
 *
 * <p>{@link #hasLineOfSightTo} and {@link #canTravelInDirection} ask the client's own line tests (Lua's
 * {@code Collision.lineOfSight}/{@code lineOfWalk}). RuneLite computes them in Java from the collision flags, so
 * results can differ at edge cases.
 */
@Value
@AllArgsConstructor
public class WorldArea {

    /**
     * The world x of the south-west tile.
     */
    int x;

    /**
     * The world y of the south-west tile.
     */
    int y;

    /**
     * How many tiles the area spans along x.
     */
    int width;

    /**
     * How many tiles the area spans along y.
     */
    int height;

    /**
     * The plane, 0-3.
     */
    int plane;

    /**
     * An area with a given south-west tile.
     *
     * @param location the south-west tile, whose plane the area takes
     * @param width    tiles along x
     * @param height   tiles along y
     */
    public WorldArea(WorldPoint location, int width, int height) {
        this(location.getX(), location.getY(), width, height, location.getPlane());
    }

    /**
     * The Chebyshev distance between the nearest tiles of two areas.
     *
     * @param other the other area
     * @return the distance, 0 if they overlap; {@link Integer#MAX_VALUE} on another plane
     */
    public int distanceTo(WorldArea other) {
        return other.plane != plane ? Integer.MAX_VALUE : distanceTo2D(other);
    }

    /**
     * The Chebyshev distance from this area's nearest tile to a tile.
     *
     * @param point the tile
     * @return the distance, 0 inside the area; {@link Integer#MAX_VALUE} on another plane
     */
    public int distanceTo(WorldPoint point) {
        return distanceTo(point.toWorldArea());
    }

    /**
     * The Chebyshev distance between the nearest tiles of two areas, ignoring planes.
     *
     * @param other the other area
     * @return the distance, 0 if they overlap
     */
    public int distanceTo2D(WorldArea other) {
        int[] d = axisDistances(other);
        return Math.max(d[0], d[1]);
    }

    /**
     * The Chebyshev distance from this area's nearest tile to a tile, ignoring planes.
     *
     * @param point the tile
     * @return the distance, 0 inside the area
     */
    public int distanceTo2D(WorldPoint point) {
        return distanceTo2D(point.toWorldArea());
    }

    /**
     * Whether a tile is in the area, plane included.
     *
     * @param point the tile
     * @return true if it is inside
     */
    public boolean contains(WorldPoint point) {
        return distanceTo(point) == 0;
    }

    /**
     * Whether a tile is in the area, ignoring planes.
     *
     * @param point the tile
     * @return true if its x and y are inside
     */
    public boolean contains2D(WorldPoint point) {
        return distanceTo2D(point) == 0;
    }

    /**
     * Whether two areas share a tile.
     *
     * @param other the other area
     * @return true if they overlap on the same plane
     */
    public boolean intersectsWith(WorldArea other) {
        if (other.plane != plane) {
            return false;
        }
        int[] d = axisDistances(other);
        return d[0] + d[1] == 0;
    }

    /**
     * Whether another area is next to this one, not diagonally: where melee can reach.
     *
     * @param other the other area
     * @return true if they touch along an edge on the same plane
     */
    public boolean isInMeleeDistance(WorldArea other) {
        if (other == null || other.plane != plane) {
            return false;
        }
        int[] d = axisDistances(other);
        return d[0] + d[1] == 1;
    }

    /**
     * Whether something on this area can see another area, by the client's line-of-sight test between the two
     * areas' nearest tiles.
     *
     * @param view  the world view the areas are in
     * @param other the other area
     * @return true if the line between the nearest tiles is clear; false on another plane or outside the scene
     */
    public boolean hasLineOfSightTo(WorldView view, WorldArea other) {
        if (other.plane != plane) {
            return false;
        }
        return view.lineOfSight(comparisonPoint(other), other.comparisonPoint(this));
    }

    /**
     * Whether something on this area can see a tile, by the client's line-of-sight test.
     *
     * @param view  the world view the area is in
     * @param other the tile
     * @return true if the line from this area's nearest tile is clear; false on another plane or outside the scene
     */
    public boolean hasLineOfSightTo(WorldView view, WorldPoint other) {
        return hasLineOfSightTo(view, other.toWorldArea());
    }

    /**
     * Whether the area can move one step in a direction: every tile of it can walk one step that way, by the client's
     * line-of-walk test.
     *
     * @param view the world view the area is in
     * @param dx   the step along x (its sign is used: -1, 0 or 1)
     * @param dy   the step along y (its sign is used)
     * @return true if no tile's step is blocked; false for no step at all, or outside the scene
     */
    public boolean canTravelInDirection(WorldView view, int dx, int dy) {
        int sx = Integer.signum(dx);
        int sy = Integer.signum(dy);
        if (sx == 0 && sy == 0) {
            return false;
        }
        for (int tx = x; tx < x + width; tx++) {
            for (int ty = y; ty < y + height; ty++) {
                WorldPoint from = new WorldPoint(tx, ty, plane);
                if (!view.lineOfWalk(from, new WorldPoint(tx + sx, ty + sy, plane))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The area's south-west tile.
     *
     * @return the tile at ({@link #getX()}, {@link #getY()}) on the area's plane
     */
    public WorldPoint toWorldPoint() {
        return new WorldPoint(x, y, plane);
    }

    /**
     * Every tile of the area.
     *
     * @return the tiles, row by row from the south-west
     */
    public List<WorldPoint> toWorldPointList() {
        List<WorldPoint> points = new ArrayList<>(width * height);
        for (int ty = y; ty < y + height; ty++) {
            for (int tx = x; tx < x + width; tx++) {
                points.add(new WorldPoint(tx, ty, plane));
            }
        }
        return points;
    }

    /**
     * The tile of this area nearest another area, axis by axis.
     *
     * @param other the other area
     * @return this area's tile closest to {@code other}
     */
    private WorldPoint comparisonPoint(WorldArea other) {
        int px = other.x <= x ? x : Math.min(other.x, x + width - 1);
        int py = other.y <= y ? y : Math.min(other.y, y + height - 1);
        return new WorldPoint(px, py, plane);
    }

    /**
     * How far apart two areas' nearest tiles are along each axis.
     *
     * @param other the other area
     * @return {dx, dy}, each 0 where the areas overlap on that axis
     */
    private int[] axisDistances(WorldArea other) {
        WorldPoint a = comparisonPoint(other);
        WorldPoint b = other.comparisonPoint(this);
        return new int[]{Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())};
    }
}
