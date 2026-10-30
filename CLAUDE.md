# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

Tooling for the official native Old School RuneScape client (`osclient.exe`, x64 C++, Windows). Three modules share one
contract, `api/src/main/resources/mappings/<version>.json`:

- `updater/` (Python, uv): statically analyses a client build (via a Ghidra export) and writes that mapping.
- `api/` (Java 21): attaches to the running client and reads game state with `ReadProcessMemory` along the mapping's
  offset chains. `GameSession.client()` is a RuneLite-shaped API whose getters replay what the client's Lua plugin API
  (`osrs.ClientOp`, `osrs.ClientState`…) reads.
- `client/` (Java 21 Swing + Guice): launches and embeds `osclient.exe`, injects `kraken_hook.dll` (native events,
  in-game drawing, native calls), and hosts plugins and dev tools.

## Commands

From the repo root (Gradle provisions JDK 21):

```bash
./gradlew :api:test --tests 'com.kraken.api.ClientTest'   # one class; append .methodName for one method
./gradlew :client:test                                    # headless
./gradlew :client:run --args="[osclient.exe] [--plugin <class>] [--developer-mode]"   # exe defaults to the Jagex Launcher install
./gradlew :client:buildNativeHook                         # kraken_hook.dll; also runs on every client build
./gradlew :api:generateGameval [-PgamevalCommit=<sha>]    # regenerates com.kraken.api.gameval; never edit it by hand
```

System properties: `kraken.mappings=<dir>`, `kraken.settings=<file>` (default `~/.kraken-native/settings.properties`),
`kraken.hook.dll=<path>`, `kraken.hook.projectileStep=true`.

From `updater/`:

```bash
uv run python -m unittest discover -s tests -t .
uv run python -m unittest tests.test_units.X86Parse       # one class; append .test_name for one method
uv run native-updater lint                                # validate spec YAML
uv run native-updater run --binary <osclient.exe> [--previous ../api/src/main/resources/mappings/<old>.json]
uv run native-updater bindings --binary <osclient.exe> --table osrs.IfType
uv run native-updater sql --binary <osclient.exe> "<SQL over program.db>"
```

`run` exits 0 if every P0 key is PROVEN/ANCHORED and none is FAILED/NEEDS_REVIEW, 1 on acquisition failure or crash, 2
if the mapping was written with keys left out or a P0 key only INFERRED. Other subcommands: `updater/README.md`.

### Platform

- Java and the client run on Windows. Building `kraken_hook.dll` (CMake 3.25+, via `client/native-hook.gradle`) needs
  Visual Studio's C++ tools or MinGW g++; see `client/src/main/native/kraken_hook/README.md`. Java tests use fakes and
  run headless. Run Ghidra `export` from Windows.
- `updater/` runs under WSL. Invoke `uv` through its shell function there, which sets
  `UV_PROJECT_ENVIRONMENT=.venv-linux` under `/mnt/*` so Windows' `.venv` survives.
- `tests/test_golden.py` skips unless the corpus build's binary and `program.db` are cached in `~/.kraken-native` (from
  WSL: the Windows profile's). Report a skip as skipped, not passed.

## Updater

Pipeline (`pipeline.py`): identify the build (`pe.py`: file version + sha256) → Ghidra export → `program.db`
(`model.py`) → parse sol2 Lua bindings and fingerprints → run key strategies in rounds (keys may depend on keys) →
consensus verdicts (`consensus.py`, `carry.py`) → invariants (`verify.py`) → emit.

- `src/native_updater/spec/*.yaml` defines every key (strategies, invariants, a `live` probe check). The prefix picks
  the mapping section: `Struct.field` → `structs`, `global.x`, `functions.x`, `enums.x`, `container.x.y`,
  `events.ON_X` (native raise sites, `events.py`). Feature areas pair a spec file with a module, e.g. `90-draw.yaml` ↔
  `highlights.py`, `62-menu-actions.yaml` ↔ `menuops.py`, `66-scripts.yaml` ↔ `scripts.py`.
- The mapping holds only PROVEN/ANCHORED/INFERRED, non-`internal` keys. Evidence and every Lua binding's handler RVA go
  to `~/.kraken-native/cache/<sha256>/analysis.json`; read it when a value looks wrong.
- Recipes: "Writing a recipe" in `updater/README.md`; explore with `bindings`, `sql` or `Provenance(model).facts(fn)`.
  Name offset params `offset` (YAML reads `off` as `false`).
- `updater/corpus/<version>.golden.json` holds accepted values; update it when a recipe change legitimately moves one.

## API

- No hard-coded offsets: `mapping.Mapping` serves the JSON. `GameSession.attach` picks `mappings/<version>.json` by the
  exe's version resource and requires its `sha256` to match. `ClientBuild.version` and the updater's
  `pe.py: file_version` parse versions the same way; change them together.
- One RuneLite-shaped layer: concrete classes by domain (`com.kraken.api`, `actor`, `scene`, `collision`, `item`,
  `definition`, `widgets`, `coords`, `events`, `eventbus`, `gameval`). No interfaces or adapters. Use RuneLite's names,
  types and meanings; leave out methods the API can't back (no stubs); put Lua-only reads on the matching class. Lombok
  `@Getter`/`@Setter` for plain fields.
- Plumbing is in `com.kraken.api.internal` (`reader.*`, `event.*`, `ConfigTypes` and its decoders, records like
  `MapCoord`). It and the hooks domain packages share (`Client.getActorReader()`, `toWorld`…) are public only because
  Java has no friend packages; their Javadoc says "Internal".
- Readers resolve offsets in their constructors: `mapping.offset(key)` for required keys (throws
  `MissingMappingException`), `optionalOffset`/`offsetOr`/`has` for keys older mappings lack. Reads use the `safe*`
  helpers and fall back to -1, "", an empty array or null. Chains start at `moduleBase + globals.client`.
- Identity: one handle per actor, pinned to its address (plus an NPC's creation cycle or a player's name), keeping its
  last values once the actor is gone. `TileObject`s are values, `Tile`s read empty once the map base moves, `Widget`s,
  `Projectile`s and `GraphicsObject`s are snapshots.
- Coordinates: actor tiles are scene tiles (`MapCoord`, relative to the map base); `Client.toWorld` gives a
  `WorldPoint`; a `LocalPoint` is scene × 128. The client keeps height in `y` and north in `z`; the API swaps them.
- Read-only, except two kinds of `internal.invoke.NativeCall` the hook DLL runs on the game thread: highlights
  (`com.kraken.api.highlight`, `Client.getHighlights()`; sends diffs, re-applies all on reconnect) and menu actions
  (`com.kraken.api.action.Actions`, `Client.menuAction`; calls the client's own minimenu row bodies and packet
  helpers). Other Lua functions that change the client (camera) are out of scope.
- Events (`com.kraken.api.events`): `EventSource`'s trackers build them, only for subscribed events, by comparing game
  state each poll. Hook raises (`GameSession.onNative`) only make trackers run sooner; ticks always come from the
  game's counters. Where a diff can miss changes (interfaces, varcs), trackers also apply the hook's ordered reports.
  `ScriptPreFired`/`ScriptPostFired` come straight from hook reports (`GameSession.onScript`), with the stacks readable
  via `Client.getIntStack()` and friends during the handler. The bus also matches supertypes and interfaces.
- The `kraken-api-poll` thread is the client thread: every 20 ms or on a raise it runs `invoke*` tasks, events
  (`GameTick` after the tick's other events, `ClientTick` per cycle), `invokeAtTickEnd` tasks, the frame listener
  (overlays), then the 150 ms poll listener.
- Adding a Lua read: take the handler RVA from `analysis.json`, map each constant it uses to a mapping key (adding a
  spec recipe if needed), and replay the steps in the struct's reader (or `Client`), under RuneLite's name if it has
  one. Javadoc names the Lua function and any deliberate divergence.
- Tests: `GameFixture` lays out a fake game in `FakeMemory` at the bundled 241.3 offsets, so tests cover plumbing, not
  offsets. Extend it for new reads. Tests sit in the tested class's package and use `new Client(game.memory, game.map)`.

## Client

- Guice: `KrakenModule` binds launch-time `@Singleton`s (frame, panels, config, `EventBus`, `PluginManager`), created
  on first use, so request the frame on the EDT after `Theme.install()`. After attaching, `ProcessAttacher` builds the
  session injector (`SessionModule`: `GameSession`, `Client`, `Mapping`, `@Named("pid")`, session services such as
  `callback.ClientThread`; bind new ones there explicitly). Pre-game components (frame, dev tools, overlay renderer)
  don't inject session objects; they use `ProcessAttacher`'s state and listeners.
- Logging: `java.util.logging` via Lombok `@Log(topic = "kraken")`, shown in the in-app console. `lombok.config` copies
  `@Named` onto generated constructors.
- Plugins (`plugins.Plugin`, `@PluginDescriptor`, `@PluginDependency`): `PluginManager` finds top-level descriptors
  under `com.kraken.client.plugins` (one package each), gives each a child of the session injector, and starts/stops
  them on the EDT via `@Named("edt")` (tests pass `Runnable::run`). On/off state: config group `kraken`, key = the
  lower-cased simple class name. Tests build the session injector from `new GameSession(memory, mapping, bus)`.
- Config (`plugins.config`): `ConfigManager.getConfig` proxies a `@ConfigGroup` interface, changes post
  `events.ConfigChanged`, `ui.ConfigPanel` builds the page (supported types listed on `ConfigManager`). Dynamic items:
  `@ShowIf(key, values)` (`ConfigManager.isShown`), `@Suggestions` (filled via `events.ConfigSuggestionsRequested` →
  `suggest`), `Button` (posts `events.ConfigButtonClicked`).
- `KrakenConfig` is the Settings page plus hidden layout state the next launch restores. `FrameRates` tracks game and
  overlay FPS and sets the DLL's unfocused frame cap (`FRAME_LIMIT`).
- Overlays (`ui.overlay`): `OverlayRenderer` draws on the poll thread, `OverlayCompositor` lays out. Frames go to the
  DLL's shared-memory `GameSurface` while it is active, otherwise to `GameOverlay` (a click-through window; dev tool
  overlays always use it). Alt + drag moves or resizes overlays (`OverlayEditor`), right-click resets; `OverlayManager`
  saves placements in group `overlays`. Worked examples: `plugins.npchighlight`, `objectmarkers`, `tilemarkers`,
  `tickcounter`, `actiontester`.
- Dev tools: subclass `DevTool<S>` and list it in `DevToolCatalog.create()`. `read(api)` runs on the poll thread and
  returns an immutable snapshot; `paintOverlay`/`createView` run on the EDT. Tools read and subscribe only while
  showing. `PlannedTool` holds spots for features the API can't read yet; `view.GamePicker` lets the user click
  something in the game.

## Reference

- `debug/`: a macOS debug build of the client (216) with symbol, string and constant dumps
  (`symbols_table_demangled.txt` is ~80 MB). Grep them for real class and member names.
- Design docs (`updater-design`, `event-hooks-design`, `event-backlog`, `in-game-drawing`, `menu-actions`,
  `ghidra-walkthrough`) are in git history: `git show ffc8ed8^:docs/<name>.md`.

## Conventions

- No banner comments (`// ---- section ----`); remove any you find.
- Commits never credit AI as a co-author.
- Comments, Javadoc and test messages say what the code does. They never mention RuneLite (credited in the README; the
  generated `gameval` license notices are the exception), don't label extras, and don't call the library "Kraken".
- Javadoc has `@param`/`@return` for every param and return type, and is never a one-liner:

```java
/**
 * This does something
 */
```
