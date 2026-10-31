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
 * payload is read, the register meanings follow the raise-site analysis in {@code docs/developer/events.md}
 * ("Capture Plans"). Some need a live check (listed there); the trackers keep a full-read fallback for those, so a
 * wrong payload costs latency, not a missed event.
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
     *       {@code ItemQuantityChanged}): these take their item id and count <b>on the stack</b>, which the
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
        // Raised right after the client adds a chat line: the chat tracker reads the history's next id at once
        map.put("ON_CHAT_TRANSMIT", List.of());
        // The op events: the client copies the menu entry it runs into the minimenu, then raises one of these, so the
        // click tracker reads the copy instead of a payload. ON_OBJ_OP is left out: its raiser takes its fifth and
        // sixth arguments (the obj's count and tile) on the stack, which the generic four-register detour can't
        // forward, so calling it through the detour would corrupt the ground item op. Those clicks come from the poll.
        map.put("ON_NPC_OP", List.of());
        map.put("ON_LOC_OP", List.of());
        map.put("ON_TILE_OP", List.of());
        map.put("ON_PLAYER_OP", List.of());
        map.put("ON_COMPONENT_OP", List.of());
        return Collections.unmodifiableMap(map);
    }

    private static List<Plan> loc() {
        return List.of(new Plan(NativeEvent.RDX, NativeProtocol.OP_I32),   // packed coord (to confirm)
                new Plan(NativeEvent.R8, NativeProtocol.OP_CSTR),          // LocType name
                new Plan(NativeEvent.R9, NativeProtocol.OP_I32));          // a byte from the loc record
    }

    private static Map<String, List<Plan>> functions() {
        Map<String, List<Plan>> map = new LinkedHashMap<>();
        // clientCycle routes to no tracker: its raise wakes the poll loop, and the poll posts a ClientTick per step of
        // the client's cycle counter. The hook sits on entry, before the function increments the counter, so the poll
        // waits briefly for the step each raise announces (EventSource.awaitCycle). It also shows the client's cycles
        // in the Event log.
        map.put(NativeEvent.CLIENT_CYCLE, List.of());
        map.put(NativeEvent.SET_GAME_STATE, List.of(new Plan(NativeEvent.RDX, NativeProtocol.OP_RAW)));
        map.put(NativeEvent.PLAYER_ADD, List.of());
        map.put(NativeEvent.PLAYER_REMOVE, List.of());
        map.put(NativeEvent.NPC_INFO_DECODE, List.of());
        map.put(NativeEvent.PLAYER_INFO_DECODE, List.of());
        map.put(NativeEvent.PROJECTILE_CTOR, List.of());
        // Interfaces opening and closing: the interface tracker reads the component, group and mode (or unload flag)
        // from the raw registers, so it sees a group closed and opened again in the same component, which a read of
        // the component table can't.
        map.put(NativeEvent.IF_OPEN_SUB, List.of());
        map.put(NativeEvent.IF_CLOSE_SUB, List.of());
        // Scripts setting varcs: the varc tracker reads the id (and an int varc's value) from the raw registers, so it
        // posts each change a script makes, even several between two polls
        map.put(NativeEvent.SET_VARC_INT, List.of());
        map.put(NativeEvent.SET_VARC_STRING, List.of());
        // NPC animations being set (the update mask and the NPC animation packet): the animation tracker reads every
        // actor's animation at once. The call can be refused (a lower-priority sequence), so it reads rather than
        // trusting the sequence in EDX. Players' animations are set inside playerInfoDecode, already hooked.
        map.put(NativeEvent.SET_SEQUENCE, List.of());
        return Collections.unmodifiableMap(map);
    }
}
