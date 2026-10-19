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
| `osrs.Engine` (config decoders) | `KrakenApi.engine()` → `com.kraken.api.game.Engine` |
| `osrs.Entity`, `osrs.Npc`, `osrs.Player` | `com.kraken.api.game.Entity`, `Npc`, `Player` |
| `osrs.ObjType`, `osrs.NpcType`, `osrs.LocType`, `osrs.TypeData` | `com.kraken.api.config.ObjType`, `NpcType`, `LocType`, `TypeData` |

Status: ✅ mirrored · 🟡 mirrored with a difference (see note) · ⏳ planned for the reader · ✍️ changes the client, needs
the in-process backend.

## Sections

The work is split into sections you can test live with the dev tools, one at a time.

| # | Section | Status | Dev tools |
|---|---|---|---|
| 1 | NPCs and players: ids, names, tiles; `ON_NPC_LOAD`/`UNLOAD`, `ON_GAME_TICK` | Done | NPCs, Players, Location, Event log |
| 2 | ClientState (stats, varps, varcs, tick, login, profile type), inventories; `ON_STAT_TRANSMIT`, `ON_VAR_TRANSMIT`, `ON_INV_TRANSMIT`, `ON_GAME_STATE_CHANGED` | Done | Client state, Skills, Inventories, Var inspector, Event log |
| 3 | Actors in depth: fine coords, uids, creation cycles, find-by-name, route length, `osrs.Entity`/`Npc`/`Player` members | Done (live check pending) | NPCs/Players columns, Interacting |
| 4 | Config types: `ocName`/`ocCost`/`ocStackable`/`ocOp`, `invSize`, `getVarbit` + `ON_VARBIT_UPDATE`, `NpcType`/`ObjType`/`LocType` | Done (live check pending) | Item names in Inventories, varbits in Var inspector and Event log |
| 5 | Camera and projection: `Camera.get*`, `Graphics.clientDimensions`, `Graphics.worldToScreenCoord` | Next | Camera, labels over NPCs and players |
| 6 | Scene: locs and ground objs (`locFind`, `objFind`, …), collision maps | Planned | Game objects, Ground items, Tile flags |
| 7 | Interfaces: `InterfaceQuery`, `osrs.IfType` | Planned | Widget inspector |

Section 4's functions don't read live game state: they look a definition (an item, NPC type, varbit…) up by id. The
client keeps decoded definitions in one small cache per type (64 entries each, least recently used dropped first), so
most ids aren't decoded in memory at any moment. But it keeps the raw config files of every type it has loaded in its
config archive (cache index 2), and never drops them. So the Java API reads a definition's bytes from that archive and
decodes them the way the client's loader does, including what the loader does after decoding (noted items merged with
the item they note, members items renamed on free worlds, and so on). The bytes don't change while the client runs, so
`ConfigTypes` caches what it decodes.

Section 3 note: as in the client, the `ClientOp` functions that take an index (`npcName(id)`, `playerCoord(id)`…)
search every loaded world (the main one and sub-worlds such as boats), while the `Npc`/`Player` objects only resolve in
the top-level world.

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
| `getVarbit(id)` | `getVarbit(int)` | ✅ | Reads the varp copy the client's varbit reader uses (`varpsBits`); an id without a definition reads bit 0 of varp 0, as in the client. -1 past the varp array |
| `getTickCount()` | `getTickCount()` | ✅ | |
| `isLoggedIn()` | `isLoggedIn()` | ✅ | Login session set and game state 30, as the handler checks |
| `getProfileType()` | `getProfileType()` | ✅ | Decodes `Client.worldFlags` like the handler |
| `getIntegerVarc(id)` | `getIntegerVarc(int)` | ✅ | |
| `getStringVarc(id)` | `getStringVarc(int)` | ✅ | |
| `Stats`, `ProfileType` | `ClientState.Stats`, `ClientState.ProfileType` | ✅ | |

Java extras (not in Lua): `getGameState()`, `getWorldFlags()`, `areStatsTransmitted()`, `getStatsAll()`, `getVarps()`,
`getVarpCount()`, `getIntegerVarcs()`, `getStringVarcs()`, `getVarbitType(id)`, `getVarbitVarps()`, `configs()`.

## `osrs.ClientOp`

| Lua | Java | Status | Note |
|---|---|---|---|
| `getNpcIdAll()` | `getNpcIdAll()` | ✅ | |
| `getNpcObj(id)` | `getNpcObj(int)` | ✅ | A live `Npc` handle (see `osrs.Npc` below); null where Lua gives nil |
| `npcName(npcId)` | `npcName(int)` | ✅ | Own name, else the type's, following the multi-NPC transform (varbit/varp-picked child type) like the client |
| `npcTypeId(npcId)` | `npcTypeId(int)` | ✅ | |
| `npcCoord(npcId)` | `npcCoord(int)` | ✅ | |
| `npcCoordFine(npcId)` | `npcCoordFine(int)` → `FineCoord` | 🟡 | `{-1,-1,-1}` when missing. Height `y` is bilinear over the tile's corners (one level up under bridges) minus the ground decoration's offset; inside the few tiles with a shaped surface the client interpolates the shape instead, so `y` can differ slightly there |
| `npcUid(npcId)` | `npcUid(int)` | ✅ | Type id × 65536 + index |
| `npcCreationCycle(npcId)` | `npcCreationCycle(int)` | ✅ | |
| `getNpcIdByName(name)` | `getNpcIdByName(String)` | 🟡 | Exact, case-sensitive. The native handler looks its match up again by uid instead of index, so in Lua it returns -1 for almost every NPC; Java returns the match |
| `getNpcIdByNameAll(name)` | `getNpcIdByNameAll(String)` | ✅ | Every active NPC with that exact name; empty (not -1) when none |
| `ncName(npcTypeId)` | `ncName(int)` | ✅ | Bound by the client but missing from the Lua docs: a type's name by type id |
| `getPlayerIdAll()` | `getPlayerIdAll()` | ✅ | |
| `getPlayerObj(id)` | `getPlayerObj(int)` | ✅ | A live `Player` handle; null where Lua gives nil |
| `playerName(playerId)` | `playerName(int)` | ✅ | Reads the name pointer at `Player+0x718` |
| `playerCoord(playerId)` | `playerCoord(int)` | ✅ | |
| `playerFindSelf()` | `playerFindSelf()` | 🟡 | Lua returns `[found, id]`; Java returns the id or -1 |
| `playerCoordFine(playerId)` | `playerCoordFine(int)` | 🟡 | The docs call it `playerFineCoord`; the client registers `playerCoordFine`. Height as `npcCoordFine` |
| `getPlayerIdByName(name)` | `getPlayerIdByName(String)` | ✅ | Matches the normalised name against the input lower-cased, like the client |
| `playerRouteLength(playerId)` | `playerRouteLength(int)` | ✅ | Steps queued on the path (0 standing still or not found) |
| `coord()` | `coord()` | ✅ | The local player's tile, with the player's own plane field |
| `coordFine()` | `coordFine()` | 🟡 | Height as `npcCoordFine` |
| `invGetObjId(invId, slot)` | `invGetObjId(int, int)` | ✅ | |
| `invGetNum(invId, slot)` | `invGetNum(int, int)` | 🟡 | 0 when missing, like the native handler (the docs say -1) |
| `invTotal(invId, objId)` | `invTotal(int, int)` | ✅ | |
| `invSize(invId)` | `invSize(int)` | ✅ | From the inventory's definition (0 without one, as in the client), whether or not the client holds the inventory |
| `ocName(objId)` | `ocName(int)` | ✅ | "" without a definition; members items read "… (Members)" on free worlds, as in the client |
| `ocCost(objId)` | `ocCost(int)` | ✅ | 1 without a definition, as in the client |
| `ocStackable(objId)` | `ocStackable(int)` | ✅ | |
| `ocOp(objId, index)` | `ocOp(int, int)` | ✅ | `index` is 1-based and picks a **ground** op (3 is usually "Take"); conditional ops are resolved against the current vars |
| `locCoord`, `locFind`, `locName`, `locTypeId`, `locGetOp`, `locWidth`, `locLength` | | ⏳ | Section 6 (the definitions they need are in now) |
| `objCoord`, `objFind`, `objName`, `objTypeId`, `objCount`, `objGetOp` | | ⏳ | Section 6 |
| `friendsChatGetDisplayName`, `friendsChatGetChatCount` | | ⏳ | Not mapped yet |
| `areProjectilesRendered()` | | ⏳ | Not mapped yet |
| `WornSlots` | `Client.WornSlots` | ✅ | |
| `npcSay`, `playerSay`, `renderProjectiles`, `highlight*` | | ✍️ | Change what the client draws |

Java extras: `getLocalPlayer()`, `getPlayers()`, `getNpcs()`, `getInventory(int)`, `getInventories()`, `getObjType(int)`,
`vars()` (the var state conditional ops resolve against), `configs()`.

When config types can't be read at all (not attached yet, or a mapping without the archive layout), the `oc*` and
`invSize` functions return "", -1 or false, and `getVarbit` -1.

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
| `ON_VARBIT_UPDATE` | `VarbitUpdateCallback` (`varbitId`, `value`) | 🟡 | Like the client: when the server's value of a varp changes, each varbit on it whose value changed fires (before `ON_VAR_TRANSMIT`). Found by comparing polls; the first subscription indexes all ~20k varbits once |
| `ON_LOC_LOAD`/`UNLOAD`, `ON_MAP_REBUILD_*` | | ⏳ | Section 6 |
| `ON_SCREEN_RESIZE` | | ⏳ | Section 5 |
| `ON_IF_*` | | ⏳ | Section 7 |
| Input (`POINTER_*`, `KEY_*`, `WHEEL_SCROLL`), menus (`ON_MINIMENU_*`), ops (`ON_*_OP`), chat, friends, clans | | ⏳ | Need the in-process backend: the reader can't see input or packets |

## `osrs.Entity`, `osrs.Npc`, `osrs.Player`

`Npc` and `Player` are live handles on an index, as the Lua objects are: every call looks the index up in the
top-level world again, so it reads the game as it is now, and returns "missing" values (false, -1, "") once nothing is
loaded there.

| Lua | Java | Status | Note |
|---|---|---|---|
| `getName()` | `getName()` | ✅ | NPCs follow multi-NPC transforms, as `npcName` |
| `isInteracting()` | `isInteracting()` | ✅ | The target's index or -1. Java extra `getInteractingType()` says whether it's an NPC, player or world entity |
| `isDead()` | `isDead()` | ✅ | Exactly `isHealthbarRendered() && isHeadbarEmpty()`, as the client does |
| `isHealthbarRendered()` | `isHealthbarRendered()` | ✅ | A head bar of type 0 is in the actor's list |
| `isHeadbarEmpty()` | `isHeadbarEmpty()` | ✅ | A head bar's current update (by client cycle) shows 0 |
| `isRendered3D()`, `isRendered2D()` | same | 🟡 | Lua reports what that Lua object last set with `render3D`/`render2D`; Java reads the flags those write on the actor, so it also sees what other plugins hid |
| `render3D(b)`, `render2D(b)` | | ✍️ | Change what the client draws |
| `Npc.getType()` | `getType()` | ✅ | The NPC's category |
| `Npc.getTypeData()` | `getTypeData()` → `NpcType` | ✅ | Decoded by the NPC's (server-sent) type id. The native method crashes for a missing NPC; Java returns null |
| `Npc.isFollower()`, `isLocalFollower()` | same | ✅ | Local: one of the follower varps the client tracks holds this NPC's index |
| `Player.isOnlineFriend()` | `isOnlineFriend()` | ✅ | The client's cached answer, or a friend list lookup while it hasn't worked it out |
| `Player.isFriendChat()` | `isFriendChat()` | ✅ | Cached answer, or a friends chat lookup |
| `Player.isGroup()` | `isGroup()` | 🟡 | The client's cached answer only (it fills it in for players it draws); false before that |
| `Player.isIgnored()` | `isIgnored()` | ✅ | Ignore list lookup (current or previous name) |
| `Player.isLocalPlayer()` | `isLocalPlayer()` | ✅ | |

Java extras on both: `index()`, `exists()`, `getCoord()`; on `Npc`: `getTypeId()`, `getUid()`, `getCreationCycle()`.

## Config types: `osrs.ObjType`, `osrs.NpcType`, `osrs.LocType`

Immutable decoded definitions (see the note under Sections). Each implements `TypeData` (`getId`, `getName`,
`getEntityType`). An id without a definition gets the client's default type, never null.

| Lua | Status | Note |
|---|---|---|
| `ObjType` getters (`getDesc`, `getModelId`, `getCertId`, `getPlaceholderId`, `getCanonicalId`, `getParams`, `getWeight`, `getValue`, `isStackable`, `isTradeable`, `isEquipable`, `isTwoHanded`, `getSlot`) | ✅ | Noted, bought and placeholder items merged as the client's loader does |
| `ObjType.getOps()`, `getIOps()` | 🟡 | The client binds `getOps` to the **inventory** ops and `getIOps` to the **ground** ops, the other way round from the docs; Java follows the client. `getIOps(client.vars())` resolves conditional ops |
| `NpcType` getters (`getType`, `getSize`, `getParams`, `getStats`) | ✅ | `getStats()` is a record (attack, defence, strength, hitpoints, ranged, magic) |
| `NpcType.getOps()`, `LocType.getOps()` | ✅ | Base texts; `getOps(client.vars()::varp, client.vars()::varbit)` resolves conditional ops like Lua |
| `LocType` getters (`blocksWalk`, `getWidth`, `getLength`, `getWallWidth`, `getParams`, `getActive`) | ✅ | `getActive` derived after decoding as the client does |
| `LocType.blocksRanged()` | 🟡 | The client binds it as `blocksRange` and returns a boolean (the docs say a number) |

## Other namespaces

| Lua | Status | Note |
|---|---|---|
| `Camera` | ⏳ / ✍️ | Getters are section 5 (positions and angles mapped); setters change the camera |
| `Graphics` | ⏳ / ✍️ | `clientDimensions`, `worldToScreenCoord` are section 5; shapes draw into the client |
| `World` (`getCollisionMap(s)`), `CollisionMap`, `osrs.Collision` | ⏳ | Section 6 |
| `InterfaceQuery`, `osrs.IfType` | ⏳ | Section 7 (IfType members are mapped) |
| `osrs.Entity`, `osrs.Npc`, `osrs.Player` | ✅ / ✍️ | See above. `render3D`/`render2D` change the client |
| `Engine` (`decodeObjType`, `decodeNpcType`, `decodeLocType`) | ✅ | `decodeDBRow`, `decodeSingleDBColumn`, `decodeAllChildDBTables` ⏳: database tables aren't mapped yet |
| `osrs.ObjType`, `osrs.NpcType`, `osrs.LocType`, `osrs.TypeData` | ✅ | See above |
| `osrs.FineCoord` | ✅ | `FineCoord` record; `getMapCoordinate(level)` takes the plane, which a FineCoord doesn't carry |
| `osrs.HintArrow` | ⏳ / ✍️ | Getters not mapped yet; setters change the arrow |
| `Settings` (`get`, `setOnChangedHandler`) | ⏳ | The game settings store isn't mapped yet |
| `HTTP`, `Math`, `Persistence`, `Shape`, `Ui` | | Plugin-side helpers (HTTP, bit maths, storage, drawing, UI widgets) rather than game state; Java and the Kraken client have their own |
