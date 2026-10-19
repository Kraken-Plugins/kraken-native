# Lua API coverage

How much of the client's Lua plugin API ([reference](https://d16t5jyl0qyupa.cloudfront.net/modules.html)) the Java
API mirrors so far, and what each missing piece needs. The Java API is an external, read-only reader: it follows the
same offset chains the native Lua handlers follow, through `ReadProcessMemory`, so it can reproduce any **getter** whose
data is mapped. Functions that **change** the client (highlights, camera control, chat bubbles, shapes) need the planned
in-process backend and are out of scope for the reader.

| Lua | Java |
|---|---|
| `osrs.ClientOp` | `KrakenApi.client()` → `com.kraken.api.game.Client` |
| `osrs.ClientState` | `KrakenApi.clientState()` → `com.kraken.api.game.ClientState` |
| `osrs.Events` | `KrakenApi.events()` → `com.kraken.api.events.Events` |

Status: ✅ mirrored · 🟡 mirrored with a difference (see note) · ⏳ planned for the reader · ✍️ changes the client, needs
the in-process backend.

## Sections

The work is split into sections you can test live with the dev tools, one at a time.

| # | Section | Status | Dev tools |
|---|---|---|---|
| 1 | NPCs and players: ids, names, tiles; `ON_NPC_LOAD`/`UNLOAD`, `ON_GAME_TICK` | Done | NPCs, Players, Location, Event log |
| 2 | ClientState (stats, varps, varcs, tick, login, profile type), inventories; `ON_STAT_TRANSMIT`, `ON_VAR_TRANSMIT`, `ON_INV_TRANSMIT`, `ON_GAME_STATE_CHANGED` | Done | Client state, Skills, Inventories, Var inspector, Event log |
| 3 | Actors in depth: fine coords, uids, creation cycles, find-by-name, route length, `osrs.Entity`/`Npc`/`Player` members | Next | NPCs/Players columns, Interacting |
| 4 | Config types: `ocName`/`ocCost`/`ocStackable`/`ocOp`, `invSize`, `getVarbit` + `ON_VARBIT_UPDATE`, `NpcType`/`ObjType`/`LocType` | Planned | Item names in Inventories, varbits in Var inspector |
| 5 | Camera and projection: `Camera.get*`, `Graphics.clientDimensions`, `Graphics.worldToScreenCoord` | Planned | Camera, labels over NPCs and players |
| 6 | Scene: locs and ground objs (`locFind`, `objFind`, …), collision maps | Planned | Game objects, Ground items, Tile flags |
| 7 | Interfaces: `InterfaceQuery`, `osrs.IfType` | Planned | Widget inspector |

Section 4 is its own section because those functions don't read live game state: they look a definition up in the
client's config cache, which only keeps recently used definitions decoded in memory. Reading it reliably from outside
needs a closer look at that cache first.

## Worked example: from a Lua handler to a Java method

Every mirrored function is built the same way. Take `ClientState.getStatXP(statId)`. The updater records the native
handler each Lua name is bound to (`bindings` in the build's `analysis.json`); for 241.3 it's at RVA `0xf5840`:

```
CMP  EDX, 0x19                              ; statId > 25?           -> return -1
JG   fail
MOV  RCX, qword ptr [0x140e98c18]           ; RCX = the Client object   (globals.client)
CMP  dword ptr [RCX + 0x413f24], 0x0        ; stats sent yet?         (Client.statsValidGate)
JLE  fail                                   ;   no                    -> return -1
MOVSXD RAX, EDX
MOV  EAX, dword ptr [RCX + RAX*0x4 + 0x3418] ; return xp[statId]      (Client.skillXp)
RET
fail: MOV EAX, 0xffffffff
```

(See [ghidra-walkthrough.md](ghidra-walkthrough.md) §0.4 for reading these lines.) The updater turns each number the
handler uses into a mapping key, and the Java method does the same steps with `ReadProcessMemory`
(`ClientState.statValue`):

```java
long client = clientBase();                              // read [moduleBase + globals.client]
if (statId < 0 || statId >= statCount                    // Client.skillIndexMax
        || safeInt(client + cStatsValid, 0) <= 0) {      // Client.statsValidGate
    return -1;
}
return safeInt(client + cSkillXp + statId * 4L, -1);     // Client.skillXp
```

Because the offsets come from the mapping and not from the code, the same Java works on the next client build once
the updater has mapped it. Where Java deliberately differs from the handler (here: it rejects id 25, which the
handler accepts but which reads past the end of the 25-entry array), the method's Javadoc says so.

## `osrs.ClientState`

| Lua | Java | Status | Note |
|---|---|---|---|
| `getStatEffectiveLevel(statId)` | `getStatEffectiveLevel(int \| Stats)` | ✅ | -1 until the server sends stats |
| `getStatBaseLevel(statId)` | `getStatBaseLevel(int \| Stats)` | ✅ | |
| `getStatXP(statId)` | `getStatXP(int \| Stats)` | ✅ | |
| `getStatRemainingXPUntilLevel(statId, level?)` | `getStatRemainingXPUntilLevel(statId[, level])` | ✅ | Same arithmetic as the client, so `level == base` can be negative |
| `getVarp(id)` | `getVarp(int)` | 🟡 | The client doesn't bounds-check; Java returns -1 past the varp array |
| `getVarbit(id)` | | ⏳ | Section 4: needs the VarBitType definition |
| `getTickCount()` | `getTickCount()` | ✅ | |
| `isLoggedIn()` | `isLoggedIn()` | ✅ | Login session set and game state 30, as the handler checks |
| `getProfileType()` | `getProfileType()` | ✅ | Decodes `Client.worldFlags` like the handler |
| `getIntegerVarc(id)` | `getIntegerVarc(int)` | ✅ | |
| `getStringVarc(id)` | `getStringVarc(int)` | ✅ | |
| `Stats`, `ProfileType` | `ClientState.Stats`, `ClientState.ProfileType` | ✅ | |

Java extras (not in Lua): `getGameState()`, `getWorldFlags()`, `areStatsTransmitted()`, `getStatsAll()`, `getVarps()`,
`getVarpCount()`, `getIntegerVarcs()`, `getStringVarcs()`.

## `osrs.ClientOp`

| Lua | Java | Status | Note |
|---|---|---|---|
| `getNpcIdAll()` | `getNpcIdAll()` | ✅ | |
| `getNpcObj(id)` | `getNpcObj(int)` | 🟡 | Returns a snapshot record; `osrs.Npc` methods are section 3 |
| `npcName(npcId)` | `npcName(int)` | ✅ | |
| `npcTypeId(npcId)` | `npcTypeId(int)` | ✅ | |
| `npcCoord(npcId)` | `npcCoord(int)` | ✅ | |
| `npcCoordFine`, `npcUid`, `npcCreationCycle` | | ⏳ | Section 3; offsets already mapped |
| `getNpcIdByName(name)`, `getNpcIdByNameAll(name)` | | ⏳ | Section 3 |
| `getPlayerIdAll()` | `getPlayerIdAll()` | ✅ | |
| `getPlayerObj(id)` | `getPlayerObj(int)` | 🟡 | Snapshot record; `osrs.Player` methods are section 3 |
| `playerName(playerId)` | `playerName(int)` | ✅ | Reads the name pointer at `Player+0x718` |
| `playerCoord(playerId)` | `playerCoord(int)` | ✅ | |
| `playerFindSelf()` | `playerFindSelf()` | 🟡 | Lua returns `[found, id]`; Java returns the id or -1 |
| `playerFineCoord`, `getPlayerIdByName`, `playerRouteLength`, `coord`, `coordFine` | | ⏳ | Section 3 |
| `invGetObjId(invId, slot)` | `invGetObjId(int, int)` | ✅ | |
| `invGetNum(invId, slot)` | `invGetNum(int, int)` | 🟡 | 0 when missing, like the native handler (the docs say -1) |
| `invTotal(invId, objId)` | `invTotal(int, int)` | ✅ | |
| `invSize(invId)` | | ⏳ | Section 4: the size comes from the InvType definition |
| `ocName`, `ocCost`, `ocStackable`, `ocOp` | | ⏳ | Section 4 |
| `locCoord`, `locFind`, `locName`, `locTypeId`, `locGetOp`, `locWidth`, `locLength` | | ⏳ | Section 6 (names and ops need section 4) |
| `objCoord`, `objFind`, `objName`, `objTypeId`, `objCount`, `objGetOp` | | ⏳ | Section 6 |
| `friendsChatGetDisplayName`, `friendsChatGetChatCount` | | ⏳ | Not mapped yet |
| `areProjectilesRendered()` | | ⏳ | Not mapped yet |
| `WornSlots` | `Client.WornSlots` | ✅ | |
| `npcSay`, `playerSay`, `renderProjectiles`, `highlight*` | | ✍️ | Change what the client draws |

Java extras: `getLocalPlayer()`, `getPlayers()`, `getNpcs()`, `getInventory(int)`, `getInventories()`.

## `osrs.Events`

`subscribe(event, callback)` returns an id for `unsubscribe(id)`; subscribing the same callback to the same event
twice returns the same id. Events are synthesised by comparing polls about 20 ms apart, only while something is
subscribed; see `EventPoller` for exactly when each fires.

| Lua event | Java callback | Status | Note |
|---|---|---|---|
| `ON_GAME_TICK` | `GameTickCallback` | ✅ | |
| `ON_NPC_LOAD`, `ON_NPC_UNLOAD` | `NpcLoadUnloadCallback` | ✅ | |
| `ON_STAT_TRANSMIT` | `StatTransmitCallback` (`List<StatUpdate>`) | 🟡 | Only stats whose values changed |
| `ON_VAR_TRANSMIT` | `VarTransmitCallback` (`int[]` varp ids) | 🟡 | Only varps whose values changed |
| `ON_INV_TRANSMIT` | `InvTransmitCallback` (`List<Inventory>`) | 🟡 | Only slots that changed |
| `ON_GAME_STATE_CHANGED` | `GameStateChangeCallback` (`GameStateTrigger`) | 🟡 | Derived from `Client.gameState` moves |
| `ON_VARBIT_UPDATE` | | ⏳ | Section 4 |
| `ON_LOC_LOAD`/`UNLOAD`, `ON_MAP_REBUILD_*` | | ⏳ | Section 6 |
| `ON_SCREEN_RESIZE` | | ⏳ | Section 5 |
| `ON_IF_*` | | ⏳ | Section 7 |
| Input (`POINTER_*`, `KEY_*`, `WHEEL_SCROLL`), menus (`ON_MINIMENU_*`), ops (`ON_*_OP`), chat, friends, clans | | ⏳ | Need the in-process backend: the reader can't see input or packets |

## Other namespaces

| Lua | Status | Note |
|---|---|---|
| `Camera` | ⏳ / ✍️ | Getters are section 5 (positions and angles mapped); setters change the camera |
| `Graphics` | ⏳ / ✍️ | `clientDimensions`, `worldToScreenCoord` are section 5; shapes draw into the client |
| `World` (`getCollisionMap(s)`), `CollisionMap`, `osrs.Collision` | ⏳ | Section 6 |
| `InterfaceQuery`, `osrs.IfType` | ⏳ | Section 7 (IfType members are mapped) |
| `osrs.Entity`, `osrs.Npc`, `osrs.Player` | ⏳ | Section 3 |
| `Engine` (config decoders), `osrs.NpcType`, `osrs.ObjType`, `osrs.LocType` | ⏳ | Section 4 |
| `osrs.HintArrow` | ⏳ / ✍️ | Getters not mapped yet; setters change the arrow |
| `Settings` (`get`, `setOnChangedHandler`) | ⏳ | The game settings store isn't mapped yet |
| `HTTP`, `Math`, `Persistence`, `Shape`, `Ui` | | Plugin-side helpers (HTTP, bit maths, storage, drawing, UI widgets) rather than game state; Java and the Kraken client have their own |
