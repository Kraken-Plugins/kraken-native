package com.kraken.api.collision;

import com.kraken.api.internal.CollisionLines;
import com.kraken.api.scene.WorldView;
import lombok.Getter;

/**
 * One plane's collision flags, as RuneLite's {@code CollisionData}. Get them from {@link WorldView#getCollisionMaps()}.
 *
 * <p>Unlike RuneLite's, which reads the client's live array, this is a copy taken when the maps were fetched (as Lua's
 * {@code CollisionMap} is), so it doesn't follow doors opening or the map reloading. Fetch the maps again for fresh
 * flags, or use {@link WorldView#lineOfSight}/{@link WorldView#lineOfWalk} for a live check.
 *
 * <p>The client's grid reaches a little past the scene's edge (on the 241 client it starts one tile outside it). The
 * RuneLite view, {@link #getFlags()}, covers just the scene; the Kraken extras ({@link #getBitFlags}, the line tests)
 * see the whole grid.
 */
public final class CollisionData {

    /**
     * The flags of every tile in the scene, indexed {@code [sceneX][sceneY]} and covering the scene's size; test them
     * with the {@link CollisionDataFlag} bits.
     */
    @Getter
    private final int[][] flags;

    /**
     * The plane these flags are for. Kraken extra.
     */
    @Getter
    private final int plane;

    /**
     * The scene x of the client's grid's first column. Kraken extra.
     */
    @Getter
    private final int originX;

    /**
     * The scene y of the client's grid's first row. Kraken extra.
     */
    @Getter
    private final int originY;

    private final int[][] grid;
    private final CollisionLines.Grid lines;

    /**
     * Wraps a copy of a plane's grid, cutting the scene's part out of it for {@link #getFlags()}.
     * Internal: used by the API's own packages; plugins don't need it.
     *
     * @param plane      the plane the flags are for
     * @param originX    the scene x of column 0
     * @param originY    the scene y of row 0
     * @param grid       the flags by column then row, as the client lays them out; kept, not copied
     * @param sceneSizeX the scene's width in tiles
     * @param sceneSizeY the scene's length in tiles
     */
    public CollisionData(int plane, int originX, int originY, int[][] grid, int sceneSizeX, int sceneSizeY) {
        this.plane = plane;
        this.originX = originX;
        this.originY = originY;
        this.grid = grid;
        this.flags = new int[sceneSizeX][sceneSizeY];
        for (int x = 0; x < sceneSizeX; x++) {
            for (int y = 0; y < sceneSizeY; y++) {
                flags[x][y] = getBitFlags(x, y);
            }
        }
        int sizeX = grid.length;
        int sizeY = grid.length == 0 ? 0 : grid[0].length;
        this.lines = new CollisionLines.Grid() {
            @Override
            public int originX() {
                return originX;
            }

            @Override
            public int originZ() {
                return originY;
            }

            @Override
            public int sizeX() {
                return sizeX;
            }

            @Override
            public int sizeZ() {
                return sizeY;
            }

            @Override
            public int at(int ix, int iy) {
                return cell(ix, iy);
            }
        };
    }

    /**
     * One tile's flags, anywhere on the client's grid. Kraken extra, mirrors Lua {@code CollisionMap.getBitFlags}.
     *
     * <p>Differs from Lua on purpose: the native method indexes the grid with the coordinates as given, without
     * subtracting the grid's origin the way the client's own walk and sight checks do, so in Lua it returns the flags
     * of the tile one south-west on the 241 client. This returns the asked tile's flags.
     *
     * @param sceneX the scene x
     * @param sceneY the scene y
     * @return the tile's flags, or {@link CollisionDataFlag#UNLOADED} outside the grid
     */
    public int getBitFlags(int sceneX, int sceneY) {
        return cell(sceneX - originX, sceneY - originY);
    }

    /**
     * Whether the end tile can be seen from the start tile (the client's check for ranged and magic attacks), over
     * this copy. Kraken extra, mirrors Lua {@code CollisionMap.lineOfSight}.
     *
     * @param x1 the start scene x
     * @param y1 the start scene y
     * @param x2 the end scene x
     * @param y2 the end scene y
     * @return true if nothing blocks the line; false if something does or either tile is outside the grid
     */
    public boolean lineOfSight(int x1, int y1, int x2, int y2) {
        return CollisionLines.sight(lines, x1, y1, x2, y2);
    }

    /**
     * Whether one can walk in a straight line from the start tile to the end tile, over this copy. Kraken extra,
     * mirrors Lua {@code CollisionMap.lineOfWalk}.
     *
     * @param x1 the start scene x
     * @param y1 the start scene y
     * @param x2 the end scene x
     * @param y2 the end scene y
     * @return true if nothing blocks the line; false if something does or the end tile is outside the grid
     */
    public boolean lineOfWalk(int x1, int y1, int x2, int y2) {
        return CollisionLines.walk(lines, x1, y1, x2, y2);
    }

    private int cell(int ix, int iy) {
        if (ix < 0 || iy < 0 || ix >= grid.length || iy >= grid[ix].length) {
            return CollisionDataFlag.UNLOADED;
        }
        return grid[ix][iy];
    }

    @Override
    public String toString() {
        return "CollisionData(plane " + plane + ", origin " + originX + "," + originY + ", " + flags.length + "x"
                + (flags.length == 0 ? 0 : flags[0].length) + ")";
    }
}
