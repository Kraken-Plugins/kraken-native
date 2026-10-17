# API ↔ updater gap analysis

This document checks every member of the planned API ([api-design.md](api-design.md)) against the mapping the
updater produces today (`mappings/166231c8f6bd05c7.json`, seed build, 219 keys). For each member it lists what still
needs mapping and where to start. Catalog references are to [client-surface.md](client-surface.md).

## 1. How to read this

| Status | Meaning |
|---|---|
| `MAPPED` | A key exists in the mapping (ANCHORED, PROVEN or INFERRED). |
| `PROBE` | Mapped, but its meaning is unconfirmed. Needs the live probe (updater M2). |
| `RECIPE` | Offset or anchor already known (catalog, or found in this review). Only a spec entry is missing. Cheap. |
| `RE` | Anchor located; the layout or field still needs reverse engineering. |
| `UNKNOWN` | No anchor yet. |
| `CACHE` | Answered by decoding the cache. No memory key needed. |
| `DERIVED` | Computed from other keys. |
| `IN-PROC` | Only possible in the in-process runtime (P2). |

What each area can do with the current mapping:

| Area | Usable now? | Main blocker |
|---|---|---|
| Client scalars, skills, varps, varbits | yes | probe: which varp array; skill order |
| Players and NPCs (list, position, name, type, animation) | yes | probe: fine-coord centring |
| Interacting, health | partly | probe: interacting encoding; headbar fields; headbar type id for the scale |
| Locs (id, position) | yes | rotation and shape not mapped |
| Ground items, inventories | yes | none |
| Widgets | **no** | no traversal from a group or id to an IfType; 26 getter-style properties unmapped |
| Projectiles, graphics objects | no | recipes only; offsets are known |
| Collision | no | flag array layout |
| External projection (`Perspective`) | no | tile heights, focal scale, viewport offsets |
| Events by diffing (A1 set) | yes | none |
| Orientation, spot anims, hitsplats, overhead text, appearance, chat, energy | no | RE |

## 2. Findings from this review

These came out of checking the API against the seed binary (`program.db`). Items 1–3 are errors or misleading
results in the current mapping.

1. **`Actor+0x3EC` is the path length, not an orientation source.** `ClientOp.playerRouteLength` (`0x1ECE80`) looks
   up the player and returns `[player+0x3EC]` (`0x1ECF17 MOV EDX,[RBP+0x3EC]`). Other code uses it to index the
   last waypoint: `0x701E0 MOVSXD RCX,[RDX+0x3EC]` then `0x701F5 MOV EAX,[RDX+RCX*4+0x3EC]`, which is
   `pathX[len-1]`. So:
   - `Actor.orientationSourceCandidate` should become **`Actor.pathLength`**, a class A anchor through
     `playerRouteLength`.
   - `Actor.orientationCandidate` (`+0x494`) is the path length copied when an animation starts. The Java client
     keeps the same value to cancel animations when the actor moves.
   - **Orientation is still unmapped.**
2. **`widgets.layout` reports satisfied, but the API cannot reach a single component.** The `IfGroupNode.value`
   layout is not decoded and there is no key for the component store. The capability should be split (§6).
3. **`projection` depends on `functions.worldToScreen`**, which can only be called in-process. The external runtime
   needs different keys (§10). Split it into `projection.call` and `projection.external`.
4. **Varc store found.** `ClientState.getIntegerVarc` (`0xF59A0`) loads `[client+0x417CA8]` and tail-calls
   `0x33170`. That function binary-searches a sorted vector (`begin` at store `+0x48`, `end` at `+0x50`) of
   0x30-byte entries: key `i32` at `+0`, value at `+8`, type byte at `+0x28` (1 = int). It returns −1 when the id is
   missing. `getStringVarc` (`0xF59C0`) uses the same store through `0x33430` and returns an `eastl::string`.
5. **Widget lookup path found.** `InterfaceQuery.retrieveIfType` (`0x228240`) calls `0x84E30(client)` to get the
   interface store, then `0x5AF750(store, group << 16 | child)`, which returns a `shared_ptr<IfType>`. A miss is
   detected by comparing with the global sentinel `0x14155E5F0`. The packed id is the same as RuneLite's widget id.
6. **`NpcType+0x15A`** (byte) is the follower flag (`osrs.Npc.isFollower`, `0x239FC1`).
7. **`Client.topLevelWorld = +0xCA80` is corroborated.** The `osrs.Npc` and `osrs.Player` handlers (`isFollower`
   `0x239F60`, `isLocalPlayer` `0x23CC00`) resolve their index through `[client+0xCA80]` → `npcs`/`players` maps.
   The Lua handle stores the index at `+0x8`. This settles the catalog's `0xCA80` vs `0xCA90` conflict in favour of
   the mapped value.
8. **The `CollisionMap.getBitFlags` pairing looks wrong.** Its handler (`0x5C29A0` → `0x5C29C0`) takes two
   coordinate pairs and bounds-checks both against a record `{x0 +0, z0 +4, sizeX +8, sizeZ +0xC}`. That is the
   shape of a line-of-sight or line-of-walk routine, not a flag getter. The record header is useful; the flag array
   is still not located.
9. **The catalog's projectile and graphics-object offsets are live in the seed build.** `Client+0xCC70` (projectile
   list) is used at 6 or more sites, and `+0xCC88`/`+0xCC90` (graphics-object map) at 6 or more. No spec entries
   exist, so this is recipe work only.

## 3. Client

| API member | Native need | Status | Lead |
|---|---|---|---|
| `getGameState` | `Client.gameState` + enum values | `MAPPED` / `PROBE` | Only `LOGGED_IN = 30` is known. RuneLite's values (10 login, 20 logging in, 25 loading, 40 connection lost, 45 hopping) probably match; confirm by logging in and hopping. |
| `getTickCount`, `getGameCycle` | `Client.tickCount`, `global.cycle` | `MAPPED` | |
| `getLocalPlayer` | `Client.localPlayerIndex`, `World.players` | `MAPPED` | |
| `getPlayers`, `getNpcs` | `World.players`, `World.npcs`, index lists | `MAPPED` | |
| `getTopLevelWorldView`, `getWorldViews` | `Client.topLevelWorld`, `Client.worlds.map` | `MAPPED` | |
| `getWorld` (world number) | `Client.worldId` | `UNKNOWN` | world-hop code, `slr.ws` world-list parser, login handshake |
| `getWorldType` | `Client.worldFlags` (`+0x30A4`, catalog) | `RECIPE` | the beta check `0x63040` reads it; bit meanings need the world list |
| `getEnergy`, `getWeight` | `Client.runEnergy`, `Client.weight` | `RE` | raise sites of `ON_RUN_ENERGY_TRANSMIT` (30) and `ON_RUN_WEIGHT_TRANSMIT` (31) store the value next to the raise |
| `getAccountType` | profile type | `RE` | `ClientState.getProfileType` (enum table unresolved) |
| `getRevision` | `build.clientVersion` | `UNKNOWN` | `"Client version: %d.%d"` call site |
| `getCanvasWidth/Height` | `Renderer.canvasWidth/Height` | `MAPPED` | |
| `getViewportX/YOffset`, `getViewportWidth/Height` | renderer logical size (`+0x30`/`+0x34`, catalog) + offsets | `RECIPE` / `RE` | `Graphics.clientDimensions` |
| `isResized` | | `UNKNOWN` | low priority |
| `getMessages` (chat) | message ring buffer + counter | `RE` | `ON_CHAT_TRANSMIT` (15) raise site |
| `getFriendContainer`, `getIgnoreContainer`, clan, friends chat | | `RE` | `ClientOp.friendsChatGetDisplayName` / `GetChatCount`; `osrs.Player.isOnlineFriend` / `isIgnored` / `isGroup` / `isFriendChat` |
| world list | | `UNKNOWN` | `slr.ws` parser |
| `getMenuEntries`, `isMenuOpen` | minimenu entries | `UNKNOWN` (P2) | `ON_MINIMENU_REBUILD` (37) raise site, `OpOrder.json` |
| `getMouseCanvasPosition`, idle ticks | | `UNKNOWN` (P2) | `ClientMouseListener` / `ClientKeyboardListener` (RTTI binders) |
| hint arrow, local destination, selected widget or spell | | `UNKNOWN` (P2) | |

## 4. World views and coordinates

| API member | Native need | Status | Lead |
|---|---|---|---|
| `WorldView.getId` | key in `Client.worlds`; `Actor.worldId` | `MAPPED` / `PROBE` | The catalog says 0 means top level; RuneLite uses −1. Confirm. |
| `getPlane`, `getBaseX/Y`, `getSizeX/Y` | `World.plane`, `baseX`, `baseZ`, `width`, `height` | `MAPPED` | |
| `getScene` | `World.scene` | `MAPPED` | |
| `WorldPoint.fromLocal` | base + `pathX[0]`/`pathZ[0]` | `MAPPED` | |
| `LocalPoint` | `Actor.fineX/Z` | `PROBE` | corner or centre of the tile (RuneLite: `tile*128+64`) |
| `isInstance`, `getInstanceTemplateChunks`, `WorldPoint.fromLocalInstance` | instance flag + template chunk table | `UNKNOWN` | map-rebuild packet; `ON_MAP_REBUILD_PRE/POST` (35/36) raise sites |
| `getMapRegions` | | `DERIVED` | from base coordinates |
| boat world views (`WorldEntity`): position and rotation in the parent, owner, size | | `UNKNOWN` | `"FATAL - WorldEntity update is bigger than expected"` |

## 5. Actors, NPCs, players

### 5.1 Actor

| API member | Native need | Status | Lead |
|---|---|---|---|
| `getWorldLocation`, `getLocalLocation` | `pathX/Z[0]`, `fineX/Z` | `MAPPED` | centring: `PROBE` |
| `getWorldView` | `Actor.worldId` | `MAPPED` | |
| `getName` | `Actor.name` (players); NPCs use `NpcType.name` or the cache | `MAPPED` | |
| `getAnimation` | `Actor.anim` | `MAPPED` | |
| `getAnimationFrame` | frame field in the AnimState block (`+0x4C8`) | `PROBE` | `Actor.animLoop` (`+0x4E0`, 16-bit, reset on restart) may be the frame |
| `getPoseAnimation` (current movement animation) | current movement sequence | `RE` | `+0x4A8` AnimState candidate (catalog `otherAnimStates`); probe while walking and running |
| idle, walk, run, turn pose ids | `Actor.poseAnims` (15 slots) | `PROBE` | slot order |
| `getInteracting` | `Actor.interacting`, `interactingValid` | `MAPPED` / `PROBE` | encoding of NPC vs player targets |
| `getHealthRatio`, `getHealthScale` | `Actor.headbars` → `HeadbarEntry.a/b`; headbar type id → cache `HealthBarDefinition.width` | `PROBE` + `RE` | `isHeadbarEmpty`; the headbar node's type field is not mapped |
| `isDead` | `osrs.Entity.isDead` = `0x21A000` (headbar check) && `0x21A0E0` | `RE` (small) | reimplement the two checks |
| `isMoving`, path | `Actor.pathLength` (`+0x3EC`) | `RECIPE` | §2 item 1 |
| `getOrientation`, `getCurrentOrientation` | current and target orientation | `UNKNOWN` | The old candidate was wrong (§2). Look for writers near reads of `Actor.turnSpeed` (`+0x3E8`). |
| `getOverheadText`, overhead cycle | `+0x2D8` (`eastl::string`), `Actor.overheadTimer` (`+0x2F4`) | `RECIPE` / `MAPPED` | `ClientOp.npcSay` and `playerSay` handlers (class A); `+0x2D8` has 6 or more references |
| `getSpotAnims`, `getGraphic` | spot anim slots | `RE` | AnimState blocks at `+0x4F0`, `+0x510`, `+0x530`; callers of the SpotAnim loader (group 13) in the update decoders |
| hitsplats (`HitsplatApplied`) | hitsplat arrays | `RE` | NPC update decoder loop `0x9EC30` |
| `getLogicalHeight` | `Actor.defaultHeight` | `MAPPED` (INFERRED) | |
| `getWorldArea` | size: `NpcType.size` or 1 | `MAPPED` | |

### 5.2 NPC

| API member | Native need | Status | Lead |
|---|---|---|---|
| `getIndex` | key in `World.npcs` | `MAPPED` | |
| `getId` | `Npc.type` → `NpcType.id` | `MAPPED` | |
| `getComposition`, `getCombatLevel` | | `CACHE` | in memory: `osrs.NpcType.getStats` |
| `getTransformedComposition` | `NpcType.multi` (`+0x140`) + varps, unless `Npc.type` is already transformed | `PROBE` | check a multi-NPC (for example a farming patch NPC) |
| `getOverheadIcon` | per-NPC head icon override | `RE` | head-icon mask in the NPC update decoder `0x98DC0`; defaults from the cache |
| spawn cycle, uid | `Npc.spawnCycle`, `Npc.uidLow` | `MAPPED` | |
| follower flag | `NpcType+0x15A` | `RECIPE` | §2 item 6 |
| recolour and retexture overrides | `+0x750..+0x770` | `RECIPE` (P2) | catalog §3.5 |

### 5.3 Player

| API member | Native need | Status | Lead |
|---|---|---|---|
| `getId` | key in `World.players` | `MAPPED` | |
| `getName` | `Actor.name` | `MAPPED` | |
| `getCombatLevel` | `Player.combatLevel` | `RE` | `+0x780` on build 64d4; player appearance decoder |
| `getPlayerComposition` (equipment, kit, gender, colours, NPC transform) | appearance block | `RE` | needs a fingerprint for the player update or appearance decoder (no player decoder is mapped; only the NPC one is) |
| `getOverheadIcon`, `getSkullIcon`, `getTeam` | | `RE` | appearance decoder |
| `isFriend`, `isClanMember`, `isFriendsChatMember`, ignored | | `RE` | `osrs.Player.isOnlineFriend` / `isGroup` / `isFriendChat` / `isIgnored` (class A) |
| local player check | `Client.localPlayerIndex` | `MAPPED` | |

## 6. Widgets

Widgets are kraken-api's heaviest dependency (≈430 call sites), and the largest gap. Today the mapping has the IfType
**data members** (layout, model, link object, graphic). It does not have the way to *reach* an IfType, or the
**getter-style properties** registered through per-property functions, which the binding parser leaves `unpaired`
(26 of 81 `osrs.IfType` names). One parser improvement for that registration shape could resolve all 26 at once, so
it is the most valuable single piece of updater work.

IfType is large: `linkObjType` sits at `+0xB38`, so any block read must be at least 0xB40 bytes. Its real size should
come from its allocation site.

| API member | Native need | Status | Lead |
|---|---|---|---|
| `Client.getWidget(group, child)` / `getWidget(packedId)` | interface store + find by packed id; the store's map layout | `RE` | §2 item 5: fingerprints `IfTypeStore` (`0x84E30`) and `IfTypeFind` (`0x5AF750`) |
| all components of a group; `getWidgetRoots` | `IfGroupNode.value` layout (32 bytes); root interface id | `RE` | `retrieveIfGroup` copies the record as two 16-byte blocks; root id via `ON_SUB_CHANGE` (34) or the top-level setter |
| `WidgetLoaded`, `WidgetClosed` | `Client.interfaces` keys | `MAPPED` | |
| `getId` | `IfType.id` | `RE` | getter-style (unpaired) |
| `getType`, `getContentType` | `IfType.type` | `RE` | getter-style; content type has no Lua name |
| `getIndex` | `IfType.componentIndex`; `IfType.subId` (`+0x8`) | `RE` / `PROBE` | is `subId` the dynamic child index? |
| `getParentId` | `IfType.layerId` (`+0x74`) | `MAPPED` | |
| `getParent` | `IfType.parent` | `RE` | getter-style; or resolve `layerId` through the store |
| `getStaticChildren` | components of the group whose `layerId == id` | `DERIVED` | needs the group listing |
| `getDynamicChildren`, `getChildren` | `IfType.children` | `RE` | getter-style; `"Error accessing children: [%s]"` |
| `getNestedChildren` | sub-interface table (component → mounted group) | `RE` | `ON_SUB_CHANGE` raise site |
| `getText`, `getName`, `getActions`, `opBase` | `text`, `text2`, `opNames`, `baseOpName` | `RE` | getter-style |
| `getTextColor` | `colour`, `colour2`, `colourOver`, `shadowColour` | `RE` | getter-style |
| `isIf3` | `isIf3` | `RE` | getter-style |
| `getScrollX/Y`, `getScrollWidth/Height` | `scrollPos`, `scrollSize` | `RE` | getter-style |
| `getBounds`, `getCanvasLocation` | `screenCoords` → `InterfaceCoordinates` (its fields are mapped) | `RE` | getter unresolved. Fallback: walk parents summing x/y minus scroll, plus the root offset. |
| `getItemId`, `getItemQuantity` | `linkObjType`, `linkObjCount` | `MAPPED` | |
| `getSpriteId` | `graphic` | `MAPPED` | |
| `getModelId`, model type, zoom, angles | `model1Id` … `modelZoom` | `MAPPED` | |
| `getOpacity` | `transparency` | `MAPPED` | |
| `isSelfHidden` / `isHidden` | `hidden` / ancestors | `MAPPED` / `DERIVED` | |
| `getRelativeX/Y`, `getWidth/Height`, original values, size and position modes | `x`, `y`, `width`, `height`, `data*`, `mode*` | `MAPPED` | |

Capability split: `widgets.read` = store + find + group listing + id, type, parent, children, text, opNames, hidden,
linkObj*. `widgets.bounds` = `widgets.read` + screenCoords or scroll + root offset.

## 7. Scene and tile objects

| API member | Native need | Status | Lead |
|---|---|---|---|
| `Scene.getTile`, bridge tiles | `Scene.tiles`, `sizeX/Z`, entry stride and pointer, `bridgeTiles` | `MAPPED` | |
| `Tile.getWallObject`, `getDecorativeObject`, `getGroundObject` | `Tile.wall`, `wallDecor`, `groundDecor` + `Loc.tag` | `MAPPED` | |
| `Tile.getGameObjects` | `Tile.gameObjects`, count, stride + `GameObject.tag`, `originX/Z` | `MAPPED` | |
| `TileObject.getId` | `tag >> Loc.tagIdShift` | `MAPPED` | |
| `getWorldLocation`, plane | tile index + base; game-object origin | `MAPPED` / `DERIVED` | |
| `GameObject.sizeX/sizeY` | cache size + rotation | `CACHE` + `RE` | |
| `getOrientation`, shape (0–22), config | loc rotation and shape | `RE` | the low 20 bits of the tag (decode at callers of `0x652270`); Loc constructors |
| `WallObject.getOrientationA/B` | | `RE` | |
| tile flags, render level | | `UNKNOWN` (P1) | |
| `ObjectComposition` (name, actions, impostors) | | `CACHE` | impostors use the same varbit/varp evaluation as multi-NPCs |
| clickbox, convex hull | model geometry | `IN-PROC` / P2 | |

## 8. Ground items and item containers

| API member | Native need | Status | Lead |
|---|---|---|---|
| `TileItem.getId/getQuantity` | `GroundItem.id/count` via `World.objPiles` | `MAPPED` | |
| ownership, despawn and visible times | | `UNKNOWN` (low) | |
| `ItemContainer` (any inventory id) | `global.inventories.*`, `Inventory.*` | `MAPPED` | add an `inventories` capability; the keys exist but no capability lists them |
| equipment slot order | inventory 94 index = slot | `MAPPED` | `ClientOp.WornSlots` enum table is unresolved; parse it to confirm |
| `ItemComposition` | | `CACHE` | |

## 9. Projectiles and graphics objects

All offsets come from catalog §3.8. Anchors: `ClientOp.renderProjectiles` (`0x3AE120`, `ADD R8,0xCC70`), the
projectile ctor `0xA24A0`, packet decode inside `0x95F10`, the map rebase `0x69610` (shifts both collections), and
the SpotAnim loader caller `0x320E0` for graphics objects.

| API member | Native need | Status | Note |
|---|---|---|---|
| `getProjectiles` | `Client.projectiles` (`+0xCC70`, `eastl::list`) | `RECIPE` | |
| `isHidden` | `Projectile.hidden` `+0x1C` | `RECIPE` | |
| source and target points, heights | `src` `+0x260`, `dst` `+0x274` (`{plane, x, z, height?}`) | `RECIPE` | height is inferred: `PROBE` |
| source and target actors | `srcEntity` `+0x270`, `dstEntity` `+0x284` (24-bit signed) | `RECIPE` | encoding: `PROBE` |
| `getStartCycle`, `getEndCycle`, `getRemainingCycles` | `+0x288`, `+0x28C` | `RECIPE` | remaining = end − `global.cycle` |
| `getSlope`, start distance | `+0x290`, `+0x294` | `RECIPE` | `PROBE` |
| `getId` | `spotAnimId` `+0x298` | `RECIPE` | |
| `getX/Y/Z` | position doubles `+0x2A0` | `RECIPE` | |
| orientation, pitch, animation frame | | `RE` | |
| `Projectile` size | 0x2F0 (`make_shared` 0x310) | `RECIPE` | |
| `getGraphicsObjects` | `Client.graphicsObjects` hash map `+0xCC88` (buckets `+0xCC90`, count `+0xCC98`) | `RECIPE` | |
| `GraphicsObject.getId` | key `+0x260`, spotAnimId `+0x264` | `RECIPE` | |
| `getLocation`, `getLevel` | packed coords `+0x268`; fine x/z floats `+0x2E0`/`+0x2E8` (or `+0x290`) | `RECIPE` / `PROBE` | |
| `getStartCycle`, `getHeight`, `finished` | | `RE` | |
| `GraphicsObject` size | 0x348 | `RECIPE` | |

## 10. Vars, skills, camera, perspective, collision

| API member | Native need | Status | Lead |
|---|---|---|---|
| `getVarpValue` | `global.varps` | `MAPPED` / `PROBE` | which of `varps` and `varpsBits` is the server copy |
| `getVarbitValue` | cache `VarbitDefinition` + `global.varbitMaskTable` | `MAPPED` + `CACHE` | |
| `getVarcIntValue`, `getVarcStrValue` | `Client.varcs` (`+0x417CA8`) + store layout | `RECIPE` | §2 item 4 |
| skills | `Client.skillLevel`, `skillBase`, `skillXp` | `MAPPED` | `Skill` enum order: `PROBE` |
| `getCameraX/Y/Z` | `Client.camera.pos` | `MAPPED` | axis order: `PROBE` |
| `getCameraPitch`, `getCameraYaw` | `Client.camera.angles` | `PROBE` | rotate and tilt during the probe |
| `getScale` (zoom) | `Client.zoomA`, `zoomB` | `PROBE` | |
| camera focal point | `Client.camera.lookAt` | `MAPPED` | |
| `getTileHeights` | per-plane height grid | `UNKNOWN` | callers of the projection that sample terrain; the `TerrainPatch.cpp` source path; the Java client's height interpolation pattern (four loads blended with `& 0x7F` and `>> 7`) |
| `Perspective` (external) | focal scale, viewport offsets, trig floats | `RE` | projection core `0x6AA7C0`: `L = (*(C+0x90)+0x1B0)->vtbl[+8]()`, trig floats `P.f32[0x418..0x424]` with `P = *(C+0xCA80)+0x10` (catalog §3.11) |
| `Perspective` (in-process) | `functions.worldToScreen` | `MAPPED` | |
| `getCollisionMaps` | `Scene.collisionMaps` (`+0x2F8`), stride 0x28; record header `{x0, z0, sizeX, sizeZ}`; flag array | `MAPPED` / `RE` | §2 item 8; also check whether the flag bits match RuneLite's `CollisionDataFlag` |

## 11. Events

The external runtime diffs snapshots (api-design §10). Every A1 event uses keys that are already mapped. The rest are
blocked by the gaps in the sections above, as listed in this table:

| Event | Blocked by |
|---|---|
| `WidgetLoaded/Closed` | nothing (`Client.interfaces`); the payload is useful only once widgets can be read |
| `ProjectileMoved`, `GraphicsObjectCreated` | §9 recipes |
| `OverheadTextChanged` | overhead text recipe |
| `HitsplatApplied` | hitsplats |
| `GraphicChanged` | spot anims |
| `ChatMessage` | chat buffer |
| `MenuOptionClicked`, `MenuOpened`, `ScriptCallbackEvent`, `ScriptPre/PostFired` | `IN-PROC` |
| native hooks for any event | `IN-PROC`: raise-site function and calling convention for each of the 64 `enums.events` values |

## 12. Definitions and actions

| Need | Status | Lead |
|---|---|---|
| Cache location and format of the native client | `UNKNOWN` | catalog §3.5 lists `%LOCALAPPDATA%\Jagex\Old School Runescape\data\main_file_cache.*`; check whether it is dat2/idx (RuneLite loaders) or something else |
| `doAction` equivalent (menu invocation) | `IN-PROC` | `ON_MINIMENU_CLICK` (38) dispatcher |
| `runScript` | `IN-PROC` | `ON_CLIENTSCRIPT` (39); the `rs2lib/script` engine |
| camera setters | `IN-PROC` | `Camera.setPos`, `setLookAt`, `setLookAtAngles` (bindings already resolved) |
| packet writer, input queues | `IN-PROC` | input listener binders (RTTI) |

## 13. Updater backlog, in API order

**Before A1: probe and quick fixes**

- a. Run the live probe on a logged-in client on the seed build. It settles every `PROBE` row: varp arrays, skill
  order, pitch and yaw, zoom, fine-coord centring, interacting encoding, headbar fields, pose slot order, game state
  values, top-level world id.
- b. Rename `Actor.orientationSourceCandidate` to `Actor.pathLength` and anchor it on `ClientOp.playerRouteLength`.
  Rename or drop `Actor.orientationCandidate` (§2 item 1).
- c. Add struct size keys (`Actor.size`, `Npc.size`, `Player.size`, `IfType.size`, `Projectile.size`,
  `GraphicsObject.size`, `Tile.size`) from `operator new` and `make_shared` sites (class E). The API uses them for
  block reads.
- d. Map the headbar node's type id, so the health scale can come from the cache.
- e. Capabilities: add `inventories`; split `widgets.layout` into `widgets.read` and `widgets.bounds`; split
  `projection` into `projection.call` and `projection.external`; rename `npcs.animation`/`npcs.combat` to
  `actors.*`.

**For A2**

1. Widget store and find by packed id: fingerprints `IfTypeStore` and `IfTypeFind`, plus the map layout inside the
   store.
2. Getter-style IfType properties: teach the binding parser the per-property registration shape (26 names).
3. Group listing (`IfGroupNode.value`), root interface, and screen bounds (`screenCoords` or the root offset).
4. Collision flag array: fix the `getBitFlags` pairing, then map the flag pointer and its stride.
5. Tile heights.
6. External projection keys: focal scale, viewport offsets, trig floats.
7. Projectile and graphics-object recipes (§9, offsets known).
8. Varc store recipe (§2 item 4).

**For A3**

9. Actor orientation (current and target).
10. Spot anim slots, current pose animation, animation frame.
11. Hitsplats.
12. Overhead text (`+0x2D8`, anchored through `npcSay`/`playerSay`).
13. Player combat level, appearance, skull and prayer icons, team (fingerprint the player update decoder first).
14. NPC head icon; whether `Npc.type` is already transformed.
15. Run energy and weight.
16. Game state enum, world number, world type flags, client version.
17. Chat message buffer.
18. Instances (template chunks).
19. World entities (boats): transform into the parent world.
20. Loc rotation and shape, wall orientation, the low bits of the tag.

**Later (P1 low, P2)**

21. Friends, ignores, clan, friends chat.
22. World list.
23. Menu entries, mouse position, idle ticks, hint arrow, local destination, selected widget or spell.
24. In-process: event raise sites, `doAction`, `runScript`, camera setters, packet writer.
