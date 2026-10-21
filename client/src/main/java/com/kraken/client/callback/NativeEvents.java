package com.kraken.client.callback;

import com.kraken.api.internal.event.NativeEvent;
import com.kraken.client.callback.NativeProtocol.Plan;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the hook captures per event: the {@code osrs.Events} raises and the plain function hooks the server asks the
 * DLL to install, each with a capture plan saying which argument registers to read and how. This is the one place the
 * client's event contract — what an argument means at a raise — is written in code, the same way the API keys its
 * reads by Lua function. The hook sites (RVAs, ids) come from the mapping; this only says which events to want and
 * how to read their payloads.
 *
 * <p>An empty plan means the event needs no payload: its tracker re-reads and diffs when the trigger arrives. Where a
 * payload is read, the register meanings follow the raise-site analysis in {@code docs/event-hooks-design.md} §2.3
 * and §4.2. Some are marked "to confirm" there and need a live check (§7); the trackers keep a full-read fallback for
 * those, so a wrong payload costs latency, not a missed event.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class NativeEvents {

    /**
     * The {@code osrs.Events} raises to hook, by {@code enums.events} name, with their capture plans. Their hook
     * sites come from {@code Mapping.eventHooks(name)}.
     */
    static final Map<String, List<Plan>> LUA = lua();

    /**
     * The plain function hooks to install, by {@code functions.*} key, with their capture plans. Their RVAs come from
     * {@code Mapping.function(name)}.
     *
     * <p>Left out on purpose:
     * <ul>
     *   <li>{@code objAdd}, {@code objDel}, {@code objCount} ({@code ItemSpawned}/{@code ItemDespawned}/
     *       {@code ItemQuantityChanged}): design §2.5 passes their item id and count <b>on the stack</b>, which the
     *       generic four-register detour cannot forward to the original — calling it would corrupt the item add or
     *       remove. Hooking them needs a stack-preserving detour. Until then the {@code ItemTracker} reads the piles
     *       on the safety poll, its stated fallback, and its {@code objAdd}/{@code objDel}/{@code objCount} triggers
     *       stay wired for when such a detour lands.
     *   <li>{@code projectileStep}: raises about 50 times a second per projectile, so it is opt-in
     *       ({@link NativeEvent#PROJECTILE_STEP}).
     * </ul>
     */
    static final Map<String, List<Plan>> FUNCTIONS = functions();

    private static Map<String, List<Plan>> lua() {
        Map<String, List<Plan>> map = new LinkedHashMap<>();
        map.put("ON_GAME_TICK", List.of());
        map.put("ON_SCREEN_RESIZE", List.of());
        map.put("ON_GAME_STATE_CHANGED", List.of(new Plan(NativeEvent.RDX, NativeProtocol.OP_I32)));
        map.put("ON_NPC_LOAD", List.of(new Plan(NativeEvent.R8, NativeProtocol.OP_I32)));
        map.put("ON_NPC_UNLOAD", List.of(new Plan(NativeEvent.R8, NativeProtocol.OP_I32)));
        map.put("ON_LOC_LOAD", loc());
        map.put("ON_LOC_UNLOAD", loc());
        map.put("ON_VARBIT_UPDATE", List.of(new Plan(NativeEvent.RDX, NativeProtocol.OP_I32X2),
                new Plan(NativeEvent.R8, NativeProtocol.OP_I32)));
        map.put("ON_VAR_TRANSMIT", List.of());
        map.put("ON_STAT_TRANSMIT", List.of());
        map.put("ON_INV_TRANSMIT", List.of());
        return Collections.unmodifiableMap(map);
    }

    private static List<Plan> loc() {
        return List.of(new Plan(NativeEvent.RDX, NativeProtocol.OP_I32),   // packed coord (to confirm)
                new Plan(NativeEvent.R8, NativeProtocol.OP_CSTR),          // LocType name
                new Plan(NativeEvent.R9, NativeProtocol.OP_I32));          // a byte from the loc record
    }

    private static Map<String, List<Plan>> functions() {
        Map<String, List<Plan>> map = new LinkedHashMap<>();
        // clientCycle is hooked but routes to no tracker yet: ClientTick stays posted once per timer poll, since a
        // trigger must not post events itself. Its per-cycle cadence arrives with the poll-loop wake-up, which runs a
        // poll per cycle. Hooking it now also lets the Event log show the client's cycles.
        map.put(NativeEvent.CLIENT_CYCLE, List.of());
        map.put(NativeEvent.SET_GAME_STATE, List.of(new Plan(NativeEvent.RDX, NativeProtocol.OP_RAW)));
        map.put(NativeEvent.PLAYER_ADD, List.of());
        map.put(NativeEvent.PLAYER_REMOVE, List.of());
        map.put(NativeEvent.NPC_INFO_DECODE, List.of());
        map.put(NativeEvent.PLAYER_INFO_DECODE, List.of());
        map.put(NativeEvent.PROJECTILE_CTOR, List.of());
        return Collections.unmodifiableMap(map);
    }
}
