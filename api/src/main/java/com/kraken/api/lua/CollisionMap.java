package com.kraken.api.lua;

/**
 * Mirrors the Lua {@code osrs.CollisionMap}: one level's collision flags, as {@link World#getCollisionMap} returns
 * them. Like the Lua object it is a copy taken when you asked for it, so it doesn't follow later changes (doors
 * opening, the map reloading); ask again for fresh flags.
 *
 * <p>Coordinates are top-level scene tiles, the space {@link ClientOp#coord()} uses. The grid reaches a little past the
 * scene's edge (on the 241 client it starts one tile outside it); {@link #getOriginX()}/{@link #getOriginZ()} give its
 * first tile.
 */
public final class CollisionMap {

    private final int level;
    private final int originX;
    private final int originZ;
    private final int sizeX;
    private final int sizeZ;
    private final int[][] flags;
    private final CollisionLines.Grid grid = new CollisionLines.Grid() {
        @Override
        public int originX() {
            return originX;
        }

        @Override
        public int originZ() {
            return originZ;
        }

        @Override
        public int sizeX() {
            return sizeX;
        }

        @Override
        public int sizeZ() {
            return sizeZ;
        }

        @Override
        public int at(int ix, int iz) {
            if (ix < 0 || iz < 0 || ix >= sizeX || iz >= flags[ix].length) {
                return CollisionFlag.UNLOADED;
            }
            return flags[ix][iz];
        }
    };

    /**
     * Wraps a copy of a level's flags.
     *
     * @param level   the level the flags are for
     * @param originX the tile x of column 0
     * @param originZ the tile z of row 0
     * @param flags   the flags by column then row; kept, not copied
     */
    CollisionMap(int level, int originX, int originZ, int[][] flags) {
        this.level = level;
        this.originX = originX;
        this.originZ = originZ;
        this.sizeX = flags.length;
        this.sizeZ = flags.length == 0 ? 0 : flags[0].length;
        this.flags = flags;
    }

    /**
     * Mirrors {@code CollisionMap.getBitFlags}: a tile's collision flags ({@link CollisionFlag}).
     *
     * <p>Differs from the client on purpose: the native method indexes the grid with the coordinates as given,
     * without subtracting the grid's origin the way the client's own walk and sight checks do, so in Lua it returns the
     * flags of tile ({@code x + originX}, {@code z + originZ}): one tile south-west on the 241 client, whose grid starts
     * at -1. This returns the asked tile's flags.
     *
     * @param x the tile x
     * @param z the tile z
     * @return the tile's flags, or {@link CollisionFlag#UNLOADED} outside the grid
     */
    public int getBitFlags(int x, int z) {
        return grid.at(x - originX, z - originZ);
    }

    /**
     * Mirrors {@code CollisionMap.lineOfSight}: whether the end tile can be seen from the start tile (the client's
     * check for ranged and magic attacks).
     *
     * @param x1 the start tile x
     * @param z1 the start tile z
     * @param x2 the end tile x
     * @param z2 the end tile z
     * @return true if nothing blocks the line; false if something does or either tile is outside the grid
     */
    public boolean lineOfSight(int x1, int z1, int x2, int z2) {
        return CollisionLines.sight(grid, x1, z1, x2, z2);
    }

    /**
     * Mirrors {@code CollisionMap.lineOfWalk}: whether one can walk in a straight line from the start tile to the end
     * tile.
     *
     * @param x1 the start tile x
     * @param z1 the start tile z
     * @param x2 the end tile x
     * @param z2 the end tile z
     * @return true if nothing blocks the line; false if something does or the end tile is outside the grid
     */
    public boolean lineOfWalk(int x1, int z1, int x2, int z2) {
        return CollisionLines.walk(grid, x1, z1, x2, z2);
    }

    /**
     * The level these flags are for. Not a Lua method.
     *
     * @return the level (plane), 0-3
     */
    public int getLevel() {
        return level;
    }

    /**
     * The tile x of the grid's first column. Not a Lua method.
     *
     * @return the grid's origin x, in scene tiles
     */
    public int getOriginX() {
        return originX;
    }

    /**
     * The tile z of the grid's first row. Not a Lua method.
     *
     * @return the grid's origin z, in scene tiles
     */
    public int getOriginZ() {
        return originZ;
    }

    /**
     * How many columns the grid has. Not a Lua method.
     *
     * @return the grid's width in tiles
     */
    public int getSizeX() {
        return sizeX;
    }

    /**
     * How many rows the grid has. Not a Lua method.
     *
     * @return the grid's height in tiles
     */
    public int getSizeZ() {
        return sizeZ;
    }

    @Override
    public String toString() {
        return "CollisionMap(level " + level + ", origin " + originX + "," + originZ + ", " + sizeX + "x" + sizeZ + ")";
    }
}
