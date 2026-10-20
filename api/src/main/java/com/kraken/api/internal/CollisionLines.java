package com.kraken.api.internal;

import com.kraken.api.collision.CollisionDataFlag;

/**
 * The client's line of walk and line of sight tests over a collision grid, step for step: a fixed-point line from
 * the start tile toward the end tile, testing each tile it leaves against a mask for the direction of the step. The
 * masks are the client's.
 *
 * <p>Grid indices are tile coordinates minus the grid's origin. A tile outside the grid reads as
 * {@link CollisionDataFlag#UNLOADED}, which every mask includes, where the client would read past the grid.
 */
public final class CollisionLines {

    /**
     * A collision grid: an origin, a size and the flags at each index.
     */
    public interface Grid {

        /**
         * The grid's origin.
         *
         * @return the tile x of index 0
         */
        int originX();

        /**
         * The grid's origin.
         *
         * @return the tile z of index 0
         */
        int originZ();

        /**
         * The grid's width.
         *
         * @return how many columns (x indices) it has
         */
        int sizeX();

        /**
         * The grid's height.
         *
         * @return how many rows (z indices) it has
         */
        int sizeZ();

        /**
         * The flags at a grid index.
         *
         * @param ix the column, tile x minus {@link #originX()}
         * @param iz the row, tile z minus {@link #originZ()}
         * @return the tile's flags, or {@link CollisionDataFlag#UNLOADED} outside the grid or if they can't be read
         */
        int at(int ix, int iz);
    }

    private static final int WALK_EAST = 0x1240108;
    private static final int WALK_WEST = 0x1240180;
    private static final int WALK_NORTH = 0x1240102;
    private static final int WALK_SOUTH = 0x1240120;
    private static final int WALK_END = 0x1240100;

    private static final int SIGHT_FIRST_EAST = 0x1021000;
    private static final int SIGHT_FIRST_WEST = 0x1030000;
    private static final int SIGHT_FIRST_NORTH = 0x1020400;
    private static final int SIGHT_FIRST_SOUTH = 0x1024000;
    private static final int SIGHT_EAST = 0x10a1000;
    private static final int SIGHT_WEST = 0x10b0000;
    private static final int SIGHT_NORTH = 0x10a0400;
    private static final int SIGHT_SOUTH = 0x10a4000;

    private static final int ONE = 0x10000;
    private static final int HALF = 0x8000;

    private CollisionLines() {
    }

    /**
     * The client's line of walk: true if nothing blocks walking in a line from the start tile to the end tile. Only the
     * end tile is checked against the grid's bounds, as in the client.
     *
     * @param g  the grid
     * @param x0 the start tile x
     * @param z0 the start tile z
     * @param x1 the end tile x
     * @param z1 the end tile z
     * @return true if the line is walkable (always for the same tile)
     */
    public static boolean walk(Grid g, int x0, int z0, int x1, int z1) {
        if (x0 == x1 && z0 == z1) {
            return true;
        }
        x1 -= g.originX();
        z1 -= g.originZ();
        if (x1 < 0 || x1 >= g.sizeX() || z1 < 0 || z1 >= g.sizeZ()) {
            return false;
        }
        int x = x0 - g.originX();
        int z = z0 - g.originZ();
        int dx = Math.abs(x1 - x);
        int dz = Math.abs(z1 - z);
        int acc = HALF;
        if (dx > dz) {
            int slope = (dz << 16) / dx;
            do {
                if (x < x1) {
                    if ((g.at(x, z) & WALK_EAST) != 0) {
                        return false;
                    }
                    x++;
                } else if (x > x1) {
                    if ((g.at(x, z) & WALK_WEST) != 0) {
                        return false;
                    }
                    x--;
                }
                acc += slope;
                if (acc >= ONE) {
                    acc -= ONE;
                    if (z < z1) {
                        if ((g.at(x, z) & WALK_NORTH) != 0) {
                            return false;
                        }
                        z++;
                    } else if (z > z1) {
                        if ((g.at(x, z) & WALK_SOUTH) != 0) {
                            return false;
                        }
                        z--;
                    }
                }
            } while (x != x1);
        } else if (z != z1) {
            int slope = (dx << 16) / dz;
            do {
                if (z < z1) {
                    if ((g.at(x, z) & WALK_NORTH) != 0) {
                        return false;
                    }
                    z++;
                } else if (z > z1) {
                    if ((g.at(x, z) & WALK_SOUTH) != 0) {
                        return false;
                    }
                    z--;
                }
                acc += slope;
                if (acc >= ONE) {
                    acc -= ONE;
                    if (x < x1) {
                        if ((g.at(x, z) & WALK_EAST) != 0) {
                            return false;
                        }
                        x++;
                    } else if (x > x1) {
                        if ((g.at(x, z) & WALK_WEST) != 0) {
                            return false;
                        }
                        x--;
                    }
                }
            } while (z != z1);
        }
        return (g.at(x1, z1) & WALK_END) == 0;
    }

    /**
     * The client's line of sight: true if nothing blocks the view in a line from the start tile to the end tile. Both
     * tiles must be inside the grid.
     *
     * @param g  the grid
     * @param x0 the start tile x
     * @param z0 the start tile z
     * @param x1 the end tile x
     * @param z1 the end tile z
     * @return true if the end tile is in sight (always for the same tile)
     */
    public static boolean sight(Grid g, int x0, int z0, int x1, int z1) {
        if (x0 == x1 && z0 == z1) {
            return true;
        }
        x1 -= g.originX();
        z1 -= g.originZ();
        if (x1 < 0 || x1 >= g.sizeX() || z1 < 0 || z1 >= g.sizeZ()) {
            return false;
        }
        int x = x0 - g.originX();
        int z = z0 - g.originZ();
        if (x < 0 || x >= g.sizeX() || z < 0 || z >= g.sizeZ()) {
            return false;
        }
        int dx = Math.abs(x1 - x);
        int dz = Math.abs(z1 - z);
        if (dx > dz) {
            int slope = (dz << 16) / dx;
            if (x < x1) {
                if ((g.at(x, z) & SIGHT_FIRST_EAST) != 0) {
                    return false;
                }
                x++;
            } else if (x > x1) {
                if ((g.at(x, z) & SIGHT_FIRST_WEST) != 0) {
                    return false;
                }
                x--;
            }
            int acc = slope + HALF;
            if (acc >= ONE) {
                acc -= ONE;
                if (!sightStepZ(g, x, z, z1)) {
                    return false;
                }
                z += Integer.signum(z1 - z);
            }
            while (x != x1) {
                if (x < x1) {
                    if ((g.at(x, z) & SIGHT_EAST) != 0) {
                        return false;
                    }
                    x++;
                } else {
                    if ((g.at(x, z) & SIGHT_WEST) != 0) {
                        return false;
                    }
                    x--;
                }
                acc += slope;
                if (acc >= ONE) {
                    acc -= ONE;
                    if (!sightStepZ(g, x, z, z1)) {
                        return false;
                    }
                    z += Integer.signum(z1 - z);
                }
            }
            return true;
        }
        int slope = (dx << 16) / dz;
        if (z < z1) {
            if ((g.at(x, z) & SIGHT_FIRST_NORTH) != 0) {
                return false;
            }
            z++;
        } else if (z > z1) {
            if ((g.at(x, z) & SIGHT_FIRST_SOUTH) != 0) {
                return false;
            }
            z--;
        }
        int acc = slope + HALF;
        if (acc >= ONE) {
            acc -= ONE;
            if (!sightStepX(g, x, z, x1)) {
                return false;
            }
            x += Integer.signum(x1 - x);
        }
        while (z != z1) {
            if (z < z1) {
                if ((g.at(x, z) & SIGHT_NORTH) != 0) {
                    return false;
                }
                z++;
            } else {
                if ((g.at(x, z) & SIGHT_SOUTH) != 0) {
                    return false;
                }
                z--;
            }
            acc += slope;
            if (acc >= ONE) {
                acc -= ONE;
                if (!sightStepX(g, x, z, x1)) {
                    return false;
                }
                x += Integer.signum(x1 - x);
            }
        }
        return true;
    }

    /**
     * One step of the line of sight along z, if it still has z to cover.
     *
     * @param g  the grid
     * @param x  the current column
     * @param z  the current row
     * @param z1 the end row
     * @return false if the step is blocked; true if it isn't, or there's no z step left to take
     */
    private static boolean sightStepZ(Grid g, int x, int z, int z1) {
        if (z < z1) {
            return (g.at(x, z) & SIGHT_NORTH) == 0;
        }
        if (z > z1) {
            return (g.at(x, z) & SIGHT_SOUTH) == 0;
        }
        return true;
    }

    /**
     * One step of the line of sight along x, if it still has x to cover.
     *
     * @param g  the grid
     * @param x  the current column
     * @param z  the current row
     * @param x1 the end column
     * @return false if the step is blocked; true if it isn't, or there's no x step left to take
     */
    private static boolean sightStepX(Grid g, int x, int z, int x1) {
        if (x < x1) {
            return (g.at(x, z) & SIGHT_EAST) == 0;
        }
        if (x > x1) {
            return (g.at(x, z) & SIGHT_WEST) == 0;
        }
        return true;
    }
}
