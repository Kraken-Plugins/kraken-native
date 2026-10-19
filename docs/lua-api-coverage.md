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
| `Camera` (getters) | `KrakenApi.camera()` → `com.kraken.api.game.Camera` |
| `Graphics` (getters), `osrs.screenSize` | `KrakenApi.graphics()` → `com.kraken.api.game.Graphics`; `KrakenApi.screenSize()` |
| `osrs.World` | `KrakenApi.world()` → `com.kraken.api.game.World` |
| `osrs.Collision`, `osrs.CollisionMap`, `osrs.CollisionFlag` | `KrakenApi.collision()` → `com.kraken.api.game.Collision`; `CollisionMap`; `CollisionFlag` |
| `osrs.Entity`, `osrs.Npc`, `osrs.Player` | `com.kraken.api.game.Entity`, `Npc`, `Player` |
| `osrs.ObjType`, `osrs.NpcType`, `osrs.LocType`, `osrs.TypeData` | `com.kraken.api.config.ObjType`, `NpcType`, `LocType`, `TypeData` |
| `osrs.Vector2`, `osrs.MapCoord`, `osrs.FineCoord` | `com.kraken.api.game.Vector2`, `MapCoord`, `FineCoord` |
| `InterfaceQuery` (the docs' `osrs.IfType` functions) | `KrakenApi.interfaces()` → `com.kraken.api.game.InterfaceQuery` |
| `osrs.IfType`, `IfGroup`, `osrs.IfType.InterfaceCoordinates`, `osrs.Colour` | `com.kraken.api.game.IfType`, `IfGroup`, `InterfaceCoordinates`, `Colour` |

Java only: `KrakenApi.scene()` → `com.kraken.api.game.Scene` lists the scene's locs and ground items, converts between
scene and world tiles, and gives ground heights.

Status: ✅ mirrored · 🟡 mirrored with a difference (see note) · ⏳ planned for the reader · ✍️ changes the client, needs
the in-process backend.

## Sections

The work is split into sections you can test live with the dev tools, one at a time.

| # | Section | Status | Dev tools |
|---|---|---|---|
| 1 | NPCs and players: ids, names, tiles; `ON_NPC_LOAD`/`UNLOAD`, `ON_GAME_TICK` | Done | NPCs, Players, Location, Event log |
| 2 | ClientState (stats, varps, varcs, tick, login, profile type), inventories; `ON_STAT_TRANSMIT`, `ON_VAR_TRANSMIT`, `ON_INV_TRANSMIT`, `ON_GAME_STATE_CHANGED` | Done | Client state, Skills, Inventories, Var inspector, Event log |
| 3 | Actors in depth: fine coords, uids, creation cycles, find-by-name, route length, `osrs.Entity`/`Npc`/`Player` members | Done | NPCs/Players columns, Interacting |
| 4 | Config types: `ocName`/`ocCost`/`ocStackable`/`ocOp`, `invSize`, `getVarbit` + `ON_VARBIT_UPDATE`, `NpcType`/`ObjType`/`LocType` | Done | Item names in Inventories, varbits in Var inspector and Event log |
| 5 | Camera and projection: `Camera.get*`, `Graphics.clientDimensions`, `Graphics.worldToScreenCoord`, `osrs.screenSize`; `ON_SCREEN_RESIZE` | Done (live check pending) | Camera; labels and tiles over NPCs and players; lines in Interacting |
| 6 | Scene: `locFind`/`objFind` and the active loc/obj getters, `osrs.World`/`Collision`/`CollisionMap`; `ON_LOC_LOAD`/`UNLOAD`, `ON_MAP_REBUILD_PRE`/`POST` | Done (live check pending) | Game objects, Walls, Ground items, Location, Zone borders, Map squares, Tile flags, Valid movement, Line of sight, Event log |
| 7 | Interfaces: `InterfaceQuery`, `osrs.IfType` | Done | Widget inspector |

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

Coordinates: a `MapCoord` (from `coord()`, `npcCoord()`…) is a **scene** tile, relative to the loaded map's base,
not a world tile. The client subtracts the base when it places an actor (the player update does
`x = worldX - World.baseX` before storing the tile), and its `MapCoord` functions add it back when they need a world
coordinate (`locFind`, `objFind`, `MapCoord.getMapTile`). A normal scene is 104×104 tiles. Fine coordinates and the
camera use the same space, times 128. `Scene.getBase()` gives the base (from the map loader, `MapLoader.baseX/Z`) and
`Scene.toWorld` the world tile the wiki and RuneLite use.

Section 5 note: `worldToScreenCoord` replays the client's handler: the point minus the camera's eye, rotated by the
sines and cosines of the yaw and pitch the scene keeps for the frame (`Scene.cameraTrig`), divided by depth and
multiplied by the projection scale (`Client.zoomA`, the viewport height × the zoom ÷ 334), offset to the viewport's
centre, then rescaled from window pixels to client pixels (`Renderer.canvasWidth / Renderer.screenWidth`) with the
client's rounding. Results are in the client's own (DPI-scaled) pixels, the units `clientDimensions` measures; the
window's physical size is `osrs.screenSize`. The camera and trig are written each frame and the reader samples them,
so a point projected while the camera turns can be a frame off.

Section 6 note: `locFind` and `objFind` make what they find the *active* loc or obj, which the no-argument
`loc*`/`obj*` functions read. The client keeps one active loc and one active obj for all its Lua plugins (and
`ON_LOC_LOAD`/`UNLOAD` set the active loc before calling subscribers); the Java `Client` keeps its own, so Java and Lua
plugins don't disturb each other. The active obj is a weak reference in the client: once the item leaves the ground,
`objTypeId`/`objCount` read -1 and `objName` "". Java checks that the entry is still in its tile's pile instead.

Section 7 note: the client keeps the components of every interface group it has loaded in its interface store
(`Client.interfaceStore`): per group, an array of `jag::shared_ptr<IfType>` slots, one per static component; per static
component, an array of slots for its dynamic children, indexed by sub id. A dynamic component carries its static root's
id, and in a field Lua doesn't expose (`IfType.layerSubId`) its direct parent's sub id, -1 when that parent is the
root. `children` searches the root's dynamic slots for components whose parent sub id is the caller's, and `parent`
looks that sub id up. The groups on screen are also in a hash map from group id to their `InterfaceCoordinates`.
Positions are client pixels, the units of `clientDimensions`. The getters that aren't data members are lambdas behind
`sol::property`; the updater follows each registration to its lambda (see `updater/README.md`). Checked against the
live client: inventory slots (items, names, ops), chatbox tabs (text, colours, ops, screen position) and the chat's
dynamic lines.

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
| `locFind(type, coord)` | `locFind(int, MapCoord)` | ✅ | Scene coord + map base → the world holding it (a boat's, else the top-level world) → that world's tile; checks wall decoration, wall, ground decoration, then the game object whose origin is the tile, in the client's order. A miss keeps the previous active loc |
| `locCoord()` | `locCoord()` | 🟡 | The active loc's tile; one level lower under a bridge, as the client reports it. null when no loc is active (Lua gets `{-1,-1,-1}`) |
| `locName()`, `locTypeId()` | same | ✅ | From the active loc's type; "" / -1 when none |
| `locGetOp(includeTags, opNumber)` | `locGetOp(int, int)` | ✅ | 1-based; conditional ops resolved against the current vars. `includeTags` 0 strips markup the client's way (`<lt>`→`<`, `<gt>`→`>`, `<at>`→`@`, `<br>`/`<n>`→newline, other tags dropped) |
| `locWidth()`, `locLength()` | same | 🟡 | The type's size, not turned for the loc's rotation; -1 when no loc is active (Lua gets nothing back) |
| `objFind(type, coord)` | `objFind(int, MapCoord)` | ✅ | First matching entry in the tile's pile, in pile order. Like the client, subtracts the map loader's base (not the found world's) |
| `objCoord()` | `objCoord()` | 🟡 | Level 0 when the tile's setting bit 3 is set, else one lower under a bridge, as the client does; null when no obj is active |
| `objName()`, `objTypeId()`, `objCount()` | same | ✅ | Read live from the pile entry; "" / -1 once it has left the ground |
| `objGetOp(includeTags, opNumber)` | `objGetOp(int, int)` | ✅ | A ground op, as `ocOp`; markup stripped as `locGetOp` |
| `friendsChatGetDisplayName`, `friendsChatGetChatCount` | | ⏳ | Not mapped yet |
| `areProjectilesRendered()` | | ⏳ | Not mapped yet |
| `WornSlots` | `Client.WornSlots` | ✅ | |
| `npcSay`, `playerSay`, `renderProjectiles`, `highlight*` | | ✍️ | Change what the client draws |

Java extras: `getLocalPlayer()`, `getPlayers()`, `getNpcs()`, `getInventory(int)`, `getInventories()`, `getObjType(int)`,
`vars()` (the var state conditional ops resolve against), `configs()`, `activateLoc(Loc)` (make a listed loc active).

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
| `ON_SCREEN_RESIZE` | `ScreenResizeCallback` | 🟡 | The client size (`clientDimensions`) or window size (`screenSize`) changed between polls. The client raises it from its resize handler |
| `ON_MAP_REBUILD_PRE`, `ON_MAP_REBUILD_POST` | `MapRebuildCallback` | 🟡 | The client raises PRE as a logged-in rebuild starts (just before game state 25) and POST when the load finishes (login too). Java: PRE on game state 30 → 25, POST on 25 → 30; a load that falls between two polls is caught by the map base moving, and fires both |
| `ON_LOC_LOAD`, `ON_LOC_UNLOAD` | `LocLoadUnloadCallback` (`locTypeId`, `name`, `layer`) | 🟡 | Not in the Lua docs; the payload is what the client passes. The client raises them when the server changes a loc (doors, felled trees), not for a map load's locs, with the loc made active first. Java compares the whole scene once per tick while subscribed (unloads, then loads, loc active during the callback) and takes a silent baseline after a map rebuild, so loc changes the server sends right after a rebuild aren't reported |
| `ON_IF_*` | | ⏳ | Per component (`IfType.subscribe`), raised from the client's input and script handlers, which the reader can't see; need the in-process backend |
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
| `Camera` | ✅ / ✍️ | Getters below; `setPos`, `setLookAt`, `setLookAtAngles`, `offsetLookAtAngles`, `setControl`, `targetNpc`/`targetPlayer`, `clearTarget`, `followTarget` change the camera |
| `Graphics` | ✅ / ✍️ | `clientDimensions`, `worldToScreenCoord` below; `addShape`, `removeShape`, `clearAllShapes` draw into the client |
| `osrs.World`, `osrs.CollisionMap`, `osrs.Collision`, `osrs.CollisionFlag` | ✅ | See below |
| `InterfaceQuery`, `osrs.IfType` | ✅ / ✍️ | See below; `addRenderOffset` and `setRenderOffset` move interfaces |
| `osrs.Entity`, `osrs.Npc`, `osrs.Player` | ✅ / ✍️ | See above. `render3D`/`render2D` change the client |
| `Engine` (`decodeObjType`, `decodeNpcType`, `decodeLocType`) | ✅ | `decodeDBRow`, `decodeSingleDBColumn`, `decodeAllChildDBTables` ⏳: database tables aren't mapped yet |
| `osrs.ObjType`, `osrs.NpcType`, `osrs.LocType`, `osrs.TypeData` | ✅ | See above |
| `osrs.FineCoord` | ✅ | `FineCoord` record; `getMapCoordinate(level)` takes the plane, which a FineCoord doesn't carry |
| `osrs.MapCoord` | 🟡 | `MapCoord` record (scene tiles). `getMapTile`/`toBitPacked` need the map base: use `Scene.toWorld`. For `getFineCoordinate(Corners)` use `Scene.getTileCenter`/`getTileCorners` (with the ground's height) |
| `osrs.Vector2` | ✅ | `Vector2` record |
| `osrs.screenSize()` | ✅ | `KrakenApi.screenSize()`: the window's physical pixel size |
| `osrs.HintArrow` | ⏳ / ✍️ | Getters not mapped yet; setters change the arrow |
| `Settings` (`get`, `setOnChangedHandler`) | ⏳ | The game settings store isn't mapped yet |
| `HTTP`, `Math`, `Persistence`, `Shape`, `Ui` | | Plugin-side helpers (HTTP, bit maths, storage, drawing, UI widgets) rather than game state; Java and the Kraken client have their own |

## `Camera` (getters)

| Lua | Java | Status | Note |
|---|---|---|---|
| `getPos()` | `getPos()` → `FineCoord` | ✅ | The eye, in scene fine coordinates (height in `y`) |
| `getLookAt()` | `getLookAt()` → `FineCoord` | ✅ | |
| `getLookAtAngles()` | `getLookAtAngles()` → `Vector2` | ✅ | `x` pitch, `y` yaw, in degrees, with the client's float constants (2π/16384, then a degrees factor one ulp below 180/π) |
| `isControlled()` | `isControlled()` | ✅ | Whether a Lua plugin took control (`setControl(true)`) |
| `hasTarget()` | `hasTarget()` | ✅ | Whether a Lua plugin set a target (`targetNpc`/`targetPlayer`) |

Java extras: `getPitch()`, `getYaw()` (native units, 16384 per circle; RuneLite's × 8), `getZoom()` (`Client.zoomB`),
`getScale()` (`Client.zoomA`, the projection scale).

## `Graphics` (getters)

| Lua | Java | Status | Note |
|---|---|---|---|
| `clientDimensions()` | `clientDimensions()` → `Vector2` | ✅ | The client's size in its own (DPI-scaled) pixels |
| `worldToScreenCoord(coord)` | `worldToScreenCoord(FineCoord)` → `Vector2` | 🟡 | The client's arithmetic step by step (see the section 5 note). null for a point at or behind the camera, where the client returns a meaningless position |

Java extra: `projection()` reads one frame's camera so many points can be projected with `Projection.project`.

## `osrs.World`, `osrs.CollisionMap`, `osrs.Collision`

| Lua | Java | Status | Note |
|---|---|---|---|
| `World.getCollisionMap(level)` | `World.getCollisionMap(int)` → `CollisionMap` | 🟡 | A copy of the level's flags, as the Lua object is. null for a level the scene doesn't have (the client doesn't check) |
| `World.getCollisionMaps()` | `World.getCollisionMaps()` | ✅ | One per level, from the scene's record vector |
| `CollisionMap.getBitFlags(x, z)` | same | 🟡 | The native method indexes the grid with the coordinates as given, without subtracting the grid's origin (-1) the way the walk and sight checks do, so in Lua it returns the flags of the tile one south-west. Java returns the asked tile's flags; `UNLOADED` outside the grid |
| `CollisionMap.lineOfSight(x1, z1, x2, z2)`, `lineOfWalk(…)` | same | ✅ | The client's line tests step for step, with its masks. Only the end tile is bounds-checked for walking; both for sight. Tiles past the grid read as `UNLOADED` (blocked) where the client would read past it |
| `Collision.getBitFlags(coord)` | `getBitFlags(MapCoord)` | 🟡 | Live from the top-level scene; same origin difference as `CollisionMap.getBitFlags` |
| `Collision.lineOfSight(a, b)`, `lineOfWalk(a, b)` | same | ✅ | false for different levels, as in the client; reads only the tiles the line crosses |
| `osrs.CollisionFlag` | `CollisionFlag` | ✅ | int constants (flags combine); `CollisionFlag.isSet(flags, mask)` |

Section 6 Java extras (`Scene`): `getBase()`, `toWorld`/`toScene`, `getSize()`, `getLocs(center, radius)` (walls, wall
decorations, game objects on their origin tile, ground decorations), `getAllLocs()`, `getObjs(center, radius)`,
`getGroundHeight`, `getTileCenter`, `getTileCorners`.

## `InterfaceQuery` and `osrs.IfType`

The client registers the table as `InterfaceQuery`; the Lua docs list its functions under `osrs.IfType`.

| Lua | Java | Status | Note |
|---|---|---|---|
| `retrieveIfType(groupId, componentId)` | `retrieveIfType(int, int)` → `IfType` | 🟡 | null where Lua gives nil. The client loads a group's components from the cache the first time a plugin asks for one; the reader can't, so a group the client hasn't loaded gives null |
| `retrieveIfGroup(groupId)` | `retrieveIfGroup(int)` → `IfGroup` | 🟡 | `components` is 0-based (Lua's array is 1-based), null where Lua has nil. `coordinates` all zero for a group that isn't open, as in the client. Unloaded groups as `retrieveIfType` |
| `addRenderOffset`, `setRenderOffset` | | ✍️ | Move an interface |
| `IfTypes` | `IfType.LAYER` … `IfType.INPUTFIELD`, `IfType.typeName` | ✅ | |
| `AlignMode`, `SizeMode`, `OverlayTypes`, `BlendMode`, `ModelTypes` | | ⏳ | The getters return the raw ints; the constants aren't copied yet |

Java extras: `getOpenGroups()` (the groups on screen), `getCoordinates(groupId)`.

`IfType` is a snapshot: one read of the component when it is retrieved, where the Lua object reads through to the live
component. Retrieve it again for fresh values. `getParent`, `getChildren`, `getStaticParent` and `getScreenCoords`
look the game up when called.

| Lua `osrs.IfType` | Java | Status | Note |
|---|---|---|---|
| Data members: `x`, `y`, `width`, `height`, `hidden`, `layerId`, `layerIdXMin`/`Max`, `mode*`, `data*`, `graphic`, `graphic2`, `transparency`, `event`, `eventCode`, `lastProcess`, `overlayType`, `overlayId`, `fill`, `blendMode`, `strokeWidth`, `diagonalStartAtBottom`, `startAngle`, `stopAngle`, `rotate`, `tiling`, `outline`, `vFlip`, `hFlip`, `model*`, `orthog`, `linkObjType`, `linkObjCount` | `getX()`, `isHidden()`, … | ✅ | `linkObjCount` is the docs' `linkObjNumber` |
| `isEntityOverlay` | `isEntityOverlay()` | 🟡 | The docs say a number; the client registers a bool |
| `id`, `componentIndex` | `getId()`, `getComponentIndex()` | ✅ | The halves of the packed id (Java extra `getPackedId()`); a dynamic component reports its static root's |
| `subId`, `isStatic`, `type` | same | ✅ | |
| `text`, `text2`, `sourceName`, `graphicURL`, `baseOpName` | same | ✅ | With the client's markup (`<col=…>`, `<br>`) |
| `opNames` | `getOpNames()` | ✅ | One entry per op slot; "" where a slot has none |
| `colour`, `colour2`, `colourOver`, `shadowColour` | → `Colour` | ✅ | |
| `aspectRatio`, `scrollPos`, `scrollSize` | → `Vector2` | ✅ | |
| `isRendering` | `isRendering()` | 🟡 | As of the snapshot: the component's draw stamp against the client's frame counter, read together |
| `parent` | `getParent()` | ✅ | The docs say nil for a static component; the client returns its static parent (only a top-level component has none), and so does Java |
| `staticParent` | `getStaticParent()` | ✅ | |
| `children` | `getChildren()` | ✅ | Dynamic children only, as in the client. A static layer's static children are the components of its group whose `layerId` is its id |
| `screenCoords` | `getScreenCoords()` | ✅ | x and y summed up the parent chain, plus the group's offset and script offset; like the client, ignores the scroll position of scrolling parents |
| `isIf3` | | ⏳ | Not mapped: registered from a register, not an immediate |
| `subscribe`, `unsubscribe` | | ⏳ | See `ON_IF_*` under `osrs.Events` |
