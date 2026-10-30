# Cookbook

Step-by-step recipes for the changes contributors make most. Each one lists every place a change has to land, so
nothing is left half done. The other developer pages explain the parts these steps touch.

## Running the Tests

```bash
./gradlew :api:test                                        # API: plumbing over a fake game in memory
./gradlew :api:test --tests 'com.kraken.api.ClientTest'    # one class; append .methodName for one method
./gradlew :client:test                                     # client: plugins, config, overlays (headless)
./gradlew :client:test -x buildNativeHook                  # the same, without compiling the DLL
```

The updater's tests run under WSL, through the `uv` shell function from [`updater/README.md`](../../updater/README.md)
(it keeps WSL's environment in `.venv-linux`, apart from Windows' `.venv`):

```bash
cd updater
uv run python -m unittest discover -s tests -t .           # unit tests, plus golden tests if the corpus build is cached
uv run python -m unittest tests.test_units.X86Parse        # one class; append .test_name for one method
uv run native-updater lint                                 # check the spec YAML
```

The golden tests skip unless the corpus build's binary and `program.db` are cached in `~/.kraken-native`. A skip is a
skip, not a pass: say so when reporting results.

The API's tests use [`GameFixture`](../../api/src/test/java/com/kraken/api/GameFixture.java), a fake game laid out in
memory at the bundled 241.3 offsets. They test the plumbing, not the offsets: only the updater's golden tests and a
live check prove a value is right.

## Mapping a New Game Build

The user-level steps are in [Mapping a new game build](../updater.md). As a contributor, also:

1. **Export once, from Windows.** Run the Ghidra export from Windows (`native-updater export`, or `run` with
   `GHIDRA_INSTALL_DIR` set). WSL then reuses it from the shared cache.
2. **Run with the previous mapping**, so carry-forward and the diff work:
   `uv run native-updater run --binary <new osclient.exe> --previous ../api/src/main/resources/mappings/<old>.json`.
3. **Read the summary.** Every key left out (`NEEDS_REVIEW` or `FAILED`) is listed with its reason, then every value that
   moved. A value that moved isn't wrong, but one that moved without its neighbours deserves a look.
4. **Fix what's left out.** For each `FAILED` or `NEEDS_REVIEW` key, read its entry in
   `~/.kraken-native/cache/<sha256>/analysis.json` and the repair bundle in `repair/<key>.md` next to it, then fix the
   recipe ([Reverse engineering](reverse-engineering.md), [Updater](updater.md)). Re-run until it exits 0, or until
   what's left is understood and optional.
5. **Probe** the mapping against the running game, logged in, from Windows:
   `native-updater probe --mapping <new>.json --display-name <name>`.
6. **Check in the API.** Run Kraken against the new build and open the dev tools: NPCs, players, inventories, widgets.
7. **Add a golden file** for the new build when it becomes the reference: `updater/corpus/<version>.golden.json`, with
   the build's `sha256`, `version`, a `source` note, and the accepted `keys`, `fingerprints` and `bindings`.
8. **Commit** the mapping in `api/src/main/resources/mappings/` (and the golden file). Binaries, Ghidra projects and
   `analysis.json` stay in `~/.kraken-native`; never commit them.

If Jagex changes how the exe's version resource is laid out, `ClientBuild.version` (Java) and `pe.py: file_version`
(Python) must keep parsing it the same way: change them together.

## Adding a Mapping Key

When the API needs a value the mapping doesn't have yet:

1. **Find the anchor.** Usually a Lua binding whose handler reads the value: list them with
   `native-updater bindings --binary <exe> --table osrs.<Table>`, or look in `analysis.json`'s `bindings`. See
   [Reverse engineering](reverse-engineering.md).
2. **Pick the spec file** for the feature area (`spec/40-actor.yaml` for actor fields, `spec/90-draw.yaml` for drawing,
   and so on) and add an entry. The key's prefix picks its mapping section: `Struct.field` → `structs`, `global.x` →
   `globals`, `functions.x`, `enums.x`, `container.x.y`, `events.ON_X`.

   ```yaml
   - key: Actor.interacting
     type: i32
     priority: P0
     live: interacting
     strategies:
       - getter: { in: osrs.Entity.isInteracting, base: sp_result, load: i32, select: { returned: true } }
     invariants:
       - adjacent: { key: Actor.interactingType, delta: -1 }
   ```

   Give it two strategies from different anchor classes where you can, so it can reach `PROVEN`. Name offset
   parameters `offset`, never `off` (YAML reads `off` as `false`). Mark keys that only feed other keys
   `internal: true`. [Updater](updater.md) has the full field reference.
3. **Lint and run:** `uv run native-updater lint`, then `uv run native-updater run`. Check the key's verdict in the
   summary and its evidence in `analysis.json`.
4. **Add it to the golden corpus** (`updater/corpus/<version>.golden.json`, under `keys`) once you trust the value, and
   a unit test with a `fake_model.py` fixture if the recipe uses a new strategy or extractor.
5. **Use it in the API** (next recipe). If older mappings lack the key, read it with `optionalOffset`, `offsetOr` or
   `has` rather than `offset`, so those mappings still attach.

## Adding a Lua-Backed Read

Every getter replays what one of the game's Lua plugin API functions reads. To add one:

1. **Find the handler.** Take the Lua function's handler RVA from `analysis.json` (`bindings`), and read its
   instructions (`native-updater sql`, Ghidra, or the updater's `Provenance(model).facts(fn)`).
2. **Map each constant it uses** to a mapping key, adding spec recipes for any that are missing (previous recipe).
3. **Resolve the offsets in the reader's constructor**: in the struct's reader under `internal/reader/` (or in
   `Client` for client-level values), with `offset(key)` for keys every mapping has and `optionalOffset`/`offsetOr`
   for newer ones.
4. **Replay the handler's steps** with the `safe*` helpers, which fall back to -1, `""`, an empty array or null
   instead of throwing. Chains start at `moduleBase + globals.client`.
5. **Expose it** on the matching public class, under RuneLite's name, type and meaning if RuneLite has the method;
   otherwise as a Lua-only read on the class it belongs to. Use Lombok `@Getter` for plain fields. No interfaces, no
   stubs.
6. **Javadoc** names the Lua function, any deliberate divergence from it, `@param` for every parameter and `@return`
   for the result. Don't call the library "Kraken" or mention RuneLite in Javadoc.
7. **Test** it: extend `GameFixture` with the new fields at their 241.3 offsets, and read them back through
   `new Client(game.memory, game.map)`. Tests live in the tested class's package.
8. **Show it** in a dev tool if it helps people check it live.

## Adding an Event

1. **The event class** goes in `com.kraken.api.events`, with RuneLite's name and fields if RuneLite has it (Lombok
   `@Value`, or `@Data` when RuneLite's is mutable).
2. **A tracker** in `internal/event/` extends [`Tracker`](../../api/src/main/java/com/kraken/api/internal/event/Tracker.java):
   * pass the event classes that should switch it on to the constructor, so it only reads while something subscribes;
   * `baseline(boolean replay)` reads the starting state, posting it as events when `replay` is true (tracking that
     starts as the API attaches), and returns false if the state can't be read yet;
   * `update(boolean ticked)` reads again and posts the differences;
   * `forget()` drops the baseline when the last subscriber leaves.
3. **Register it** in the `EventSource` constructor. The list's order is the order events post in within a poll.
4. **Make the hook wake it** (optional): override `triggerNames()` with the native event names that should make it
   read at once, and `onTrigger(NativeEvent)` if it uses the message's payload. Then make sure the hook sends those
   names (next recipe).
5. **Test** it with `GameFixture`, and through `GameSession.onNative` if it takes triggers (see `GameSessionTest`).
6. **Add a filter** for it to the Event log dev tool ([`EventLogTool`](../../client/src/main/java/com/kraken/client/ui/devtools/tools/EventLogTool.java)).
7. **Document it** in the user [Events](../events.md) table, saying whether it needs the hook.

[Events](events.md) explains the replay rule, scene loads and post order.

## Hooking a New Raise or Function

For a client function whose calls should wake a tracker:

1. **Check its shape.** The generic detour forwards four integer registers and nothing else. A function that takes
   arguments on the stack or in XMM registers needs a dedicated detour in the DLL (see
   [Native hook](native-hook.md#adding-a-hook)).
2. **Get it into the mapping:** a `functions.<name>` key for a plain function, or an `events.ON_X` entry for a Lua
   event raiser (found by `events.py`). Add the recipe and re-run the updater.
3. **Name it** in [`NativeEvent`](../../api/src/main/java/com/kraken/api/internal/event/NativeEvent.java) (for
   functions), and add it to [`NativeEvents`](../../client/src/main/java/com/kraken/client/callback/NativeEvents.java):
   `LUA` for raisers, `FUNCTIONS` for plain functions, with a capture plan for any payload (registers and how to read
   them: `RAW`, `I32`, `I64`, `I32X2`, `CSTR`).
4. **Route it** to a tracker through `triggerNames()`.
5. Nothing changes in the DLL: the handshake carries the new slot. Restart the game to pick it up.

## Adding a DLL Command

1. Add `CMD_<NAME>` to [`protocol.h`](../../client/src/main/native/kraken_hook/src/protocol.h) and
   `COMMAND_<NAME>` with an encoder to [`NativeProtocol`](../../client/src/main/java/com/kraken/client/callback/NativeProtocol.java).
2. Handle it in the DLL: a function in its module that reads the body with a `FrameReader`, and a `case` in
   `readerThread` (`connection.cpp`). Hand anything the game thread needs over without blocking it.
3. Undo its effect in `connection::run`'s cleanup, for when the connection drops.
4. Send it from [`NativeEventServer`](../../client/src/main/java/com/kraken/client/callback/NativeEventServer.java)
   once STATUS says the feature it needs is live.
5. If it needs a new feature bit, add it to `FEATURE_*` on both sides and to `sendStatus`.
6. Add new source files to `add_library` in `CMakeLists.txt`.
7. Document it in [Native protocol](native-protocol.md).

## Adding a Dev Tool

1. **Subclass** [`DevTool<S>`](../../client/src/main/java/com/kraken/client/ui/devtools/DevTool.java) in
   `ui/devtools/tools/`, where `S` is an immutable snapshot type. The constructor passes an id (the preference key, so
   keep it stable), a name, a `Category`, a Material Design icon and a description.
2. **Say what it offers:** override `overlaySupport()` and/or `windowSupport()` to return `Support.READY`.
3. **Read** in `read(GameSession)`, which runs on the client thread and returns the snapshot (null for nothing to
   show). Read only what the snapshot needs; the tool only reads while its overlay or window is showing.
4. **Draw** in `paintOverlay(S, OverlayContext)` and/or build a window in `createView()`, from the views in
   `ui/devtools/view/` (`TableView`, `FactsView`, `LogView`, `TabbedView`…). These run on the EDT and use only the
   snapshot.
5. **Options** are fields made with `option(key, label, defaultValue)`; they appear as checkboxes under the row.
6. **List it** in `DevToolCatalog.create()`, in its category, in sidebar order.
7. For a tool the API can't back yet, add a `PlannedTool` that says what it needs.

`WallsTool` is a short complete example.

## Adding a Built-In Plugin

1. Create a package under `client/src/main/java/com/kraken/client/plugins/<name>/` with the plugin, its config
   interface and its overlays; [Writing a plugin](../writing-plugins.md) covers each.
2. Built-in plugins set `enabledByDefault = false`, so nothing runs until the user switches it on.
3. Add a section for it to the user [Plugins](../plugins.md) page.

## Adding a Client Setting

1. Add a `@ConfigItem` to [`KrakenConfig`](../../client/src/main/java/com/kraken/client/KrakenConfig.java), in one of
   its sections, with a default body. The Settings page picks it up.
2. Inject `KrakenConfig` where it's used, and react to `ConfigChanged` with group `kraken` if it applies live.
3. Hidden state (window layout and the like) uses `hidden = true` and a matching `void` setter.
4. Add it to the settings table in [Using the client](../client.md).

## Adding a Session Service

A service that needs the attached game (`GameSession`, `Client`, the mapping or the pid) belongs to the session
injector:

1. Write it as a normal class with an `@Inject` constructor.
2. Bind it in [`SessionModule`](../../client/src/main/java/com/kraken/client/SessionModule.java) explicitly, as a
   singleton: `bind(MyService.class).in(Singleton.class)`. Without the binding, each plugin's child injector would make
   its own instance.
3. Pre-game components (the frame, dev tools, the overlay renderer) can't inject it; they use `ProcessAttacher`'s
   state and listeners instead. See [Client internals](client.md).

## Regenerating Game Constants

`com.kraken.api.gameval` is generated from RuneLite's gameval constants:

```bash
./gradlew :api:generateGameval -PgamevalCommit=<runelite commit sha>
```

Without `-PgamevalCommit` it uses the commit pinned in `api/build.gradle`; move that pin when the game's cache revision
moves. Never edit the generated files by hand; they keep RuneLite's license notice.

## Changing the Mapping Format

The format is shared by the updater, the API and the client's hook handshake. Change these together:

1. `emit.py` (what the updater writes) and the golden test's section check in `tests/test_golden.py`.
2. [`Mapping`](../../api/src/main/java/com/kraken/api/mapping/Mapping.java) (how the API reads it), and any reader or
   `NativeEventServer`/`NativeProtocol` code using the changed section.
3. The bundled mappings and the golden corpus.
4. [Mapping format](mapping-format.md).

## Keeping These Docs Current

* User-facing behaviour changes go in `docs/*.md`; how-it-works changes go in `docs/developer/*.md`.
* The protocol tables in [Native protocol](native-protocol.md) and the hook list in
  [Native hook](native-hook.md#every-place-kraken-changes-the-game) must match the code exactly.
* Check diagrams render: GitHub renders Mermaid in Markdown previews.
