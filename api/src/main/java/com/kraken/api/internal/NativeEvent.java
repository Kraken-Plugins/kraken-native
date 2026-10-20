package com.kraken.api.internal;

import java.util.Map;

/**
 * One native event raise, parsed from a {@code kraken_hook.dll} message: the event's name (its
 * {@code enums.events} name, or a function hook's name), the id it carried, the four argument
 * registers at the raise and any values the capture plan read out of them. {@code EventSource}
 * routes these to the trackers through {@link EventSource#onNative}; the client's hook server builds
 * them off the socket.
 *
 * <p>Internal: plugins never see these. They subscribe to the {@code com.kraken.api.events} classes
 * the trackers post in response.
 *
 * @param name   the event's name, e.g. {@code "ON_NPC_LOAD"} or {@code "clientCycle"}
 * @param id     the raise's event id, or a function hook's synthetic id
 * @param regs   the four argument registers (RCX, RDX, R8, R9) at the raise; unused ones are 0
 * @param values the captured values, keyed by the argument register ({@link #RCX}…{@link #R9}) they
 *               were read from; a value is an {@link Integer}, {@link Long}, {@code int[]} or
 *               {@link String} depending on how the plan read it
 * @param seq    the raise's sequence number, which climbs by one per raise so a gap marks dropped
 *               messages
 */
public record NativeEvent(String name, int id, long[] regs, Map<Integer, Object> values, long seq) {

    /** Argument register indices, matching the hook's capture-plan register ids. */
    public static final int RCX = 0;
    public static final int RDX = 1;
    public static final int R8 = 2;
    public static final int R9 = 3;

    /**
     * Names for the function hooks that aren't {@code osrs.Events} raises. Each is the matching
     * {@code functions.*} key in the mapping, and is the {@link #name()} the hook server stamps on
     * those messages and the trackers route on, so the client and the API agree on them here.
     */
    public static final String CLIENT_CYCLE = "clientCycle";
    public static final String SET_GAME_STATE = "setGameState";
    public static final String PLAYER_ADD = "playerAdd";
    public static final String PLAYER_REMOVE = "playerRemove";
    public static final String NPC_INFO_DECODE = "npcInfoDecode";
    public static final String PLAYER_INFO_DECODE = "playerInfoDecode";
    public static final String OBJ_ADD = "objAdd";
    public static final String OBJ_DEL = "objDel";
    public static final String OBJ_COUNT = "objCount";
    public static final String PROJECTILE_CTOR = "projectileCtor";
    public static final String PROJECTILE_STEP = "projectileStep";

    /**
     * A captured {@code int32}, from a {@code RAW}, {@code I32} or {@code I64} value.
     *
     * @param reg the argument register the value was read from
     * @return the value as an int, or null if the plan captured nothing there
     */
    public Integer i32(int reg) {
        Object value = values.get(reg);
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            return l.intValue();
        }
        return null;
    }

    /**
     * A captured {@code int64} or raw register value.
     *
     * @param reg the argument register the value was read from
     * @return the value as a long, or null if the plan captured nothing there
     */
    public Long i64(int reg) {
        Object value = values.get(reg);
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return (long) i;
        }
        return null;
    }

    /**
     * A captured pair of {@code int32}s (an {@code I32X2} value), e.g. a varbit id and its previous
     * value.
     *
     * @param reg the argument register the value was read from
     * @return the two ints, or null if the plan captured no pair there
     */
    public int[] i32x2(int reg) {
        return values.get(reg) instanceof int[] pair ? pair : null;
    }

    /**
     * A captured NUL-terminated string (a {@code CSTR} value), e.g. a loc or NPC name.
     *
     * @param reg the argument register the value was read from
     * @return the string, or null if the plan captured none there
     */
    public String str(int reg) {
        return values.get(reg) instanceof String s ? s : null;
    }

    /**
     * One raw argument register, whether or not the plan captured a value from it.
     *
     * @param reg the register index, {@link #RCX}…{@link #R9}
     * @return the register's value at the raise
     */
    public long reg(int reg) {
        return regs[reg];
    }
}
