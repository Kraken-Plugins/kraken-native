package com.kraken.api.lua;

/**
 * Mirrors the Lua {@code osrs.CollisionFlag} enum: the bits of a tile's collision flags
 * ({@link CollisionMap#getBitFlags}, {@link Collision#getBitFlags}) and the masks the client's movement and line of
 * sight tests check. Flags combine, so these are int constants; test them with {@link #isSet}.
 *
 * <p>{@code W_*} bits block walking across one edge or corner of the tile, {@code V_*} bits block line of sight the
 * same way, and the {@code PL_WALK_*} / {@code NPC_WALK_*} / {@code VIS_*} values are the combined masks the client
 * tests for a step or a line in one direction.
 */
public final class CollisionFlag {

    public static final int W_NW = 1;
    public static final int W_N = 2;
    public static final int W_NE = 4;
    public static final int W_E = 8;
    public static final int W_SE = 16;
    public static final int W_S = 32;
    public static final int W_SW = 64;
    public static final int W_W = 128;
    public static final int WALK_SCENARY = 256;
    public static final int V_NW = 512;
    public static final int V_N = 1024;
    public static final int V_NE = 2048;
    public static final int V_E = 4096;
    public static final int V_SE = 8192;
    public static final int V_S = 16384;
    public static final int V_SW = 32768;
    public static final int V_W = 65536;
    public static final int VIS_SCENARY = 131072;
    public static final int WR_GROUND_DECOR = 262144;
    public static final int BLOCK_NPC_AND_PLAYERS = 524288;
    public static final int ROOF = 1048576;
    public static final int WR_GRND = 2097152;
    public static final int MULTIWAY = 4194304;
    public static final int FREEMAP = 8388608;

    /**
     * The tile isn't loaded (outside the scene, or the map is still loading).
     */
    public static final int UNLOADED = 16777216;
    public static final int NPCS_OR_PLAYERS = 33554432;

    public static final int WALK_BLOCK_FLAGS = 255;
    public static final int VIS_BLOCK_FLAGS = 130560;

    /**
     * Blocks movement onto the tile from any side.
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

    private CollisionFlag() {
    }

    /**
     * Whether any bit of {@code mask} is set in {@code flags}: the test the Lua examples make with
     * {@code osrs.Math.Bitwise.isSet(flags, CollisionFlag.SQ_BLOCKED)}, and the one the client's walk and line of
     * sight checks make with their masks.
     *
     * @param flags a tile's flags
     * @param mask  a flag or mask from this class
     * @return true if {@code flags & mask} is non-zero
     */
    public static boolean isSet(int flags, int mask) {
        return (flags & mask) != 0;
    }
}
