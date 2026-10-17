# Native client surface: what an updater must map

This catalog lists every part of the OSRS native client (`osclient.exe`) that a RuneLite-style API needs
to know about. For each part it gives the mapping keys the updater must produce, the anchors used to find
them, and the values known today. The [updater design](updater-design.md) describes how these keys are
derived automatically for each new client build.

Status of the values in this document:

- **Seed build:** `osclient.exe` sha256 `166231c8f6bd05c7…6944`, PE timestamp 2026-09-30, build tag
  `OSRS-ORSM295-BWXF` (from embedded source paths).
- **Evidence type:** static analysis only (Ghidra 12.1.4 via REA plus a full disassembly dump). Nothing here
  has been checked against a running client yet. Raw notes live in `C:\Users\<user>\osrs-re\findings_*.md`.
- **Older builds** `64d448bb…` and `2ce03524…` were partially mapped in `CLionProjects/kraken-api-native`.
  Their values appear in the drift columns.

Notation: `C` = `Client*`, `W` = `World*`, `A` = `Actor*` (shared base of NPC and Player). Offsets are hex
byte offsets. RVAs are relative to the image base (`0x140000000` in Ghidra).

---

## 1. What the API needs (derived from kraken-api)

kraken-api (RuneLite 1.13.1) uses the following, roughly in order of weight:

| Weight | Area | Native requirement |
|---|---|---|
| Very high | Widgets/interfaces (≈430 call sites; spellbook, bank, GE, dialogue, tabs) | IfType tree, component fields, screen bounds |
| Very high | Coordinates (`WorldPoint`/`LocalPoint`, ≈720 lines) | World base/origin, plane, actor tile + fine coords |
| High | Scene tiles: game, wall, decor and ground objects | Scene grid, tile slots, loc tags → ids |
| High | Varbits (76 distinct), varps, varcs | Varp arrays, varbit definitions, varc storage |
| High | Definitions (object/item/NPC compositions) | Type ids from memory; definitions from cache or memory |
| Medium | Collision maps (pathfinding, LoS) | Per-plane collision flags |
| Medium | Perspective, clickboxes, convex hulls | Camera, projection, tile heights; model geometry (later) |
| Medium | Local player: anim, pose, run/spec energy, HP, poison | Actor fields, varps, stats |
| Medium | NPCs: anim, interacting, health ratio/scale, head icon, actions | Actor + NPC fields, headbars, NpcType |
| Medium | Item containers: INV, BANK, WORN | Inventory storage |
| Low | Skills (real/boosted/xp), prayers, camera yaw/pitch/zoom | Stat arrays, varbits, camera fields |
| Low | Projectiles, graphics objects | Projectile list, graphics-object map |
| Low | Game state, tick, cycle, idle ticks, world list | Client scalars |
| Actions | doAction menu invocation, packets, synthetic input, `runScript`, camera setters | Function targets (in-process only; phase 3) |
| Events | GameTick, GameStateChanged, ChatMessage, ScriptCallback | Tick counter + event hook sites |

Not used by kraken-api today: menu entry reads, hitsplats, actor spot anims, friends/clan, WorldEntity boats.
These are listed below at lower priority.

**Priorities:** P0 = the read-only MVP that reaches parity with kraken-api's read paths; P1 = full
RuneLite-style read API; P2 = in-process features (events, actions).

---

## 2. Client-wide facts the updater must treat as contracts

The updater maps these once per build like any other key. Every traversal depends on them.

| Contract | Layout (seed build) | Anchor | Risk |
|---|---|---|---|
| Compiler / ABI | MSVC x64, Microsoft x64 calling convention, `.pdata` covers non-leaf functions | PE headers | low |
| RTTI | Stripped, except `std::function` binders. Do not rely on vtables for type identity | — | — |
| `eastl::string` (24 B) | heap form `{char* p, u64 size, u64 cap}` when the top bit of byte 23 is set; otherwise SSO with `len = 23 - byte[23]` | `admin` command handler, name getters | low |
| `jag::shared_ptr<T>` (16 B) | `{ctrl*, T*}`; uses count at ctrl+0x8, weak count at ctrl+0xC | NPC lookup, list nodes | low |
| `eastl::hash_map<int, shared_ptr<T>>` | map: buckets `+0x8`, bucket count `+0x10`; node `{u32 key @0, ctrl @8, T* @0x10, next @0x18}`; key bucket = `key % bucketCount` | NPC/player/graphics tables | medium |
| `eastl::list<shared_ptr<T>>` (24 B) | `{next @0, prev @8, size @0x10}`; node value ctrl @0x10, `T*` @0x18 | projectiles, obj piles, headbars | low |
| `eastl::vector<T>` | `{begin, end, capacity}` | timed effects, tile objects | low |
| Coord packing | `plane<<28 \| x<<14 \| z` (14-bit x/z) | `FUN_140231600`, projectile decode | low |
| Loc tag | 64-bit; type id = `tag >> 20` | `FUN_140652270` (`>> 0x14`) | medium |
| Fine units | 128 per tile (`<<7` in rebase code) | `FUN_1400a2ad0` | low |
| Angles | 16384 units per circle (`raw*360/16384`) | Camera `getLookAtAngles` | low |

---

## 3. Catalog

Columns: **Key** (stable name used in the mappings file), **Seed value**, **Drift** (older builds, if
known), **Anchors** (how the updater finds it), **Pri**, **State**. States: `known` = observed in code;
`partial` = observed, meaning not fully confirmed; `unknown` = not mapped yet.

### 3.1 Build identity and globals

| Key | Seed value | Drift | Anchors | Pri | State |
|---|---|---|---|---|---|
| `build.sha256` / `timestamp` / `sizeOfImage` | `166231c8…` / 2026-09-30 / `0x2303000` | — | PE | P0 | known |
| `build.tag` | `OSRS-ORSM295-BWXF` | — | source-path strings | P0 | known |
| `build.clientVersion` | unknown | — | `"Client version: %d.%d"` call site | P1 | unknown |
| `global.client` (Client* slot) | RVA `0xE98C18` | `0xE95668` (64d4), `0xE754B8` (2ce0) | first RIP-relative qword load in every ClientOp handler | P0 | known |
| `global.cycle` | RVA `0xE98C14` | — | added to packet cycle fields (`ADD ECX,[cycle]`) | P0 | known |
| `global.varps` | RVA `0x155E528` | — | ClientState `getVarp` getter | P0 | known |
| `global.varpsBits` (array varbits read) | RVA `0x155E540` | — | `getVarbit` → `FUN_1405ae330` | P0 | partial (two arrays; which is server-side?) |
| `global.varbitMaskTable` | RVA `0xB3D6C0` | — | `getVarbit` | P0 | known |
| `global.configCache` | `DAT_140ec3110` | — | first arg of the `FUN_140625a50` loader calls | P1 | known |

### 3.2 Client scalars

| Key | Seed | Drift | Anchors | Pri | State |
|---|---|---|---|---|---|
| `Client.gameState` | `+0x2160` (30 = in game) | — | ClientState `isLoggedIn` | P0 | partial |
| `Client.loginToken?` | `+0x3318` (≠ −1 when logged in) | — | `isLoggedIn` | P2 | partial |
| `Client.tickCount` | `+0x3198` | — | ClientState `getTickCount` | P0 | known |
| `Client.skillLevel[26]` / `skillBase[26]` / `skillXp[26]` | `+0x3350` / `+0x33B4` / `+0x3418` | — | `getStatEffectiveLevel` / `getStatBaseLevel` / `getStatXP` | P0 | known |
| `Client.statsValidGate` | `+0x413F24` | — | same getters (`<= 0` → −1) | P1 | partial |
| `Client.worlds` (WorldManager) | `+0xC9B8`; hash_map at `+0xC9D0` (buckets `+0xC9D8`, count `+0xC9E0`) | `+0xC9C8` (64d4) | `npcCoord` passes it to the NPC lookup; `getNpcIdAll` iterates it | P0 | known |
| `Client.topLevelWorld` | `+0xCA80` or `+0xCA90` (conflicting) | `+0xCA90` (64d4) | entity→actor resolver `FUN_140219b90`; projectile decode | P0 | partial |
| `Client.localPlayerIndex` | `+0xCC4C` (−1 = none) | `+0xCC5C` (64d4) | `playerFindSelf` | P0 | known |
| `Client.worldViewId` | unknown (`+0xCC60` in 64d4) | `+0xCC60` | `playerFindSelf` | P1 | unknown |
| `Client.playerIndexCount` / `playerIndices[]` | `+0xCCD0` / `+0xCCD4` (int[]) | — | `getPlayerIdAll` | P0 | known |
| `Client.projectiles` | `+0xCC70` (eastl::list) | — | `renderProjectiles` lambda (`ADD R8,imm32`) | P1 | known |
| `Client.graphicsObjects` | `+0xCC88` (hash_map; buckets `+0xCC90`, count `+0xCC98`) | — | map-rebase function after the projectile loop | P1 | known |
| `Client.zoneOrigin` | `+0x89540`/`+0x89541` (bytes) | — | projectile and graphics packet decoders | P2 | known |
| `Client.camera.pos[3]` | `+0x895C8` | — | Camera `getPos` | P0 | known |
| `Client.camera.angles[2]` | `+0x895DC` (which one is pitch is unknown) | — | Camera `getLookAtAngles` | P0 | partial |
| `Client.camera.lookAt[3]` | `+0x89640` | — | Camera `getLookAt` | P1 | known |
| `Client.renderer` (viewport record) | `+0x90` → canvas W/H `+0x6C/+0x70`, logical `+0x30/+0x34` | — | Graphics `clientDimensions` | P0 | partial |
| `Client.prefs` | `+0x23B8` (`PluginSafeMode` @ prefs `+0x1518`) | — | Lua engine gate | P2 | known |
| `Client.worldFlags` | `+0x30A4` (`0x40000` = beta) | — | `FUN_140063040` | P2 | partial |
| `Client.luaEngine` | `+0x418690` | — | `admin` command | P2 | known |
| `Client.runEnergy` / `weight` / `specEnergy` | unknown | — | `ON_RUN_ENERGY_TRANSMIT` / `ON_RUN_WEIGHT_TRANSMIT` raise sites; varps | P0 | unknown |
| `Client.idleTicks` (mouse/keyboard), `mouseCanvasPos` | unknown | — | input listeners (`ClientMouseListener`) | P2 | unknown |
| `Client.worldList` | unknown | — | `slr.ws` world-list parser | P1 | unknown |
| `Client.chatMessages` | unknown | — | `ON_CHAT_TRANSMIT` raise site | P1 | unknown |
| `Client.menu` (minimenu entries) | unknown | — | `ON_MINIMENU_REBUILD` / `ON_MINIMENU_CLICK`; `OpOrder.json` | P2 | unknown |

### 3.3 World (≈ RuneLite WorldView)

| Key | Seed | Drift | Anchors | Pri | State |
|---|---|---|---|---|---|
| `World.scene` | `+0x10` (Scene*) | — | `locFind` | P0 | known |
| `World.plane` | `+0x18` | same (64d4) | `npcCoord` | P0 | known |
| `World.width` / `height` | `+0x1C` / `+0x20` | — | world-from-coord bounds `FUN_1400ec4a0` | P0 | known |
| `World.baseX` / `baseZ` | `+0x24` / `+0x28` | same (64d4) | `locFind`, packet decoders | P0 | known |
| `World.players` (hash_map) | `+0x60` (buckets `+0x68`, count `+0x70`) | same (64d4) | player lookup `FUN_140187280` (limit `0x800`) | P0 | known |
| `World.npcs` (hash_map) | `+0x90` (buckets `+0x98`, count `+0xA0`) | — | NPC lookup `FUN_1401878b0` (limit `0x10000`) | P0 | known |
| `World.npcIndices` / count | `+0xD0` / `+0xD8` | — | `getNpcIdAll` | P0 | known |
| `World.objPiles` (grid) | `+0x140` → `[plane]+0x10, stride 0x18` → `[x]+0x10, stride 0x18` → `z*0x18` = list | — | `objFind` → `FUN_14009d8b0` | P0 | known |
| `World.collision[plane]` | unknown | — | `getCollisionMap(s)`, LuaCollisionMap `getBitFlags` | P0 | unknown |
| `World.tileHeights` | unknown | — | worldToScreen callers; terrain code | P0 | unknown |
| `World.isInstance` / template chunks | unknown | — | map-rebuild packet | P1 | unknown |
| `World.id` / WorldEntity (boat) data | unknown | — | `"FATAL - WorldEntity update is bigger than expected"` | P1 | unknown |

### 3.4 Actor (shared by NPC and Player)

| Key | Seed | Drift | Anchors | Pri | State |
|---|---|---|---|---|---|
| `Actor.fineX` / `fineZ` | `+0x268` / `+0x26C` | same (64d4) | `npcCoordFine` | P0 | known |
| `Actor.poseAnims[14]` (idle, walk, turn, run…) | `+0x280` | — | Actor ctor (14× −1); NPC update decoder | P0 | partial (order unknown) |
| `Actor.timedEffects` (vector) | `+0x2C0` | — | `FUN_14009f2c0` | P1 | partial |
| `Actor.overheadText` (eastl::string) / timer | `+0x2D8` / `+0x2F4` (=100) | — | Actor ctor defaults | P1 | partial |
| `Actor.headbars` (list) / count | `+0x388` / `+0x398` | — | `isHealthbarRendered` | P0 | known |
| `Headbar.entry` (ratio/scale) | entry `+0x4`, `+0x8` | — | `isHeadbarEmpty` | P0 | partial |
| `Actor.hitsplats` | unknown | — | NPC update decoder loop `FUN_14009ec30` | P1 | unknown |
| `Actor.interactingValid` / `interacting` | `+0x3A7` (u8) / `+0x3A8` (i32) | — | `isInteracting` | P0 | known |
| `Actor.pathX[10]` / `pathZ[10]` | `+0x3F0` / `+0x418` | same (64d4) | `npcCoord` | P0 | known |
| `Actor.world` | `+0x4A0` (0 → top level) | same (64d4) | entity→actor resolver | P0 | known |
| `Actor.defaultHeight` | `+0x49C` (=200) | — | Actor ctor | P1 | partial |
| `Actor.anim` (AnimState) | block `+0x4C8`: SeqType* `+0x4D0`, **id `+0x4D8`**, loop `+0x4E0`, delay `+0x4E8` | — | `setSequence` `FUN_14009fe50` + Seq loader (group 12) | P0 | known |
| `Actor.otherAnimStates` | `+0x4A8`, `+0x4F0`, `+0x510`, `+0x530` | — | `FUN_14009f360`; spot-anim candidates | P1 | partial |
| `Actor.orientation` / target orientation | unknown | — | turn logic; face-direction mask (`+0x494`/`+0x498` candidate) | P0 | unknown |
| `Actor.spotAnims` | unknown | — | SpotAnim loader (group 13) callers on actors | P1 | unknown |
| `Actor.name` (eastl::string) | `+0x710` | `+0x718` (64d4, Player) | `npcName` / `playerName` | P0 | known (drift or player-specific?) |

### 3.5 NPC and NpcType

| Key | Seed | Anchors | Pri | State |
|---|---|---|---|---|
| `Npc.type` (NpcType*) | `+0x730` | `npcTypeId` | P0 | known |
| `Npc.uidLow` | `+0x738` (`uid = typeId*0x10000 + this`) | `npcUid` | P1 | known |
| `Npc.spawnCycle` | `+0x73C` | `npcCreationCycle` | P1 | known |
| `Npc.overrides` (recolour/retexture/tint) | `+0x750..+0x770`, tint `+0x551..+0x55C` | NPC update setters | P2 | partial |
| `Npc.headIcon` / overhead prayer | unknown | NPC update decoder | P1 | unknown |
| `NpcType.id` / `name` / `size` | `+0x0` / `+0x8` / `+0x20` | NpcType binding getters | P0 | known |
| `NpcType.multi` (transforms) | `+0x140` | `ncName` | P0 | partial |
| `NpcType.ops` / `stats` / `params` | ops map `+0x100`; stats unknown | `getOps` / `getStats` / `getParams` | P1 | partial |

The API can also take NpcType, LocType and ObjType definitions from the on-disk cache
(`%LOCALAPPDATA%\Jagex\Old School Runescape\data\main_file_cache.*`) and read only the type id from
memory. That is fewer offsets to maintain; see design §4.6.

### 3.6 Player

| Key | Seed | Drift | Anchors | Pri | State |
|---|---|---|---|---|---|
| `Player.index` | unknown | `+0x7D0` (64d4) | `playerFindSelf` | P0 | unknown |
| `Player.combatLevel` | unknown | `+0x780` (64d4) | player update decoder | P0 | unknown |
| `Player.appearance` (equipment, gender, colours) | unknown | — | appearance mask decoder | P1 | unknown |
| `Player.skullIcon` / `prayerIcon` / team | unknown | — | appearance decoder | P1 | unknown |
| `Player` relationship flags (friend/clan/ignored) | unknown | — | LuaPlayer `isOnlineFriend` / `isGroup` / `isFriendChat` / `isIgnored` | P2 | unknown |

### 3.7 Scene and locs (game objects)

| Key | Seed | Anchors | Pri | State |
|---|---|---|---|---|
| `Scene.sizeX` / `sizeZ` | `+0x948` / `+0x94C` | loc tile lookup `FUN_1406a2cb0` | P0 | known |
| `Scene.tiles` | `+0x968`; index `(plane*sizeX + x)*sizeZ + z`; 16-byte entries, `Tile*` at `+8` | same | P0 | known |
| `Scene.bridgeTiles` (plane −1) | `+0x980` | same | P1 | known |
| `Tile.wall` / `wallDecor` / `groundDecor` | `+0x108` / `+0x110` / `+0x118` | same (layers 0/1/3) | P0 | known |
| `Tile.gameObjectCount` / `gameObjects` | `+0x34` / `+0x38` (array of `{ctrl, obj}`) | `FUN_1406a2b80` | P0 | known |
| `Loc.tag` (wall/decor) | `+0x38`; id = `tag>>20` | `FUN_1406a2cb0` | P0 | known |
| `GameObject.tag` / `originX` / `originZ` | `+0x1F0` / `+0x210` / `+0x218` | `FUN_1406a2b80` | P0 | known |
| `Loc.orientation` / `shape` / size / height / model | unknown (likely in tag bits) | tag-decode sites; Loc constructors | P1 | unknown |
| `LocType.id` | `+0x8` (cache object 0x1E8 B) | `locTypeId`, loader group 6 | P1 | known |
| `GroundItem.id` / `count` | `+0x20` / `+0x24` | `objFind`, `objCount` | P0 | known |
| `ObjType` (name, cost, stackable, noted, ops) | loader group 10 `FUN_1405f5cd0` | `ocName` / `ocCost` / `ocStackable` handlers | P1 | partial |

### 3.8 Projectiles and graphics objects

| Key | Seed | Anchors | Pri | State |
|---|---|---|---|---|
| `Projectile.hidden` | `+0x1C` | `renderProjectiles` | P1 | known |
| `Projectile.src` `{plane,x,z,height?}` | `+0x260` | ctor copy `FUN_1400a24a0` + packet decode | P1 | known (height inferred) |
| `Projectile.srcEntity` / `dstEntity` | `+0x270` / `+0x284` | packet decode (24-bit signed) | P1 | partial |
| `Projectile.dst` `{plane,x,z,height?}` | `+0x274` | same | P1 | known |
| `Projectile.startCycle` / `endCycle` | `+0x288` / `+0x28C` | `+ global.cycle` in decode | P1 | known |
| `Projectile.slope` / `startDistance` | `+0x290` / `+0x294` | decode | P1 | partial |
| `Projectile.spotAnimId` | `+0x298` | SpotAnim loader call in ctor | P1 | known |
| `Projectile.pos` (double x,y,z) | `+0x2A0` | rebase `FUN_1400a2ad0` | P1 | known |
| `GraphicsObject.key` / `spotAnimId` | `+0x260` / `+0x264` | `FUN_1400320e0` (SpotAnim loader) | P1 | known |
| `GraphicsObject.coords` / fine x,z (float) | `+0x268`, `+0x290` / `+0x2E0`, `+0x2E8` | rebase `FUN_140032080` | P1 | partial |

### 3.9 Items, vars and stats

| Key | Seed | Anchors | Pri | State |
|---|---|---|---|---|
| `Inventory` storage (inv id → `{objId[], count[]}`) | unknown | ClientOp `invGetObjId` `0x1401a93d0`, `invGetNum` `0x1401a9420`, `invSize`, `invTotal` | P0 | unknown (handlers located) |
| `VarbitType` (varp, lsb, msb) | loader group 14 `FUN_140600690` | `getVarbit` | P0 | known |
| `Varc` int/string storage | unknown | ClientState `getIntegerVarc` / `getStringVarc` | P1 | unknown |
| Prayer, run, spec, poison | via varps/varbits | — | P0 | depends on varps |

### 3.10 Widgets / interfaces (largest gap; kraken-api's heaviest dependency)

| Key | Anchors | Pri | State |
|---|---|---|---|
| Interface root lookup (group id → component list) | Lua `retrieveIfGroup`, `retrieveIfType`, `osrs.InterfaceQuery`, `osrs.ComponentList` | P0 | unknown |
| `IfType` fields: id/uid, parent, children, isIf3, type, hidden, x/y/width/height + modes, scroll, text/text2, colour, graphic/sprite, model id/type/zoom/angles, item id/qty (`linkObjType` / `linkObjCount`), opNames, opBase, `isRendering` | `osrs.IfType` usertype getters (strings `text`, `hidden`, `children`, `parent`, `opNames`, `graphic`, `modelZoom`, `linkObjType`, …), `"Error accessing children: [%s]"` | P0 | unknown (anchors located) |
| Screen bounds of a component | `osrs.InterfaceCoordinates` (`clipX1..clipY2`, `screenCoords`) | P0 | unknown |
| Open sub-interfaces (sub-changes) | `ON_SUB_CHANGE` raise site | P1 | unknown |
| ClientScript runner (`runScript` equivalent) | `ON_CLIENTSCRIPT`; script engine (`rs2lib/script`) | P2 | unknown |

### 3.11 Camera, projection and terrain

| Key | Seed | Anchors | Pri | State |
|---|---|---|---|---|
| `worldToScreen` (function) | RVA `0x21EB80`; core `0x6AA7C0`; viewport scale `0x61AA30` | Graphics binding `worldToScreenCoord` | P0 | known |
| Projection trig floats (sin/cos pitch, yaw) | `P.f32[0x418..0x424]`, `P = *(C+0xCA80)+0x10` | projection core | P0 | partial |
| Focal scale `K`, viewport offsets | `L.i32[0x10]`, `+0x14/+0x18`, `+0x50/+0x58`; `L = (*(C+0x90)+0x1B0)->vtbl[+8]()` | projection core | P0 | partial (indirect call unresolved) |
| Zoom inputs | `C+0x4830`, `C+0x4814` (passed to `L->vtbl[+8]`) | same | P0 | partial |
| Tile heights | unknown | terrain / `TerrainPatch.cpp` | P0 | unknown |
| Collision flags | unknown | `osrs.CollisionMap`, `getBitFlags`, `lineOfSight`, `lineOfWalk` | P0 | unknown |
| Model geometry (clickboxes / hulls) | unknown | renderer; deferred | P2 | unknown |

### 3.12 Config loaders (shared by many keys)

`FUN_140625a50(cache, &key, group, id)` loads cache config group `group`. Each loader is found by its
`MOV R8D, group` immediate:

| Group | Type | Loader | Known fields |
|---|---|---|---|
| 6 | LocType | `FUN_1405ed080` | id `+0x8`, size 0x1E8 |
| 9 | NpcType | `FUN_1405f18f0` | see 3.5 |
| 10 | ObjType | `FUN_1405f5cd0` | — |
| 12 | SeqType | `FUN_1405fbb00` | priority `+0xC8`, restart mode `+0xE4` |
| 13 | SpotAnimType | `FUN_1405fedf0` | seq id at `+0x8` of the loaded record (used by the projectile ctor) |
| 14 | VarbitType | `FUN_140600690` | `{varp, lsb, msb}` |
| others | 1, 3, 4, 5, 8, 11, 15, 16, 19, 32–39, 72–75 | see `disasm.txt` | — |

### 3.13 Event hook sites (P2, in-process runtime)

The client raises an event (each with a name and a number) for its own Lua plugin system. These map
directly onto RuneLite events, and the updater should record each raise site and enum value:

| Native event | RuneLite equivalent |
|---|---|
| `ON_GAME_TICK` | `GameTick` |
| `ON_GAME_STATE_CHANGED` | `GameStateChanged` |
| `ON_NPC_LOAD` / `ON_NPC_UNLOAD` | `NpcSpawned` / `NpcDespawned` |
| `ON_LOC_LOAD` / `ON_LOC_UNLOAD` | `GameObjectSpawned` / `GameObjectDespawned` |
| `ON_VARBIT_UPDATE`, `ON_VAR_TRANSMIT` | `VarbitChanged` |
| `ON_STAT_TRANSMIT` | `StatChanged` |
| `ON_INV_TRANSMIT` | `ItemContainerChanged` |
| `ON_CHAT_TRANSMIT` | `ChatMessage` |
| `ON_MINIMENU_CLICK` / `ON_MINIMENU_REBUILD` | `MenuOptionClicked` / `MenuOpened` |
| `ON_MAP_REBUILD_PRE` / `ON_MAP_REBUILD_POST` | scene load |
| `ON_CLIENTSCRIPT` | `ScriptCallbackEvent` |

An external (read-only) runtime synthesizes these events by diffing snapshots on `Client.tickCount`
changes.

### 3.14 Action targets (P2; in-process only)

The RuneLite-API equivalents are `doAction` (menu invocation), the packet writer, input queues, camera
target setters and `runScript`. The updater records these as function RVAs with a call signature. Anchors
to investigate: `ON_MINIMENU_CLICK` raise site (the menu action dispatcher); menu-swap code
(`OpOrder.json`, `"%s -> %s"`); input listeners
(`jag::oldscape::input::ClientMouseListener` / `ClientKeyboardListener` binders, which do have RTTI);
Camera `setPos` / `setLookAt`; `ON_CLIENTSCRIPT`.

---

## 4. Known function anchors (seed build)

| Function | RVA | Role |
|---|---|---|
| ClientOp registration | `0x3A82F0` | 66 name→handler pairs parsed (`clientop_handlers.txt`); functor-factory registrations such as `playerFindSelf` need extra handling |
| Graphics registration | `0x21E3B0` | `worldToScreenCoord`, `clientDimensions`, shapes |
| `osrs.Entity` registration | `0xF4420` | `isDead`, `isInteracting`, `render2D/3D`, … |
| `osrs.NpcType` registration | `0xF3FE0` | `getSize`, `getOps`, `getStats`, … |
| NPC update decoder | `0x98DC0` | extended-info masks |
| `setSequence(actor, seq, delay)` | `0x9FE50` | animation |
| Actor ctor | `0x9DCD0` | field defaults |
| Projectile ctor / packet | `0xA24A0` / inside `0x95F10` | projectile layout |
| Map rebase | `0x69610` | shifts projectiles and graphics objects |
| Lua engine gate | `0x63040` (beta) / `0x8C2E0` (create) | — |

## 5. Observed drift across builds (why we need an updater)

| Item | 2ce03524 | 64d448bb | 166231c8 |
|---|---|---|---|
| `global.client` RVA | `0xE754B8` | `0xE95668` | `0xE98C18` |
| `playerFindSelf` RVA | `0x3810F0` | `0x3A9220` | `0x3A8280` (probable; registered through a functor factory) |
| `Client.worlds` | — | `+0xC9C8` | `+0xC9B8` (−0x10) |
| `Client.localPlayerIndex` | — | `+0xCC5C` | `+0xCC4C` (−0x10) |
| `World.plane` / `baseX` / `players` | — | `+0x18` / `+0x24` / `+0x60` | unchanged |
| `Actor.fine*`, `path*`, `world` | — | `+0x268` / `+0x3F0` / `+0x4A0` | unchanged |
| `Player.name` | — | `+0x718` | `+0x710` (if the same field) |

Two things stand out:

- **Shifts move in blocks.** Every `Client` field past some point moved by the same −0x10. Inner structs (World, Actor up to `+0x4A0`) were stable.
- **Function RVAs move every build.**

Both patterns drive the updater's carry-forward and delta-propagation strategies.
