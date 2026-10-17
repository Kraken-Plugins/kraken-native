# Kraken Native API: design

Status: **draft, for review.** Nothing here is implemented yet.
Companion documents:

- [client-surface.md](client-surface.md): what the native client contains.
- [updater-design.md](updater-design.md): how the offsets are mapped for each build.
- [api-gaps.md](api-gaps.md): each API member checked against the current mapping, and what the updater still needs.

## 1. Purpose and scope

This document describes a RuneLite-style API for the native OSRS client (`osclient.exe`). It covers widgets, players,
NPCs, actors, game objects, ground items, projectiles, graphics objects, item containers, vars, skills, the camera,
coordinates and events. The only input that changes per client build is `mappings/<sha16>.json`, which the updater
produces. The API contains no hard-coded offsets.

Goals:

- **RuneLite-shaped.** Use RuneLite's interface and method names (`Client.getLocalPlayer()`,
  `Actor.getWorldLocation()`, `Widget.getText()`), so plugin code and kraken-api habits carry over.
- **Driven by capabilities.** A feature works only when the mapping marks its capability as satisfied. If not, the
  API says which capability is missing. It never returns a silent guess.
- **Lean.** Use a few layers that one person can review. Struct access goes through a single block reader, not a
  framework.
- **Runtime-agnostic.** The API sits on a `Memory` interface. An external read-only reader comes first. An in-process
  backend can replace it later without changing the API.

Non-goals for the first phases: actions (menu invocation, packets, input), writing to client memory, model clickboxes
and hulls, and anti-detection of any kind.

## 2. Decisions this design proposes

| # | Decision | Proposal | Why |
|---|---|---|---|
| D1 | API language | **Java 21**, Gradle, in `api/` next to `updater/` | Same ecosystem as kraken-api, kraken-plugins and RuneLite. JDK 21 is already installed. |
| D2 | Memory access | **JNA** `Kernel32.ReadProcessMemory` behind a `Memory` interface | RuneLite already ships JNA and it works on JDK 17+. FFM (`java.lang.foreign`) can replace it on JDK 22+. |
| D3 | Runtime model | **External reader first** (read-only, out of process). An in-process backend later, only for events at the source and actions. | Needs no injection, reuses the probe's safety model, and covers about 90% of RuneLite's read API. See §8. |
| D4 | Definitions | **Decode the local cache** (RuneLite's `net.runelite:cache` loaders, if the native cache format matches; otherwise an OpenRS2 snapshot) | Already decided in updater-design §10. Memory supplies only type ids. |
| D5 | Data model | **Immutable per-tick snapshot** for entities. **Lazy, tick-scoped views** for the scene and the widget tree. | Snapshots keep within-tick reads consistent and make event diffing trivial. The scene and widgets are too large to copy every tick. |
| D6 | Naming | RuneLite interface and method names in package `com.kraken.nativeapi.api`. Kraken-style queries are layered on top later. | Code reads like RuneLite. The kraken-api query layer can be ported onto these interfaces. |

D3 and D4 are the decisions that most need your confirmation. They are listed again in §12.

## 3. Architecture

```
mappings/<sha16>.json                         osclient.exe (running)
        │                                              │
  ┌─────▼──────┐   ┌───────────────┐   ┌──────────────▼─────────────┐
  │ mapping    │   │ defs          │   │ memory                      │
  │ Mappings,  │   │ DefinitionStore│  │ Memory (read only),         │
  │ Layouts,   │   │ (cache decode) │  │ ExternalMemory (JNA RPM),   │
  │ Capability │   └──────┬────────┘   │ Attach (pid, base, sha256)  │
  └─────┬──────┘          │            └──────────────┬─────────────┘
        │                 │            ┌──────────────▼─────────────┐
        │                 │            │ struct                      │
        └─────────────────┼───────────►│ Block, EastlString, HashMap,│
                          │            │ EastlList, Vector, SharedPtr│
                          │            └──────────────┬─────────────┘
                          │            ┌──────────────▼─────────────┐
                          └───────────►│ model  (implements api)     │
                                       │ SnapshotReader → Snapshot   │
                                       │ SceneView, WidgetView       │
                                       └──────────────┬─────────────┘
                                       ┌──────────────▼─────────────┐
                                       │ runtime                     │
                                       │ TickLoop, EventDiffer,      │
                                       │ EventBus, PluginHost        │
                                       └──────────────┬─────────────┘
                                                      ▼
                                          api (interfaces) ◄── plugins
```

| Package | Responsibility | Depends on |
|---|---|---|
| `api` | Public interfaces and value types: `Client`, `WorldView`, `Actor`, `NPC`, `Player`, `Tile`, `TileObject`, `Widget`, `ItemContainer`, `Projectile`, `GraphicsObject`, `WorldPoint`, `LocalPoint`, events. No implementation. | nothing |
| `mapping` | Loads the mapping JSON, selects it by module sha256, and resolves keys into typed `Layout` records. Exposes `Capabilities`. | JSON lib |
| `memory` | `Memory` interface (`read(addr, len)`), `ExternalMemory` (JNA), process attach and module identity checks. | JNA |
| `struct` | Readers for the client's containers: one bulk `Block` read per struct, then typed getters by offset. `eastl::string`, `hash_map`, `list`, `vector` and `shared_ptr` walkers, with bounds and sanity checks. | `memory`, `mapping` |
| `defs` | `DefinitionStore`: NPC, Object (Loc), Item (Obj), Varbit, Seq, SpotAnim, HealthBar and Enum definitions, decoded from the cache. | cache lib |
| `model` | Implements `api`. `SnapshotReader` builds a `Snapshot` per tick. `SceneView` and `WidgetView` are lazy. | all of the above |
| `runtime` | `TickLoop` (polls `Client.tickCount`), `EventDiffer` (snapshot N−1 → N events), `EventBus`, `PluginHost`. | `model` |

The only coupling to the updater is the mapping file. A unit test in `api/` checks that every mapping key the API
names exists in `updater/native_updater/spec/*.yaml`, so renaming a key on either side fails fast.

## 4. Consuming the mapping

At attach time:

1. Find `osclient.exe` (by PID or name). Check the image path, the PE headers of the loaded module, the module base
   and `SizeOfImage`, the same way the probe does.
2. Hash the file on disk (sha256) and look it up in `mappings/index.json`. **No mapping means no attach.** The API
   reports `UNSUPPORTED_BUILD` and the hash.
3. Load the mapping. Check the build fields (`timestamp`, `sizeOfImage`). Check the guard bytes as well once the
   updater emits them (milestone M3).
4. Resolve layouts. Each struct gets a small hand-written record that is filled from keys:

```java
record ActorLayout(int fineX, int fineZ, int pathX, int pathZ, int pathLength, int anim,
                   int interacting, int headbars, int name, int worldId, int size) {
    static ActorLayout from(Mapping m) {
        return new ActorLayout(m.offset("Actor.fineX"), m.offset("Actor.fineZ"), m.offset("Actor.pathX"),
                m.offset("Actor.pathZ"), m.offsetOr("Actor.pathLength", -1), m.offset("Actor.anim"),
                m.offset("Actor.interacting"), m.offset("Actor.headbars"), m.offset("Actor.name"),
                m.offset("Actor.worldId"), m.sizeOr("Actor", 0x780));
    }
}
```

5. Read `capabilities` from the mapping. The API keeps its own table from API feature to capability (§9). A missing
   capability disables that feature only. Calling a disabled feature throws
   `CapabilityUnavailableException("widgets.read", build)`. `client.getCapabilities()` lets plugins check first.

The updater's verdicts carry through. `INFERRED` keys are usable but flagged. `LIVE_VERIFIED` (from the probe
overlay) is shown in diagnostics.

## 5. Reading memory

### 5.1 Blocks, not fields

Each `ReadProcessMemory` call is a syscall, so struct reads are batched. `Block.read(memory, address, size)` copies a
whole struct in one call (for example, an Actor is about 0x780 bytes). The model then decodes fields from the byte
array using layout offsets. Struct sizes come from the mapping (`<Struct>.size` keys, from `operator new` and
`make_shared` sites; see the gaps). Until those keys exist, a conservative fallback size is used.

Container walkers return addresses, and the caller reads the elements as blocks:

| Walker | Contract keys (`containers.*` in the mapping) | Safety checks |
|---|---|---|
| `EastlString.read(block, off)` | `string.heapFlagByte`, `heapFlagMask`, `size` | length ≤ 4096, valid UTF-8 or printable bytes |
| `HashMapWalker.entries(mapAddr)` | `hash_map.buckets`, `bucketCount`, `node.key/value/next` | bucket count ≤ 1<<16; ≤ N nodes visited; key hashes to its bucket |
| `ListWalker.values(listAddr)` | `list.node.value`, sentinel at the head | ≤ N nodes; stop at the sentinel; detect cycles |
| `VectorReader.ints(begin, end)` | `{begin, end, cap}` | `end ≥ begin`, length ≤ limit |
| `SharedPtr.get(addr)` | `shared_ptr.ptr`, `ctrl.useCount` | use count > 0 |

Pointers must be canonical user-space addresses. Any failed check skips that one entity and increments a diagnostics
counter. It never throws into plugin code.

### 5.2 Consistency

The external reader races the client's main thread. The approach:

- The `TickLoop` polls the 4-byte `Client.tickCount` every 10–20 ms. When it changes, it builds a snapshot right
  away. The client has just applied that tick's server packets, so state is most stable then.
- After a snapshot, it reads `tickCount` again. If the tick changed during the read, the snapshot is rebuilt once.
  If it changes again, the snapshot is published with `consistent=false`.
- Hash-map and index-list reads are cross-checked. Each index in `World.npcIndices` must be found in `World.npcs`.
  Entities that fail are dropped from that snapshot.

This is the same "sampled memory, not a game-phase boundary" contract as the earlier native project. The in-process
backend (§8) removes the race by snapshotting on the client thread.

Cost estimate per tick: one block read per actor (players plus NPCs, typically < 700), one read of about 20 KB for
the varps, about 60 reads for inventories, and a few for projectiles and graphics objects. That totals about 1–3 ms
every 600 ms.

## 6. Data model

### 6.1 Snapshot (eager, every tick)

`Snapshot` is immutable and thread-safe. It is captured once per tick and handed to subscribers:

```java
record Snapshot(int tick, int cycle, GameState gameState, boolean consistent,
                WorldViewData topLevel, Map<Integer, WorldViewData> worldViews,
                int localPlayerIndex, Skills skills, int[] varps,
                Map<Integer, ItemContainerData> containers,
                List<ProjectileData> projectiles, List<GraphicsObjectData> graphicsObjects,
                Set<Integer> openInterfaces /* group ids */) {}

record WorldViewData(int id, int plane, int baseX, int baseY, int sizeX, int sizeY,
                     Map<Integer, NpcData> npcs, Map<Integer, PlayerData> players, long sceneAddress) {}
```

`NpcData` and `PlayerData` hold the decoded fields (positions, path, animation, interacting, health, name, type id).
They also hold their source address and a generation key, which is used to tell respawns from moves. The `api`
interfaces (`NPC`, `Player`) are implemented by thin wrappers over these records and the `DefinitionStore`.

### 6.2 Lazy views (scene, widgets, collision)

The scene (4 planes × 104 × 104 tiles) and the widget tree are read on demand. `SceneView` and `WidgetView` are bound
to a tick. Their reads go through a block cache keyed by address that is cleared when the tick changes. A view from
an earlier tick can still answer from its cache. It throws `StaleViewException` instead of reading memory that may
have been freed.

### 6.3 Live samples

The camera moves every frame, so `Perspective` and `Camera` read the camera block (`Client.camera.*`, zoom, canvas
size) live. That is about 128 bytes per call, and overlays can call it at frame rate from any thread.

## 7. API surface

Interfaces follow RuneLite. The full mapping of every member to keys, with status, is in
[api-gaps.md](api-gaps.md). This section fixes the shape and the native-specific semantics.

### 7.1 Client

```java
public interface Client {
    GameState getGameState();           int getTickCount();        int getGameCycle();
    Player getLocalPlayer();            WorldView getTopLevelWorldView();
    WorldView getWorldView(int id);     Collection<WorldView> getWorldViews();
    List<Player> getPlayers();          List<NPC> getNpcs();       // top-level world view
    int getRealSkillLevel(Skill s);     int getBoostedSkillLevel(Skill s);   int getSkillExperience(Skill s);
    int getVarpValue(int varp);         int getVarbitValue(int varbit);
    int getVarcIntValue(int varc);      String getVarcStrValue(int varc);
    ItemContainer getItemContainer(int inventoryId);
    Widget getWidget(int groupId, int childId);   Widget getWidget(int packedId);   Widget[] getWidgetRoots();
    List<Projectile> getProjectiles();  List<GraphicsObject> getGraphicsObjects();
    int getCameraX(); int getCameraY(); int getCameraZ(); int getCameraPitch(); int getCameraYaw(); int getScale();
    int getCanvasWidth(); int getCanvasHeight();
    int getEnergy(); int getWeight(); int getWorld();       // need updater work, see gaps
    Capabilities getCapabilities();     BuildInfo getBuild();
    DefinitionStore getDefinitions();   // getNpcDefinition / getObjectDefinition / getItemDefinition shortcuts
}
```

### 7.2 World views and coordinates

The native client stores a set of worlds (`Client.worlds`, a hash map) and a top-level world
(`Client.topLevelWorld`). They map onto RuneLite's `WorldView`. Sailing boats are extra world views, and each actor
records its world (`Actor.worldId`).

Native coordinates use **x/z** for the ground plane, where z points north. RuneLite uses x/y. The API uses RuneLite
names, so `WorldPoint.y` and `LocalPoint.y` are the native z.

| Type | Definition | From |
|---|---|---|
| `WorldPoint(x, y, plane)` | absolute tile | `World.baseX + localTileX`, `World.baseZ + localTileZ`, `World.plane` |
| `LocalPoint(x, y, worldView)` | fine units inside the scene, 128 per tile | `Actor.fineX`, `Actor.fineZ` |
| scene tile | `fine >> 7`, the same as `Actor.pathX[0]` / `pathZ[0]` | |
| angles | native 16384 units per circle. The API returns RuneLite units (2048 per circle, `native >> 3`) and keeps the raw value. | camera, orientation |

Whether native fine coordinates are tile corners or tile centres (RuneLite uses centres, `tile*128+64`) has to be
confirmed with the live probe. Instanced regions need the template chunk table for
`WorldPoint.fromLocalInstance` (gap). Boat world views need each world entity's position and rotation in its parent
(gap).

`WorldView` exposes `getId`, `getPlane`, `getBaseX/Y`, `getSizeX/Y`, `getPlayers`, `getNpcs`, `getScene`,
`getCollisionMaps`, `getTileHeights` and `isInstance`.

### 7.3 Actors, NPCs, players

```java
public interface Actor {
    WorldView getWorldView();      WorldPoint getWorldLocation();   LocalPoint getLocalLocation();
    String getName();              int getAnimation();              int getPoseAnimation();
    Actor getInteracting();        int getHealthRatio();            int getHealthScale();
    int getOrientation();          String getOverheadText();        int getLogicalHeight();
    List<ActorSpotAnim> getSpotAnims();   boolean isDead();         boolean isMoving();  // pathLength > 0
}
public interface NPC extends Actor {
    int getIndex(); int getId(); NPCComposition getComposition(); NPCComposition getTransformedComposition();
    int getCombatLevel(); HeadIcon getOverheadIcon(); int getSpawnCycle();
}
public interface Player extends Actor {
    int getId(); int getCombatLevel(); PlayerComposition getPlayerComposition();
    HeadIcon getOverheadIcon(); int getSkullIcon(); int getTeam();
    boolean isFriend(); boolean isClanMember(); boolean isFriendsChatMember();
}
```

Native-specific notes:

- **Index.** An NPC's index is its key in `World.npcs`. A player's index is its key in `World.players`. The local
  player is `World.players[Client.localPlayerIndex]` in the top-level world.
- **Interacting.** `Actor.interacting` holds one integer for both NPC and player targets. The encoding (RuneLite uses
  `< 32768` for NPCs; the spec invariant suggests NPC `< 0x10000` and player `0x10000 + index`) needs the probe.
- **Names.** Player names come from `Actor.name`. NPC names come from the definition (`NpcType.name` in memory, or
  the cache). Transformed NPCs (multi-NPCs) are resolved through the definition's varbit or varp and the current
  varps. Whether `Npc.type` already points at the transformed type needs the probe.
- **Health.** The ratio comes from the first headbar entry. The scale comes from the headbar's `HealthBarDefinition`
  width in the cache, so the headbar's type id is needed (gap).

### 7.4 Scene and tile objects

```java
public interface Scene { Tile getTile(int plane, int sceneX, int sceneY); int getSizeX(); int getSizeY(); }
public interface Tile {
    WorldPoint getWorldLocation(); int getPlane();
    WallObject getWallObject(); DecorativeObject getDecorativeObject(); GroundObject getGroundObject();
    List<GameObject> getGameObjects(); List<TileItem> getGroundItems(); Tile getBridge();
}
public interface TileObject { long getHash(); int getId(); WorldPoint getWorldLocation(); int getPlane();
                              ObjectComposition getComposition(); }
public interface GameObject extends TileObject { int sizeX(); int sizeY(); int getOrientation(); int getShape(); }
public interface TileItem { int getId(); int getQuantity(); }
```

The id is `tag >> Loc.tagIdShift`. The hash is the 64-bit native tag, so it is not interchangeable with RuneLite's
hash. Size comes from the cache `ObjectComposition` (sizeX/sizeY, swapped for rotations 1 and 3) until the rotation
and shape are mapped (gap). Ground items come from `World.objPiles`, a grid of lists, one per tile.

### 7.5 Projectiles and graphics objects

`Projectile`: `getId` (spot anim), `getStartCycle`, `getEndCycle`, `getRemainingCycles` (= `endCycle − cycle`),
`getX/Y/Z` (from the double position), source and target points and heights, `getSlope`, `getStartDistance`,
`getTargetActor`, `getSourceActor`, `isHidden`.
`GraphicsObject`: `getId`, `getLocation`, `getLevel`, `getStartCycle`, `getHeight`, `finished()`.
All these offsets are in the catalog but have no updater recipe yet (gap: recipe only).

### 7.6 Widgets

```java
public interface Widget {
    int getId(); int getType(); int getIndex(); int getParentId(); Widget getParent();
    Widget[] getChildren(); Widget[] getStaticChildren(); Widget[] getDynamicChildren(); Widget[] getNestedChildren();
    String getText(); String getName(); String[] getActions(); int getTextColor();
    int getItemId(); int getItemQuantity(); int getSpriteId(); int getModelId(); int getOpacity();
    boolean isHidden(); boolean isSelfHidden(); boolean isIf3();
    int getRelativeX(); int getRelativeY(); int getWidth(); int getHeight();
    int getScrollX(); int getScrollY(); int getScrollWidth(); int getScrollHeight();
    Rectangle getBounds(); Point getCanvasLocation();
}
```

Native facts that shape this interface:

- Components are found by packed id, `group << 16 | child`, the same as RuneLite. `InterfaceQuery.retrieveIfType`
  calls an interface-store getter on the client (`0x84E30`) and then a find by packed id (`0x5AF750`) that returns a
  `shared_ptr<IfType>`. This is the same addressing RuneLite uses, so `WidgetInfo` and `ComponentID` constants carry
  over unchanged.
- Open groups live in `Client.interfaces` (a hash map keyed by group id), which gives `WidgetLoaded`/`WidgetClosed`
  by diffing.
- `isHidden()` is derived: the component is hidden itself, or any ancestor is.
- `getBounds()` needs the computed screen position: either `osrs.IfType.screenCoords`
  (`InterfaceCoordinates`) or a walk up through the parents summing positions and scroll offsets. Both need
  updater work.

**Widgets are the largest gap.** Layout data members are mapped. Traversal (group → components, children), the
component's own id and type, text, actions, colour, scroll and screen position are not (see api-gaps §6).

### 7.7 Items, vars, skills

- `ItemContainer`: `getId`, `getItems()` (pairs of item id and quantity from the two vectors), `count(id)`,
  `contains(id)`. The global inventory hash map is fully mapped.
- Vars: `getVarpValue` reads the varp array. `getVarbitValue` uses the cache `VarbitDefinition` (varp, lsb, msb) and
  `global.varbitMaskTable`, or recomputes the mask. Which of the two varp arrays (`global.varps`, `global.varpsBits`)
  is the server copy needs the probe. Varcs: `Client+0x417CA8` is the varc store (found during this review; recipe
  needed).
- Skills: the three 26-entry arrays are mapped. The `Skill` enum order must match the native index order (probe:
  `level(xp) == base`).

### 7.8 Camera and perspective

`Perspective.localToCanvas(client, LocalPoint, plane, zOffset)` and `getCanvasTilePoly` work differently in the two
runtimes:

- **External:** projection math is reimplemented in Java from camera position, yaw and pitch, zoom (scale), viewport
  size and offset, and **tile heights**. Tile heights are the main missing key. Pitch and yaw need the probe to tell
  them apart.
- **In-process:** call `functions.worldToScreen` directly (mapped).

Model clickboxes and convex hulls need model geometry. They are deferred (P2).

### 7.9 Definitions

`DefinitionStore` is opened once against the client's cache. It exposes `NPCComposition`, `ObjectComposition`,
`ItemComposition`, `VarbitDefinition`, `SequenceDefinition`, `SpotAnimDefinition`, `HealthBarDefinition`, enums and
structs. Memory is used only where the cache cannot answer, for example per-instance overrides and transformed
types. Open item: confirm the native client's cache location and format. client-surface §3.5 lists
`%LOCALAPPDATA%\Jagex\Old School Runescape\data\main_file_cache.*`. If it is the standard dat2/idx format, the RuneLite
cache loaders work as they are.

## 8. Runtime models

| | External reader (phase 1) | In-process (later) |
|---|---|---|
| How | Separate JVM, `ReadProcessMemory` | Injected bridge; the JVM in a host process talks to a native agent, or the agent snapshots and ships data |
| Writes or calls into the client | none | function calls on the client thread |
| Tick alignment | polled, can race | exact (hook the tick raise site) |
| Events | synthesized by diffing (§10); can miss changes within a tick | native raise sites (`enums.events`) plus diffing |
| Projection | Java reimplementation (needs tile heights) | call `worldToScreen` |
| Actions (doAction, runScript, packets) | not available | possible (P2 function keys) |
| Risk and complexity | low | high (injection, lifetime, crashes) |

Only `memory.Memory`, the `TickLoop` driver and an `Actions` service differ between the two. `Actions` exists from
day one with `isSupported() == false` in the external runtime.

## 9. Capabilities: API features to mapping

The API keeps one table of features. The updater spec defines the key lists. Proposed capability names (current
updater names in **bold**, new ones in plain text):

| API feature | Capability | Status on the seed build |
|---|---|---|
| attach, game state, tick, cycle | **core** | satisfied |
| skills | **skills** | satisfied |
| varps, varbits | **vars** | satisfied (probe: which array) |
| varcs | vars.varc | recipe needed |
| world views, coordinates | **worlds** | satisfied |
| NPCs: list, position, name, type | **npcs.basic** | satisfied |
| animation | **npcs.animation** (rename to `actors.animation`) | satisfied |
| interacting, health | **npcs.combat** (rename to `actors.combat`) | satisfied (probe: semantics) |
| players, local player | **players.basic** | satisfied |
| player combat level, appearance, icons | players.appearance | RE needed |
| locs | **scene.locs** | satisfied (ids and positions only) |
| loc orientation and shape | scene.locs.shape | RE needed |
| ground items | **scene.groundItems** | satisfied |
| inventories | inventories (keys exist, no capability yet) | satisfied |
| widgets | **widgets.layout** → split into `widgets.read` (traversal, id, type, text, actions) and `widgets.bounds` | `widgets.layout` reports satisfied but cannot reach any component; see gaps |
| projectiles, graphics objects | projectiles, graphicsObjects | recipe needed |
| collision | collision | RE needed |
| projection (external) | projection.external (camera, zoom, viewport, tile heights) | blocked on tile heights |
| projection (in-process) | **projection** (rename to `projection.call`) | satisfied |
| events (in-process raise sites) | events.hooks | P2 |
| actions | actions.menu, actions.script | P2 |

## 10. Events

The external runtime produces events by diffing snapshot N−1 against snapshot N on the tick thread, in a fixed
order. Handlers run on the tick thread, which plays the role of RuneLite's client thread.

| Event | Source | Phase |
|---|---|---|
| `GameTick` | `tickCount` changed | A1 |
| `ClientTick` | `global.cycle` changed (polled, best effort) | A1 |
| `GameStateChanged` | `Client.gameState` changed | A1 |
| `NpcSpawned` / `NpcDespawned` / `NpcChanged` | NPC index set diff; address change means respawn; type id change means changed | A1 |
| `PlayerSpawned` / `PlayerDespawned` | player index set diff | A1 |
| `AnimationChanged`, `InteractingChanged` | per-actor field diff | A1 |
| `VarbitChanged` | varp array diff; varbits derived through a varp → varbits index built from the cache | A1 |
| `StatChanged` | skill array diff | A1 |
| `ItemContainerChanged` | inventory map diff | A1 |
| `WidgetLoaded` / `WidgetClosed` | `Client.interfaces` key diff | A2 |
| `ItemSpawned` / `ItemDespawned` | `objPiles` diff (only while subscribed; it scans the grid) | A2 |
| `GameObjectSpawned` / `GameObjectDespawned` (and Wall, Decorative, Ground variants) | scene tile diff (only while subscribed), with a full rescan when the region base changes | A2 |
| `ProjectileMoved` (and a `ProjectileSpawned` convenience event) | projectile list diff | A2 |
| `GraphicsObjectCreated` | graphics-object map diff | A2 |
| `WorldViewLoaded` / `WorldViewUnloaded` | `Client.worlds` key diff | A2 |
| `OverheadTextChanged`, `HitsplatApplied`, `GraphicChanged`, `ChatMessage` | need the gap keys | A3 |
| `MenuOptionClicked`, `MenuOpened`, `ScriptCallbackEvent` | in-process only | A4 |

Limitation of the external runtime: a change that starts and ends within one tick is invisible, for example a
one-tick animation or a projectile that is created and removed between two samples. Chat needs a message buffer that
the diff can walk forward, so a ring buffer with a counter is needed.

## 11. Repository layout and build

```
kraken-api-native/
  updater/                 (existing, Python)
  mappings/                (existing, produced by the updater; read by the API)
  api/                     (new, Gradle, Java 21)
    build.gradle
    src/main/java/com/kraken/nativeapi/
      api/        Client, WorldView, Actor, NPC, Player, Scene, Tile, TileObject, Widget, ItemContainer,
                  Projectile, GraphicsObject, WorldPoint, LocalPoint, Perspective, Skill, GameState, events/*
      mapping/    Mapping, MappingIndex, Layouts (ActorLayout, NpcLayout, ...), Capabilities
      memory/     Memory, ExternalMemory, ProcessAttach, ModuleIdentity
      struct/     Block, EastlString, HashMapWalker, ListWalker, VectorReader, SharedPtr
      defs/       DefinitionStore (+ cache adapter)
      model/      SnapshotReader, Snapshot, *Data records, Native* wrappers, SceneView, WidgetView
      runtime/    TickLoop, EventDiffer, EventBus, PluginHost, Plugin
    src/test/java/   unit tests over fake Memory images built from fixtures; mapping ↔ spec key lint
```

Tests use a `FakeMemory` built from small byte images (one NPC, one hash map, one string). The layout and container
logic can be tested without the game, the same way the updater's tests run without Ghidra. A live smoke test against
a logged-in client is manual, like the probe.

## 12. Milestones and open decisions

| M | Scope | Depends on (updater) |
|---|---|---|
| **A0** | `api/` skeleton; mapping load; attach and identity checks; `Memory`; container walkers; `Client` scalars (game state, tick, skills, varps); diagnostics CLI that prints a snapshot | updater M2: live probe run on the seed build |
| **A1** | Snapshot reader; players, NPCs, local player; `WorldPoint`/`LocalPoint`; animation, interacting, health; inventories; ground items; loc ids and positions; tick loop and A1 events; `DefinitionStore` | probe confirms interacting encoding, headbar fields, fine-coord centring |
| **A2** | Widgets (read and bounds); collision; tile heights and external projection; projectiles and graphics objects; varcs; A2 events | widget, collision, tile-height, projectile and varc recipes (api-gaps §13, items 1–8) |
| **A3** | Orientation, spot anims, hitsplats, overhead text, player appearance and combat level, NPC head icons, chat, energy and weight, world number, instances, boats | api-gaps §13, items 9–20 |
| **A4** | In-process backend: tick hook, event raise sites, `worldToScreen` call, actions | P2 function keys |

Open decisions for you:

1. **Runtime model (D3).** External reader first, with in-process later behind the same API? This is the
   recommendation.
2. **API language (D1/D2).** Java 21 with JNA? Or C++ first, building on `CLionProjects/kraken-api-native`?
3. **Naming (D6).** Mirror RuneLite names exactly (recommended), or a kraken-api-style query surface as the primary
   API?
4. **Cache source (D4).** The local native cache (format to confirm), or an OpenRS2 snapshot keyed by build?
