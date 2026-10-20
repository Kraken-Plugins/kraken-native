# A RuneLite-shaped Kraken API and plugin system

Status: phases 1-8 implemented, 2026-10-20 (live checks pending, see §15). Covers `api/` and `client/`. The updater and
the mapping format don't change.

## 1. Summary

Today the Java API mirrors Jagex's Lua plugin API name for name: `ClientOp`, `ClientState`, `Events`, `MapCoord`,
`IfType`. This design puts a RuneLite-shaped API on top of that mirror without changing what is read or how it is read.

- **Three layers, like RuneLite's.** The Lua mirror stays and becomes the low-level layer (moved to
  `com.kraken.api.lua`). New public interfaces with RuneLite names (`Client`, `Actor`, `NPC`, `WorldPoint`, `Widget`,
  `TileObject`, …) live in `com.kraken.api`. Small adapter classes implement those interfaces by calling the Lua
  mirror. RuneLite splits its code the same way (public API, raw accessors, mixins), but Kraken needs no bytecode
  injector: the "client" here is a memory image, so a Java object holding an address is a complete implementation.
- **Same meaning, not just same name.** `WorldPoint` is a world tile (x, y, plane), so it is the scene tile *plus the
  map base*, not a renamed `MapCoord`. `GroundObj` (an item lying on the floor) becomes RuneLite's `TileItem`.
  RuneLite's `GroundObject` is floor decoration, which Kraken already has as a loc layer (§8.6).
- **An `EventBus` with `@Subscribe`.** Event classes named and shaped like RuneLite's (`GameTick`, `NpcSpawned`,
  `ItemContainerChanged`, …) go through one bus. The client can post its own events on it too (`PluginChanged`,
  `ConfigChanged`, later `ScreenshotTaken`). Polling stays demand-driven: a kind of event is only tracked while
  something subscribes to it.
- **One "client thread".** The API's poll thread plays the part of RuneLite's client thread. Game events,
  `ClientThread` tasks *and overlay rendering* all run on it, so plugin code is single-threaded the way RuneLite plugin
  code is.
- **Plugins, config and overlays in the client.** `Plugin` and `@PluginDescriptor`, a `PluginManager` with Guice child
  injectors, config interfaces (`@ConfigGroup`/`@ConfigItem`) turned into settings panels automatically, a Plugins
  sidebar panel with on/off switches, and `Overlay`/`OverlayManager` drawing through the existing transparent overlay
  window.
- **Read-only stays read-only.** RuneLite methods Kraken can't back (menus, input, setters, models) aren't declared at
  all. They aren't declared-and-throwing either.
- **Small steps.** Nine phases (§15), each shippable and testable on its own. The first is a package move with no
  behaviour change.

## 2. Goals and non-goals

**Goals**

- A RuneLite plugin developer can read a Kraken plugin and know what every line does.
- Names, types and coordinate conventions match RuneLite's wherever Kraken has the data.
- Plugin lifecycle, config and overlay code ports from RuneLite with few edits.
- The client can post its own events for plugins to consume.
- The Lua mirror keeps working exactly as it does now, with its tests and `docs/lua-api-coverage.md`.

**Non-goals**

- No new game reads. Every RuneLite-shaped method is backed by a read the Lua mirror already does, or a Java-side
  combination of such reads (a diff, a sum, a coordinate conversion). Where RuneLite has a method Kraken can't back, it's
  left out and listed (§8.11). Adding reads is separate updater + API work, as today.
- No writes to the game, no input, no menu entries, no automation. Same rule as today: the API only reads.
- Not a RuneLite clone: no plugin hub, no account sync or profiles, no bytecode injection.
- No changes to the updater, the mapping format or where mappings live (`api/src/main/resources/mappings`).

## 3. Where things stand

**`api/`.** `KrakenApi` attaches to `osclient.exe` and exposes one object per Lua namespace: `client()` (`ClientOp`),
`clientState()`, `events()`, `camera()`, `graphics()`, `world()`, `collision()`, `interfaces()`, `engine()`, plus the
Java-only `scene()`. `Npc` and `Player` are live handles on an index. Events are callback interfaces keyed by Lua id.
`EventPoller` makes them up by diffing reads every 20 ms, only for events that have subscribers.

**`client/`.** Launches and embeds the game. `ProcessAttacher` attaches in the background and hands each poll (about
every 150 ms) to listeners. Dev tools (`DevTool<S>`) read snapshots on the poll thread and paint them on the EDT into a
click-through transparent window (`GameOverlay`). The Plugins sidebar tab is an empty placeholder. Settings are two
checkboxes, and dev tool state is kept in `java.util.prefs`.

Facts about the current code that shape this design:

1. `MapCoord` is a **scene** tile: the world tile minus the map base (`Scene.getBase()`). The one exception is
   `Projectile.Endpoint.tile`, which is already a world tile.
2. `FineCoord` is the same space × 128, with the ground height in `y`. North is `z`.
3. `GroundObj` is an item stack on the ground. Jagex calls items "objs" and scenery "locs".
4. `Loc.layer` has exactly RuneLite's four tile-object kinds: `WALL`, `WALL_DECOR`, `GAME_OBJECT`, `GROUND_DECOR`.
5. Several numbering schemes already match RuneLite's:
   - The raw game states (10, 20, 25, 30, 40, 45) are `GameState`'s values.
   - The collision flag bits are `CollisionDataFlag`'s.
   - The world flag bits are `WorldType`'s (`getProfileType` decodes bits 6, 8, 16, 29 and 30, which are RuneLite's
     `PVP_ARENA`, `QUEST_SPEEDRUNNING`, `BETA_WORLD`, `DEADMAN` and `SEASONAL`).
   - `WornSlots` is `EquipmentInventorySlot`.
   - Packed component ids are `group << 16 | component`, as RuneLite packs widget ids.
6. Lua's `ON_LOC_LOAD` doesn't fire for the locs a map load builds. RuneLite's object spawn events do. Lua has no player
   or ground-item events.
7. Reads can run on any thread. Memory reads are stateless, `ConfigTypes` is synchronized, and the only mutable reader
   state (the active loc and obj) belongs to the Lua-style `loc*`/`obj*` functions, which the RuneLite layer doesn't use.

## 4. Principles

1. **The Lua mirror is the source of truth.** It's verified against the native handlers, documented in
   `lua-api-coverage.md`, and tested with `GameFixture`. The RuneLite layer calls into it and never reads memory itself.
   If a RuneLite-shaped value is wrong, the fix goes in the mirror (or the mapping), and both layers get it.
2. **Interfaces out, adapters in.** Plugins see only interfaces and value types. Implementations are package-private
   classes in `com.kraken.api.impl`.
3. **RuneLite's meaning, not just RuneLite's name.** If a Kraken value means something different from RuneLite's
   same-named value (a scene tile versus a world tile, an online friend versus a friend), either convert it or give it
   a different name and say why in the Javadoc.
4. **Don't declare what can't be backed.** No `UnsupportedOperationException` traps and no methods that always return
   -1. A missing method fails at compile time, which is the most helpful place for someone porting a RuneLite plugin.
5. **Kraken extras are allowed, and marked.** Lua offers a few things RuneLite doesn't (`isHeadbarEmpty`, line of walk,
   `isRendered2D`). They stay, with RuneLite-style names, and their Javadoc says "Kraken extra, mirrors Lua …".
6. **Cost stays visible.** Every read is a `ReadProcessMemory` call. Anything that scans (all tiles, all ground items)
   is only done while someone subscribes, and the Javadoc says it is expensive.

## 5. Architecture

### 5.1 How RuneLite does it

RuneLite ships three layers:

- **`runelite-api`**: public interfaces (`Client`, `NPC`, `Widget`, …), value types (`WorldPoint`) and event classes.
  Plugins compile against this.
- **`rs-api`**: `RS`-prefixed interfaces with one raw getter per hooked field of the obfuscated game client
  (`RSNPC.getComposition()`, …).
- **Mixins**: classes annotated `@Mixin` that implement the public interfaces in terms of the raw `rs-api` getters. A
  build-time *injector* takes the vanilla gamepack, a hooks file (from RuneLite's updater, which maps obfuscated names to
  real ones), and the mixins, and writes an injected client whose classes implement `net.runelite.api.*`.

### 5.2 The Kraken equivalent

Kraken already has the first two halves of that pipeline. It only needs a thin third layer:

| RuneLite | Kraken | What it is |
|---|---|---|
| Updater + hooks file | `updater/` + `mappings/<version>.json` | Names for the client's fields, per build |
| `rs-api` (raw accessors) | `com.kraken.api.lua` (today's `game`, `events`, `config` packages) | Faithful reads of each field or handler, Lua-named |
| Mixins + injector | `com.kraken.api.impl` adapters, wired by plain constructors (and Guice in the client) | Implements the public interfaces using the raw layer |
| `runelite-api` | `com.kraken.api` and subpackages | What plugins compile against |
| `runelite-client` | `client/` | Plugin manager, event bus wiring, config UI, overlays |

There's no injector because there's nothing to inject into. RuneLite has to make the game's own objects implement its
interfaces, because plugins get the game's objects. In Kraken the game's objects live in another process, so a plugin
always holds a Java handle (an address plus an identity check), and the handle's class can implement any interface
directly.

A declarative step closer to RuneLite's mixins is possible later: interfaces whose methods name mapping keys
(`@Field("Actor.fineX") int fineX();`) with an annotation processor generating the readers. It isn't worth doing now.
Most mirror methods replay a handler's logic (multi-NPC transforms, head bar cycles, bridge levels) rather than read one
field, and those are hand-written either way.

### 5.3 Packages

One Gradle module for now. The mapping stays at `api/src/main/resources/mappings`, which the updater, docs and
`.gitignore` all refer to.

```
api/  com.kraken.api                 KrakenApi, Client, Actor, NPC, Player, Renderable, WorldView, IndexedObjectSet,
                                     Scene, Tile, TileObject, GameObject, WallObject, DecorativeObject, GroundObject,
                                     TileItem, Projectile, ItemContainer, Item, ItemComposition, NPCComposition,
                                     ObjectComposition, Skill, GameState, WorldType, EquipmentInventorySlot, Point,
                                     Perspective, CollisionData, CollisionDataFlag
      com.kraken.api.coords          WorldPoint, LocalPoint, WorldArea
      com.kraken.api.widgets         Widget, WidgetType, WidgetUtil
      com.kraken.api.events          GameTick, ClientTick, GameStateChanged, NpcSpawned, ... (value classes)
      com.kraken.api.eventbus        EventBus, Subscribe, Subscriber
      com.kraken.api.gameval         InventoryID (more later, see §8.10)
      com.kraken.api.impl            ClientImpl, NpcImpl, PlayerImpl, ..., RuneLiteEventSource (package-private)
      com.kraken.api.lua             ClientOp (today's game.Client), ClientState, Camera, Graphics, World, Collision,
                                     InterfaceQuery, Engine, Scene, Entity, Npc, Player, MapCoord, FineCoord, IfType...
      com.kraken.api.lua.events      Events, Event, *Callback, EventPoller (today's com.kraken.api.events)
      com.kraken.api.lua.types       ObjType, NpcType, LocType, ConfigTypes... (today's com.kraken.api.config)
      com.kraken.api.mapping, .memory, .struct   unchanged

client/ com.kraken.client.plugins    Plugin, PluginDescriptor, PluginDependency, PluginManager
        com.kraken.client.plugins.*  built-in plugins, one package each
        com.kraken.client.plugins.config  Config, ConfigGroup, ConfigItem, ConfigSection, Range, Units, Alpha,
                                          ConfigManager, ConfigDescriptor
        com.kraken.client.events     PluginChanged, ConfigChanged, ApiStateChanged, ClientShutdown
        com.kraken.client.callback   ClientThread, GameTickCallback, CallbackInjector
        com.kraken.client.ui         ClientToolbar, NavigationButton, PluginPanel, ColorScheme (+ today's classes)
        com.kraken.client.ui.overlay Overlay, OverlayManager, OverlayLayer, OverlayPosition, OverlayPanel,
                                     OverlayUtil, components.* (+ today's GameOverlay, OverlayContext)
```

Why these moves:

- `com.kraken.api.config` becomes `lua.types`. In RuneLite "config" means plugin settings (`net.runelite.client.config`),
  and RuneLite calls cache definitions "compositions". Leaving the definitions in a package called `config` would
  confuse exactly the people this work is for.
- `game.Client` becomes `lua.ClientOp`. That frees the name `Client`, and it's what Lua calls it anyway.
- The Lua events move to `lua.events`. `com.kraken.api.events` then holds RuneLite-shaped event classes, as
  `net.runelite.api.events` does.

Name clashes between the layers (`lua.Player` and `api.Player`, `lua.Scene` and `api.Scene`) only meet inside
`com.kraken.api.impl` and the dev tools, so they're tolerable.

A later, optional step (phase 9) splits `api/` into an interfaces-only module and an implementation module, so plugins
can't reach `ProcessMemory` or the Lua mirror even by accident. That move touches the mapping path in about 14 files
(updater, docs, `.gitignore`), so it waits until the interfaces have settled.

### 5.4 `KrakenApi` after the change

```java
KrakenApi api = KrakenApi.attach(pid, exe, eventBus);  // the bus is optional; the client passes its own
api.client();      // com.kraken.api.Client, the RuneLite-shaped root
api.eventBus();    // where game events are posted
api.lua();         // LuaApi: clientOp(), clientState(), events(), engine(), camera(), graphics(), world(),
                   // collision(), interfaces(), scene(), configs(), screenSize()
api.invoke(task);  // run on the poll thread (ClientThread is built on this)
```

Dev tools change `api.client()` to `api.lua().clientOp()` and so on. That's a mechanical edit, and they keep showing
exactly what the Lua API reads, which is their job.

## 6. Name map

How each Kraken type or function maps to the RuneLite-shaped API.

| Kraken today (Lua mirror) | RuneLite-shaped | Note |
|---|---|---|
| `KrakenApi.client()` → `Client` (`osrs.ClientOp`) | `Client` interface | Lua mirror moves to `KrakenApi.lua().clientOp()` |
| `ClientState` | Folded into `Client` | `getGameState`, `getTickCount`, skills, vars |
| `Events`, `Event<C>`, `*Callback` | `EventBus`, `@Subscribe`, event classes | Lua callbacks still on `lua().events()` |
| `Entity` | `Actor` | |
| `Npc` | `NPC` | Pinned handle, §8.2 |
| `Player` | `Player` | Pinned handle |
| (none) | `Renderable` | Supertype of `Actor`, `Projectile`, `TileItem` |
| `MapCoord` (scene tile) | `WorldPoint` (world tile), scene tile via `LocalPoint.getSceneX/Y` | **Not a rename**, §7 |
| `FineCoord` | `LocalPoint`, plus height from `Perspective.getTileHeight` | `y` ↔ `z` swap |
| `Vector2` | `Point` (screen), `Dimension` (sizes) | Rounded to ints |
| `Colour` | packed `int` RGB or `java.awt.Color` | |
| `Loc` (record with a layer) | `TileObject`: `WallObject`, `DecorativeObject`, `GameObject`, `GroundObject` | One type per layer |
| `GroundObj` | `TileItem` | **Not** `GroundObject` |
| `Scene` (Java extra) | `Scene`, `Tile` | |
| `World` (collision namespace) | `WorldView.getCollisionMaps()` → `CollisionData[]` | The native `World` struct *is* RuneLite's `WorldView` |
| `CollisionMap`, `CollisionFlag` | `CollisionData`, `CollisionDataFlag` | Same bits |
| `Collision.lineOfSight/lineOfWalk` | Kraken extra on `WorldView` | |
| `Inventory`, `InvItem` | `ItemContainer`, `Item` | |
| `Client.INV_BACKPACK/WORN/BANK` | `gameval.InventoryID.INV/WORN/BANK` | |
| `Client.WornSlots` | `EquipmentInventorySlot` | Same slot numbers |
| `ClientState.Stats` | `Skill` | Same order, `SAILING` included; `SKILL_RUNECRAFTING` is RuneLite's `RUNECRAFT` |
| `ClientState.GAME_STATE_*` | `GameState` | Same numbers |
| `ClientState.ProfileType`, `getWorldFlags` | `Client.getWorldType()` → `EnumSet<WorldType>` | Same bits; `ProfileType` stays a Kraken extra |
| `ObjType`, `NpcType`, `LocType` | `ItemComposition`, `NPCComposition`, `ObjectComposition` | Adapters over the decoded types |
| `Engine.decode*`, `getObjType` | `Client.getItemDefinition/getNpcDefinition/getObjectDefinition` | |
| `InterfaceQuery`, `IfType`, `IfGroup` | `Client.getWidget`, `Widget` | §8.7 |
| `InterfaceCoordinates` | `Widget.getBounds()`; raw values as a Kraken extra | |
| `Camera` getters | `Client.getCameraX/Y/Z/Pitch/Yaw`, `getScale` | Axis and unit changes, §7.4 |
| `Graphics.worldToScreenCoord`, `projection()` | `Perspective.localToCanvas`, `getCanvasTilePoly`, … | |
| `Graphics.clientDimensions`, `osrs.screenSize` | `Client.getCanvasWidth/Height`, `getRealDimensions` | |
| `Projectile` record | `Projectile` interface | Axis changes |
| `ClientOp.getLocalPlayer` | `Client.getLocalPlayer()` | |
| `Npc.isLocalFollower` | `Client.getFollower()` | |

## 7. Coordinates

### 7.1 The spaces

| Space | RuneLite | Kraken today | Conversion |
|---|---|---|---|
| World tile | `WorldPoint(x, y, plane)` | world `MapCoord` (only `Scene.toWorld` results and projectile endpoints) | identity, renaming `z` → `y`, `level` → `plane` |
| Scene tile | `int sceneX, sceneY` (`LocalPoint.getSceneX()`, `Tile.getSceneLocation()`) | `MapCoord` from actors, locs, items | world = scene + map base |
| Local (fine) | `LocalPoint(x, y, worldView)`, 128 per tile, scene-relative | `FineCoord(x, y = height, z)` | `LocalPoint(fine.x, fine.z)`; height dropped |
| Height | `Perspective.getTileHeight(client, lp, plane)`, more negative is higher | `FineCoord.y`, more negative is higher | Same sign, same units |
| Canvas | `Point(x, y)`, client pixels | `Vector2`, the client's own (DPI-scaled) pixels | Round to int |

The Lua mirror keeps `MapCoord` and `FineCoord` as they are. The RuneLite layer never exposes either.

### 7.2 Value types

```java
@Value
public class WorldPoint {
    int x;      // east, world tile
    int y;      // north, world tile (Lua's z)
    int plane;  // 0-3 (Lua's level)

    public static WorldPoint fromLocal(Client client, LocalPoint local);
    public static WorldPoint fromScene(WorldView view, int sceneX, int sceneY, int plane);
    public static WorldPoint fromRegion(int regionId, int regionX, int regionY, int plane);
    public int distanceTo(WorldPoint other);   // Chebyshev; Integer.MAX_VALUE on another plane, as RuneLite
    public int distanceTo2D(WorldPoint other);
    public WorldPoint dx(int dx);              // also dy, dz
    public int getRegionID();                  // also getRegionX, getRegionY
    public boolean isInArea(WorldArea... areas);
    public WorldArea toWorldArea();
}

@Value
public class LocalPoint {
    int x;          // east, 1/128 tile, scene-relative
    int y;          // north (Lua's fine z)
    int worldView;  // -1 for the top-level world, as RuneLite's WorldView.TOPLEVEL

    public static LocalPoint fromWorld(Client client, WorldPoint world);       // null outside the scene
    public static LocalPoint fromWorld(WorldView view, WorldPoint world);
    public static LocalPoint fromScene(int sceneX, int sceneY, WorldView view); // tile centre: x * 128 + 64
    public int getSceneX();                    // x >> 7
    public int getSceneY();
    public int distanceTo(LocalPoint other);   // Integer.MAX_VALUE across world views, as RuneLite
    public LocalPoint dx(int dx);              // also dy, plus(dx, dy)
}
```

`WorldArea(x, y, width, height, plane)` comes with them, with `contains`, `distanceTo` and `intersectsWith`. Its
`hasLineOfSightTo(WorldView, …)` and `canTravelInDirection(…)` can delegate to the client's own line tests
(`Collision.lineOfSight`/`lineOfWalk`). RuneLite computes those in Java, so results can differ at edge cases, and the
Javadoc says so.

These shapes follow RuneLite's current ones. `LocalPoint` carries a world view id. The `Client`-taking overloads that
RuneLite has deprecated (`fromScene(Client, …)`, `isInScene(Client)`, `LocalPoint(x, y)`) are left out, and scene
checks go through `WorldView.contains(WorldPoint | LocalPoint)`. Constants go in `Perspective`, as in RuneLite:
`LOCAL_COORD_BITS = 7`, `LOCAL_TILE_SIZE = 128`, `LOCAL_HALF_TILE_SIZE = 64`, `SCENE_SIZE = 104`.

### 7.3 Where conversions happen

- `Actor.getWorldLocation()` is `ClientOp.npcCoord`/`playerCoord` plus `Scene.getBase()`.
- `Actor.getLocalLocation()` is `npcCoordFine`/`playerCoordFine` with `y` dropped and `z` renamed to `y`.
- `TileObject` and `TileItem` locations come from the `MapCoord` the scene listing returns, converted the same way.
- `Projectile.getSourcePoint()`/`getTargetPoint()` are world tiles already, so they convert directly.
- The map base is read for each conversion. A plugin that converts many points in one go can take it once from
  `client.getTopLevelWorldView().getBaseX()/getBaseY()`.

Things to state in Javadoc, because they differ from RuneLite:

- **Instances.** RuneLite's `WorldPoint.fromLocalInstance` maps an instance back to the template's real-world tiles
  using the instance chunk table. Kraken doesn't map that table, so only `fromLocal` exists. Inside an instance it gives
  the instance's own coordinates, as RuneLite's `fromLocal` does.
- **Bridges.** The Lua mirror reports some loc and item levels one lower under bridges, as the client does (see the
  `locCoord`/`objCoord` notes in the coverage doc). `TileObject.getPlane()` reports the scene level the tile is on,
  which is what RuneLite's `Tile.getPlane()` reports. The layer that changes the level gets the test.
- **Actor height.** `npcCoordFine().y` is the actor's ground height including a ground decoration's offset.
  `Perspective.getTileHeight` is the bare tile height. They differ on a few decorated tiles. RuneLite overlays use the
  tile height, so the RuneLite layer does too.

### 7.4 Camera

| RuneLite | From | Conversion |
|---|---|---|
| `getCameraX()` | `Camera.getPos().x()` | none (local x) |
| `getCameraY()` | `Camera.getPos().z()` | north axis |
| `getCameraZ()` | `Camera.getPos().y()` | height |
| `getCameraPitch()`, `getCameraYaw()` | `Camera.getPitch()/getYaw()` | native is 16384 per turn, RuneLite 2048: divide by 8 |
| `getScale()` | `Camera.getScale()` (`Client.zoomA`) | none |

The divide-by-8 is from the coverage doc. Whether the zero direction and the sense of rotation also match RuneLite's
needs a live check before release (phase 8).

*As built (phase 8):* no division. RuneLite now measures camera angles in JAU14, 16384 per turn (its `Perspective`
indexes `SINE14[client.getCameraPitch()]`), which is the client's own unit, so `getCameraPitch()`/`getCameraYaw()`
return the native values. The native projection (`Graphics.Projection.project`) has the same structure as RuneLite's
`localToCanvas` (yaw turns x and north, then pitch turns north and height), so the angles agree as long as the trig the
scene stores comes from `Client.camera.angles` unchanged. That is the live check that remains.

## 8. Core interfaces

### 8.1 `Client`

The root interface, injected into plugins as RuneLite's is. Every method maps to existing reads:

```java
public interface Client {
    GameState getGameState();                  // ClientState.getGameState
    int getTickCount();                        // getTickCount
    int getGameCycle();                        // globals.cycle (20 ms client cycles)
    EnumSet<WorldType> getWorldType();         // getWorldFlags, RuneLite's bit table

    Player getLocalPlayer();
    NPC getFollower();                         // the NPC for which Npc.isLocalFollower holds
    WorldView getTopLevelWorldView();
    WorldView getWorldView(int id);            // -1 for the top level
    List<NPC> getNpcs();                       // = getTopLevelWorldView().npcs(), see below
    List<Player> getPlayers();
    Scene getScene();

    int getRealSkillLevel(Skill skill);        // getStatBaseLevel
    int getBoostedSkillLevel(Skill skill);     // getStatEffectiveLevel
    int getSkillExperience(Skill skill);       // getStatXP
    int[] getRealSkillLevels();                // getStatsAll
    int[] getBoostedSkillLevels();
    int[] getSkillExperiences();
    int getTotalLevel();                       // sum of real levels

    int getVarbitValue(int varbitId);          // getVarbit
    int getVarpValue(int varpId);              // getVarp
    int[] getVarps();
    int getVarcIntValue(int varcId);           // getIntegerVarc
    String getVarcStrValue(int varcId);        // getStringVarc

    ItemContainer getItemContainer(int inventoryId);  // getInventory
    ItemComposition getItemDefinition(int itemId);    // Engine.decodeObjType
    NPCComposition getNpcDefinition(int npcId);       // decodeNpcType
    ObjectComposition getObjectDefinition(int objectId); // decodeLocType

    Widget getWidget(int componentId);         // packed group << 16 | child
    Widget getWidget(int groupId, int childId);

    List<Projectile> getProjectiles();         // getProjectiles

    int getCameraX();                          // and Y, Z, Pitch, Yaw, see §7.4
    int getScale();
    int getCanvasWidth();                      // Graphics.clientDimensions
    int getCanvasHeight();
    Dimension getRealDimensions();             // osrs.screenSize (window pixels)
}
```

In RuneLite, `getNpcs()`, `getPlayers()` and `getScene()` are deprecated default methods that delegate to
`getTopLevelWorldView()`, and the map base and plane live only on `WorldView`. Kraken does the same, but without the
deprecation for now: until sub-world views exist (§8.3), the top-level view is the only one, and these are the calls
most RuneLite plugins still make.

Kraken extras on `Client`: `isLoggedIn()`, `getProfileType()`, `areProjectilesRendered()` and `getOpenWidgetGroups()`.

*As built (phases 7-8):* `getScene()` (non-default, like `getNpcs()`), `getWidget(int)`, `getWidget(int, int)`,
`getProjectiles()`, the camera getters and the extras above are there. `getProjectiles()` returns a `Deque`, as
RuneLite's does, rather than a `List`. `getCollisionMaps()` is only on `WorldView`, as RuneLite now has it.
Phase 6 added `getCameraProjection()`, which reads the camera once (Lua's `Graphics.projection()`) and is what
`Perspective` projects with, since `Perspective` only sees the interfaces.

### 8.2 `Renderable`, `Actor`, `NPC`, `Player`

```java
public interface Renderable {
    boolean isHidden();          // Kraken extra: a Lua plugin hid it (render3D(false) / renderProjectiles(false))
}

public interface Actor extends Renderable {
    String getName();
    boolean isInteracting();     // RuneLite's boolean, not Lua's index; see the notes
    Actor getInteracting();      // NPC or Player; null for nothing or a world entity (boat)
    WorldPoint getWorldLocation();
    LocalPoint getLocalLocation();
    WorldArea getWorldArea();    // location and footprint
    int getFootprintSize();      // 1 for players, the type's size for NPCs
    WorldView getWorldView();
    boolean isDead();
    Polygon getCanvasTilePoly(); // via Perspective; NPCs use their size
    Point getCanvasTextLocation(Graphics2D graphics, String text, int zOffset);

    boolean isHealthbarRendered(); // Kraken extras, mirroring Lua Entity
    boolean isHeadbarEmpty();
    boolean isRendered2D();
}

public interface NPC extends Actor {
    int getId();                 // base type id (npcTypeId)
    int getIndex();
    NPCComposition getComposition();            // base type
    NPCComposition getTransformedComposition(); // follows the multi-NPC varbit/varp, as npcName does
    boolean isFollower();        // Kraken extra
    int getSpawnCycle();         // Kraken extra (npcCreationCycle)
}

public interface Player extends Actor {
    int getId();                 // player index, as RuneLite
    boolean isOnlineFriend();    // Kraken extra, Lua isOnlineFriend; no isFriend(), see the note below
    boolean isFriendsChatMember(); // Lua isFriendChat
    boolean isClanMember();      // Lua isGroup
    boolean isIgnored();         // Kraken extra
}
```

Notes:

- **`Renderable` is small on purpose.** RuneLite's `Renderable` is about models (`getModel`, `getModelHeight`), and
  Kraken can't read models. The type still earns its place: it's the supertype RuneLite developers expect, and it carries
  the one property Kraken can read for everything drawn in the scene, the hidden flag that Lua's `render3D` and
  `renderProjectiles` write.
- **`Player.isFriend()`.** Lua's `isOnlineFriend` is "on the friend list *and* online". RuneLite's `isFriend` is "on
  the friend list". The reader can't tell an offline friend apart from a stranger without new reads, so the method is
  named `isOnlineFriend()` (a Kraken extra), and `isFriend()` is left out until the friend list read can answer it.
  This is principle 3 in action.
- **`isInteracting()` has the same name and a different meaning.** Lua's `Entity.isInteracting()` returns the target's
  index (or -1). RuneLite's `Actor.isInteracting()` returns a boolean. The RuneLite layer uses RuneLite's meaning
  (`index != -1`). `getInteracting()` turns the index and `getInteractingType()` into a handle, with a world entity
  target giving null until `WorldEntity` exists. Kraken extras `getInteractingIndex()` and `getInteractingType()` keep
  the raw values.

**Pinned handles.** Lua objects (and today's `Npc`/`Player`) are live handles on an *index*: if the client reuses the
index for another actor, the handle follows the new one. RuneLite's objects are the actors themselves. A despawned
`NPC` still answers `getId()` and `getName()`, which plugins rely on in `onNpcDespawned`. RuneLite-layer handles copy
that:

- A handle pins `(index, address)`, plus the spawn cycle for NPCs and the name for players, since an allocator can hand
  the same address to the next occupant.
- Each getter checks that the slot still holds the pinned actor, then reads live.
- Once the actor is gone, getters return the last values read. The event tracker refreshes id, name and location once
  per game tick for tracked actors. A despawned NPC therefore reports where it was and what it was, like RuneLite's.
- `Client` caches one handle per pin, so `getNpcs()` returns the same instances from call to call. Identity equality
  works and `Set<NPC>`/`Map<NPC, …>` behave as in RuneLite.

The Lua-layer `Npc`/`Player` keep their index semantics. Only the wrapper differs.

### 8.3 `WorldView` and `IndexedObjectSet`

The client's `World` struct (`World.npcs`, `players`, `baseX/Z`, `plane`, `scene`) is what RuneLite calls a
`WorldView`: one per loaded world, with the top level plus sub-worlds such as boats.

```java
public interface WorldView {
    int getId();                 // -1 for the top level
    boolean isTopLevel();
    Scene getScene();
    IndexedObjectSet<NPC> npcs();
    IndexedObjectSet<Player> players();
    int getPlane();
    int getBaseX();
    int getBaseY();
    int getSizeX();
    int getSizeY();
    boolean contains(WorldPoint point);
    boolean contains(LocalPoint point);
    CollisionData[] getCollisionMaps();      // World.getCollisionMaps
    boolean lineOfSight(WorldPoint a, WorldPoint b);  // Kraken extras (Collision); WorldArea delegates here
    boolean lineOfWalk(WorldPoint a, WorldPoint b);
}

public interface IndexedObjectSet<T> extends Iterable<T> {
    T byIndex(int index);
    Stream<T> stream();
}
```

`WorldView.getTileHeight(x, y, plane)` is a Kraken extra (phase 6) over the Java `Scene.getGroundHeight`; it backs
`Perspective.getTileHeight`, where RuneLite reads the scene's tile heights array.

Phase 2 ships the top-level view only, matching what the Lua `Npc`/`Player` handles resolve. Sub-world views come later
(`ActorReader.worlds()` already lists them).

### 8.4 Items and inventories

```java
@Value
public class Item {
    int id;        // -1 for an empty slot
    int quantity;  // 0 for an empty slot
}

public interface ItemContainer {
    int getId();
    Item[] getItems();
    Item getItem(int slot);       // null if the slot is empty or out of range
    boolean contains(int itemId);
    int count(int itemId);        // invTotal
    int count();                  // occupied slots
    int size();                   // invSize, from the inventory's definition
    int find(int itemId);         // first slot holding it, or -1
}
```

`ItemContainer` is a snapshot taken when it's fetched, as `Inventory` is today, and it says so in its Javadoc. Plugins
fetch it again each tick, which is what RuneLite plugins do anyway.

The compositions are adapters over the decoded types:

| RuneLite | Kraken type | Getters |
|---|---|---|
| `ItemComposition` | `ObjType` | `getId`, `getName`, `getPrice` (cost), `isStackable`, `isTradeable`, `isMembers`, `getNote`/`getLinkedNoteId` (cert), `getPlaceholderId`, `getInventoryActions` (`getOps`), `getWeight`, `getIntValue`/`getStringValue` (params). Extras: `getGroundActions`, `isEquipable`, `isTwoHanded`, `getSlot` |
| `NPCComposition` | `NpcType` | `getId`, `getName`, `getSize`, `getActions` (resolved ops), `isFollower`, params. Extras: `getCategory`, `getStats` |
| `ObjectComposition` | `LocType` | `getId`, `getName`, `getActions`, `getSizeX` (width), `getSizeY` (length), params. Extras: `blocksWalk`, `blocksRange` |

### 8.5 Skills, game state, vars

- `Skill` enum: RuneLite's order and names (`ATTACK` … `SAILING`, 24 skills), with `getName()`. It's
  `ClientState.Stats` with the `SKILL_` prefix dropped, except `SKILL_RUNECRAFTING`, which RuneLite calls `RUNECRAFT`.
  The order, and so the ordinal, is the same as the stat ids.
- `GameState` enum: `UNKNOWN(-1)`, `STARTING(0)`, `LOGIN_SCREEN(10)`, `LOGIN_SCREEN_AUTHENTICATOR(11)`,
  `LOGGING_IN(20)`, `LOADING(25)`, `LOGGED_IN(30)`, `CONNECTION_LOST(40)`, `HOPPING(45)`, with `of(int)`. `UNKNOWN`
  and `STARTING` are what the client reports before the API is attached (§10.4). Whether the native client ever sets 11
  is untested, so `of(int)` maps any value it doesn't know to `UNKNOWN`.
- Vars: `getVarbitValue`, `getVarpValue`, `getVarcIntValue`, `getVarcStrValue`. RuneLite's `Varbits`/`VarPlayer`
  constants classes are deprecated in favour of `gameval.VarbitID`/`VarPlayerID`. See §8.10 for gamevals.

### 8.6 Scene, tiles and tile objects

```java
public interface Scene {
    Tile[][][] getTiles();       // [plane][x][y], lazy handles; reading every tile is expensive
    int getBaseX();
    int getBaseY();
    int getWorldViewId();
    Tile getTile(int plane, int sceneX, int sceneY);  // Kraken extra, avoids building the whole array
}

public interface Tile {
    WorldPoint getWorldLocation();
    LocalPoint getLocalLocation();
    Point getSceneLocation();
    int getPlane();
    WallObject getWallObject();             // Loc.WALL
    DecorativeObject getDecorativeObject(); // Loc.WALL_DECOR
    GameObject[] getGameObjects();          // Loc.GAME_OBJECT, origin tile only
    GroundObject getGroundObject();         // Loc.GROUND_DECOR
    List<TileItem> getGroundItems();        // today's GroundObj, pile order
}

public interface TileObject {
    int getId();
    int getX();                             // local x (tile centre, or the footprint's centre for game objects)
    int getY();                             // local y
    int getZ();                             // ground height there
    int getPlane();
    WorldView getWorldView();
    WorldPoint getWorldLocation();
    LocalPoint getLocalLocation();
    long getHash();                         // Kraken's own packing (x, y, plane, layer, id)
    Point getCanvasLocation();
    Point getCanvasLocation(int zOffset);
    Polygon getCanvasTilePoly();
    Point getCanvasTextLocation(Graphics2D graphics, String text, int zOffset);
}

public interface GameObject extends TileObject {
    int sizeX();                            // LocType width
    int sizeY();                            // LocType length
    Point getSceneMinLocation();            // origin tile
    Point getSceneMaxLocation();            // origin + size - 1
}

public interface WallObject extends TileObject { }
public interface DecorativeObject extends TileObject { }
public interface GroundObject extends TileObject { }

public interface TileItem extends Renderable {
    int getId();
    int getQuantity();
}
```

Notes:

- **`GroundObj` → `TileItem`, not `GroundObject`.** RuneLite's `GroundObject` is floor decoration (flowers, rugs), and
  RuneLite's `TileItem` is an item on the floor. Kraken's `GroundObj` is the second. Naming it `GroundObject` would make
  every ported ground-item plugin silently look at decorations.
- **Locs become values.** A `TileObject` holds the `Loc` it came from (id, layer, scene tile). Name and actions come from
  `ObjectComposition` by id, so the RuneLite layer never touches the Lua mirror's "active loc".
- **Size and rotation.** `GameObject.sizeX/sizeY` are the type's width and length. `ClientOp.locWidth` documents that
  they aren't turned for the loc's rotation. RuneLite's are, so the Javadoc says so. Rotation isn't read yet (§8.11).
- **`ItemLayer` is left out.** RuneLite's `ItemLayer` is the three rendered items of a pile. Kraken reads the whole
  pile, and `Tile.getGroundItems()` covers what plugins use `ItemLayer` for.
- **Cost.** `getTiles()` returns lazy handles, so building it is cheap. Calling `getGameObjects()` on all ~43,000 tiles
  isn't. Plugins should track objects with the spawn events (§9.4), as RuneLite plugins do, and the Javadoc says so.

*As built (phase 7):*

- `Scene` adds two Kraken extras: `getSizeX()`/`getSizeY()`, and `getTileObjects()`, every object in one pass (the
  whole-scene read the object events make each tick, ~43,000 reads). A plugin started mid-session uses it to learn the
  scene, since walking `getTiles()` costs several reads per tile and getter. The Lua mirror gained the Java-only
  `Scene.getAllObjs()` (the pile grid read a column at a time, a few hundred reads) for the item events.
- A `Tile` handle holds its scene position and the map base it was made with. Its object and item getters list that one
  tile, after checking the base hasn't moved; on a reloaded scene they read as empty. `getGroundItems()` returns an
  empty list where RuneLite returns null, and makes new item handles each call. `getGameObjects()` holds just the
  objects (no null slots), on their south-west tile only.
- `TileObject`s are values (kind, loc, map base): equal across reads, so sets filled from spawns empty on despawns.
  `getHash()` uses RuneLite's bit layout (the native tag's too: id at bit 20, type at 16), built from the fields;
  RuneLite's bit 19 isn't read and is 0. `getWorldLocation()` is the footprint's centre tile rounded south-west, as
  RuneLite documents; `getCanvasTilePoly()` outlines a game object's whole footprint. The four subtypes add nothing:
  their orientation, offsets and config bits aren't read.
- `TileItem` handles have identity: the item events keep one per stack and update its quantity. `isHidden()` is false,
  since no Lua function hides ground items.

### 8.7 Widgets

`Widget` wraps `IfType`. The RuneLite names map like this:

| `Widget` | From `IfType` / `InterfaceQuery` |
|---|---|
| `getId()` | `getPackedId()` |
| `getIndex()` | `getSubId()` (dynamic children) |
| `getType()`, `getContentType()` | `getType()`; content type isn't mapped |
| `getParent()`, `getParentId()` | `getParent()` |
| `getChildren()`, `getDynamicChildren()` | `getChildren()` (dynamic only, as in the client) |
| `getStaticChildren()` | group components whose `layerId` is this id |
| `getChild(int index)` | dynamic child by sub id |
| `isSelfHidden()` | `isHidden()` (own flag) |
| `isHidden()` | own flag or any parent's, as RuneLite computes it |
| `getText()`, `getName()` | `getText()`, `getBaseOpName()` |
| `getTextColor()` | `getColour().rgb()` |
| `getSpriteId()` | `getGraphic()` |
| `getItemId()`, `getItemQuantity()` | `getLinkObjType()`, `getLinkObjCount()` |
| `getModelId()` | `getModel1Id()` |
| `getActions()` | `getOpNames()` |
| `getOpacity()` | `getTransparency()` |
| `isFilled()` | `isFill()` |
| `getRelativeX/Y()`, `getWidth()`, `getHeight()` | `getX/Y()`, `getWidth()`, `getHeight()` |
| `getOriginalX/Y/Width/Height()` | `getDataX/Y/Width/Height()` |
| `getXPositionMode()`, `getYPositionMode()`, `getWidthMode()`, `getHeightMode()` | `getModeX()`, `getModeY()`, `getModeWidth()`, `getModeHeight()` |
| `getScrollX/Y()`, `getScrollWidth/Height()` | `getScrollPos()`, `getScrollSize()` |
| `getCanvasLocation()`, `getBounds()` | `getScreenCoords()` → `Point`; bounds from it plus size |

Kraken extras: `getSourceName()`, `isRendering()`, and the raw `InterfaceCoordinates` of the group.

A widget is a snapshot of one read of the component, as `IfType` is, and the Javadoc says so. RuneLite widgets are live,
but RuneLite plugins call `client.getWidget(...)` where they need it rather than keeping widgets, so this rarely shows.
If it does, the adapter can become a pinned live handle the way actors are (one read per getter), and nothing outside
`impl` changes.

`WidgetUtil.packComponentId(group, child)`, `componentToInterface`, `componentToId` mirror RuneLite's. Interface ids
come from `gameval.InterfaceID` (§8.10).

*As built (phase 8):* `com.kraken.api.widgets` has `Widget`, `WidgetUtil`, `WidgetType`, `WidgetPositionMode` and
`WidgetSizeMode` (the last three are cache values, shared with Lua's `IfTypes`, `AlignMode`, `SizeMode`). Notes:

- `getChildren()` is indexed by child index with nulls, as RuneLite's, and holds the *direct* children: the native
  client can nest a dynamic layer under another, so a nested child sits under its own layer, not the static root.
  `getChild(index)` reads one slot through a new Java extra, `IfType.getChild(subId)`, rather than every child.
  `getChildren()` is empty rather than null.
- `isHidden()` is RuneLite's rule: the own flag, any parent's, and a group's top-level widget counts as hidden while
  its group isn't in the open-interface map. A group's top-level widgets have no parent (`getParentId()` -1): Kraken
  doesn't read which component a group is opened in.
- `getActions()` turns empty op slots into null and is null with no ops, as RuneLite's. The model getters
  (`getModelType`, `getAnimationId`, `getRotationX/Y/Z`, `getModelZoom`) and the sprite flags are included, since
  `IfType` has them.
- The raw `InterfaceCoordinates` extra became `getGroupClipBounds()`, the group's clip rectangle, so no Lua type
  leaks into the RuneLite layer. Two widgets over the same native component are equal.

### 8.8 Projectiles

`Projectile extends Renderable`, over today's record:

| RuneLite-shaped | From | Note |
|---|---|---|
| `getId()` | `id` (spot anim) | |
| `getX()`, `getY()`, `getZ()` | `x`, `z`, `y` | north and height swap |
| `getStartCycle()`, `getEndCycle()`, `getRemainingCycles()` | same | |
| `getSlope()`, `getStartPos()` | `slope`, `startDistance` | |
| `getStartHeight()`, `getEndHeight()` | endpoint heights | |
| `getSourcePoint()`, `getTargetPoint()` | endpoint tiles | already world tiles |
| `getSourceLevel()`, `getTargetLevel()` | endpoint tile levels | |
| `getSourceActor()`, `getTargetActor()` | endpoint actor refs → handles | |
| `getOrientation()` | `yaw` | |

These are RuneLite's current names. It has deprecated the older `getInteracting`, `getTarget`, `getX1`/`getY1`,
`getFloor` and `getHeight`, so Kraken skips them. Kraken extras: `getPitch()`, `isStarted()`.

*As built (phase 8):* a snapshot of one read, as the record is; `getRemainingCycles()` reads the cycle counter when
called, as RuneLite's subtracts the current cycle, and the endpoint actors are looked up when asked for.

### 8.9 `Perspective`, collision, camera

- **`Perspective`**: static helpers built on `Graphics.projection()` and `Scene.getGroundHeight`. They are
  `localToCanvas(client, lp, plane)`, `localToCanvas(client, lp, plane, zOffset)`, `getCanvasTilePoly(client, lp)`,
  `getCanvasTileAreaPoly(client, lp, size)`, `getCanvasTextLocation(client, g, lp, text, zOffset)` and
  `getTileHeight(client, lp, plane)`. They return `null` behind the camera, as RuneLite's do. One call reads the camera
  once, like today's `Projector`.
  *As built (phase 6):* `localToCanvas`, `getCanvasTilePoly`, `getCanvasTileAreaPoly`, `getCanvasTextLocation`,
  `getCanvasImageLocation` and `getTileHeight`, over `Client.getCameraProjection()` and `WorldView.getTileHeight`. Each
  call reads the camera once plus a height per corner; a tile outline is a few dozen reads. One small difference: each
  corner checks the bridge flag of its own tile, where RuneLite checks the centre tile's for all four. `Actor` gained
  `getCanvasTilePoly()`, `getCanvasTextLocation(...)` and `getCanvasImageLocation(...)` on top of them.
- **`CollisionData`**: `getFlags()` returns `int[][]` indexed `[sceneX][sceneY]`, built from `CollisionMap` with the
  origin subtracted. `CollisionDataFlag` holds RuneLite's names over today's `CollisionFlag` values, which are already
  the same bits.

### 8.10 Game value constants (`gameval`)

RuneLite's `net.runelite.api.gameval` (`ItemID`, `NpcID`, `ObjectID`, `InterfaceID`, `InventoryID`, `VarbitID`,
`VarPlayerID`, …) replaced its older hand-written constants. The ids are cache ids, the same in the native client.
There are two options:

1. **Generate Kraken copies** from RuneLite's sources, keeping RuneLite's BSD 2-clause notice, into
   `com.kraken.api.gameval`. Pin the copy to the gameval update for the cache revision the native client runs. RuneLite
   updates gamevals per revision (its latest is "rev241", the revision of today's 241.3 mapping). Plugins get the names
   they already know. Recommended.
2. **Depend on `runelite-api`** just for `gameval`. It's smaller to maintain, but it puts a second `Client`, `NPC` and
   `WorldPoint` on every plugin's classpath, and the IDE will happily import the wrong one.

Phase 2 hand-writes `InventoryID` (three constants). The generator comes in phase 8.

*As built (phase 8):* option 1. `./gradlew :api:generateGameval` downloads RuneLite's `gameval` sources at a pinned
commit (default `4baec1bd46db`, "Update GameVals to 2026-10-14-rev241"; `-PgamevalCommit=<sha>` overrides), renames
the package, adds RuneLite's BSD 2-clause notice and writes them to `api/src/main/java/com/kraken/api/gameval`. The
13 files (about 14 MB) are committed like the mapping, so builds don't need the network; the task isn't part of the
build and is run when the client's revision moves. The generated `InventoryID` replaces the hand-written one.

### 8.11 What isn't there, and why

| RuneLite | Why not | Path |
|---|---|---|
| `Actor.getAnimation`, `getPoseAnimation`, `getOrientation`, `getGraphic`, `getSpotAnims`, `getOverheadText`, `getHealthRatio/Scale`, `getLogicalHeight`, `getCombatLevel` | Not read by the Lua mirror. The updater maps `Actor.anim` and `orientationCandidate` as anchors, but nothing verifies their meaning | Updater + mirror work, then one getter each |
| `GameObject.getOrientation`, `WallObject.getOrientationA/B`, `getConfig` | Loc rotation and config bits aren't read | Updater + mirror |
| `TileItem.getDespawnTime`, `getOwnership`, `isPrivate` | Not read | Updater + mirror |
| `Overlay.drawAfterInterface`, `drawAfterLayer` | Kraken draws in its own window, so it can't slot in between the game's layers | None |
| `Model`, convex hulls, `getConvexHull`, `getClickbox` | Models aren't read | Large; maybe never |
| Menus (`MenuEntry`, `MenuOptionClicked`, `MenuEntryAdded`) | Input and menus are inside the game process | In-process backend only |
| `KeyManager`, `MouseManager`, `Keybind` config | The game window belongs to another process, so Java never receives its input | Global hooks, out of scope |
| Setters (`setHidden`, `setCameraX`, widget changes, `runScript`) | Read-only by design | In-process backend (Lua `render3D`, `Camera.setPos`) |
| `ChatMessage`, `ChatMessageManager` | Chat isn't read | Needs a chat buffer read |
| `ItemManager.getImage`, sprites | Needs sprite decoding from the cache | Possible later (config archive reader) |
| `WorldPoint.fromLocalInstance` | Instance chunk table isn't read | Updater + mirror |
| `Player.getPlayerComposition`, `getCombatLevel`, `getOverheadIcon`, `getSkullIcon`, `getTeam` | Not read | Updater + mirror |
| `ItemLayer`, `GraphicsObject`, `GraphicsObjectCreated` | Not read (spot anims are a planned dev tool) | Updater + mirror |

## 9. Events and the `EventBus`

### 9.1 The bus

```java
public class EventBus {
    public void register(Object subscriber);    // finds its @Subscribe methods
    public void unregister(Object subscriber);
    public <T> Subscriber register(Class<T> type, Consumer<? super T> handler, float priority);
    public void unregister(Subscriber subscriber);
    public void post(Object event);             // runs subscribers on the calling thread, highest priority first
    public boolean hasSubscribers(Class<?> type);
    public void addSubscriptionListener(Consumer<Class<?>> onChange); // for demand-driven tracking
}

@Retention(RUNTIME)
@Target(METHOD)
public @interface Subscribe {
    float priority() default 0;
}
```

- `register` walks the class and its superclasses for methods annotated `@Subscribe` that take one parameter, of any
  visibility, as RuneLite's does. It builds invokers once (`LambdaMetafactory`, falling back to `MethodHandle`).
- Posting reads a copy-on-write subscriber table, so registering from the EDT while the poll thread posts is safe.
- A subscriber that throws is logged with its class name and skipped. Dispatch continues.
- **Dispatch by type and supertype.** RuneLite dispatches on the event's exact class. Kraken also matches interfaces the
  event implements (the lookup is cached per class, so it costs one map read per post). Events stay value classes like
  RuneLite's, and the four object-spawn events can share an interface, so one handler can take all four:

  ```java
  @Subscribe
  public void onTileObjectSpawned(TileObjectSpawned event)  // fires for game, wall, decorative and ground objects
  ```

  This is the "events can be interfaces" option, without making RuneLite-style code any different.
- The bus lives in `api/` so the API can post game events to it with or without the client. The client creates one
  bus, passes it to `KrakenApi.attach`, and posts its own events on the same instance.

### 9.2 Event classes

Lombok `@Value` or `@Data` classes, whichever RuneLite uses for the same event, so the getters read `event.getNpc()` as
in RuneLite. Field-less events (`GameTick`, `ClientTick`, `CanvasSizeChanged`) are empty classes, and Kraken posts one
shared instance of each.

```java
@Value
public class NpcSpawned {
    NPC npc;
}

@Value
public class StatChanged {
    Skill skill;
    int xp;
    int level;         // real (base) level
    int boostedLevel;
}
```

### 9.3 Posting custom events from the client

Any object can be posted, so a new client event is just a class. For example, RuneLite's `ScreenshotTaken`:

```java
@Value
public class ScreenshotTaken {
    File path;
    BufferedImage screenshot;
}

eventBus.post(new ScreenshotTaken(path, screenshot));
```

Client events start with:

| Event | When |
|---|---|
| `PluginChanged(plugin, loaded)` | after a plugin starts or stops. This also covers "plugin disabled" |
| `ConfigChanged(group, key, oldValue, newValue)` | after a config value is set or unset. RuneLite's also has a `profile`, which Kraken leaves null because it has no profiles |
| `ApiStateChanged(state, detail)` | `ProcessAttacher` changes state (waiting, attached, unsupported build, failed). Kraken-specific |
| `ClientShutdown` | the frame is closing. As in RuneLite, it carries a task queue: plugins add a `Future` to it and the client waits briefly for each |

`ScreenshotTaken`, `NotificationFired`, `OverlayMenuClicked`, `ProfileChanged` and the like can be added later without
touching the bus.

The same bus is the way in for events the reader can't see. An in-process backend could post chat, ops and interface
loads under RuneLite's names. The Lua events it would forward are `ON_CHAT_TRANSMIT` → `ChatMessage` and
`ON_IF_LOAD` → `WidgetLoaded`. `ON_NPC_OP`, `ON_LOC_OP` and similar would become a Kraken op event, since RuneLite's
`MenuOptionClicked` assumes a menu Kraken doesn't have. Plugins wouldn't change.

### 9.4 Game events: what they come from

| RuneLite-shaped event | From | Semantics |
|---|---|---|
| `GameTick` | `ON_GAME_TICK` | Posted last in a poll, after the tick's other events, as RuneLite posts it after the tick's packets |
| `ClientTick` | every poll (~20 ms) | Close to RuneLite's per-client-cycle tick, but not locked to the client's cycle |
| `GameStateChanged(gameState)` | raw `Client.gameState` moves | Every change of the raw value, as in RuneLite, not Lua's five triggers. If the map base moves without a polled `LOADING` (a load between two polls), it posts `LOADING` then `LOGGED_IN`, because RuneLite plugins clear their caches on `LOADING` |
| `NpcSpawned`, `NpcDespawned` | `ON_NPC_LOAD/UNLOAD` diff | The despawned NPC keeps its last-known values (§8.2) |
| `PlayerSpawned`, `PlayerDespawned` | diff of `getPlayerIdAll` | New synthesised event, same technique as NPCs. No Lua equivalent |
| `ItemContainerChanged(containerId, itemContainer)` | `ON_INV_TRANSMIT` | Carries the **whole** container, as RuneLite does; Lua's carries only changed slots |
| `StatChanged(skill, xp, level, boostedLevel)` | `ON_STAT_TRANSMIT` | One event per changed skill |
| `VarbitChanged(varpId, varbitId, value)` | `ON_VAR_TRANSMIT` + `ON_VARBIT_UPDATE` | See below |
| `CanvasSizeChanged` | `ON_SCREEN_RESIZE` | |
| `GameObjectSpawned/Despawned`, `WallObjectSpawned/Despawned`, `DecorativeObjectSpawned/Despawned`, `GroundObjectSpawned/Despawned` (each `tile`, object) | loc scan | Unlike Lua, **also fires for every object of a newly loaded scene**, as RuneLite does. Plugins clear on `GameStateChanged(LOADING)` and re-learn from the spawns |
| `ItemSpawned`, `ItemDespawned` (`tile`, `item`), `ItemQuantityChanged` | obj pile scan | New synthesised events; one pile scan per tick while subscribed |
| `InteractingChanged(source, target)` | diff of `isInteracting` per tracked actor | Phase 8 |
| `ProjectileMoved(projectile, position, z)` | `getProjectiles` diff | Phase 8. Posted when a projectile first appears, with its target's position, since the reader can't see the client retarget it |

`VarbitChanged` has RuneLite's fields: `varpId` and `varbitId` (each -1 when not set) and a `long value`. Kraken posts
one event per changed varp (`varbitId = -1`, `value` = the varp's value), and one per varbit on that varp whose value
changed (both ids set, `value` = the varbit's value). The varbit events go first, the order the client's varp handler
uses and `EventPoller` already keeps. Check this against RuneLite's posting order when implementing. Plugins mostly
filter on `getVarbitId()` or `getVarpId()`, so order rarely matters.

*As built (phases 7-8):*

- **Scene loads.** A scene counts as new when the client comes back to logged in from anything but a lost connection,
  or when the map base moved. The object and item trackers then read the whole scene and post a spawn for everything
  in it, and nothing despawns. `GameStateTracker` holds a `LOGGED_IN` back until those spawns are out, so plugins see
  `LOADING`, spawns, `LOGGED_IN`, as in RuneLite (where the spawns come while the scene builds). The replay at attach
  posts the scene's objects and items too, and every actor's target.
- **Objects** are compared once per game tick with a whole-scene loc read (Lua's `ON_LOC_LOAD` technique, ~43,000
  reads), keyed by slot. A changed slot (a door opening) posts the old object's despawn and the new one's spawn, all
  despawns first. Events are only built for kinds with subscribers. `TileObjectSpawned`/`TileObjectDespawned` are the
  shared interfaces from §9.1.
- **Ground items** are compared on the tick's poll and the one after (in case the tick's updates land after the counter
  moves), with `Scene.getAllObjs()`. Per tile, stacks with the same item and quantity keep their handle, then stacks
  with the same item and another quantity are a quantity change, then the rest despawn or spawn.
- **`InteractingChanged`** compares every loaded actor's target on the tick's poll and the one after; an actor seen
  for the first time posts one if it has a target.
- **`ProjectileMoved`** is posted once, the first poll a projectile is in the list (projectiles are identified by what
  the server sent: spot anim, endpoints, cycles, slope, start distance). Projectiles already in flight at attach
  aren't replayed. Its position is the target actor's location, or the target tile's centre, and `z` the ground there
  less the end height.

`ON_MAP_REBUILD_PRE/POST` and Lua's `GameStateTrigger` have no RuneLite event. They stay on the Lua bus for anyone who
wants them.

### 9.5 Demand-driven tracking and the replay rule

Today `EventPoller` only tracks a Lua event while something subscribes to it, and takes a silent baseline first. The
RuneLite layer keeps both properties, with one rule added for attach:

- `RuneLiteEventSource` (in `impl`) listens to the bus's subscription changes. When the first subscriber for
  `NpcSpawned` arrives, it starts NPC tracking. When the last one leaves, it stops.
- **Tracking that starts at attach replays the current state as spawns.** That covers plugins that were already enabled
  when the game came up. From the plugin's point of view the client just logged in, so every NPC, player, object and
  item spawns, as after a RuneLite login.
- **Tracking that starts later takes a silent baseline.** That covers a plugin enabled mid-session, which in RuneLite
  also sees no spawns until the next scene load. Plugins that need the current state at start read it in `startUp`, as
  RuneLite plugins do.

Implementation in two steps:

1. Phase 3 builds the RuneLite source on the Lua events where the semantics agree (tick, NPCs, stats, vars,
   inventories, resize). It adds its own small trackers where they don't (raw game state, players, locs on scene load,
   ground items).

   *As built:* `RuneLiteEventSource` has its own tracker for every event, reusing the Lua poller's diff logic rather
   than its callbacks. The Lua events turned out not to agree closely enough: the replay rule needs the tracker to see
   the first state itself, a new NPC in an old one's index has to despawn the old pinned handle (Lua only sees the
   index), and `VarbitChanged` follows the client's working copy of the varps as RuneLite's does (Lua's events follow
   the server's copy). The replay posts the game state, NPC and player spawns, every skill's `StatChanged` and every
   held inventory's `ItemContainerChanged`, which is what a RuneLite login posts; vars and the canvas size aren't
   replayed. Pins are only checked when the index list changes or a tick passes, so a quiet poll costs one read of each
   list. The Lua `EventPoller` is unchanged.
2. If profiling shows double reads (say, the Lua loc scan and the RuneLite loc scan both running because the Event log
   dev tool is open), split `EventPoller` into shared *trackers* that produce diffs and two *emitters*, Lua and
   RuneLite, that turn diffs into events with their own semantics. The Lua emitter's behaviour stays byte-for-byte the
   same, and `EventPollerTest` proves it.

### 9.6 Threads

Kraken's poll thread (`kraken-api-poll`) is the "client thread". Game events are posted there, `ClientThread` tasks run
there, and so does overlay rendering (§12.4).

```java
@Singleton
public class ClientThread {
    public void invoke(Runnable task);           // now if on the client thread, else at the next poll
    public void invoke(BooleanSupplier task);    // re-run each poll until it returns true, as RuneLite's
    public void invokeLater(Runnable task);      // always at the next poll
    public void invokeLater(BooleanSupplier task);
    public void invokeAtTickEnd(Runnable task);  // after the next GameTick's subscribers
}
```

Each poll iteration does this, in order:

1. Drain `ClientThread` tasks.
2. Poll: Lua events, then RuneLite events, with `GameTick` last.
3. Post `ClientTick`.
4. Run tick-end tasks if a tick happened.
5. Render plugin overlays if a frame is due.
6. Call the dev tools' poll listener (every 150 ms, as today).

## 10. Plugin system (`client/`)

### 10.0 What the Lua side offers, and why this follows RuneLite instead

Jagex's Lua plugins are a folder with a `manifest.json` (`name`, `description`, `author`, `version`, `api_version`)
and an `index.lua` that runs once, top to bottom. A plugin stays alive while it holds a callback (an event
subscription, a `setInterval`, a UI element). `registerDisableCallback` is the only lifecycle hook. Plugins are
switched on and off with the `luatoggle` console command. `osrs.Persistence` is a per-plugin key-value store, and
`osrs.Settings` reads values the user sets in the game's settings panel. The docs don't say how a plugin declares
those settings.

None of that gives a Java plugin system anything to mirror: there's no declarative config, no plugin list and no
enable state beyond a console command. So the plugin, config and UI side follows RuneLite directly. Where a Lua concept
has a natural counterpart, the counterpart is RuneLite's:

| Lua | Kraken |
|---|---|
| `manifest.json` | `@PluginDescriptor` |
| `index.lua` running once, `registerDisableCallback` | `startUp()`, `shutDown()` |
| `luatoggle` | Plugins panel switch, `PluginManager.setPluginEnabled` |
| `osrs.Persistence` | `ConfigManager` (hidden items), or a plugin data folder |
| `osrs.setInterval` | `ClientThread.invoke(BooleanSupplier)`, the shared scheduler |

### 10.1 Types

```java
public abstract class Plugin implements Module {
    protected Injector injector;              // set by PluginManager

    @Override
    public void configure(Binder binder) {
    }

    protected void startUp() throws Exception {
    }

    protected void shutDown() throws Exception {
    }

    public final Injector getInjector() {
        return injector;
    }
}

@Retention(RUNTIME)
@Target(TYPE)
public @interface PluginDescriptor {
    String name();
    String description() default "";
    String[] tags() default {};
    boolean enabledByDefault() default true;
    boolean hidden() default false;
    boolean developerPlugin() default false; // only listed with --developer-mode
    String[] conflicts() default {};
}

@Retention(RUNTIME)
@Target(TYPE)
@Repeatable(PluginDependencies.class)
public @interface PluginDependency {
    Class<? extends Plugin> value();
}
```

These are RuneLite's shapes, so a RuneLite plugin's class declaration ports by changing imports.

### 10.2 `PluginManager`

- **Discovery.**
  - Built-in plugins are found by scanning `com.kraken.client.plugins` for `@PluginDescriptor` classes (ClassGraph, as
    RuneLite does). Adding a plugin means adding a package, nothing else.
  - Sideloaded jars in `~/.kraken-native/plugins/` are scanned the same way through a `URLClassLoader` (phase 9). They
    run with full permissions, like RuneLite's developer-mode sideloading, and the Plugins panel says where they came
    from.
- **Dependencies** work as in RuneLite: a dependency must offer `getPublicModule()` (added to `Plugin`), which is
  installed into the dependent's injector. Starting a plugin doesn't start its dependencies; `startPlugins` starts
  every enabled plugin in dependency order.
- **Developer loop.** RuneLite developers run `ExternalPluginManager.loadBuiltin(MyPlugin.class)` from a test `main`.
  Kraken's equivalent is `KrakenClient.launch(args, MyPlugin.class)`, or `--plugin com.example.MyPlugin` on
  `:client:run`.
- **Injection.** Each plugin gets a child of the client's injector:
  `parent.createChildInjector(binder -> { binder.bind(type).toInstance(plugin); binder.install(plugin); })`. Installing
  the plugin as a module picks up its `@Provides` methods, so the usual config provider works unchanged. The parent
  injector binds `Client`, `EventBus`, `ClientThread`, `ConfigManager`, `OverlayManager`, `ClientToolbar` and a shared
  `ScheduledExecutorService`.
- **Order.** Plugins start in dependency order (topological sort on `@PluginDependency`) and stop in reverse.
  Conflicting plugins can't both be on.
- **Lifecycle.** The same order as RuneLite:
  - Start: `startUp()` on the EDT, then `eventBus.register(plugin)`, then post `PluginChanged(plugin, true)`.
  - Stop: unregister, `shutDown()` on the EDT, post `PluginChanged(plugin, false)`.
  - A plugin whose `startUp` throws is logged, marked failed and switched off. The Plugins panel shows the error.
    As in RuneLite, its `shutDown` runs after the failed `startUp`.
  - Turning a plugin on turns off the plugins it conflicts with (RuneLite's rule), so at launch the first of two
    conflicting enabled plugins wins. The Plugins panel asks before turning others off.
- **Enabled state** is stored in config group `kraken`, key = the plugin class's simple name lower-cased, as RuneLite
  stores it under `runelite`.

### 10.3 Example

```java
@PluginDescriptor(
        name = "NPC Highlight",
        description = "Outlines NPCs whose names you list",
        tags = {"npc", "highlight", "overlay"}
)
public class NpcHighlightPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private NpcHighlightConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private NpcHighlightOverlay overlay;

    @Inject
    private ClientThread clientThread;

    @Getter
    private final Set<NPC> highlighted = new HashSet<>();

    private volatile Set<String> names = Set.of();

    @Provides
    NpcHighlightConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NpcHighlightConfig.class);
    }

    @Override
    protected void startUp() {
        names = parse(config.npcNames());
        overlayManager.add(overlay);
        clientThread.invoke(this::rebuild);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        clientThread.invoke(highlighted::clear);
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        if (names.contains(npc.getName())) {
            highlighted.add(npc);
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        highlighted.remove(event.getNpc());
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            highlighted.clear();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals("npchighlight")) {
            names = parse(config.npcNames());
            clientThread.invoke(this::rebuild);
        }
    }

    private void rebuild() {
        highlighted.clear();
        for (NPC npc : client.getNpcs()) {
            if (names.contains(npc.getName())) {
                highlighted.add(npc);
            }
        }
    }

    private static Set<String> parse(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}
```

That is a RuneLite plugin with Kraken imports. The threading is RuneLite's too:

- `highlighted` is a plain `HashSet` because spawn events, `ClientThread` tasks and the overlay's `render` all run on
  the client thread (§9.6, §12.4).
- `startUp`, `shutDown` and `onConfigChanged` run on the EDT. They hand set changes to the client thread, and swap
  `names` as a whole immutable set.

The same feature against today's API, for comparison:

```java
Set<Integer> highlighted = ConcurrentHashMap.newKeySet();
api.events().subscribe(Events.ON_NPC_LOAD, (name, index) -> {
    if (names.contains(name)) {
        highlighted.add(index);
    }
});
api.events().subscribe(Events.ON_NPC_UNLOAD, (name, index) -> highlighted.remove(index));
```

Drawing it then needs a `DevTool`. Its `read()` projects each index's `npcCoordFine` with `Graphics.projection()` on
the poll thread, and its `paintOverlay()` converts the result to overlay pixels with `OverlayContext.toOverlay()` on
the EDT. The NPC's tile comes back as a scene tile, which needs `api.scene().getBase()` added before it means anything
outside the scene.

### 10.4 Before the game is attached

RuneLite plugins can assume a `Client` exists. In Kraken the API attaches after the game boots, or never (unsupported
build), so plugins load and start only once the API has attached, never against a fake client:

- At client launch nothing plugin-related runs yet; `KrakenClient` registers an attach listener on `ProcessAttacher`
  that loads the core (and developer-launcher) plugins and starts the enabled ones. The injected `Client` is therefore
  always the attached `Client`. If the API never attaches (unsupported build or a failed attach), no plugins load and
  the Plugins panel stays empty.
- The listener runs on the attach thread before the first poll, so plugins are registered on the bus in time;
  `ApiStateChanged` is posted, then the replay rule (§9.5) brings plugins up to date with `GameStateChanged` and spawns
  on that first poll.

## 11. Configuration

### 11.1 Declaring a config

Identical to RuneLite:

```java
@ConfigGroup("npchighlight")
public interface NpcHighlightConfig extends Config {
    @ConfigSection(name = "Display", description = "How highlights look", position = 1)
    String display = "display";

    @ConfigItem(keyName = "names", name = "NPC names", description = "Comma-separated names to highlight",
            position = 0)
    default String npcNames() {
        return "";
    }

    @Alpha
    @ConfigItem(keyName = "colour", name = "Colour", description = "Outline colour", section = display, position = 1)
    default Color colour() {
        return Color.CYAN;
    }

    @Range(min = 1, max = 4)
    @Units(Units.PIXELS)
    @ConfigItem(keyName = "width", name = "Outline width", description = "", section = display, position = 2)
    default int width() {
        return 2;
    }
}
```

Annotations: `@ConfigGroup`, `@ConfigItem` (`keyName`, `name`, `description`, `position`, `section`, `hidden`,
`secret`, `warning`), `@ConfigSection` (`name`, `description`, `position`, `closedByDefault`), `@Range`, `@Units`,
`@Alpha`.

### 11.2 `ConfigManager`

- `getConfig(Class<T>)` returns a `java.lang.reflect.Proxy`. A getter returns the stored value if there is one, else the
  interface's default, through `InvocationHandler.invokeDefault` (Java 16+, so none of RuneLite's `MethodHandles`
  workaround is needed). A one-argument `void` method with `@ConfigItem` is a setter, as in RuneLite.
- `getConfiguration(group, key[, type])`, `setConfiguration(group, key, value)`, `unsetConfiguration(group, key)`, and
  `getConfigDescriptor(config)`, which the UI uses.
- Storage is one properties file, `~/.kraken-native/settings.properties`, with keys `group.key`. It's written on a
  short debounce, and atomically (write to a temp file, then move). That's the same root as the updater's cache, so
  Kraken has one home folder. Dev tool state can move there from `java.util.prefs` later.
- Supported types: `boolean`, `int`, `long`, `double`, `String`, enums, `Color` (`#AARRGGBB`), `Dimension`, and
  `Set<E extends Enum>`. `Keybind`/`ModifierlessKeybind` aren't supported (§8.11).
- Every change posts `ConfigChanged` on the thread that made it, usually the EDT. As in RuneLite, a plugin that needs
  game state in response uses `clientThread.invoke(...)`.

*As built (phase 5):* as above, plus `float`. Colours are stored `#AARRGGBB` (RuneLite's decimal form still reads),
`Dimension` as `WIDTHxHEIGHT`, a `Set` of an enum as comma-separated names. A stored value that doesn't convert gives
the default, with one warning. Values aren't cached: a getter is a map lookup and a parse. `getConfigurationKeys(prefix)`
and `objectToString`/`stringToObject` are there as in RuneLite. A `default` method without `@ConfigItem` runs as
written. Package-private config interfaces work (their defaults go through a private lookup).
`PluginManager.getPluginConfigProxy` finds a plugin's config among its injector's bindings, as RuneLite's does. Kraken's
own settings are `KrakenConfig` (group `kraken`: always on top, grow window, overlay frame rate), which the Settings page
is generated from, so those two window settings now persist.

## 12. UI

### 12.1 Plugins panel

The existing Plugins sidebar tab becomes RuneLite's plugin list:

```
┌ Plugins ──────────────────────────┐
│ ┌───────────────────────────────┐ │
│ │ Search plugins…               │ │
│ └───────────────────────────────┘ │
│ NPC Highlight            ⚙   [■ ] │
│ Tick Counter             ⚙   [ □] │
│ XP Tracker                   [■ ] │
│ Example (sideloaded)     ⚙   [■ ] │
│ Broken Plugin  ⚠ failed      [ □] │
└───────────────────────────────────┘
```

- Rows are sorted by name. The search matches name, description and tags. The tooltip is the description.
- The switch starts or stops the plugin through `PluginManager`. The cog only shows if the plugin provides a `Config`.
- Clicking the cog swaps the panel to the plugin's settings, with a back arrow, as RuneLite's config panel does.

### 12.2 Generated settings panels

The panel is built from the plugin's `ConfigDescriptor`: items in `position` order, grouped into collapsible sections.

| Type | Control |
|---|---|
| `boolean` | Switch |
| `int`, `long`, `double` | Spinner (bounds from `@Range`, suffix from `@Units`) |
| `String` | Text field (masked if `secret`) |
| enum | Combo box |
| `Set<Enum>` | Checkbox list |
| `Color` | Swatch that opens a colour picker (alpha with `@Alpha`) |
| `Dimension` | Two spinners |

There's a "Reset" button per plugin, and a `warning` shows a confirm dialog before the value changes.

*As built (phase 5):* `ui.ConfigPanel`. Strings are a wrapping text area saved after a pause in typing or on focus
loss; sections remember whether they're open while Kraken runs; changes made elsewhere (a plugin's setter) show while
the page is open. Reset leaves hidden items alone, since plugins keep their state in them (RuneLite's resets those
too).

### 12.3 Plugin sidebar panels

`ClientToolbar.addNavigation(NavigationButton)` and `removeNavigation(...)`, with `NavigationButton` built as in
RuneLite (`tooltip`, `icon`, `priority`, `panel`). `PluginPanel extends JPanel` sets RuneLite's padding and the
sidebar's width. Under the hood this is `Sidebar.addTab`, made public, with removal and priority ordering added.
`ColorScheme` holds RuneLite's colour names (`DARK_GRAY_COLOR`, `BRAND_ORANGE`, …) mapped onto `Theme`, so ported
panels compile.

### 12.4 Overlays

The API is RuneLite's:

```java
public abstract class Overlay {
    public static final float PRIORITY_LOW = 0f;    // also PRIORITY_DEFAULT, _MED, _HIGH, _HIGHEST, as RuneLite
    public abstract Dimension render(Graphics2D graphics);  // null for DYNAMIC overlays
    // position (OverlayPosition), layer (OverlayLayer), priority (float), bounds, preferred size
}

@Singleton
public class OverlayManager {
    public boolean add(Overlay overlay);
    public boolean remove(Overlay overlay);
    public boolean removeIf(Predicate<Overlay> filter);
    public boolean anyMatch(Predicate<Overlay> filter);
}
```

The example plugin's overlay is RuneLite code, unchanged except for imports:

```java
class NpcHighlightOverlay extends Overlay {
    private final NpcHighlightPlugin plugin;
    private final NpcHighlightConfig config;

    @Inject
    NpcHighlightOverlay(NpcHighlightPlugin plugin, NpcHighlightConfig config) {
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        for (NPC npc : plugin.getHighlighted()) {
            Polygon tile = npc.getCanvasTilePoly();
            if (tile != null) {
                OverlayUtil.renderPolygon(graphics, tile, config.colour(), new BasicStroke(config.width()));
            }
        }
        return null;
    }
}
```

`OverlayUtil` (`renderPolygon`, `renderTextLocation`, `renderActorOverlay`, `renderTileOverlay`), `OverlayPanel`, and
the panel components (`PanelComponent`, `LineComponent`, `TitleComponent`) are ported in reduced form. Today's
`OverlayContext.infoPanel` becomes `PanelComponent`.

How it's drawn on Kraken's click-through window:

- **On the client thread, into a back buffer.** At each due frame the poll thread clears an ARGB image, calls every
  overlay's `render` into it, and hands it to the EDT, which paints the last finished frame. There are two images, so
  the EDT never sees a half-drawn one.
  - This keeps plugin state single-threaded, as in RuneLite, where `render` runs on the client thread.
  - It also keeps memory reads off the EDT.
  - The image is sized in device pixels so HiDPI screens stay sharp.
- **Canvas units.** The `Graphics2D` passed to `render` is scaled from client pixels to overlay pixels, so `Perspective`
  results can be drawn as they are, as in RuneLite. Today's `OverlayContext.toOverlay` does that conversion by hand.
- **Frame rate.** 30 fps by default, and nothing is rendered while no overlay is registered. That's a client setting.
  RuneLite draws at the game's frame rate, but every frame here costs real reads, so it's a choice.
- **Layers.** RuneLite has `MANUAL`, `ABOVE_SCENE`, `UNDER_WIDGETS`, `ABOVE_WIDGETS` and `ALWAYS_ON_TOP`. Kraken's
  single window sits above everything, so every layer draws above the game's interfaces. Layers still order the
  overlays among themselves. `MANUAL` (which needs `drawAfterInterface`/`drawAfterLayer`) is left out. `UNDER_WIDGETS`
  could later be approximated by clipping out the bounds of open interface groups (the `InterfaceCoordinates` clip
  rectangles). That would need care, because the top-level viewport group covers the whole screen.
- **Positions.** `DYNAMIC`, `TOP_LEFT`, `TOP_CENTER`, `TOP_RIGHT`, `BOTTOM_LEFT` and `BOTTOM_RIGHT` come first, laid
  out by stacking returned sizes as RuneLite's `OverlayRenderer` does. `CANVAS_TOP_RIGHT` is easy too.
  `ABOVE_CHATBOX_RIGHT` can follow once the chatbox widget's bounds are wrapped. `TOOLTIP` needs the mouse position,
  which Kraken doesn't have.
- **No drag to move.** The window is click-through and the game owns input. Overlay positions are fixed per anchor.
- **Dev tools.** They keep their own painter (EDT, snapshots), composited in the same window. Plugin overlays and dev
  tool overlays can be on together.

*As built (phase 6):* the overlay package moved to `client.ui.overlay`. `KrakenApi.setFrameListener` is the per-poll
hook (step 5 of §9.6); `ProcessAttacher.addFrameListener` fans it out. `OverlayRenderer` owns the frame rate (the
`KrakenConfig` setting, capped at the ~50 Hz poll rate, stepped so 30 fps on a 20 ms poll averages 30), the two images
(each locked while drawn or painted), and the hand-off to `GameOverlay`, which now also tracks the game area's size and
display scale. `OverlayCompositor` lays out and draws a frame and is tested headless. Differences from RuneLite:

- Anchors are the canvas's corners; `TOP_RIGHT` and `CANVAS_TOP_RIGHT` share one stack. Overlays at one anchor stack
  across layers (RuneLite restarts the corners per layer, so layers overlap there). An overlay that drew nothing for a
  while is placed by its last real size when it comes back, rather than misplaced for a frame.
- `DETACHED`, `TOOLTIP` and `ABOVE_CHATBOX_RIGHT` are left out, as are dragging, resizing, snapping and overlay menus;
  `setPreferredLocation`/`setPreferredPosition` still work from code.
- `PanelComponent` measures its children before drawing its background, so the background fits this frame's lines
  (RuneLite's uses last frame's). Lines don't wrap; a long line widens the panel. `TextComponent` has no colour tags.
- `OverlayUtil` has `renderPolygon`, `renderTextLocation`, `renderImageLocation` and the actor helpers; actor text goes
  `OverlayUtil.ACTOR_OVERHEAD_OFFSET` (240) above the ground, since model heights aren't read. Phase 7 added the
  tile helpers, `renderTileOverlay` (for a `TileObject`, and for a `LocalPoint` with an image) and
  `renderImageLocation(client, …)`, without their minimap half.
- The dev tools keep `OverlayContext.infoPanel`; plugins use `PanelComponent`.

### 12.5 Dev tools

The dev tools stay on the Lua layer, because their job is showing what the Lua API reads. Two additions:

- The Location tool (and later NPCs, Players and Game objects) gets a "RuneLite view" option showing `WorldPoint` and
  `LocalPoint` next to the Lua values. That's how the adapters get checked on the live client.
- The Event log can show RuneLite-shaped events as well as Lua ones.

Turning the dev tools into a built-in developer plugin (`@PluginDescriptor(developerPlugin = true)`), as RuneLite does,
is optional and doesn't block anything.

## 13. Threading summary

| Thread | Runs |
|---|---|
| `kraken-api-poll` ("client thread") | Polling, game event dispatch, `ClientThread` tasks, plugin overlay rendering, dev tool reads |
| EDT | Plugin `startUp`/`shutDown`, Swing panels, config UI, `ConfigChanged` (usually), blitting the overlay frame, dev tool painting |
| `kraken-api-attach` | Attach retries (unchanged) |
| Shared scheduler | Plugin background work (`@Schedule`-style tasks, later) |

## 14. Testing

- **Adapters**: extend `GameFixture` tests through the RuneLite layer. For example, `NPC.getWorldLocation()` equals the
  fixture's scene tile plus base, `Widget.isHidden()` follows a hidden parent, and a despawned NPC keeps its last name.
  As today, these test the plumbing, and the live client tests the offsets.
- **EventBus**: priority order, private methods, supertype dispatch, a throwing subscriber not stopping others,
  register and unregister during a post.
- **Event sources**: `EventPollerTest`-style fakes for each RuneLite event, including the replay rule and the
  missed-`LOADING` case.
- **Config**: defaults through `invokeDefault`, setters, type round-trips, `ConfigChanged` payloads, descriptor
  ordering.
- **Plugins**: fake plugins for dependency order, conflicts, a failing `startUp`, and persisted enabled state, without
  Swing (inject an executor for "EDT" work).
- **Lua parity**: the existing suite must pass unchanged after every phase. That's the guard on "the underlying API
  doesn't change".
- **Live**: the "RuneLite view" columns in the dev tools, and one example plugin per phase.

## 15. Phases

Each phase builds, passes both test suites and leaves the client usable.

| # | Phase | Delivers | Done when |
|---|---|---|---|
| 1 | Make room | Packages move (`game` → `lua`, `events` → `lua.events`, `config` → `lua.types`), `Client` → `ClientOp`, `KrakenApi.lua()`; dev tools, tests, `CLAUDE.md` and the coverage doc updated | Every existing test passes unchanged except imports; dev tools behave the same |
| 2 | Core types | `coords`, `Point`, `GameState`, `Skill`, `WorldType`, `EquipmentInventorySlot`, `Item`, `ItemContainer`, `InventoryID`; `Client`, `Actor`, `NPC`, `Player`, `Renderable`, top-level `WorldView`, compositions; pinned handles; `KrakenApi.client()` | Adapter tests; RuneLite view in the Location tool matches the wiki's world tiles live |
| 3 | Events | `EventBus`, `@Subscribe`, `GameTick`, `ClientTick`, `GameStateChanged`, NPC and player spawns, `ItemContainerChanged`, `StatChanged`, `VarbitChanged`, `CanvasSizeChanged`; `RuneLiteEventSource` with the replay rule; `invoke`/`ClientThread` queue | Bus and source tests; Event log shows RuneLite events live |
| 4 | Plugins | `Plugin`, `@PluginDescriptor`, `@PluginDependency`, `PluginManager` (loaded once the API attaches), `PluginChanged`, `ApiStateChanged`, `ClientShutdown`; Plugins panel with switches; developer launcher; one example plugin (tick counter) | Enabled state persists; a failing plugin is switched off and shown |
| 5 | Config | Annotations, `ConfigManager`, `ConfigChanged`, generated settings panels | Example plugin configurable; settings survive restart |
| 6 | Overlays | `Overlay`, `OverlayManager`, positions and layers subset, back-buffer rendering on the client thread, `Perspective`, `OverlayUtil`, `PanelComponent` | NPC Highlight example works live at 30 fps without stalling the EDT |
| 7 | Scene | `Scene`, `Tile`, `TileObject` family, `TileItem`, object and item spawn events with RuneLite's scene-load behaviour | Example "object markers lite" plugin; tests for scene-load spawns |
| 8 | Widgets and the rest | `Widget`, `WidgetUtil`, `getWidget`; `Projectile`; `CollisionData`; camera getters (live check on yaw/pitch); generated `gameval`; `InteractingChanged` | Widget inspector RuneLite view matches; gameval generator in the build |
| 9 | Optional | Sideloaded plugin jars; dev tools as a developer plugin; split `api/` into interface and implementation modules | Decided when we get there |

Phases 2 and 3 are the ones that change how the API feels. Phase 4 is where it starts feeling like RuneLite.

Done so far (2026-10-20): phases 1-8. Phase 7's example is `plugins.objectmarkers` (marks objects listed by name or
id in its settings, since Kraken has no menus to pick them from); phase 8's dev tool checks are the RuneLite views of
the Widget inspector and Game objects tool and the new Event log entries. Live checks for phases 7 and 8: object and
item spawns after a teleport (the whole new scene, `LOGGED_IN` last), a door opening, Object Markers outlines,
`InteractingChanged` in combat, `ProjectileMoved` for spells and arrows, the Widget inspector's RuneLite view, and
the camera angles (§7.4). Still to do from earlier phases: the Location tool's RuneLite view against the wiki's
world tiles (phase 2), and the Event log's RuneLite events, the Tick Counter plugin and the Plugins panel with the game
running (phases 3 and 4). For phases 5 and 6: the NPC Highlight and Tick Counter settings pages and their overlays with
the game running (outlines on the NPCs' tiles at 30 fps without stalling the EDT, the tick panel in the top-left corner,
HiDPI sharpness), and settings surviving a restart. Phase 4 pulled forward the string store of `ConfigManager`; phase 5
added the typed proxies, `ConfigChanged` and the settings pages on top. `ClientToolbar` (§12.3) isn't in any phase yet.

## 16. Decisions for you

1. **Package names.** `com.kraken.api` for the RuneLite shape and `com.kraken.api.lua` for the mirror. *Recommended.*
   The alternative is leaving the mirror in `game` and putting the new layer in a new package, which leaves the most
   familiar names (`Client`, `events`) to the Lua side.
2. **Handle semantics.** Pinned, RuneLite-like handles in the new layer (§8.2). *Recommended.* Index handles would be
   simpler, but they break `onNpcDespawned` code that reads the NPC.
3. **Where the bus lives.** `api/`, so the API can post without the client. *Recommended.* RuneLite has it in the
   client, but RuneLite's API never runs without its client.
4. **Overlay threading.** Render on the client thread into a back buffer. *Recommended.* Rendering directly on the EDT
   is simpler but makes every plugin deal with two threads.
5. **Settings location.** `~/.kraken-native/settings.properties`, or a separate `~/.kraken` folder? *Phase 4 uses
   `~/.kraken-native/settings.properties`, as §11.2 describes.*
6. **`gameval`.** Generate copies from RuneLite with its license notice (recommended), or depend on `runelite-api`.
   *Phase 8 generates copies (§8.10).*
7. **Plugin discovery.** ClassGraph scanning (RuneLite's way, adds a dependency) or an explicit catalog like
   `DevToolCatalog` (no dependency, one more line per plugin). *Phase 4 uses ClassGraph, as §10.2 describes.*
8. **`Player.isFriend()`.** Leave it out until the friend list can answer "friend but offline" (recommended), or offer
   it with Lua's "online friend" meaning and a loud Javadoc.
