package com.kraken.api.collision;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The bits of a tile's collision flags ({@link CollisionData#getFlags()}). They are the client's own bits, the same as
 * Lua's {@code CollisionFlag}.
 *
 * <p>The bits and masks that only Lua's {@code CollisionFlag} names keep its names. The {@code PL_WALK_*},
 * {@code NPC_WALK_*} and {@code VIS_*} values are the combined masks the client tests for a step or a line in one
 * direction. Test any of them with {@link #isSet}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CollisionDataFlag {

    /**
     * Blocks movement across the tile's north-west corner.
     */
    public static final int BLOCK_MOVEMENT_NORTH_WEST = 0x1;

    /**
     * Blocks movement across the tile's north edge.
     */
    public static final int BLOCK_MOVEMENT_NORTH = 0x2;

    /**
     * Blocks movement across the tile's north-east corner.
     */
    public static final int BLOCK_MOVEMENT_NORTH_EAST = 0x4;

    /**
     * Blocks movement across the tile's east edge.
     */
    public static final int BLOCK_MOVEMENT_EAST = 0x8;

    /**
     * Blocks movement across the tile's south-east corner.
     */
    public static final int BLOCK_MOVEMENT_SOUTH_EAST = 0x10;

    /**
     * Blocks movement across the tile's south edge.
     */
    public static final int BLOCK_MOVEMENT_SOUTH = 0x20;

    /**
     * Blocks movement across the tile's south-west corner.
     */
    public static final int BLOCK_MOVEMENT_SOUTH_WEST = 0x40;

    /**
     * Blocks movement across the tile's west edge.
     */
    public static final int BLOCK_MOVEMENT_WEST = 0x80;

    /**
     * An object stands on the tile and blocks it.
     */
    public static final int BLOCK_MOVEMENT_OBJECT = 0x100;

    /**
     * A floor decoration blocks the tile.
     */
    public static final int BLOCK_MOVEMENT_FLOOR_DECORATION = 0x40000;

    /**
     * The floor itself blocks the tile (water, say).
     */
    public static final int BLOCK_MOVEMENT_FLOOR = 0x200000;

    /**
     * Any of the three whole-tile blocks.
     */
    public static final int BLOCK_MOVEMENT_FULL = BLOCK_MOVEMENT_OBJECT | BLOCK_MOVEMENT_FLOOR_DECORATION
            | BLOCK_MOVEMENT_FLOOR;

    /**
     * Blocks line of sight across the tile's north edge.
     */
    public static final int BLOCK_LINE_OF_SIGHT_NORTH = 0x400;

    /**
     * Blocks line of sight across the tile's east edge.
     */
    public static final int BLOCK_LINE_OF_SIGHT_EAST = 0x1000;

    /**
     * Blocks line of sight across the tile's south edge.
     */
    public static final int BLOCK_LINE_OF_SIGHT_SOUTH = 0x4000;

    /**
     * Blocks line of sight across the tile's west edge.
     */
    public static final int BLOCK_LINE_OF_SIGHT_WEST = 0x10000;

    /**
     * Blocks line of sight through the whole tile.
     */
    public static final int BLOCK_LINE_OF_SIGHT_FULL = 0x20000;

    /**
     * Blocks line of sight across the tile's north-west corner. Lua's {@code V_NW}.
     */
    public static final int BLOCK_LINE_OF_SIGHT_NORTH_WEST = 0x200;

    /**
     * Blocks line of sight across the tile's north-east corner. Lua's {@code V_NE}.
     */
    public static final int BLOCK_LINE_OF_SIGHT_NORTH_EAST = 0x800;

    /**
     * Blocks line of sight across the tile's south-east corner. Lua's {@code V_SE}.
     */
    public static final int BLOCK_LINE_OF_SIGHT_SOUTH_EAST = 0x2000;

    /**
     * Blocks line of sight across the tile's south-west corner. Lua's {@code V_SW}.
     */
    public static final int BLOCK_LINE_OF_SIGHT_SOUTH_WEST = 0x8000;

    /**
     * NPCs and players can't stand on the tile. Lua's {@code BLOCK_NPC_AND_PLAYERS}.
     */
    public static final int BLOCK_NPCS_AND_PLAYERS = 0x80000;

    /**
     * The tile has a roof. Lua's {@code ROOF}.
     */
    public static final int ROOF = 0x100000;

    /**
     * The tile is in a multi-combat area. Lua's {@code MULTIWAY}.
     */
    public static final int MULTIWAY = 0x400000;

    /**
     * Lua's {@code FREEMAP}.
     */
    public static final int FREEMAP = 0x800000;

    /**
     * The tile isn't loaded (outside the scene, or the map is still loading). Lua's {@code UNLOADED}.
     */
    public static final int UNLOADED = 0x1000000;

    /**
     * An NPC or player stands on the tile. Lua's {@code NPCS_OR_PLAYERS}.
     */
    public static final int NPCS_OR_PLAYERS = 0x2000000;

    /**
     * Every walk-blocking edge and corner bit. Lua's {@code WALK_BLOCK_FLAGS}.
     */
    public static final int WALK_BLOCK_FLAGS = 255;

    /**
     * Every sight-blocking bit. Lua's {@code VIS_BLOCK_FLAGS}.
     */
    public static final int VIS_BLOCK_FLAGS = 130560;

    /**
     * Blocks movement onto the tile from any side. Lua's {@code SQ_BLOCKED}.
     */
    public static final int SQ_BLOCKED = 19136768;

    public static final int PL_WALK_NW = 19136899;
    public static final int PL_WALK_N = 19136770;
    public static final int PL_WALK_NE = 19136782;
    public static final int PL_WALK_E = 19136776;
    public static final int PL_WALK_SE = 19136824;
    public static final int PL_WALK_S = 19136800;
    public static final int PL_WALK_SW = 19136992;
    public static final int PL_WALK_W = 19136896;
    public static final int PL_WALK_NWSW = 19136995;
    public static final int PL_WALK_NESE = 19136830;
    public static final int PL_WALK_NWNE = 19136911;
    public static final int PL_WALK_SWSE = 19137016;

    public static final int NPC_WALK_NW = 17039747;
    public static final int NPC_WALK_N = 17039618;
    public static final int NPC_WALK_NE = 17039630;
    public static final int NPC_WALK_E = 17039624;
    public static final int NPC_WALK_SE = 17039672;
    public static final int NPC_WALK_S = 17039648;
    public static final int NPC_WALK_SW = 17039840;
    public static final int NPC_WALK_W = 17039744;
    public static final int NPC_WALK_NWSW = 17039843;
    public static final int NPC_WALK_NESE = 17039678;
    public static final int NPC_WALK_NWNE = 17039759;
    public static final int NPC_WALK_SWSE = 17039864;
    public static final int NPC_WALK_NESW = 17039854;

    public static final int VIS_N = 17433600;
    public static final int VIS_E = 17436672;
    public static final int VIS_S = 17448960;
    public static final int VIS_W = 17498112;
    public static final int VIS_N_NON_PC = 16909312;
    public static final int VIS_E_NON_PC = 16912384;
    public static final int VIS_S_NON_PC = 16924672;
    public static final int VIS_W_NON_PC = 16973824;

    /**
     * Whether any bit of a mask is set in a tile's flags: the test the client's walk and line of sight checks make
     * with their masks.
     *
     * @param flags a tile's flags
     * @param mask  a flag or mask from this class
     * @return true if {@code flags & mask} is non-zero
     */
    public static boolean isSet(int flags, int mask) {
        return (flags & mask) != 0;
    }
}
