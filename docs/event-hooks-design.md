# Native event hooks

The client's own event raises decide when the API reads. `kraken_hook.dll`, injected into `osclient.exe`, hooks the
raise functions the mapping names and pushes a message per raise; `NativeEventServer` hands each one to
`GameSession.onNative`, which wakes the poll loop, and `EventSource`'s trackers read and post the same
`com.kraken.api.events` classes they post without a hook. Without the DLL (not found, injection failed, an older
mapping) the trackers run on the 20 ms poll alone.

Built: the updater side (`events.py`, the mapping's `events` section, the function hooks), the DLL (generic slots;
protocol v3 since the drawing, `docs/in-game-drawing.md`, rides on the same socket; v5 adds the script events, §2.6),
the Java server and the API seam. Several payloads and hook sites still need a live check (§7).
Build and run steps are in `client/src/main/native/kraken_hook/README.md`.

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
| ON_IF_LOAD (47) | `0x1b2030` fixed | 3 | raised by the on-load runner (`0x19bff0`) once per component with an on-load script, after the script ran: RDX → `int` interface id (high 16 bits of the component key), R8 → `int` low 16 bits. Not hooked; the interface events hook the open function (§2.7) |
| ON_TARGET_ENTER (21), ON_TARGET_LEAVE (22) | `0x3902b0` id in RCX | 2 | RDX → an object (not decoded) |
| ON_NPC_OP (23), ON_PLAYER_OP (26), ON_COMPONENT_OP (28) | `0x3924f0` id in RCX | 4 | raised by the op dispatch (`0x3803d0`) for the menu entry being run, right after it is copied to `Minimenu.clicked`: RDX → `char*` option text, R8 → `int` (for an entry aimed by a selected item or spell, a field of the selection; else -1; to confirm), R9 → `int` the entry's `subjectIndex` (NPC or player index, component sub id) |
| ON_LOC_OP (24), ON_TILE_OP (25) | `0x3926e0` / `0x3922f0` fixed | 4 / 3 | same dispatch: RDX → `char*` option; loc: R8 → `int` loc id, R9 → the world tile `{level, x, z}`; tile: R8 → the tile |
| ON_OBJ_OP (27) | `0x3928e0` fixed | 4, plus 2 on the stack | same dispatch: RDX → `char*` option, R8 → `int` as for NPCs, R9 → `int` obj id, then the obj count and the tile on the stack. `events.py` counts register arguments only, so the mapping says 4; the generic detour can't forward the stack arguments, so it is not hooked (§4.3) |
| ON_CLIENTSCRIPT (38) | `0xc69a0` fixed | 3 | raised from the packet handler for the server's run-clientscript packet (`0x8c530` builds the Lua table from the request): script id and arguments. Not hooked; the script hooks (§2.6) see the run itself |
| ON_VAR_TRANSMIT (11), ON_INV_TRANSMIT (12), ON_STAT_TRANSMIT (13), run energy/weight, reboot, friends, clans, stock, offers | `0x520b0` id in RCX, deferred | 1 | none in the arguments; the data is in the client's transmit state and the normal fields |
| ON_CHAT_TRANSMIT (14), ON_FRIEND_CHANNEL_TRANSMIT (20), and second raisers for friends (15) and clan records (18) | dedicated deferred wrappers | 1 | RCX = client. ON_CHAT_TRANSMIT's (`0x63e70`) only caller is the chat add function, right after it files a line (§2.8) |

Two hook targets that are not Lua events:

| Function | Hook | Why |
|---|---|---|
| `functions.clientCycle` (`0x58b70`, Client vtable slot 4) | entry | the client cycle (`ClientTick`); it increments `global.cycle` |
| `functions.setGameState` (`0x5b810`) | entry; old state at `[RCX + Client.gameState]`, new in EDX | every state write, which the Lua event is not |

Not raised natively in 241.3 (no wrapper found): ON_MAP_REBUILD_PRE/POST, ON_SUB_CHANGE, ON_MINIMENU_CLICK, the
ON_IF_* interaction events other than ON_IF_LOAD, and the pointer/key events (those have raisers, `0x20e520`…, but
without the gate: they belong to the plugin UI input path). Nothing in the API depends on them.

### 2.4 Events with no native counterpart

API events the client raises no Lua event for: `PlayerSpawned` / `PlayerDespawned`, `ItemSpawned` /
`ItemDespawned` / `ItemQuantityChanged`, `InteractingChanged`, `ProjectileMoved` and `ProjectileSpawned`.
For these the mapping carries plain function hook targets (§2.5), found from the struct fields they write rather than
from an event id.

The menu events have no hook of their own: `MenuEntryAdded` / `MenuEntryRemoved` / `MenuOpened` come from a diff of
`Minimenu.rows` on every poll (ON_MINIMENU_REBUILD fires every client cycle while the menu is closed, so it is not
worth a message), and `MenuOptionClicked` from `Minimenu.clicked` and `Minimenu.clickCycles`, which the op events
above make the API read right away (`updater/src/native_updater/minimenu.py` maps the layout).

`GraphicsObjectCreated` / `GraphicsObjectDestroyed` have no hook either: they come from a diff of the top-level world's
`World.graphicsObjects` list on every poll. The client creates graphics objects in its constructor (`0x38c00`, from the
map-anim cases of the zone update `0x95f10` and the packet handler `0x8e500`), which takes five stack arguments, and
drops them in a per-frame filter (`0x73840`) that runs once per object every frame, so neither is worth hooking.
With the hook connected, `clientCycle` wakes a poll every cycle anyway.

Keyed projectiles (`Client.keyedProjectiles`, the ones the server can retarget in flight) have no hook either:
`ProjectileTracker` reads the map on every poll, posting `ProjectileSpawned` and `ProjectileMoved` for a new key and
another `ProjectileMoved` when a projectile's target changes. Their constructor (`0x31120`) and step (`0x31430`) could
be hooked, but a poll a cycle later loses nothing.

### 2.5 Function hook targets

All under `functions` in the mapping (`hooks.py`, the `projectiles` extractor and two fingerprints). The DLL only
hooks entries: the message is queued before the original runs. "Post" below means the state the tracker needs is
written by the function, so the tracker reads it on the poll the raise wakes, after the call has returned; "pre"
means the state is still there at the entry.

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
| `projectileStep` | `0xa2630` | post | RCX Projectile* | a `ProjectileMoved` per cycle per projectile, if wanted |
| `projectileListUpdate` | `0xc5e10` | post | RCX Client*, RDX the list | once per cycle after every projectile stepped: a cheaper single trigger for the projectile tracker |
| `clientCycle` | `0x58b70` | pre | RCX Client* | `ClientTick` |
| `setGameState` | `0x5b810` | pre | RCX Client*, EDX new state | `GameStateChanged` on every write |
| `ifOpenSub` | `0x84fe0` | pre | RCX Client*, EDX component, R8D group, R9D modal mode | `WidgetLoaded` (§2.7) |
| `ifCloseSub` | `0x851d0` | pre | RCX Client*, EDX component, R8D group, R9B unload | `WidgetClosed` (§2.7) |
| `setVarcInt` | `0x33080` | pre | RCX the varc store, EDX varc id, R8D value | `VarClientIntChanged` (§2.9) |
| `setVarcString` | `0x33340` | pre | RCX the varc store, EDX varc id, R8 → the script's string value | `VarClientStrChanged` (§2.9) |
| `setSequence` | `0x9fe50` | post | RCX Actor*, EDX sequence, R8D delay | `AnimationChanged` (§2.10): an NPC's animation set by its update or the NPC animation packet |

How they were found, so a new build can be checked by hand: `objAdd` is the one function that allocates, stores
`GroundItem.id` and `GroundItem.count` and reads `Client.tickCount` (despawn timers); `objCount` and `objDel` are
its siblings under the zone-update handler, telling them apart by the count store and the ObjType load before the
list unlink. `playerAdd` is the Actor-constructor caller that stores `Player.index`; `playerRemove` is called next to
it and reads `Player.index` and `Actor.worldId`; `playerInfoDecode` is the packet handler's callee that receives the
same packet stack slot as `npcInfoDecode` (the caller of the NPC update decoder) and reaches `playerAdd`. The
projectile functions come from the list walker the projectile extractor already follows.

Note on the pile functions: the same six zone-update handlers also exist under a second caller (`0x95f10`), the
scene rebuild replaying the zone; hooking the functions catches both paths.

### 2.6 Script events

`ScriptPreFired` and `ScriptPostFired` come from four plain function hooks on the client's script runtime (cs2),
mapped by `updater/src/native_updater/scripts.py` (`spec/66-scripts.yaml`). The addresses are 241.3's.

Every script runs on one static runner (`global.scriptRunner`, `0x154fe50`), which holds a 1000-entry int stack
(`ScriptRunner.intStack`, its size at `intStackSize`), a long stack, a 1000-entry stack of `VarValue`s
(`objStack`, `objStackSize`; a 40-byte variant whose kind byte at `VarValue.type` says int, string or long,
`enums.varValueType`), the current locals and up to 50 gosub frames (`frameCount`). The functions:

| Function | 241.3 | What it is | Hook |
|---|---|---|---|
| `functions.executeScript` | `0x1994c0` | runs a component listener's request (`HookReq`): resolves the script (the first argument is its id), resets the stacks, copies the other arguments into the locals, loading a request field in place of each placeholder (`0x80000001` mouse x … `0x8000000a` sub-op) and of the string `"event_opbase"`, then calls the interpreter. The one function referencing `"event_opbase"` | entry: keeps the request for the interpreter run it starts |
| `functions.executeScriptInner` | `0x199cc0` | the interpreter loop `(runner, shared_ptr<ClientScript>*, maxOps, int)`; every root run goes through it (requests, trigger scripts `0x1992e0`, a held item's overlay `0x199a20`) | entry: `PRE_ROOT` with the script's id (`ClientScript.id`) and the request, if a request run started it; after it returns: `POST` for the root |
| `functions.scriptLookup` | `0x322a0` | the script with an id; called only by the gosub case (op 40, with the callee's id from the caller's int operands, before it pops the callee's arguments) and by the request entry | entry, inside an interpreter run: `PRE_GOSUB` with the callee's id; notes the id at depth `frameCount + 1` |
| `functions.scriptSwapLocals` | `0x198740` | `(frame, ints, longs, objects)`, swaps a frame's locals with the runner's; called only by the gosub case (on the frame it pushed, holding the caller's script) and the return case (op 21, on the frame it popped, after clearing the frame's script slot `ScriptFrame.script`) | entry, inside an interpreter run, on a cleared frame: `POST` for the script at depth `frameCount + 1` |

The ids of nested scripts aren't in reach at a return (the frame holds the caller), so the DLL keeps the id it saw for
each depth: depth 0 at the interpreter's entry, depth `frameCount + 1` at each gosub's lookup. The hooks only report
while an interpreter run they saw start is on the stack, so a script already running while they install is passed
over rather than half reported. Every message also carries the top of both stacks: at a gosub the callee's arguments,
at a return its return values.

Wire format (protocol v5, `KRK5`): the handshake ends with a `u16` length and a 147-byte script block (the four
function RVAs and the runner's, the runner, frame, script and VarValue offsets, the three VarValue kinds, the
request's fields, `IfType.packedId`/`subId` and the eastl string layout; `NativeProtocol.scriptLayout` writes it,
`parseScriptLayout` in the DLL reads it); an empty block installs no script hooks. STATUS sets feature bit 8 once all
four are hooked. Command `4 SCRIPTS {u8 flags, u8 count, count x i32 id}` (bit 0 starts, bit 1 returns, bit 2 root
runs of the listed scripts starting, at most 8) says what to send; nothing is sent until it asks, and a dropped
connection resets it. A script message is id -6 with
`regs = {kind (0 root start, 1 gosub, 2 return), id, depth, 0}`, no plan values, then the int stack's size, a `u16`
count and up to 64 ints from its top, the object stack's size and up to 16 values from its top, then a request flag
and, for a root run a request started, the source and target components' ids and indices, the op, sub-op, mouse x
and y, key code and key char, the base op name and up to 24 of the listener's arguments. A value is a kind byte
(0 other, 1 int, 2 string, 3 long) and its payload; strings are cut at 256 bytes. Script messages share the ring and
the sequence numbers with the raises; the ring holds 4 MiB of variable-length records, so a burst of scripts in one
frame fits.

On the Java side `NativeEventServer` hands each script message to `GameSession.onScript`, which posts the event on
the client thread at once (it doesn't ask for an event read). `EventSource` tells the DLL, on each poll, to send
starts while `ScriptPreFired` has subscribers and returns while `ScriptPostFired` does (`internal.event.ScriptHook`).
While a handler runs, `Client.getIntStack()`, `getIntStackSize()`, `getObjectStack()` and `getObjectStackSize()`
give the captured stacks (as long as the stacks were, the entries below the captured top 0 or null); otherwise they
read the runner now. A root run's `ScriptPreFired.getScriptEvent()` is built from the request: its arguments are the
listener's as it holds them (placeholders unreplaced), and its source and target are read when asked for.

Deliberate differences from posting in the script engine itself:

- The events come after the fact. The game thread doesn't wait for Java, so a handler sees the game as it is now,
  and changing the stacks or the arguments changes nothing in the game.
- A root run that stops on an error (an unknown gosub, the op limit) still posts `ScriptPostFired` for the root,
  since the interpreter's return is the only exit the hook sees; the nested scripts it abandons post none.
- A root run a trigger started has no request: its event's arguments are just its id, and its id is the trigger key
  `ClientScript.id` holds (at least `0x10000`).
- There is no `ScriptCallbackEvent`: the native client's scripts have no callback op for plugins to hook.

### 2.7 Interface events

`WidgetLoaded` and `WidgetClosed` come from the client's component table and two plain function hooks, mapped by
`updater/src/native_updater/interfaces.py` (`spec/67-interfaces.yaml`). The addresses are 241.3's.

The client keeps the groups opened inside components in a hash map on the Client (`Client.subInterfaces`: a
component's packed id to a node holding the group, `SubInterfaceNode.group`, and the modal mode the server opened it
with, `SubInterfaceNode.type`: 0, 1 or 3), and the top-level interface in `Client.topLevelInterface` (-1 on the login
screen). Every change to the table goes through two functions:

| Function | 241.3 | What it is | Called by |
|---|---|---|---|
| `functions.ifOpenSub` | `0x84fe0` | `(client, component, group, mode)`: inserts the node, stores the group and the mode, lays the parent out again, then runs the group's on-load (`0x198f30`, a stub into `0x19bff0`, the one function raising ON_IF_LOAD: it loads the group's components and runs each one's on-load script) | the packet handler's open case and its resync |
| `functions.ifCloseSub` | `0x851d0` | `(client, component, group, unload)`: erases the node, unloads the group's components if `unload` is set, drops the group's event flags, lays the top-level interface out again | the packet handler's open case (on a component already holding a group: `unload` unless it is the same group), close case, move case (on the destination) and resync (on the groups the server no longer lists); close-modal (`0x84e40`, the nodes of mode 0 and 3); the logout reset (`0x8b800`, every node) |

The open-top case (and the resync, when the top-level interface changes) unloads the old top-level group, writes the
new one and runs its on-load itself, with no function to hook. One path (`0x8cde0`) clears the table without closing
each node.

`WidgetTracker` compares the component table and the top-level interface on every poll with what it last saw: a group
gone from its component posts `WidgetClosed` (with the mode it was opened with, `unload` true), a group come into one
posts `WidgetLoaded`, and a group that moved to another component (the move case) posts neither. A new top-level
interface posts `WidgetLoaded`, and the one it replaced posts nothing, since the client replaces it rather than
closing it. The read is strict: one that fails part way, or finds a group id above `0xffff` (the table changing under
the read), is dropped, and the next poll compares again.

The two hooks are entry hooks whose payload is the raw registers (no plan). The tracker applies their reports in order
before it compares, which adds what a comparison can't see: a group closed and opened again in the same component (the
open case with the same group, which a dialogue does for each new line: `WidgetClosed` with `unload` false, then
`WidgetLoaded`), and a group opened and closed between two polls. A report of a change a read already showed (an open
of the group the component holds, a close of a group it doesn't hold) is passed over. An entry hook's report can reach
the tracker before the change reaches memory, so the components the reports touched sit out that poll's comparison.

`WidgetLoaded` waits until the interface store holds the group's components (`IfStoreGroup.count` above 0; the on-load
runner loads them), for at most 50 polls, so a handler can read the widgets. The events behind it wait with it.

Deliberate differences from posting in the client itself:

- The events come after the fact: a handler sees the interface as it is now, and `WidgetClosed`'s widgets may already
  be unloaded.
- Without the hook, a group reopened in the same component or opened and closed between two polls posts nothing, and
  every close is an unload.
- The top-level interface posts `WidgetLoaded` when it changes, and never `WidgetClosed`.
- ON_IF_LOAD isn't used: it fires once per component with an on-load script, not once per group.

### 2.8 Chat, hitsplats, focus and fake XP drops

Four more events, each from what the client keeps rather than a hook of their own. The addresses are 241.3's.

**`ChatMessage`** comes from the chat history, mapped by `updater/src/native_updater/chat.py` (`spec/68-chat.yaml`).
Every line goes through one function, `functions.chatAddLine` (`0x2e280`, `(type, name, text, sender)` with eastl
strings; internal, the recipe's anchor): it finds the type's history buffer (a ring of 100 lines in a global hash map
keyed by type, `0x154fa18`), reuses the buffer's oldest line or makes a new one, fills it in (`0x2f070`), files it
under its id in a second global hash map (`global.chatLines.buckets` `0x154fa48`, `bucketCount` beside it; a node
holds the id and a `shared_ptr<ChatLine>`), then raises ON_CHAT_TRANSMIT. Filling a line takes the id from a global
counter (`global.chatLines.nextId`, `0xe98b70`; the counter's old value is the id), stores the client cycle
(`ChatLine.cycle`) and the type (`ChatLine.type`), and copies the name, text and sender (`ChatLine.name`, `text`,
`sender`). The client's own "[HH:MM]" timestamp string sits in the line too, unmapped. Logout (`0x8b800`) and one other
reset (`0x8a5e0`) clear both maps and set the counter back to 0 (`0x2e730`).

`ChatTracker` reads the counter every poll (one int) and, when it moved, looks each id from its last value up in the
id map, posting a `ChatMessage` per line found, oldest first; a counter that went back means the history was cleared,
so every id from 0 is new. The hook adds ON_CHAT_TRANSMIT to `NativeEvents`, which only makes the read happen at
once. `MessageNode` is a snapshot of the line; its timestamp is when the API read it.

**`HitsplatApplied`** comes from each actor's hitmarks, mapped by `updater/src/native_updater/hitmarks.py`
(`spec/69-hitmarks.yaml`). The NPC and player update masks hand each hitmark to `functions.addHitmark` (`0x9e200`,
`(actor, type, value, delay, cycle, count)`, called from `0x98dc0` and `0x1e7ad0` with `global.cycle`; internal). The
actor keeps its hitmarks in an `eastl::vector` (`Actor.hitmarks` `0x330`, `hitmarksEnd` `0x338`) of 12-byte entries
(`Hitmark.size`): `{type, value, end cycle}` (`Hitmark.type`, `value`, `endCycle`; end = arrival cycle + delay +
the type's duration), reused in place. A new hitmark goes over the first entry that has ended, onto the end while the
vector has fewer entries than the count allows, or, when every entry still shows, over the one its HitmarkType's rule
picks (lowest end cycle, or lowest value if that is below the new one), or nowhere.

The add function takes two stack arguments, so the generic detour can't hook it. `HitsplatTracker` compares every
NPC's and player's entries on the tick's poll and the one after, and on the poll after `npcInfoDecode` or
`playerInfoDecode` (hitmarks only arrive in those updates): a slot holding a different entry than at the last read is
a new hitsplat. An actor seen for the first time posts the entries it still shows (it came into view with them).

**`FocusChanged`** has no client state behind it. The game's window procedures (`0x6d21d0` for the frame, `0x6d1e00`
and the input handler `0xadcbd0` for the render view) only move the focus to the render view and let go of held keys
on `WM_KILLFOCUS`; nothing records whether the game has the focus, and the client sends no focus packet. So
`Client.isFocused()` asks Windows (`internal.GameFocus`): the focus window of the foreground thread
(`GetGUIThreadInfo(0)`) belongs to the game's process. That holds for a top-level game window and for one embedded in
another process's window, since an embedded window shares its parent's input state. `FocusTracker` compares it every
poll. Nothing is mapped for it.

**`FakeXpDrop`** comes from the script hooks (§2.6). The server shows an XP drop that adds no XP (a skill at 200M XP)
by running script 2091 with the skill and the XP through its run-clientscript packet (the case near `0x93fea`: the
ON_CLIENTSCRIPT raise, then `0x198ef0` runs the request through `executeScript`), so the interpreter hook sees it as a
root run with the request's arguments `{2091, skill, xp}`. While `FakeXpDrop` has subscribers, the API lists 2091 in
the SCRIPTS command (bit 2), so the DLL reports that root run even with `ScriptPreFired` off, and `ScriptEvents`
posts a `FakeXpDrop` after the script's `ScriptPreFired`.

Deliberate differences from posting in the client itself:

- All four come after the fact; a handler sees the game as it is now.
- `ChatMessage`: lines added after the history was cleared and refilled to the same count between two polls aren't
  seen (logout and login take many polls). Lines already there when tracking starts post nothing.
- `HitsplatApplied`: a hitmark the client doesn't keep (every entry showing and the new one ranking below them) posts
  nothing, nor does one written over within one update; the event's hitsplat is the entry as kept.
- `FocusChanged` is Windows' keyboard focus, so clicking a control beside the embedded game counts as losing it.
- `FakeXpDrop` needs the hook connected; without it the script reports never come.

### 2.9 Varc events

`VarClientIntChanged` and `VarClientStrChanged` come from the client's varc store and two plain function hooks, mapped
by `updater/src/native_updater/varcs.py` (`spec/64-varcs.yaml`). The addresses are 241.3's.

The Client points at the varc store (`Client.varcs`, `0x417ca8`): a vector of 48-byte entries sorted by id
(`Varcs.entries`, `entriesEnd`), each holding the id (`VarcEntry.key`), a variant value (`VarcEntry.value`) and its kind
(`VarcEntry.type`: 1 int, 2 string, 5 long, 4 a shared value the client builds for a few varcs). Lua's
`getIntegerVarc` and `getStringVarc` binary-search it, and so do `Client.getVarcIntValue` and `getVarcStrValue`.

Scripts are the only writers that set one varc. Each setter is called from one case of the interpreter
(`functions.executeScriptInner`) and nowhere else:

| Function | 241.3 | Called by | What it does |
|---|---|---|---|
| `functions.setVarcInt` | `0x33080` | op 43, SET_VARC_INT | `(store, id, value)`: finds the id's entry with the store's lower bound (`0x47a90`), inserts one if there is none, assigns the int, and marks the store for saving when the varc is a saved one |
| `functions.setVarcString` | `0x33340` | op 50, SET_VARC_STRING | `(store, id, value)`: the same with the string value the case pops off the object stack |
| (`0x331d0`) | | op 52 | the same for a long varc; not mapped, since neither event covers longs |

The other writers work on the whole store: the logout reset (`0x8b800`) calls `0x33ae0`, which drops every varc that
isn't saved, and `0x342f0` (called from `0x32d30` and `0x8c2e0`) loads the saved ones from `preferences*.dat`. The
recipe takes the interpreter's dispatch (`scripts._op_cases`) and, in the case of each op, the one call whose first
argument is the store (`[[global.client] + Client.varcs]`) and whose callee inserts into `Varcs.entries`. The ops are
the cache's script opcodes, so they hold across builds.

`VarcTracker` reads every int and string varc in one block read per poll (`Client.readVarcs`; strict, so a read that
fails part way gives nothing rather than looking like varcs being dropped) and compares it with the values it knows: a
varc whose value changed, or that was set or dropped, posts its event, ints then strings, each in id order.

The two hooks are entry hooks whose payload is the raw registers (no plan): `setVarcInt` reports the id and the value,
`setVarcString` only the id (a string's text can be on the heap, past what a capture plan reads). The tracker applies
the reports in order before it compares. An int report posts if its value differs from the one the tracker knows, so
each change a script makes posts, even several between two polls; a string report posts if the poll's read shows a
new value for that varc. An entry hook's report can come before the write, so the varcs the reports touched sit out
that poll's comparison, and a string write the read missed posts on the next poll.

Deliberate differences from posting in the client itself:

- The events come after the fact: a handler reads the varc's value as it is now.
- A set to the value the varc already holds posts nothing.
- A string varc set several times between two polls posts once. Without the hook, so does an int varc, and a varc
  changed and changed back between two polls posts nothing.
- Varcs the logout reset drops and the saved ones loading at login post too.
- Long varcs and the shared values post nothing.

`VarbitChanged` needed nothing new: `VarTracker` compares the client's varps every poll, and ON_VAR_TRANSMIT and
ON_VARBIT_UPDATE make it read at once (§4.3).

### 2.10 Animations, deaths and NPC changes

`AnimationChanged`, `ActorDeath` and `NpcChanged` come from fields the mapping already had, plus one more function hook;
nothing new in the mapping. The addresses are 241.3's.

The three trackers compare one value per loaded actor (`internal.event.ActorValues`), keyed by slot (an NPC's index, or
a player's index past every NPC index). The reads are strict: an actor that is gone or can't be read gives no value
rather than a "nothing" value, and an actor the client lists but whose handle can't be pinned for a moment keeps the
value the last read saw, so a failed read never looks like a change, or like an actor leaving and coming back.

**`AnimationChanged`** comes from the actor's sequence, `Actor.anim` (`0x4d8`), read by `Actor.getAnimation()`. It sits
in an animation block at `0x4c8` (the SeqType shared pointer, `Actor.animSeqType` at `0x4d0`, then the sequence, frame
and loop count) that the client's animation helpers take whole; `0x5e4880` sets a block's sequence (a negative id
clears it to -1) and `0x5e4b50` steps its frames. No Lua function reads it. The functions that change it:

| Function | 241.3 | Called by | What it does |
|---|---|---|---|
| `functions.setSequence` | `0x9fe50` | the NPC update mask's sequence block (in `0x98dc0`, under `npcInfoDecode`) and the NPC animation packet (in the packet handler `0x8e500`) | `(actor, seq, delay)`: the sequence playing restarts by its SeqType's restart mode (`+0xe4`: 1 resets the frame and the delay, 2 the loop count) and isn't written again; another one is written unless it ranks below the one playing (SeqType priority, `+0xc8`); -1 always clears |
| (`0x71470`) | | the player update mask's sequence block (`0x1e7d90`, a closure `playerInfoDecode` applies through `0x1dc130` and `0x1b7ed0`), the appearance block (`0x1e7550`, which clears the sequence), the local player's animation packet (`0x8e500`), a cs2 op (`0x1a59d0`) and a callback that animates the local player (`0x3a3b20`) | `(client, actor, seq, delay)`: the same rules, after swapping the sequence id through a table on the Client (`+0x26b0`). Not mapped: its calls with server updates run inside `playerInfoDecode`, which is hooked |
| (`0x70d80`) | | the per-cycle actor step (`clientCycle` → `0x6b400` → `0x61680` → `0x6fdb0` per actor) | counts `Actor.animDelay` down, then steps the frames; when the step reports the sequence finished, clears it (`0x71221`) |
| (`0x6fdb0`, `0x9f7f0`, `0x9f8c0`, `0xa2220`) | | the actor step; movement in the NPC and player updates | clear the sequence when the actor leaves the scene, or when it moves and its SeqType says moving cancels it (`+0xe0` = 1) |

So the server starts animations inside the update decoders and the animation packets, while the client ends them on its
own, any cycle. `AnimationTracker` reads every NPC's and player's animation on the tick's poll and the one after, and on
the poll after `setSequence`, `npcInfoDecode` or `playerInfoDecode`; on the other polls it reads again only the actors
playing an animation, whose animations can end between ticks, which keeps the per-poll cost to the animating actors. An
actor seen for the first time posts one if it is playing an animation, and tracking that starts as the API attaches
posts one per actor playing one (the replay rule, as for targets).

The `setSequence` hook is an entry hook with no plan: the call can be refused (a lower priority) or only restart the
sequence, so the tracker reads the actors rather than trusting the sequence in EDX. It takes three register arguments,
so the generic detour forwards it.

Deliberate differences from posting where the client writes the sequence:

- The events come after the fact: a handler reads the animation as it is now.
- An animation that starts and ends between two reads posts nothing, nor does one replaced and set back.
- Restarting the sequence an actor is playing posts nothing, since the client doesn't write it again.
- A start the local-player packet or a script makes between ticks (through `0x71470`, which isn't hooked) is seen on
  the next tick's poll.

**`ActorDeath`** comes from `Actor.isDead()`, Lua's `isDead`: a health bar (head bar type 0) is showing and one of the
actor's head bars' current update shows 0/0. Head bar updates arrive in the NPC and player update masks, each with a
start cycle (the server can delay it) and a duration (`HeadbarType.duration`). `DeathTracker` reads every actor's head
bars on the tick's poll and the one after, and on the poll after `npcInfoDecode` or `playerInfoDecode`, and posts when
`isDead()` turns true. The read is strict (`ActorReader.readDead`; Lua's three getters now share its walk and read a
failure as false): a head bar or update list that can't be read, or breaks off at a null link, keeps the last answer, so
it can't look like the actor coming back to life and dying again. An actor seen for the first time posts one if it is
dead; tracking that starts posts none.

Deliberate differences from posting as the update arrives:

- The event comes when the empty bar starts showing: an update the server delays posts on the first tick's poll after
  its start cycle.
- An actor dies again only after a read shows it not dead: its empty bar timed out, or a later update shows health. A
  second empty update while the first still shows posts nothing.
- An actor the server kills without an empty health bar posts nothing.

**`NpcChanged`** comes from each NPC's base type id (`Npc.type` → `NpcType.id`), read by `NPC.getId()`. A transform
rewrites the type in place: the NPC update decoder (`0x98dc0`), for mask bit `0x2`, reads a 16-bit type id, loads the
type (`functions.npcTypeLoader`, `0x5f18f0`) and assigns it to the NPC's type shared pointer (`0x3d440` on
`npc + 0x728`, the control block; `Npc.type` at `0x730` is the object), then `0x9dad0` copies the new type's size and
pose animations onto the NPC. The NPC's address and `Npc.spawnCycle` stay as they were, so its handle survives, and
`ActorTracker` posts no despawn or spawn. Other writers: the add pass (`0x98470`) reuses an NPC it still holds at the
index (looked up in `World.npcs`) or makes a new one, assigns the type and restamps `Npc.spawnCycle` from the packet;
the removal (`0x9d070`) raises ON_NPC_UNLOAD, then clears the type pointer before it erases the NPC, which the strict
read takes as no value.

`NpcChangeTracker` compares every NPC's base type id on the tick's poll and the one after, and on the poll after
`npcInfoDecode`, and posts the event with the old type's definition (`Client.getNpcDefinition`). A multi-NPC showing
another child type isn't a change: its base type stays the same.

Deliberate differences from posting as the type is assigned:

- The event comes after the fact, and its old definition is decoded from the config archive by id.
- A transform and its reversal between two reads post nothing.

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
  records. `functions.dispatchEvent` is the no-payload raiser the updater starts from.
- **What the arguments mean is not in the mapping.** It is the client's Lua event contract, stable across builds
  like the Lua function names, so the Java side keys payload decoding by event name (§4.2), the same way the API
  keys reads by Lua function.

## 4. Design

```
 osclient.exe (game thread)                      kraken_hook.dll                     Kraken client (JVM)
 ───────────────────────────                     ───────────────                     ───────────────────
 raiser entry ─► detour[k] ──capture plan──► ring buffer ──► writer thread ──socket──► NativeEventServer (reader thread)
   (id, RCX..R9, derefs)       ~1 µs                                                      │ GameSession.onNative
                                                                                          ▼
                                                                          EventSource.onNative(event)  (poll thread)
                                                                            triggers the trackers ─► EventBus ─► plugins
```

### 4.1 The DLL: a table of generic hooks

- **Handshake** (Java → DLL, little-endian; v3 changes the magic to `KRK3` and appends the drawing's three RVAs,
  `docs/in-game-drawing.md` §2.1; v4 changes it to `KRK4` and adds two call argument kinds, `docs/menu-actions.md`
  §2): magic `KRK2`, then `u16 count`, then per slot
  `{u64 rva, i32 fixedId (-1 = read idArg), u8 idArg, u8 args, u8 planLen, plan[planLen]}`. A plan entry is
  `{u8 arg, u8 op}` with ops `RAW`, `I32`, `I64`, `I32x2` (two ints at the pointer), `CSTR` (NUL-terminated, at most
  64 bytes). Java builds the table from `Mapping.eventHooks` for the events in `NativeEvents` plus the function
  hooks, and attaches the plan by event name.
- **Slots, not per-function code.** MinHook needs a distinct detour per target, so the DLL compiles a fixed array of
  64 identical stubs, `detour<k>(a, b, c, d)`: look up slot k, read the id (fixed or from the chosen argument),
  run the plan against the raw registers, push a message, then call the slot's trampoline with the same four
  registers and return its result. One signature `std::int64_t __fastcall (std::int64_t, std::int64_t, std::int64_t,
  std::int64_t)` fits every raiser that takes no stack or floating-point arguments; extra registers are ignored by
  the originals that take fewer. Raisers with stack arguments (`objAdd`, `objDel`, `objCount`, ON_OBJ_OP) are not
  hooked, since forwarding them through this detour would corrupt the call.
- **Message** `{u32 len, u32 seq, i32 eventId, u64 regs[4], u8 nValues, values...}` where a value is
  `{u8 arg, u8 op, payload}`. `seq` climbs by one per raise, so Java sees drops as gaps. Event id `-2` is a status
  message reporting how many hooks installed.
- **Never stall the game thread.** The detour only writes into a single-producer ring of 4 MiB of variable-length
  records (the game thread is the one producer; raises off that thread would be a finding, §7). A DLL writer thread
  drains it to the socket, and the detour only signals it when it is about to wait; when the ring is full the
  message is dropped. Dereferences happen in the detour, under SEH guards, because the
  pointers are to the caller's stack and die when the raiser returns.
- **Lifecycle.** Hooks stay installed for the life of the process (unhooking live code is riskier than leaving a
  pass-through detour). If the socket drops, the writer thread reconnects every second; Java re-sends the handshake
  and the DLL only adds slots it does not have yet. A second injection is refused by a named mutex.

### 4.2 Java: `NativeEventServer`

In `client/.../callback`:

- `NativeEventServer` lives in the session injector (`SessionModule`) and installs on every launch: `KrakenClient`'s
  attach listener calls `install()`, which injects (`CallbackInjector`) the DLL bundled in the jar or the one
  `-Dkraken.hook.dll` points at.
- It builds the slot table from the mapping: every event in `NativeEvents` that the mapping has hooks for, plus the
  function hooks. It logs each event it could not hook (older mapping, no raise site) and goes on.
- The reader thread parses messages into an `internal.event.NativeEvent(name, id, regs, values, seq)` and hands each
  to `GameSession.onNative`, which queues it for the client thread and wakes the poll loop. Nothing is posted from the
  hook thread.
- `NativeEvents` is the Java-side catalogue of payload shapes by event name (for `ON_NPC_LOAD` `{R8: I32}`, for
  `ON_LOC_LOAD` `{RDX: I32, R8: CSTR, R9: I32}`, for `ON_VARBIT_UPDATE` `{RDX: I32x2, R8: I32}`, for
  `ON_GAME_STATE_CHANGED` `{RDX: I32}`, for `setGameState` `{RDX: RAW}`, none for the rest). It is the one place the
  Lua event contract is written down in code. `projectileStep` is opt-in (`-Dkraken.hook.projectileStep=true`).
- `NativeEventServer.onEvent(String name, Consumer<NativeEvent>)` is a read-only diagnostic tap on raw messages.
  Plugins only ever see `com.kraken.api.events` classes on the `EventBus`, posted by `EventSource`.

### 4.3 The API seam: hooks trigger `EventSource`, they do not replace it

The events must keep their semantics (pinned handles, replay on attach, scene-load spawns, the held `LOGGED_IN`,
posting order) and must keep working without the hook. So the trackers stay, and the native events are
**triggers**: `EventSource.onNative` records a trigger on each tracker whose `triggerNames()` include the event, and
the next poll runs those trackers in their usual order, so a tick arriving together with NPC loads still posts the
spawns before `GameTick`.

| Native event | Tracker | Effect |
|---|---|---|
| ON_GAME_TICK | tick | wakes the poll right after `tickCount` moved, so `GameTick` and the per-tick trackers run at once |
| ON_NPC_LOAD / ON_NPC_UNLOAD, `playerAdd` / `playerRemove` | NPC / player `ActorTracker` | runs the index-list diff now (the payload slot is not used yet) |
| ON_LOC_LOAD / ON_LOC_UNLOAD | `LocTracker` | re-reads the tile at the payload coord between ticks; the whole-scene compare still runs once per tick |
| ON_VAR_TRANSMIT, ON_VARBIT_UPDATE | `VarTracker` | diffs vars now (VARBIT_UPDATE alone covers only the client's watch list) |
| ON_STAT_TRANSMIT | `StatTracker` | re-reads stats now |
| ON_INV_TRANSMIT | `ContainerTracker` | re-reads the item containers now |
| `setGameState`, ON_GAME_STATE_CHANGED | `GameStateTracker` | reads the state now; the holding of `LOGGED_IN` and the scene-load logic are unchanged |
| ON_SCREEN_RESIZE | `CanvasTracker` | compares the canvas size now |
| `clientCycle` | (none) | wakes the poll loop, which posts one `ClientTick` per step of the cycle counter (§4.4) |
| `npcInfoDecode` / `playerInfoDecode` | `InteractingTracker` | diffs targets right after the packet |
| `projectileCtor`, `projectileStep` (opt-in) | `ProjectileTracker` | diffs the projectile list now; the keyed projectiles are read on every poll (§2.4) |
| (none) | `GraphicsObjectTracker` | diffs the world's graphics object list on every poll (§2.4) |
| ON_NPC_OP, ON_LOC_OP, ON_TILE_OP, ON_PLAYER_OP, ON_COMPONENT_OP | `MenuClickTracker` | reads `Minimenu.clicked` now and posts `MenuOptionClicked` if the copy or its cycle count shows a new run; ON_OBJ_OP is not hooked (stack arguments), so ground item clicks come from the poll |
| `objAdd` / `objDel` / `objCount` | `ItemTracker` | wired, but not hooked (stack arguments); piles are read on the poll |
| `ifOpenSub` / `ifCloseSub` | `WidgetTracker` | applies the open and close reports in order, then compares the component table (§2.7); the table is also compared on every poll |
| ON_CHAT_TRANSMIT | `ChatTracker` | reads the chat's next line id now (§2.8); it is also read on every poll |
| `npcInfoDecode` / `playerInfoDecode` | `HitsplatTracker` | compares every actor's hitmarks right after the update (§2.8); also on the tick's polls |
| (none) | `FocusTracker` | compares Windows' keyboard focus on every poll (§2.8) |
| `setVarcInt` / `setVarcString` | `VarcTracker` | applies the set reports in order, then compares the varc store (§2.9); the store is also compared on every poll |
| `npcInfoDecode` | `NpcChangeTracker` | compares every NPC's base type id right after the update (§2.10); also on the tick's polls |
| `setSequence`, `npcInfoDecode` / `playerInfoDecode` | `AnimationTracker` | reads every actor's animation now (§2.10); also on the tick's polls, and the animating actors on every poll |
| `npcInfoDecode` / `playerInfoDecode` | `DeathTracker` | compares every actor's head bars right after the update (§2.10); also on the tick's polls |

While the hook is connected the events are read when a raise asks for it, and at least every 250 ms as a safety net,
so a dropped message costs latency, not an event. Without a hook they are read every 20 ms.

### 4.4 Threads and latency

- The detour runs on the game thread for about a microsecond (capture + ring push). The socket write is on the
  DLL's writer thread.
- The Java reader thread does nothing but parse and enqueue.
- Dispatch is on `kraken-api-poll`, the client thread. The poll loop waits between iterations, bounded by the 20 ms
  poll interval, and `GameSession.onNative` wakes it, so a raise is read as soon as it arrives instead of after a
  sleep.
- `GameTick` and `ClientTick` still come from the game's tick and cycle counters, one per step; a raise only makes
  the read happen sooner, so a dropped or repeated raise can't lose or double a tick. Every hook is an entry hook (the
  DLL queues the message before calling the original), and the two counters sit on opposite sides of their raise
  (241.3):
  - The server-tick handler (`0x8e500`) runs `INC [client+tickCount]` at `0x95638` and raises `ON_GAME_TICK` (ECX
    `0x2c`) right after, so the read that raise wakes already sees the new tick. Logout (`0x8ca70`) calls
    `setGameState(LOGIN_SCREEN)` and then sets `tickCount` to 0; the API counts only forward steps, so that reset is no
    tick.
  - `clientCycle` increments `global.cycle` at `0x58bb4`, after a task-queue drain, so its message leaves before the
    step. The poll counts the raises and, when they announce more steps than the counter shows, re-reads it for up to
    2 ms; a step that comes later is counted then.
- Everything else (handlers, overlays, tasks) already runs on the client thread and does not change.

### 4.5 Safety and scope

- Installed on every launch, and still the only thing that writes to the game process: MinHook's entry
  patches and trampolines. The event hooks observe and never call into the client; the only calls the DLL makes are
  the highlight functions the API asks for, on the game thread (`docs/in-game-drawing.md`).
- The DLL guards each detour with `__try/__except` around the plan (a bad pointer must not take the client down)
  and forwards to the trampoline unconditionally.
- After the handshake the DLL sends a status message with how many hooks installed; events without a hook fall back
  to the poll.

### 4.6 From a raise to a plugin: who knows what

The DLL cannot build a Java object and does not try: the object model (pinned actor handles, compositions,
coordinate conversion) lives in the API. So the split is **the DLL sends "which", the API builds "what"**. The DLL
captures an identifier, or nothing, at the moment of the raise; the tracker reads memory with the readers it already
has and posts the same object a poll would hand out. The hook decides *when* and, where a payload is used, narrows
*where*; the tracker still produces the object. Despawns post the handle or `TileItem` the tracker already holds,
which keeps its last known values once the client drops the object, so reading a few milliseconds after the raise is
safe. The DLL never copies whole objects: its job is a dozen bytes per event.

## 5. Not done yet

- A stack-preserving detour, so `objAdd`/`objDel`/`objCount` and ON_OBJ_OP can be hooked.
- Targeted reads from payloads beyond the loc tile: the NPC slot from ON_NPC_LOAD's int, the player index from
  `playerAdd`, the projectile at RCX of `projectileCtor`. Each waits on the live checks below.
- Dropping `LocTracker`'s whole-scene read per tick once ON_LOC_LOAD's coord is confirmed.

## 6. Carry-forward

Nothing here is per build: the updater re-finds the raisers from `Client.tickCount` and `enums.events`, the golden
corpus pins the 241.3 values, and the Java side names events, never addresses. A new build that adds or renames
events shows up as a `new`/`gone` `events.*` key in the run's diff.

## 7. Live checks pending

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
- Script events (§2.6): that every root run reaches `executeScriptInner` on the game thread, that a gosub's lookup
  and a return's swap report the ids and depths the shadow ids say (a known proc's `ScriptPreFired` and
  `ScriptPostFired` pair up), that a component op's request carries the expected op, sub-op and source, how many
  scripts a busy interface (the bank) runs a frame and whether the ring keeps up, and the game's frame time with both
  kinds switched on.
- Interface events (§2.7): that opening and closing the bank, a dialogue and the side panels each make one open or close
  call on the game thread, that a dialogue's next line closes its group without unloading and opens it again in the
  same component, that the modes read 0, 1 or 3, what clears the table without closing each node (`0x8cde0`) and when,
  and that a `WidgetLoaded` handler finds the group's widgets.
- Chat (§2.8): that the probe's `chat` check passes (the newest lines by id have text, a type and a past cycle), that
  ON_CHAT_TRANSMIT fires once per line, what `0x8a5e0` (the second history reset) is, and that a busy public chat
  posts every line once.
- Hitsplats (§2.8): the probe's `hitmarks` check, that one hit posts one `HitsplatApplied` on the right actor with the
  shown amount, what the count argument the update passes is (a cap on the entries), and that the end cycle matches
  when the hitsplat disappears.
- Focus (§2.8): `FocusChanged` on alt-tab, on clicking the Kraken sidebar and back into the game, and with the game
  standalone.
- Fake XP drops (§2.8): that a skill at 200M XP gaining XP makes the server run script 2091 with the skill and the XP,
  as a root run the interpreter hook sees.
- Varcs (§2.9): that typing in the chatbox reports `setVarcString` for `VarClientID.CHATINPUT` and posts
  `VarClientStrChanged`, that opening an input dialogue posts `VarClientIntChanged` for `MESLAYERMODE`, how many sets a
  second a busy interface makes (the ring and the poll keep up), and which varcs logging out drops.
- Animations (§2.10): the probe's `anim` check; that chopping a tree posts `AnimationChanged` with the woodcutting
  animation and, within a poll of the client clearing it, -1 when the player stops; that an NPC attacking raises
  `setSequence` on the game thread; the poll's cost with many animating actors (a busy bank or a crowded fight); and
  what `0x71470`'s swap table on the Client holds.
- Deaths (§2.10): that killing an NPC posts one `ActorDeath` as its bar shows empty, that the local player's death
  posts one, and how long an empty bar shows before it times out.
- NPC changes (§2.10): that an NPC transforming (a boss's next phase) posts `NpcChanged` with the old definition and the
  same handle, and whether the add pass ever reuses an NPC at an index while keeping its `Npc.spawnCycle` (that would
  post `NpcChanged` where a new NPC came in).
