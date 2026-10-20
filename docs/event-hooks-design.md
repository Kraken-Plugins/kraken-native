# Native event hooks: from `GameTickCallback` to every event

Status: design, 2026-10-20. The updater side is done (`events.py`, the mapping's `events` section, `functions.clientCycle`,
`functions.setGameState`, `Client.pluginGate`). The DLL, the Java hook server and the API seam described here are
not built yet.

## 1. Where we are

Two things produce events today:

- `api/.../internal/EventSource` polls the client every 20 ms on the `kraken-api-poll` thread and diffs reads into
  the RuneLite-shaped events in `com.kraken.api.events`. It is complete (every event class has a tracker), correct
  about identity (pinned actor handles, the replay rule, scene loads) and expensive where it has to be: the scene's
  objects are a whole-scene read once per tick, and every other kind of state is re-read on a timer whether or not
  anything happened.
- `client/.../callback/GameTickCallback` plus `kraken_hook.dll` is a push proof of concept for one event. Once the
  API has attached it injects the DLL, which inline-hooks `functions.dispatchEvent` and sends one byte per
  `ON_GAME_TICK`. It is off unless `-Dkraken.hook=true`.

The goal is a push path for all of it: plugins keep subscribing to the same `com.kraken.api.events` classes, but the
client's own event raises drive when the API reads, and, where the raise carries a payload, what it reads.

## 2. What the client gives us

Everything below is from the static analysis behind `events.py`, build 241.3 (RVAs are for that build; the mapping
carries them forward).

### 2.1 One shape of raise wrapper

The client raises its Lua plugin events (`osrs.Events`, `enums.events` in the mapping) through a family of small
wrapper functions, one per argument shape. Each one opens the same way:

```
MOV  RCX, [global.client]
ADD  RCX, Client.pluginGate      ; 0x418690: the optional plugin-manager slot
CALL gateTest                    ; 0x41cb0: false when Lua plugins are off
TEST AL, AL ; JZ skip
MOV  RCX, [RCX]
CALL runtimeGetter               ; 0x218cd0 (immediate) or 0x218ce0 (deferred)
...                              ; event id: MOV EDX, <id>, or the wrapper's ECX passed through
CALL/JMP fire<Args...>           ; sol2 call into Lua, or the pending-flag setter
```

Two consequences shape the design:

- **A hook on a wrapper's entry sees every raise, whether or not Lua plugins are enabled**, because the detour runs
  before the gate. We do not depend on BETA worlds or on `luaplugin` being on.
- **The event id is always known at the entry**: either the wrapper is specific to one event (the id is a constant in
  its body) or its callers pass the id in `ECX` (`MOV ECX, 0x2c; CALL dispatchEvent`). The mapping records which
  (`idArg: 0` vs `id: N`).

The wrappers take at most four integer register arguments (RCX, RDX, R8, R9) and nothing on the stack or in XMM
registers, so one detour shape forwards any of them.

### 2.2 Immediate and deferred events

Most wrappers fire the Lua callback on the spot (`fire0`, `fire2`, `fire3`, or an inlined copy of the same body).
The `*_TRANSMIT` family is different: its wrappers (`0x520b0` generic, plus dedicated ones for chat, friends, clans)
only mark the event pending; the client fires it to Lua later (`0xf9950`). For us the pending-flag wrapper is the
better hook: it runs in the packet handler, right after the var, inventory or stat data landed in the client's
memory, which is exactly when a re-read is worth doing.

### 2.3 The raise sites in 241.3

The mapping's `events` section, with what each wrapper's arguments hold at the site (from the call-site code; the
payload interpretations marked "to confirm" need the live check in §7):

| Native event | Hook site | Args | Payload at the site |
|---|---|---|---|
| ON_GAME_TICK (44) | `0xc44d0` id in RCX | 1 | none; raised right after `INC [client + Client.tickCount]` in the packet handler |
| ON_SCREEN_RESIZE (45) | `0xc44d0` | 1 | none; raised by a Client vtable method on a 500 ms deadline, not only on a change |
| ON_DIALOG_ABORT (32), ON_MINIMENU_REBUILD (36) | `0xc44d0` | 1 | none |
| ON_GAME_STATE_CHANGED (39) | `0xc5550` fixed | 2 | RDX → `int` trigger (`enums.gameStateTrigger`); raised from the login packet path (trigger 3) and `setGameState` (trigger 1) only |
| ON_NPC_LOAD (42), ON_NPC_UNLOAD (43) | `0xc7930` id in RCX | 3 | RDX → `char*` NPC name (an eastl::string's data), R8 → `int` = `hi << 16 \| lo` from two NPC fields, believed to be the NPC type id and its slot (to confirm) |
| ON_LOC_LOAD (40), ON_LOC_UNLOAD (41) | `0xc7b20` / `0xc7c80` fixed | 4 | RDX → packed coord (to confirm: `plane<<28 \| x<<14 \| z`, world or scene), R8 → `char*` LocType name, R9 → `byte` from the loc record |
| ON_VARBIT_UPDATE (46) | `0xc6ca0` fixed | 3 | RDX → `{int varbitId, int previous}`, R8 → `int` new value; only for varbits the client watches, inside the var packet handler |
| ON_IF_LOAD (47) | `0x1b2030` fixed | 3 | RDX → `int` interface id (high 16 bits of the component key), R8 → `int` low 16 bits |
| ON_TARGET_ENTER (21), ON_TARGET_LEAVE (22) | `0x3902b0` id in RCX | 2 | RDX → an object (not decoded) |
| ON_NPC_OP (23), ON_PLAYER_OP (26), ON_COMPONENT_OP (28) | `0x3924f0` id in RCX | 4 | menu op arguments (not decoded) |
| ON_LOC_OP (24), ON_TILE_OP (25), ON_OBJ_OP (27) | dedicated fixed wrappers | 3–4 | menu op arguments (not decoded) |
| ON_CLIENTSCRIPT (38) | `0xc69a0` fixed | 3 | script id and arguments (not decoded) |
| ON_VAR_TRANSMIT (11), ON_INV_TRANSMIT (12), ON_STAT_TRANSMIT (13), run energy/weight, reboot, friends, clans, stock, offers | `0x520b0` id in RCX, deferred | 1 | none in the arguments; the data is in the client's transmit state and the normal fields |
| ON_CHAT_TRANSMIT (14), ON_FRIEND_CHANNEL_TRANSMIT (20), and second raisers for friends (15) and clan records (18) | dedicated deferred wrappers | 1 | RCX = client |

Two hook targets that are not Lua events:

| Function | Hook | Why |
|---|---|---|
| `functions.clientCycle` (`0x58b70`, Client vtable slot 4) | entry | the client cycle, RuneLite's client tick; it increments `global.cycle` |
| `functions.setGameState` (`0x5b810`) | entry; old state at `[RCX + Client.gameState]`, new in EDX | every state write, which the Lua event is not |

Not raised natively in 241.3 (no wrapper found): ON_MAP_REBUILD_PRE/POST, ON_SUB_CHANGE, ON_MINIMENU_CLICK, the
ON_IF_* interaction events other than ON_IF_LOAD, and the pointer/key events (those have raisers, `0x20e520`…, but
without the gate: they belong to the plugin UI input path). Nothing in the API depends on them.

### 2.4 Events with no native counterpart

RuneLite events the client raises no Lua event for: `PlayerSpawned` / `PlayerDespawned`, `ItemSpawned` /
`ItemDespawned` / `ItemQuantityChanged`, `InteractingChanged`, `ProjectileMoved` and Kraken's `ProjectileSpawned`.
For these the mapping carries plain function hook targets (§2.5), found from the struct fields they write rather than
from an event id.

### 2.5 Function hook targets

All under `functions` in the mapping (`hooks.py`, the `projectiles` extractor and two fingerprints). "Pre" means the
detour reports before calling the original, "post" after it returns, which is when the written state is readable.

| Function | 241.3 | Hook | Arguments at the entry | Serves |
|---|---|---|---|---|
| `playerAdd` | `0x1dbc20` | post | RCX player-state record, RDX → out `{ctrl, Player*}` pair, R8D index, R9 World* | `PlayerSpawned`: the new `Player*` is the pair's second slot; or re-read slot R8D |
| `playerRemove` | `0x9ce60` | pre | RCX Client*, RDX World*, R8D index | `PlayerDespawned`: the player is still readable |
| `playerInfoDecode` | `0x1dd530` | post | RCX → `Client.playerIndexCount` (count, then indices), RDX packet, R8 World* | `InteractingChanged` and any player change: the whole player info packet has been applied |
| `npcInfoDecode` | `0x97ff0` | post | RCX NPC-update state, RDX World*, R8B large-packet flag, R9 packet | `InteractingChanged` and NPC changes: removal pass, add pass and update masks applied (ON_NPC_LOAD/UNLOAD fire inside it) |
| `objAdd` | `0x748c0` | post | RCX Client*, RDX World*, R8D x, R9D z (zone-relative, to confirm), then id and count on the stack | `ItemSpawned`: re-read that tile's pile (or all piles) |
| `objDel` | `0x74be0` | pre | same shape | `ItemDespawned`: the entry is still in the pile |
| `objCount` | `0x74e30` | post | same shape, old and new count on the stack | `ItemQuantityChanged` |
| `projectileCtor` | `0xa24a0` | post | RCX Projectile* (inside a fresh `make_shared` block), RDX parameters | `ProjectileSpawned`, then `ProjectileMoved`: read the projectile at RCX |
| `projectileStep` | `0xa2630` | post | RCX Projectile* | a `ProjectileMoved` per cycle per projectile, RuneLite's actual cadence, if wanted |
| `projectileListUpdate` | `0xc5e10` | post | RCX Client*, RDX the list | once per cycle after every projectile stepped: a cheaper single trigger for the projectile tracker |
| `clientCycle` | `0x58b70` | pre | RCX Client* | `ClientTick` |
| `setGameState` | `0x5b810` | pre | RCX Client*, EDX new state | `GameStateChanged` on every write |

How they were found, so a new build can be checked by hand: `objAdd` is the one function that allocates, stores
`GroundItem.id` and `GroundItem.count` and reads `Client.tickCount` (despawn timers); `objCount` and `objDel` are
its siblings under the zone-update handler, telling them apart by the count store and the ObjType load before the
list unlink. `playerAdd` is the Actor-constructor caller that stores `Player.index`; `playerRemove` is called next to
it and reads `Player.index` and `Actor.worldId`; `playerInfoDecode` is the packet handler's callee that receives the
same packet stack slot as `npcInfoDecode` (the caller of the NPC update decoder) and reaches `playerAdd`. The
projectile functions come from the list walker the projectile extractor already follows.

Note on the pile functions: the same six zone-update handlers also exist under a second caller (`0x95f10`), the
scene rebuild replaying the zone; hooking the functions catches both paths.

## 3. The mapping contract

```json
"events": {
  "ON_GAME_TICK": [ { "fn": "0xc44d0", "idArg": 0, "args": 1 } ],
  "ON_LOC_LOAD":  [ { "fn": "0xc7b20", "id": 40,   "args": 4 } ],
  "ON_FRIEND_TRANSMIT": [ { "fn": "0x520b0", "idArg": 0, "args": 1 }, { "fn": "0x64320", "id": 15, "args": 1 } ]
},
"functions": { "dispatchEvent": "0xc44d0", "clientCycle": "0x58b70", "setGameState": "0x5b810" },
"structs":   { "Client": { "pluginGate": "0x418690" } }
```

- One list per event, one entry per raiser; a few events have a generic and a dedicated raiser and list both. The
  DLL hooks each distinct `fn` once.
- `args` is how many of RCX, RDX, R8, R9 to capture and forward. `idArg` names the register carrying the event id
  (always 0 so far); `id` is the fixed id otherwise. `Mapping.eventHooks(name)` returns these as `EventHook`
  records; `functions.dispatchEvent` stays for the existing game-tick hook.
- **What the arguments mean is not in the mapping.** It is the client's Lua event contract, stable across builds
  like the Lua function names, so the Java side keys payload decoding by event name (§4.2), the same way the API
  keys reads by Lua function.

## 4. Design

```
 osclient.exe (game thread)                      kraken_hook.dll                     Kraken client (JVM)
 ───────────────────────────                     ───────────────                     ───────────────────
 raiser entry ─► detour[k] ──capture plan──► ring buffer ──► writer thread ──socket──► NativeEventServer (reader thread)
   (id, RCX..R9, derefs)       ~1 µs                                                      │ KrakenApi.invoke(...)
                                                                                          ▼
                                                                          EventSource.onNative(event)  (poll thread)
                                                                            drives the trackers ─► EventBus ─► plugins
```

### 4.1 The DLL: a table of generic hooks

`kraken_hook.cpp` stops being about one function. The handshake becomes a table, and the detours become slots:

- **Handshake v2** (Java → DLL, little-endian): magic `KRK2`, then `u16 count`, then per slot
  `{u64 rva, i32 fixedId (-1 = read idArg), u8 idArg, u8 args, u8 planLen, plan[planLen]}`. A plan entry is
  `{u8 arg, u8 op}` with ops `RAW`, `I32`, `I64`, `I32x2` (two ints at the pointer), `CSTR` (NUL-terminated, at most
  64 bytes). Java builds the table from `Mapping.eventHooks` for the events it wants (not all 34) plus the two
  function hooks, and attaches the plan by event name.
- **Slots, not per-function code.** MinHook needs a distinct detour per target, so the DLL compiles a fixed array of
  N (64) identical stubs, `detour<k>(a, b, c, d)`: look up slot k, read the id (fixed or from the chosen argument),
  run the plan against the raw registers, push a message, then call the slot's trampoline with the same four
  registers and return its result. One signature `std::int64_t __fastcall (std::int64_t, std::int64_t, std::int64_t,
  std::int64_t)` fits every raiser: none takes stack or floating-point arguments, and extra registers are ignored
  by the originals that take fewer.
- **Message** `{u32 len, u32 seq, i32 eventId, u64 regs[4], u8 nValues, values...}` where a value is
  `{u8 arg, u8 op, payload}`. `seq` lets Java detect drops.
- **Never stall the game thread.** The detour only writes into a fixed-size single-producer ring (the game thread
  is the one producer; raises off that thread would be a finding, §7). A DLL writer thread drains it to the socket.
  When the ring is full the message is dropped and a drop counter is sent with the next message. Dereferences happen
  in the detour, because the pointers are to the caller's stack and die when the raiser returns.
- **Lifecycle.** Hooks stay installed for the life of the process (unhooking live code is riskier than leaving a
  pass-through detour). If the socket drops, the writer thread reconnects every second; Java re-sends the handshake
  and the DLL only adds slots it does not have yet. A second injection is refused by a named mutex.

### 4.2 Java: `NativeEventServer` replaces `GameTickCallback`

In `client/.../callback`, the server socket, injection and reader thread move from `GameTickCallback` into a
`NativeEventServer`:

- Arms exactly as today (`-Dkraken.hook=true`, DLL path property, inject once `ProcessAttacher` is `ATTACHED`).
- Builds the slot table from the mapping: every event in `NativeEvents` (below) that the mapping has hooks for, plus
  `clientCycle` and `setGameState`. Logs each event it could not hook (older mapping, no raise site) and goes on.
- The reader thread parses messages into a `NativeEvent(name, id, long[] regs, Map<Integer, Object> values, seq)`
  and hands each one to the API on the client thread with `KrakenApi.invoke`, which runs it at the start of the
  next poll iteration (≤ 20 ms away, before `EventSource.poll()`), or to a new `KrakenApi.pushNative` that wakes the
  poll loop immediately (§4.4). Nothing is posted from the hook thread.
- `NativeEvents` is the Java-side catalogue of payload shapes by event name: for `ON_NPC_LOAD`
  `{R8: I32}`, for `ON_LOC_LOAD` `{RDX: I32, R8: CSTR, R9: I32}`, for `ON_VARBIT_UPDATE` `{RDX: I32x2, R8: I32}`,
  for `ON_GAME_STATE_CHANGED` `{RDX: I32}`, for `setGameState` `{RDX: RAW}` and `{RCX: RAW}`, none for the rest.
  This is the one place the Lua event contract is written down in code.

The old `onTick` listeners survive as `NativeEventServer.onEvent(String name, Consumer<NativeEvent>)` for dev
tools; the `GameLauncher` log line becomes an `ON_GAME_TICK` subscription.

### 4.3 The API seam: hooks drive `EventSource`, they do not replace it

The RuneLite events must keep their semantics (pinned handles, replay on attach, scene-load spawns, the held
`LOGGED_IN`, posting order) and must keep working when the hook is absent: no DLL, an older mapping, or a user who
has not opted in. So the trackers stay, and the native events become **triggers**: a tracker runs when its trigger
arrives instead of on the 20 ms timer, and reads only what the payload points at when there is one.

`EventSource` gains one entry point, `onNative(NativeEvent)`, called on the poll thread, and a per-tracker
`trigger(NativeEvent)`:

| Native event | Tracker | Effect |
|---|---|---|
| ON_GAME_TICK | tick | run the tick poll now: `GameTick` and the per-tick trackers (scene objects, ground items, targets, actors), instead of noticing `tickCount` moved up to 20 ms later. The counter is incremented right before the raise, so a read after the raise sees the new tick. |
| ON_NPC_LOAD / ON_NPC_UNLOAD | NPC `ActorTracker` | re-read just that slot (payload low 16 bits, to confirm) and post the spawn or despawn; the whole-array diff remains as the fallback and still runs on the tick |
| ON_LOC_LOAD / ON_LOC_UNLOAD | `LocTracker` | re-read the one tile at the payload coord and diff its objects; drop the whole-scene read per tick, keep it for scene loads |
| ON_VAR_TRANSMIT, ON_VARBIT_UPDATE | `VarTracker` | diff vars now (VARBIT_UPDATE alone is not enough: it covers only the client's watch list) |
| ON_STAT_TRANSMIT | `StatTracker` | re-read stats now |
| ON_INV_TRANSMIT | `ContainerTracker` | re-read the item containers now |
| `setGameState` hook, ON_GAME_STATE_CHANGED | `GameStateTracker` | take the new state from the payload; the holding of `LOGGED_IN` and the scene-load logic are unchanged |
| ON_SCREEN_RESIZE | `CanvasTracker` | compare the canvas size now |
| `clientCycle` hook | `ClientTick` | one `ClientTick` per real client cycle (about 50 a second) instead of one per poll |
| `playerAdd` / `playerRemove` hooks | player `ActorTracker` | re-read just that index (R8D) and post the spawn, or post the despawn with the pinned handle before the client drops it |
| `npcInfoDecode` / `playerInfoDecode` hooks | `InteractingTracker`, both `ActorTracker`s | diff targets and actor sets right after the packet is applied, instead of on the next tick poll |
| `objAdd` / `objDel` / `objCount` hooks | `ItemTracker` | re-read the pile at the given tile (to confirm the coordinate base live; until then, all piles) and post the spawn, despawn or quantity change |
| `projectileCtor` hook | `ProjectileTracker` | read the projectile at RCX and post `ProjectileSpawned` then `ProjectileMoved`; the per-poll list diff becomes the fallback |
| `projectileStep` hook (optional) | `ProjectileTracker` | one `ProjectileMoved` per cycle per projectile, matching RuneLite's cadence, at about 50 messages a second per projectile; off by default |

The 20 ms poll keeps running as a safety net at a slower rate (say 250 ms) while the hook is connected, so a
dropped message costs latency, not an event. When the hook disconnects the poll goes back to 20 ms.

Ordering stays RuneLite's: `onNative` queues triggers, and the next poll iteration runs the trackers in their
usual order with the queued triggers applied, so a tick arriving together with NPC loads still posts spawns before
`GameTick`.

### 4.4 Threads and latency

- The detour runs on the game thread for about a microsecond (capture + ring push). The socket write is on the
  DLL's writer thread.
- The Java reader thread does nothing but parse and enqueue.
- Dispatch is on `kraken-api-poll`, the client thread. With `KrakenApi.invoke` the worst case is one 20 ms sleep.
  To get rid of that, the poll loop's `Thread.sleep` becomes a wait on a condition the native queue signals
  (`pushNative`), bounded by the poll interval. That is a small change to `KrakenApi.pollLoop` and is the second
  step, not the first.
- Everything else (handlers, overlays, tasks) already runs on the client thread and does not change.

### 4.5 Safety and scope

- Still opt-in (`-Dkraken.hook=true`) and still the only thing that writes to the game process: MinHook's entry
  patches and trampolines. Read-only semantics of the API are unchanged; hooks observe, they never call into the
  client.
- The DLL guards each detour with `__try/__except` around the plan (a bad pointer must not take the client down)
  and forwards to the trampoline unconditionally.
- If any hook fails to install, the DLL reports which (a status message after the handshake) and Java falls back to
  polling for those events.

## 5. Milestones

1. **Protocol v2 and generic slots.** DLL: slot table, ring buffer, writer thread, ids only. Java:
   `NativeEventServer`, `NativeEvent`, `onEvent` listeners; the Event log dev tool shows raw native events. Verifies
   the hook sites of every mapped event on a live client (§7).
2. **Triggers.** `EventSource.onNative` with the tick, var, stat, inventory and game-state triggers; the poll slows
   to 250 ms while connected. Measures `GameTick` latency against the poll path.
3. **Payloads.** Capture plans; NPC slot and loc tile targeted updates; `LocTracker` stops the whole-scene read per
   tick.
4. **Function hook targets.** `clientCycle` and `setGameState` (`ClientTick` per cycle, every state write), then the
   §2.5 set: players, ground items, the two info decoders, the projectile constructor. After this every event class
   in `com.kraken.api.events` has a push trigger; only the 250 ms safety poll remains timer-driven.
5. **Poll-loop wake-up** (`pushNative`) once the rest is stable.

## 6. Carry-forward

Nothing here is per build: the updater re-finds the raisers from `Client.tickCount` and `enums.events`, the golden
corpus pins the 241.3 values, and the Java side names events, never addresses. A new build that adds or renames
events shows up as a `new`/`gone` `events.*` key in the run's diff.

## 7. Live checks before milestone 2

- Which thread the raises run on (expected: the game main thread for all of them; the deferred wrappers run inside
  the packet handler). If any raiser runs on another thread the ring becomes multi-producer.
- ON_GAME_TICK: one raise per server tick while logged in, none on the login screen; that NPC and player updates of
  the tick precede it (the INC sits at the end of the tick block).
- ON_NPC_LOAD's int: slot in the low 16 bits, type id in the high 16; that the slot matches `Client.npcIndices`.
- ON_LOC_LOAD's coord packing and whether it is a world or a scene tile; that the loc name matches the
  LocType at that tile.
- ON_SCREEN_RESIZE cadence (expected every 500 ms regardless of a change, so the tracker must still compare).
- That hooks survive a logout/login and a world hop, and that `Client.pluginGate` is null on a non-BETA world while
  raises still reach the detours.
- `objAdd` / `objDel` / `objCount`: the base of the x and z arguments (zone-relative, scene or world), one call per
  dropped, picked-up or merged stack, and that the scene-rebuild path (`0x95f10`) fires them for every pile of a new
  scene (then the trackers must treat those as the scene-load replay, not as fresh spawns).
- `playerAdd` / `playerRemove`: one call per player entering and leaving the index list, the `Player*` in the out
  pair after `playerAdd` returns, and that `playerRemove` really drops the slot (it is reached from the removed-index
  list; its hash-map erase was not read to the end).
- `projectileCtor`: that every projectile packet goes through it (the only caller is the `make_shared` site under the
  packet handler), and that the fields are complete when it returns.
