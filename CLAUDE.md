# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Tooling for the official native Old School RuneScape client (`osclient.exe`, x64 C++, Windows). Three modules, joined
by one contract, `api/src/main/resources/mappings/<version>.json`:

- `updater/` (Python, uv): statically analyses a client build (via a Ghidra export) and writes that mapping.
- `api/` (Java 21 library): attaches to the running client and reads game state with `ReadProcessMemory`, following
  the offset chains in the mapping. `GameSession.client()` is a RuneLite-shaped API (`Client`, `NPC`, `WorldPoint`…)
  whose getters replay what the client's own Lua plugin API (`osrs.ClientOp`, `osrs.ClientState`…) reads.
- `client/` (Java 21 Swing + Guice): launches `osclient.exe`, embeds its window in a RuneLite-style frame, injects
  `kraken_hook.dll` for native events and drawing in the game, and hosts plugins and dev tools.

## Commands

Java, from the repo root (the Gradle toolchain auto-provisions JDK 21):

```bash
./gradlew :api:test
./gradlew :api:test --tests 'com.kraken.api.ClientTest'                                # one class
./gradlew :api:test --tests 'com.kraken.api.ClientTest.readsTicksCyclesAndLogin'       # one method
./gradlew :client:test                                    # plugins, config, settings pages, overlay layout (headless)
./gradlew :client:run --args="D:/path/to/osclient.exe"   # Windows only; arg optional (defaults to the Jagex Launcher install)
./gradlew :client:run --args="--plugin com.example.MyPlugin --developer-mode"   # extra plugin; load developer plugins
./gradlew :client:buildNativeHook                         # compile kraken_hook.dll (CMake) into client resources (also runs on every client build)
./gradlew :api:generateGameval [-PgamevalCommit=<sha>]   # rewrite com.kraken.api.gameval from RuneLite's (by hand)
```

System properties: `kraken.mappings=<dir>` (a folder of `<version>.json` instead of the bundled ones),
`kraken.settings=<file>` (default `~/.kraken-native/settings.properties`), `kraken.hook.dll=<path>`,
`kraken.hook.projectileStep=true`.

Updater, from `updater/`:

```bash
uv run python -m unittest discover -s tests -t .          # all tests
uv run python -m unittest tests.test_units.X86Parse       # one class (append .test_name for one method)
uv run native-updater lint                                # validate spec YAML
uv run native-updater run --binary <osclient.exe> [--previous ../api/src/main/resources/mappings/<old>.json]
uv run native-updater bindings --binary <osclient.exe> --table osrs.IfType   # dump parsed Lua bindings
uv run native-updater sql --binary <osclient.exe> "<SQL over program.db>"
```

`run` exits 0 when every P0 key is PROVEN/ANCHORED and no key is FAILED/NEEDS_REVIEW, 1 on acquisition failure or
crash, 2 when the mapping was written but some key was left out or a P0 key is only INFERRED. Other subcommands
(`export`, `build-model`, `probe`) are in `updater/README.md`.

### Platform

- Windows only: the client attaches to, reads and injects into `osclient.exe`. A full `:client` build compiles
  `kraken_hook.dll`, a CMake project (`client/native-hook.gradle` runs its presets), so it needs Visual Studio's C++
  tools or a MinGW g++, and CMake 3.25+ (Visual Studio bundles one; see `client/src/main/native/kraken_hook/README.md`
  for its layout and build). Build output goes to `client/build/native/kraken_hook/<preset>`. The Java test suites use
  fakes and run headless. Run Ghidra `export` from Windows too.
- The `updater/` Python tooling runs under WSL: `uv` is a shell function there that sets
  `UV_PROJECT_ENVIRONMENT=.venv-linux` under `/mnt/*`, keeping Windows' `.venv` intact. Invoke uv through that function.
- `tests/test_golden.py` skips unless the corpus build's binary and `program.db` are cached in `~/.kraken-native` (from
  WSL: the Windows profile's `.kraken-native`). Report a skipped golden run as skipped, not passed.

## Updater architecture

Pipeline (`pipeline.py`): identify the build (`pe.py` file version + sha256) → Ghidra export → `program.db` SQLite
(`model.py`) → parse sol2 Lua bindings and fingerprints → run each key's strategies in rounds (keys can depend on
other keys) → consensus verdicts (PROVEN/ANCHORED/INFERRED/NEEDS_REVIEW/FAILED) → invariants (`verify.py`) → emit.

- `src/native_updater/spec/*.yaml` is the source of truth for what gets mapped: one entry per key with strategies,
  invariants and a `live` probe check. Key names map to mapping sections: `Struct.field` → `structs`, `global.x` →
  `globals`, `functions.x` → `functions`, `enums.x` → `enums`, `container.x.y` → `containers`, `events.ON_X` →
  `events` (the client's native event raise sites; `events.py`, see `docs/event-hooks-design.md`). The highlight
  functions, `HighlightConfig` layout and the DLL's pump/present hook sites are `spec/90-draw.yaml` (`highlights.py`);
  the minimenu row bodies and packet helpers behind menu actions are `spec/62-menu-actions.yaml` (`menuops.py`); the
  script runtime the DLL's script hooks sit on (runner, stacks, listener request) is `spec/66-scripts.yaml`
  (`scripts.py`); the interface open and close functions and the top-level interface are `spec/67-interfaces.yaml`
  (`interfaces.py`); the chat history (line layout, id counter, id-to-line map) is `spec/68-chat.yaml` (`chat.py`);
  each actor's hitmark vector is `spec/69-hitmarks.yaml` (`hitmarks.py`); its spot animation slots are
  `spec/42-spotanims.yaml` (`spotanims.py`); its overhead text and the client's actor step counter are
  `spec/41-overhead.yaml` (`overhead.py`); the varc setters scripts call are `spec/64-varcs.yaml` (`varcs.py`).
- The mapping JSON holds only PROVEN/ANCHORED/INFERRED, non-`internal` keys. The evidence behind each value, plus every
  Lua binding's handler RVA, goes to `~/.kraken-native/cache/<sha256>/analysis.json`; read that when a value looks
  wrong.
- Writing recipes: see "Writing a recipe" in `updater/README.md`. Inspect what a recipe can match with `bindings`,
  `sql`, or `Provenance(model).facts(fn)` in `uv run python`. Spell offset params `offset`, since YAML parses `off` as
  `false`.
- `updater/corpus/<version>.golden.json` holds the accepted values; update it when a recipe change legitimately moves a
  value.

## API architecture

- No hard-coded offsets: `mapping.Mapping` serves everything from the JSON. `GameSession.attach` picks
  `mappings/<version>.json` by the exe's version resource and rejects it unless its `sha256` matches.
  `ClientBuild.version` parses the version the same way as the updater's `pe.py: file_version`; change the two
  together.
- One layer, shaped as RuneLite's: concrete classes grouped by domain (`com.kraken.api` for `GameSession`, `Client` and
  client-wide types; `actor`, `scene`, `collision`, `item`, `definition`, `widgets`, `coords`, `events`, `eventbus`,
  `gameval`). No interfaces or adapters: each getter reads memory itself or calls a reader that does. Names, types and
  meanings follow RuneLite's; methods the API can't back are left out, not stubbed. Reads the Lua API has and RuneLite
  doesn't are added to the matching class. Use Lombok `@Getter`/`@Setter` for plain fields.
- Plumbing lives in `com.kraken.api.internal`: `reader.*` (`GameReader`, the base, and the per-struct readers),
  `event.*` (`EventSource`, its trackers, `NativeEvent`), `ConfigTypes` and the decoders that build the compositions,
  and the records readers return (`MapCoord`, `Loc`…). It is public because Java has no friend packages, as are the
  hooks the domain packages share (`Client.getActorReader()`, `toWorld`, handle constructors…); their Javadoc says
  "Internal", and plugins never need them.
- Readers resolve offsets once in their constructors: `mapping.offset(key)` for required keys (throws
  `MissingMappingException`), `optionalOffset`/`offsetOr`/`has` for keys older mappings may lack. Reads go through the
  `safe*` helpers and return fallbacks (-1, "", empty array, null) for unreadable or logged-out state. Chains start at
  `moduleBase + globals.client`.
- Identity: actor handles are pinned to an actor's address (plus an NPC's creation cycle or a player's name), one per
  actor, and keep their last values once it is gone. `TileObject`s are values, `Tile`s read empty once the map base
  moves, `Widget`s, `Projectile`s and `GraphicsObject`s are snapshots (a graphics object's snapshots are equal across
  reads: address plus what the server sent).
- Coordinates: actor tiles are scene tiles (relative to the map base), returned as `MapCoord`s; `Client.toWorld` adds
  the base for a `WorldPoint`. A `LocalPoint` is scene × 128. The client keeps height in `y` and north in `z`; the API
  uses `y` north, `z` height, so readers that copy Lua's order swap them.
- Read-only by design, with two exceptions, both `internal.invoke.NativeCall`s the hook DLL runs on the game thread
  (`GameSession.setNativeInvoker`). Highlights: `com.kraken.api.highlight` (`Client.getHighlights()`) keeps each
  `HighlightChannel`'s style and targets, sends the differences and re-applies everything when the hook reconnects
  (`docs/in-game-drawing.md`). Menu actions: `com.kraken.api.action.Actions` (`Client.getActions()`,
  `Client.menuAction`) calls the client's own minimenu row bodies with the captures a row would hold (NPC, loc,
  ground item and player ops, Walk here, component ops, selecting an item and using it on something, pause
  buttons) and builds the mouse click packet and the dialog answers (count, name, string, item) with the client's
  packet helpers in the batch's scratch block; nothing is queued for a later hook (`docs/menu-actions.md`). Other
  Lua functions that change the client (camera control) are out of scope.
- Events (`com.kraken.api.events`) are synthesised by `EventSource`'s trackers, only for events with subscribers. The
  native hook's raises (`GameSession.onNative`) trigger trackers sooner; ticks still come from the game's counters, so
  a raise never counts a tick itself. Design and the trigger table: `docs/event-hooks-design.md`. The bus also matches
  supertypes and interfaces of an event. `ScriptPreFired`/`ScriptPostFired` have no tracker: the hook reports each
  script with the top of the stacks (`GameSession.onScript`, only while they have subscribers), the API posts one event
  per report, and `Client.getIntStack()` and the other stack getters give the captured stacks while its handlers run.
  `WidgetTracker` (`WidgetLoaded`/`WidgetClosed`) compares the component table every poll and also applies the
  interface open and close hooks' reports in order, since a dialogue reopening its group shows no change in the table.
  `ChatMessage` follows the chat's next-id counter, `HitsplatApplied` compares each actor's hitmark slots,
  `FocusChanged` follows `Client.isFocused()` (Windows' focus: the client keeps none), and `FakeXpDrop` comes from the
  script reports for the server's XP drop script (the API asks the hook for that root script by id).
  `VarcTracker` (`VarClientIntChanged`/`VarClientStrChanged`) compares the varc store every poll and also applies the
  varc setter hooks' reports in order, so each change a script makes posts even when several come between two polls.
  `AnimationChanged`, `ActorDeath` and `NpcChanged` compare one value per actor (`ActorValues`, strict reads keyed by
  slot): `Actor.getAnimation()`, `isDead()` and an NPC's base type id (a transform keeps the NPC and its handle). The
  animating actors are read every poll, since the client ends animations itself between ticks. `GraphicChanged`
  compares each actor's spot animation slots the same way (slot, id and start cycle: every set starts a new one), and
  `OverheadTextChanged` each actor's overhead text and timer: the same text with more time left than the client's actor
  step counter (`Client.actorSteps`) allows was said again.
- The `kraken-api-poll` thread is the "client thread": every 20 ms, or at once on a raise, it runs `invoke*` tasks,
  the events (`GameTick` after the tick's other events, a `ClientTick` per client cycle; with the hook connected only
  when a raise asks or every 250 ms), `invokeAtTickEnd` tasks, the frame listener (overlays), then the 150 ms poll
  listener. Event handlers, tasks and overlay rendering all run there.
- Adding a read the Lua API has: take the handler RVA from `analysis.json` bindings, map each constant it uses to a
  mapping key (adding a spec recipe if needed), and replay the same steps in Java in the reader for that struct (or
  `Client` for its own fields), exposed on the class it belongs to, under RuneLite's name if RuneLite has one. Javadoc
  names the Lua function and any deliberate divergence from the native handler.
- `com.kraken.api.gameval` is generated (`generateGameval`) and committed; don't edit it by hand.
- Tests: `GameFixture` lays out a fake game graph in `FakeMemory` at the bundled 241.3 mapping's real offsets, so tests
  cover the read plumbing, not whether offsets are correct (the live client verifies that). New reads extend
  `GameFixture`. Tests sit in the package of the class they test and build the client with
  `new Client(game.memory, game.map)`.

## Client architecture

- Guice wiring (`KrakenModule`); components are `@Singleton`s created on first use, so the frame is requested on the EDT
  after `Theme.install()`. Logging is `java.util.logging` via Lombok `@Log(topic = "kraken")`, shown in the in-app
  console. `lombok.config` copies `@Named` onto Lombok-generated constructors.
- Two injectors. `KrakenModule` binds what exists from launch (frame, panels, config, `EventBus`, `PluginManager`).
  `ProcessAttacher` attaches the API in the background, then creates the session injector (`SessionModule`: the
  `GameSession`, `Client`, `Mapping`, `@Named("pid")`, and session-only services such as `callback.ClientThread` and
  `callback.NativeEventServer`; bind such services there explicitly). Components that exist before the game (frame,
  dev tools, overlay renderer) don't inject session objects; they use `ProcessAttacher`'s state, attach, poll and
  frame listeners.
- Plugins (`plugins.Plugin`, `@PluginDescriptor`, `@PluginDependency`) follow RuneLite's. `PluginManager` finds
  top-level `@PluginDescriptor` classes under `com.kraken.client.plugins` (one package per plugin), gives each a child
  of the session injector, and starts/stops them on the EDT through the `@Named("edt")` executor (tests pass
  `Runnable::run`). Plugins load only once the API attaches. On/off state is `ConfigManager` group `kraken`, key =
  class simple name lower-cased. Tests build the session injector from `new GameSession(memory, mapping, bus)`.
- Config (`plugins.config`) follows RuneLite's: `ConfigManager.getConfig` proxies a `@ConfigGroup` interface; changes
  post `events.ConfigChanged`; `ui.ConfigPanel` generates the settings page. Supported types are listed on
  `ConfigManager`. The page is dynamic: `@ShowIf(key, values)` shows an item or section only while another item has
  one of the values (several must all hold, a chain nests; `ConfigManager.isShown` decides, for tests),
  `@Suggestions` gives a `String` item a drop-down the plugin fills when it opens (`events.ConfigSuggestionsRequested`,
  answered with `suggest`), and a `Button` item is a button that posts `events.ConfigButtonClicked`.
- `KrakenConfig` is the Settings page (window, frame rate, overlay look) and, in hidden items, the layout the next
  launch restores: `KrakenFrame` saves its bounds a second after it stops moving, and the sidebar and console save
  their own state. `FrameRates` holds the game's frame rate (the DLL's FPS reports) and the overlays', and decides the
  frame cap the DLL holds the game to while Kraken is unfocused (`FRAME_LIMIT`, `docs/in-game-drawing.md` §2.3).
- Overlays (`ui.overlay`) follow RuneLite's: `OverlayRenderer` draws on the poll thread, `OverlayCompositor` does the
  layout. While the DLL draws the `GameSurface` (a shared-memory triple buffer it composites into the game's frame
  before present), frames go there at the framebuffer's size; otherwise `GameOverlay` (the click-through window over
  the game) paints them on the EDT. Dev tool overlays always use the window. With Alt held, `OverlayEditor` outlines
  the movable non-dynamic overlays and lays a nearly clear, non-activating window over just them to catch the mouse:
  drag to move (`OverlayDrag`), drag an edge to resize (`resizable`, on for `OverlayPanel`), right-click to reset.
  `OverlayManager` saves placements in settings group `overlays` and restores them on `add`. `plugins.npchighlight`,
  `plugins.objectmarkers`, `plugins.tilemarkers` (game highlights plus a fallback overlay), `plugins.tickcounter`
  and `plugins.actiontester` (menu actions: every action and packet, run from its settings page, which shows only
  the chosen action's settings and suggests what is loaded) are the worked examples.
- Dev tools: subclass `DevTool<S>` and list it in `DevToolCatalog.create()`. `read(api)` runs on the poll thread and
  returns an immutable snapshot; `paintOverlay`/`createView` run on the EDT. A tool reads only while it is showing.
  `PlannedTool` entries hold spots for features the API can't read yet. Event-driven tools subscribe to
  `api.eventBus()` only while they show. `host()` gives a tool the game area and its own overlay switch;
  `view.GamePicker` lays a click-catching window over the game so the user can point at something (the widget
  inspector picks widgets with it, and names groups and components from `gameval.InterfaceID` via `WidgetNames`).

## Reference

- `docs/updater-design.md`: anchor classes, verdict rules, carry-forward and delta propagation. Read before changing
  `consensus.py`, `carry.py` or `verify.py`.
- `docs/event-hooks-design.md`: the native raise sites, the hook DLL and its protocol, how raises trigger trackers,
  the script hooks behind `ScriptPreFired`/`ScriptPostFired` (§2.6), the interface events (§2.7), chat,
  hitsplats, focus and fake XP drops (§2.8), the varc events (§2.9), animations, deaths and NPC changes (§2.10), and
  spot animations and overhead text (§2.11).
- `docs/event-backlog.md`: RuneLite events not posted yet, what each needs, and a prompt for adding a batch of them.
- `docs/in-game-drawing.md`: the highlight functions, the pump and present hooks, protocol v3's commands and the
  overlay surface layout; its last section lists the live checks still pending.
- `docs/menu-actions.md`: the minimenu row bodies and packet helpers behind `Actions`, protocol v4's scratch and
  indirect call arguments, and the live checks still pending.
- `docs/ghidra-walkthrough.md`: locating offsets by hand in Ghidra, with an x64 assembly primer.
- `debug/`: a macOS debug build of the client (build 216) with text dumps of its symbols, strings and constants
  (`symbols_table_demangled.txt` is ~80 MB). Grep them for real class and member names; nothing in the code reads them.

## Formatting

- Remove banner comments like `// ---- dispatch (called by the poller) -----------------` wherever you find them.
- Commits must NOT reference AI as a co-author.
- Comments, Javadoc and test messages say what the code does and never mention RuneLite (it is credited in the
  README's Acknowledgements), even where a class follows RuneLite's design. The exception is the license notice in
  the generated `gameval` files. Javadoc doesn't label extras or call the library "Kraken".
- Block comments include `@param` and `@return` for the method's params and return type.
- Block comments are never one-liners, i.e. prefer this
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
