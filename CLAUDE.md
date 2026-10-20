# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Tooling for the official native Old School RuneScape client (`osclient.exe`, x64 C++, Windows). Three modules, joined
by one contract, `api/src/main/resources/mappings/<version>.json`:

- `updater/` (Python, uv): statically analyses a client build (via a Ghidra export) and writes that mapping.
- `api/` (Java 21 library): attaches to the running client and reads game state with `ReadProcessMemory`, following
  the offset chains in the mapping. `GameSession.client()` is a RuneLite-shaped API (`Client`, `NPC`, `WorldPoint`…;
  design in `docs/runelite-api-design-opus.md`) whose getters replay what the client's own Lua plugin API
  (`osrs.ClientOp`, `osrs.ClientState`…) reads.
- `client/` (Java 21 Swing + Guice): launches `osclient.exe`, embeds its window in a RuneLite-style frame, and hosts dev
  tools that show what the API reads.

## Commands

Java, from the repo root (the Gradle toolchain auto-provisions JDK 21):

```bash
./gradlew :api:test
./gradlew :api:test --tests 'com.kraken.api.ClientTest'                                # one class
./gradlew :api:test --tests 'com.kraken.api.ClientTest.readsTicksCyclesAndLogin'       # one method
./gradlew :client:test                                    # plugins, config, settings pages, overlay layout (headless)
./gradlew :client:run --args="D:/path/to/osclient.exe"   # Windows only; arg optional (defaults to the Jagex Launcher install)
./gradlew :client:run --args="--plugin com.example.MyPlugin --developer-mode"   # extra plugin; load developer plugins
./gradlew :api:generateGameval [-PgamevalCommit=<sha>]   # rewrite com.kraken.api.gameval from RuneLite's (by hand)
```

The client reads bundled mappings unless the system property `kraken.mappings=<dir>` points at a folder of
`<version>.json` files (see `ProcessAttacher`). Settings (plugin on/off state) live in
`~/.kraken-native/settings.properties` unless `kraken.settings=<file>` says otherwise.

Updater, from `updater/`:

```bash
uv run python -m unittest discover -s tests -t .          # all tests
uv run python -m unittest tests.test_units.X86Parse       # one class (append .test_name for one method)
uv run native-updater lint                                # validate spec YAML
uv run native-updater run --binary <osclient.exe> [--previous ../api/src/main/resources/mappings/<old>.json]
uv run native-updater bindings --binary <osclient.exe> --table osrs.IfType   # dump parsed Lua bindings
uv run native-updater sql --binary <osclient.exe> "<SQL over program.db>"
```

`run` exits 0 when every P0 key is PROVEN/ANCHORED, 1 on acquisition failure or crash, 2 when the mapping was written
but some key was left out (FAILED/NEEDS_REVIEW) or a P0 key is only INFERRED.

### Platform

- The test suites run on Linux/WSL (they use fakes; the client's run headless). Anything that touches the live process (`:client:run`,
  `GameSession.attach`, `native-updater probe`) needs Windows. Run Ghidra `export` from Windows too; WSL reuses it from the
  shared cache.
- In WSL, `uv` is a shell function that sets `UV_PROJECT_ENVIRONMENT=.venv-linux` under `/mnt/*`, keeping Windows'
  `.venv` intact. Invoke uv through that function.
- `tests/test_golden.py` skips unless the corpus build's binary and `program.db` are cached in `~/.kraken-native` (from
  WSL: the Windows profile's `.kraken-native`). Report a skipped golden run as skipped, not passed.

## Updater architecture

Pipeline (`pipeline.py`): identify the build (`pe.py` file version + sha256, archived in the binary store) → Ghidra
export → `program.db` SQLite (`model.py`) → parse sol2 Lua bindings and fingerprints → run each key's strategies in
rounds (keys can depend on other keys) → consensus verdicts (PROVEN/ANCHORED/INFERRED/NEEDS_REVIEW/FAILED) → invariants
(`verify.py`) → emit.

- `src/native_updater/spec/*.yaml` is the source of truth for what gets mapped: one entry per key with strategies,
  invariants and a `live` probe check. Key names map to mapping sections: `Struct.field` → `structs`, `global.x` →
  `globals`, `functions.x` → `functions`, `enums.x` → `enums`, `container.x.y` → `containers`, `events.ON_X` →
  `events` (the hook sites where the client raises that native event; `events.py`, design in
  `docs/event-hooks-design.md`).
- The mapping JSON is updater output and holds only PROVEN/ANCHORED/INFERRED, non-`internal` keys. The evidence behind
  each value, plus every Lua binding's handler RVA, goes to `~/.kraken-native/cache/<sha256>/analysis.json`; read that
  when a value looks wrong.
- Writing recipes: see "Writing a recipe" in `updater/README.md`. Inspect what a recipe can match with `bindings`,
  `sql`, or `Provenance(model).facts(fn)` in `uv run python`. Spell offset params `offset`, since YAML parses `off` as
  `false`.
- `updater/corpus/<version>.golden.json` holds the accepted values; update it when a recipe change legitimately moves a
  value.

## API architecture

- No hard-coded offsets: `Mapping` serves everything from the JSON. `GameSession.attach` picks `mappings/<version>.json`
  by the exe's version resource and rejects it unless its `sha256` matches the exe. `ClientBuild.version` parses the
  version the same way as the updater's `pe.py: file_version`; change the two together.
- One layer, shaped as RuneLite's: concrete classes, grouped by domain. `com.kraken.api` holds `GameSession`, `Client`
  and the client-wide types (`GameState`, `Skill`, `WorldType`, `Point`, `Perspective`, `CameraProjection`…); `actor`
  (`Actor`, `NPC`, `Player`, `IndexedObjectSet`), `scene` (`WorldView`, `Scene`, `Tile`, `TileObject` and its four
  subclasses, `TileItem`, `Projectile`), `collision`, `item` (`Item`, `ItemContainer`, `EquipmentInventorySlot`),
  `definition` (the compositions), `widgets`, `coords`, `events`, `eventbus`, `gameval`. There are no interfaces or
  adapters: each getter reads memory itself or calls a reader that does (`NPC.getSpawnCycle()` →
  `actors.npcCreationCycle(address())`). Names,
  types and meanings follow RuneLite's; methods the API can't back are left out, not stubbed. Reads the client's Lua API
  has and RuneLite doesn't are added to the matching class (camera focal point, `Actor.getPathLength`, widget fields,
  `CollisionDataFlag`'s Lua names…). Javadoc says what the code does, which Lua function a getter mirrors, and any
  divergence from the native handler. It never mentions RuneLite (credited in the README) and doesn't label extras or
  call the library "Kraken". Use Lombok `@Getter`/`@Setter` for plain fields.
- All plumbing lives in `com.kraken.api.internal`: `GameReader` (the base) and `StructReader`, `ActorReader` (actors by
  address), `SceneReader` (locs, piles, collision, map base), `ProjectileReader`, `InventoryReader`, `WidgetReader`,
  `ConfigTypes` (the config archive and its decoders, `ItemDecoder`/`NpcDecoder`/`ObjectDecoder` with their builders,
  which build the compositions), `EventSource`, and the records readers return (`MapCoord`, `FineCoord`, `Loc`,
  `GroundObj`, `ProjectileEndpoint`). Java has no friend packages, so it is public, as are the hooks the domain
  packages share (`Client.getActorReader()`, `npc(index)`, `toWorld`, `Actor.remember`, the handles' constructors…);
  their Javadoc says "Internal", and plugins never need them. `Client` reads its own struct's fields (game state,
  stats, vars, varcs, camera, canvas) directly.
- Actor handles are pinned to an actor's address (and an NPC's creation cycle or a player's name): `Client` hands out
  one handle object per actor, which keeps its last name, id and location once the actor is gone. `TileObject`s are
  values (loc + the map base it was read with), `Tile`s are handles on a scene position that read empty once the base
  moves, `TileItem`s keep their identity through the item events, and `Widget`s and `Projectile`s are snapshots of one
  read. Compositions are cached by `ConfigTypes` and resolve conditional actions against `Client`'s live vars.
- `com.kraken.api.gameval` is generated from RuneLite's gamevals at a pinned commit (`generateGameval`) and committed;
  don't edit it by hand.
- `internal.GameReader` is the base for `Client` and the readers. Chains start at `moduleBase + globals.client`.
  Constructors resolve offsets once: `mapping.offset(key)` for required keys (throws `MissingMappingException`),
  `optionalOffset`/`offsetOr`/`has` for keys older mappings may lack. Reads go through the `safe*` helpers and return
  fallbacks (-1, "", empty array, null) for unreadable or logged-out state.
- Coordinates: the client stores actor tiles as scene tiles (relative to the loaded map's base), which the readers
  return as `MapCoord`s; the API adds the base (`Client.toWorld`) for a `WorldPoint`, a world tile. A `LocalPoint` is
  the scene × 128, the space of fine coordinates and the camera. The client (and its Lua API) keeps the height in `y`
  and north in `z`; the API uses RuneLite's axes (`y` north, `z` height), so readers that copy Lua's order swap them
  (`CameraProjection.project`, `Projectile`, the camera getters).
- Read-only by design: the API only reads the game process. Lua functions that change the client (highlights, camera
  control, actions) belong to a future in-process backend and are out of scope.
- Events (`com.kraken.api.events`, posted on the `eventbus.EventBus` passed to `GameSession.attach`) are synthesised by
  `EventSource`, only for events that have subscribers: its trackers diff reads every 20 ms, spawns carry pinned
  handles, vars follow the client's working copy and every raw game state counts. The Lua API's own events
  (`osrs.Events`) aren't mirrored: each has a RuneLite event that covers it. Tracking that starts on
  the API's first poll replays the current state (game state, the scene's objects and ground items, NPC/player spawns,
  targets, stats, inventories); later it takes a silent baseline. As in RuneLite, a scene load (back to logged in, or
  a moved map base) spawns every object and item of the new scene and despawns none of the old, and the `LOGGED_IN`
  is held until those spawns are posted. Objects are a whole-scene read once per tick (costly); items and targets are
  read on the tick's poll and the next. The bus also matches supertypes and interfaces of an event, unlike RuneLite's
  (`TileObjectSpawned` takes all four object spawns).
- The `kraken-api-poll` thread is the "client thread". Each poll: `GameSession.invoke*` tasks (`TaskQueue`), the events
  with `GameTick` after the tick's other events, `ClientTick`, `invokeAtTickEnd` tasks if a tick
  happened, the frame listener (where the client draws plugin overlays), then the 150 ms poll listener. Event handlers,
  tasks and overlay rendering all run there.
- `Perspective` (canvas helpers, RuneLite's names) only calls `Client.getCameraProjection()` (the camera, read once)
  and `WorldView.getTileHeight`, both additions to RuneLite's API. Canvas positions are the client's own pixels
  (`Client.getCanvasWidth/Height`).
- Adding a read the Lua API has: take the handler RVA from `analysis.json` bindings, map each constant it uses to a
  mapping key (adding a spec recipe if needed), and replay the same steps in Java, in the reader for that struct (or
  `Client` for its own fields), exposed on the RuneLite-shaped class it belongs to, under RuneLite's name if RuneLite
  has one. Javadoc names the Lua function and any deliberate divergence from the native
  handler.
- Tests: `GameFixture` lays out a fake Client → World → NPC/Player/inventory/scene/camera/interface/projectile graph in `FakeMemory` at the bundled 241.3
  mapping's real offsets, so tests cover the read plumbing, not whether offsets are correct (the live client verifies
  that). New reads extend `GameFixture`. Tests sit in the package of the class they test and build the client with
  `new Client(game.memory, game.map)`.

## Client architecture

- Guice wiring (`KrakenModule`); components are `@Singleton`s created on first use, so the frame is requested on the EDT
  after `Theme.install()`. Logging is `java.util.logging` via Lombok `@Log(topic = "kraken")`, and shows in the in-app
  console. `lombok.config` copies `@Named` onto Lombok-generated constructors, so `@AllArgsConstructor(onConstructor_ =
  @Inject)` with `@Named` fields works.
- `ProcessAttacher` attaches the API in the background (retrying while the client boots) with the client's one `EventBus`,
  posts `events.ApiStateChanged` on each state change, and fans each poll out to listeners on the poll thread, about
  every 150 ms. `callback.ClientThread` (RuneLite's) forwards to `GameSession.invoke*`, holding tasks until the API
  attaches.
- Plugins (`plugins.Plugin`, `@PluginDescriptor`, `@PluginDependency`) follow RuneLite's. `PluginManager` finds
  top-level `@PluginDescriptor` classes under `com.kraken.client.plugins` with ClassGraph (one package per plugin, e.g.
  `plugins.tickcounter`), gives each a child injector (plugin bound and installed as a module, plus each dependency's
  `getPublicModule()`), and starts/stops them on the EDT through the `@Named("edt")` executor (tests pass
  `Runnable::run`). On/off state is `ConfigManager` group `kraken`, key = class simple name lower-cased; a plugin whose
  `startUp` throws is switched off and keeps its error (`getFailure`). Plugins load and start only once the API
  attaches, so they always inject the real `Client`; if it never attaches, none load (`KrakenClient` registers the
  loader as a `ProcessAttacher` attach listener). `ExitHandler` posts `ClientShutdown`, stops plugins and flushes
  settings on exit.
- Config (`client.plugins.config`; Kraken's own `KrakenConfig` sits in the root package) follows RuneLite's: `ConfigManager.getConfig` implements a `@ConfigGroup` interface (getters
  annotated `@ConfigItem`, defaults in `default` bodies) as a proxy, usually from the plugin's `@Provides` method; every
  change posts `events.ConfigChanged`. `PluginManager.getPluginConfigProxy` finds a plugin's config among its injector's
  bindings, and `ui.ConfigPanel` generates the settings page (the Plugins panel's cog; the Settings tab is
  `KrakenConfig`'s). Supported types are listed on `ConfigManager`.
- Overlays (`client.ui.overlay`) follow RuneLite's: plugins add `Overlay`s to `OverlayManager`. `OverlayRenderer` draws
  them on the poll thread (the API's frame listener) at the `KrakenConfig` frame rate into a device-pixel image, scaled
  so overlays draw in canvas pixels, and hands finished frames to `GameOverlay`, the click-through window over the game,
  which the EDT paints. `OverlayCompositor` does the layout (tested headless). Differences from RuneLite are in the
  Javadoc and design doc §12.4. `plugins.npchighlight`, `plugins.objectmarkers` (scene events) and
  `plugins.tickcounter` are the worked examples.
- Dev tools: subclass `DevTool<S>` and list it in `DevToolCatalog.create()`. `read(api)` runs on the poll thread and
  returns an immutable snapshot; `paintOverlay`/`createView` run on the EDT. A tool reads only while its overlay or
  window is showing. `PlannedTool` entries hold sidebar spots for features the API can't read yet; replace the entry
  when its API lands. Tools read through `api.client()`, the API's one layer, and show its values; event-driven tools
  (Event log, Var inspector) subscribe to `api.eventBus()` only while they show, and only to the kinds they log.

## Reference

- `docs/updater-design.md`: anchor classes, verdict rules, carry-forward and delta propagation. Read before changing
  `consensus.py`, `carry.py` or `verify.py`.
- `docs/ghidra-walkthrough.md`: locating offsets by hand in Ghidra, with an x64 assembly primer.
- `debug/`: a macOS debug build of the client (build 216) with text dumps of its symbols, strings and constants
  (`symbols_table_demangled.txt` is ~80 MB). Grep them for real class and member names; nothing in the code reads them.

## Formatting

- Prefer not to leave comments in this format `// ---- dispatch (called by the poller) ---------------------------------------------------------` if you encounter comments like this remove them from the codebase
- Any commits made should NOT reference AI as a co-author
- Comments, Javadoc and test messages say what the code does and never mention RuneLite (it is credited in the
  README's Acknowledgements), even where a class follows RuneLite's design. The exception is the license notice in
  the generated `gameval` files.
- Block level Comments should include `@return` and `@param` referencing the params and the return type of the method for javadoc generation.
- Any block level comments should have proper spacing i.e. prefer this
```java
/**
 * This does something
 */
private static final int foo = -1;
```
instead of:
```java
/** This does something */
private static final int foo = -1;
```
