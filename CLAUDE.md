# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Tooling for the official native Old School RuneScape client (`osclient.exe`, x64 C++, Windows). Three modules, joined
by one contract, `api/src/main/resources/mappings/<version>.json`:

- `updater/` (Python, uv): statically analyses a client build (via a Ghidra export) and writes that mapping.
- `api/` (Java 21 library): attaches to the running client and reads game state with `ReadProcessMemory`, following
  the offset chains in the mapping. `KrakenApi.lua()` mirrors the client's Lua plugin API (`clientOp()` ↔
  `osrs.ClientOp`, `clientState()` ↔ `osrs.ClientState`, `events()` ↔ `osrs.Events`), and `KrakenApi.client()` puts a
  RuneLite-shaped API on top of it (`Client`, `NPC`, `WorldPoint`…; design in `docs/runelite-api-design-opus.md`).
- `client/` (Java 21 Swing + Guice): launches `osclient.exe`, embeds its window in a RuneLite-style frame, and hosts dev
  tools that show what the API reads.

## Commands

Java, from the repo root (the Gradle toolchain auto-provisions JDK 21):

```bash
./gradlew :api:test
./gradlew :api:test --tests 'com.kraken.api.lua.ClientReadTest'                        # one class
./gradlew :api:test --tests 'com.kraken.api.lua.ClientReadTest.readsLocalPlayerIndex'  # one method
./gradlew :client:test                                    # plugin manager, stand-in client, settings (headless)
./gradlew :client:run --args="D:/path/to/osclient.exe"   # Windows only; arg optional (defaults to the Jagex Launcher install)
./gradlew :client:run --args="--plugin com.example.MyPlugin --developer-mode"   # extra plugin; load developer plugins
```

The client reads bundled mappings unless the system property `kraken.mappings=<dir>` points at a folder of
`<version>.json` files (see `ApiConnection`). Settings (plugin on/off state) live in
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
  `KrakenApi.attach`, `native-updater probe`) needs Windows. Run Ghidra `export` from Windows too; WSL reuses it from the
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
  `globals`, `functions.x` → `functions`, `enums.x` → `enums`, `container.x.y` → `containers`.
- The mapping JSON is updater output and holds only PROVEN/ANCHORED/INFERRED, non-`internal` keys. The evidence behind
  each value, plus every Lua binding's handler RVA, goes to `~/.kraken-native/cache/<sha256>/analysis.json`; read that
  when a value looks wrong.
- Writing recipes: see "Writing a recipe" in `updater/README.md`. Inspect what a recipe can match with `bindings`,
  `sql`, or `Provenance(model).facts(fn)` in `uv run python`. Spell offset params `offset`, since YAML parses `off` as
  `false`.
- `updater/corpus/<version>.golden.json` holds the accepted values; update it when a recipe change legitimately moves a
  value.

## API architecture

- No hard-coded offsets: `Mapping` serves everything from the JSON. `KrakenApi.attach` picks `mappings/<version>.json`
  by the exe's version resource and rejects it unless its `sha256` matches the exe. `ClientBuild.version` parses the
  version the same way as the updater's `pe.py: file_version`; change the two together.
- Two layers. The Lua mirror lives in `com.kraken.api.lua` (`LuaApi` holds one object per namespace), with its events
  in `lua.events` and the decoded config types in `lua.types`. The RuneLite-shaped layer is interfaces and value types
  in `com.kraken.api` (`coords`, `gameval`), implemented by package-private adapters in `com.kraken.api.impl` that only
  call the Lua mirror and never read memory. A wrong RuneLite-shaped value is fixed in the mirror (or the mapping).
  Names, types and meanings follow RuneLite's; methods Kraken can't back are left out, not stubbed, and Javadoc marks
  Kraken extras and any divergence from RuneLite.
- RuneLite-layer actor handles are pinned (`ClientOp.pinNpc`/`pinPlayer`): one handle object per actor, which keeps its
  last name, id and location once the actor is gone. Lua-layer `Npc`/`Player` handles follow their index.
- `GameReader` is the base for the Lua-namespace mirrors (`ClientOp`, `ClientState`, `Camera`, `Graphics`,
  `InterfaceQuery`) and the package-private readers they share (`ActorReader`, `SceneReader`). Chains start at
  `moduleBase + globals.client`. Constructors resolve offsets once: `mapping.offset(key)` for required keys (throws
  `MissingMappingException`), `optionalOffset`/`offsetOr`/`has` for keys older mappings may lack. Reads go through the
  `safe*` helpers and return fallbacks (-1, "", empty array, null) for unreadable or logged-out state.
- Coordinates: a `MapCoord` is a scene tile (relative to the loaded map's base, as the client stores actor tiles), not a
  world tile; `Scene.getBase()`/`toWorld` convert. Fine coordinates and the camera are the same space × 128. In the
  RuneLite layer a `WorldPoint` is a world tile (scene tile + base, `y` = Lua's `z`) and a `LocalPoint` is
  `FineCoord(x, z)` without the height.
- Read-only by design: the API only reads the game process. Lua functions that change the client (highlights, camera
  control, actions) belong to a future in-process backend and are out of scope.
- Events are synthesised, only for events that have subscribers. Lua events: `EventPoller` diffs snapshots every 20 ms
  (the first poll after subscribing takes a silent baseline); ids are the Lua ids, and `Events.verify` checks them
  against the mapping's `enums.events`. RuneLite-shaped events (`com.kraken.api.events`, posted on the
  `eventbus.EventBus` passed to `KrakenApi.attach`): `impl.RuneLiteEventSource` has its own trackers, because spawns
  carry pinned handles, vars follow the client's working copy and every raw game state counts. Tracking that starts on
  the API's first poll replays the current state (game state, NPC/player spawns, stats, inventories); later it takes a
  silent baseline. The bus also matches supertypes and interfaces of an event, unlike RuneLite's.
- The `kraken-api-poll` thread is the "client thread". Each poll: `KrakenApi.invoke*` tasks (`TaskQueue`), Lua events,
  RuneLite events with `GameTick` after the tick's other events, `ClientTick`, `invokeAtTickEnd` tasks if a tick
  happened, then the 150 ms poll listener. Event handlers and tasks all run there.
- Adding a Lua mirror follows the worked example in `docs/lua-api-coverage.md`: take the handler RVA from
  `analysis.json` bindings, map each constant it uses to a mapping key (adding a spec recipe if needed), and replay the
  same steps in Java. Javadoc states any deliberate divergence from the native handler. Update the coverage tables in
  that doc in the same change.
- Tests: `GameFixture` lays out a fake Client → World → NPC/Player/inventory/scene/camera/interface/projectile graph in `FakeMemory` at the bundled 241.3
  mapping's real offsets, so tests cover the read plumbing, not whether offsets are correct (the live client verifies
  that). New reads extend `GameFixture`. RuneLite-layer adapters are tested over the same fixture in `impl` tests.

## Client architecture

- Guice wiring (`KrakenModule`); components are `@Singleton`s created on first use, so the frame is requested on the EDT
  after `Theme.install()`. Logging is `java.util.logging` via Lombok `@Log(topic = "kraken")`, and shows in the in-app
  console. `lombok.config` copies `@Named` onto Lombok-generated constructors, so `@AllArgsConstructor(onConstructor_ =
  @Inject)` with `@Named` fields works.
- `ApiConnection` attaches the API in the background (retrying while the client boots) with the client's one `EventBus`,
  posts `events.ApiStateChanged` on each state change, and fans each poll out to listeners on the poll thread, about
  every 150 ms. `callback.ClientThread` (RuneLite's) forwards to `KrakenApi.invoke*`, holding tasks until the API
  attaches.
- Plugins (`plugins.Plugin`, `@PluginDescriptor`, `@PluginDependency`) follow RuneLite's. `PluginManager` finds
  top-level `@PluginDescriptor` classes under `com.kraken.client.plugins` with ClassGraph (one package per plugin, e.g.
  `plugins.tickcounter`), gives each a child injector (plugin bound and installed as a module, plus each dependency's
  `getPublicModule()`), and starts/stops them on the EDT through the `@Named("edt")` executor (tests pass
  `Runnable::run`). On/off state is `ConfigManager` group `kraken`, key = class simple name lower-cased; a plugin whose
  `startUp` throws is switched off and keeps its error (`getFailure`). Plugins get a `StandInClient` proxy that answers
  "logged out" until the API attaches. `ExitHandler` posts `ClientShutdown`, stops plugins and flushes settings on exit.
  `ConfigManager` is the string store only so far (typed `@ConfigGroup` proxies are phase 5 of the design doc).
- Dev tools: subclass `DevTool<S>` and list it in `DevToolCatalog.create()`. `read(api)` runs on the poll thread and
  returns an immutable snapshot; `paintOverlay`/`createView` run on the EDT. A tool reads only while its overlay or
  window is showing. `PlannedTool` entries hold sidebar spots for features the API can't read yet; replace the entry
  when its API lands. Tools show what the Lua layer reads (`api.lua()`); a "RuneLite view" option (Location tool) shows
  the RuneLite-shaped values (`api.client()`) beside them for live checks.

## Reference

- `docs/updater-design.md`: anchor classes, verdict rules, carry-forward and delta propagation. Read before changing
  `consensus.py`, `carry.py` or `verify.py`.
- `docs/ghidra-walkthrough.md`: locating offsets by hand in Ghidra, with an x64 assembly primer.
- `docs/lua-api-coverage.md`: which Lua functions are mirrored, and the section roadmap.
- `debug/`: a macOS debug build of the client (build 216) with text dumps of its symbols, strings and constants
  (`symbols_table_demangled.txt` is ~80 MB). Grep them for real class and member names; nothing in the code reads them.

## Formatting

- Prefer not to leave comments in this format `// ---- dispatch (called by the poller) ---------------------------------------------------------` if you encounter comments like this remove them from the codebase
- Any commits made should NOT reference AI as a co-author
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
