# kraken_hook.dll — native events, client calls, in-game drawing and script events

A DLL injected into `osclient.exe`. It does four things for the Kraken client:

- turns the client's own event raises into **push** events, replacing `EventSource`'s poll-and-diff as
  the thing that decides when the API reads: it inline-hooks the raise functions named in a handshake
  and signals the Kraken client the instant the client raises one;
- runs the client functions the Kraken client asks for (the highlight functions) **on the game
  thread**, at the start of each frame;
- draws the Kraken client's overlay **inside the game's own OpenGL frame**, just before it is
  presented. See [in-game-drawing.md](../../../../../docs/developer/in-game-drawing.md);
- reports each script the client runs (a root run starting, a gosub, a return) with the top of the
  script engine's stacks, while the Kraken client asks for them. See [events.md](../../../../../docs/developer/events.md).

It is installed on every launch, once the API attaches (`NativeEventServer`, created by the session
injector). It is the one place the client writes to the game process. If the DLL can't be found or
injected, the launch carries on: the API's timer poll drives the reads alone and overlays fall back to
the transparent window.

## Why a socket, not a JNI/JNA callback

The injected DLL runs inside `osclient.exe`, which has no JVM. A JNA/JNI callback pointer only exists
in the Kraken client's address space, so native code in the game process cannot call it. The bridge
is IPC — here a loopback TCP socket. (Standing up a JVM inside `osclient.exe` with
`JNI_CreateJavaVM` is the heavier alternative, and the road toward a true in-process backend.)

## What it hooks

The client raises its `osrs.Events` events through a family of small wrapper functions, and writes
game state through a handful of plain functions. The Kraken client reads both from the mapping for
the running build (`events.*`, `functions.*`) and hands the DLL, in a handshake, the ones it wants:
each raiser's RVA, the event id it carries (fixed, or read from an argument register) and a capture
plan saying which argument registers to read and how. So nothing here is hard-coded per build; a new
build only needs the updater re-run.

One generic detour shape fits every raiser, because they take their arguments in the four registers
`RCX, RDX, R8, R9` and nothing on the stack or in XMM registers. The detour reads the id and the
planned values, pushes a message, then forwards to the original unchanged.

> Note: `objAdd`/`objDel`/`objCount` (ground items) pass their item id and count **on the stack**, so
> the generic four-register detour cannot forward them without corrupting the call. They are left out
> until the DLL grows a stack-preserving detour; the API's `ItemTracker` reads the piles on its safety
> poll meanwhile. See [native-hook.md](../../../../../docs/developer/native-hook.md#raise-hooks).

### The pump and present hooks

Two more functions are hooked with dedicated detours, once each, from the first handshake that names
them:

- **The pump** (`functions.appUpdate`, the client's per-frame step, `void(App*, float)`). Before the
  original runs, the detour holds the frame to the frame cap, if one is set, then runs every queued
  call batch on the game thread, each call under an SEH guard, and reports each batch with a
  `CALLS_DONE` message. The queue is taken with a try-lock, so the game thread never waits on the
  reader thread.
- **Present** (`functions.present`, `void(GlContext*)`, which calls `SwapBuffers`). Before the
  original runs, the detour uploads the newest overlay frame from the surface and draws it over
  framebuffer 0 as one full-screen triangle with premultiplied-alpha blending (GLSL 330 core on GL 3.3+,
  150 on 3.2, 130 on 3.0 and 3.1; nothing below 3.0). It saves every piece of GL state it changes and
  restores it after, under SEH guards; a fault turns the overlay off for the session. It also reports
  the framebuffer size, GL version and the surface it draws in `FRAME` messages.

### The script hooks

Four more functions are hooked with dedicated detours, once, from the first handshake whose script
block names them (`functions.executeScript`, `executeScriptInner`, `scriptLookup`,
`scriptSwapLocals`): the request entry keeps the listener's request for the interpreter run it
starts; the interpreter reports a root script starting and, when it returns, ending; the script
lookup, called inside a run, is a gosub; the locals swap, called on a frame whose script slot was
cleared, is a return. The DLL notes each running script's id by depth, since a return can't see the
returning script. The detours send nothing until a `SCRIPTS` command asks.

### The menu and key hooks

The minimenu's view rebuild, run and node destroy are hooked from the first handshake whose menu block
names them: before the rebuild the detour takes the rows of Kraken's own out, after it applies the rules
the `MENU` command sent (swaps, hides, deprioritizes, text rewrites through the client's own assignment,
rows of Kraken's own built in a pool of the DLL's and inserted after the client's reserve made room); a
click on a row of Kraken's own is reported as `MENU_CLICK` and runs nothing; the node destroy leaves a
children vector of the DLL's alone. The game window's procedure is hooked from the key block: `WM_KEYDOWN`,
`WM_KEYUP` and `WM_CHAR` are rewritten or dropped by the mode in force of the table `KEYS` sent, and a
rule can move the mode as its key goes down.

### Entity visibility and component writes

Two more jobs run in the pump, after the calls. From the visibility block the DLL knows where the worlds, their actors,
the projectiles and the graphics objects are; each frame it applies the rules `VISIBILITY` sent, setting the hidden
byte of what they hide and the 2D byte of actors, and clearing only what it set
([entity-visibility.md](../../../../../docs/developer/entity-visibility.md)). From the component block it knows the
interface store's layout; it looks up each component a `COMPONENTS` write names, by id, and writes the field or calls
the client's listener clear on it ([widget-writes.md](../../../../../docs/developer/widget-writes.md)).

## Protocol v7 (little-endian)

**Handshake** (Java → DLL): magic `KRK7`, a `u16` slot count, then per slot
`{u64 rva, i32 fixedId (-1 = read idArg), u8 idArg, u8 args, u8 planLen, plan[planLen]}`, where a
plan entry is `{u8 arg, u8 op}` with ops `RAW, I32, I64, I32X2, CSTR` = 0..4; then
`u64 clientGlobal, u64 pumpFn, u64 presentFn` (RVAs; 0 = none); then five `u16`-length-prefixed
blocks: the script block (empty for no script hooks; its 155-byte layout is `NativeProtocol.scriptLayout`'s
and `parseScriptLayout`'s), the menu block (142 bytes, `menuLayout`'s and `menus.cpp`'s `parseLayout`'s),
the key block (8 bytes, the window procedure's RVA), the visibility block (84 bytes, `visibilityLayout`'s and
`visibility.cpp`'s) and the component block (56 bytes, `componentLayout`'s and `components.cpp`'s).

**Messages** (DLL → Java): a `u32` length frame, then
`{u32 seq, i32 eventId, u64 regs[4], u8 nValues, values...}`, where a value is `{u8 arg, u8 op, payload}`.
`seq` climbs by one per message, so the Kraken client sees a ring-buffer drop as a gap. Reserved ids:

| id | Name | regs |
|---|---|---|
| -2 | STATUS | installed slots, requested slots, features (1 = pump hooked, 2 = present hooked, 4 = frame rate, 8 = script hooks, 16 = menu hooks, 32 = key hook, 64 = visibility, 128 = component writes), protocol version 7 |
| -3 | FRAME | framebuffer width, height, GL version (`major << 16 \| minor`), surface generation drawn (0 = none) |
| -4 | CALLS_DONE | batch id, calls run, calls faulted or skipped, last return value |
| -5 | FPS | frames since the last FPS, microseconds since it, frame cap in force, 1 if presents were counted (0: frame steps) |
| -6 | SCRIPT | kind (0 root start, 1 gosub, 2 return), script id, depth, 0; then the top of both stacks, the active and dot components and, for a root run a listener started, the request ([native-protocol.md](../../../../../docs/developer/native-protocol.md#the-script-payload)) |
| -7 | MENU_CLICK | 0 for a row of Kraken's own or 1 for a rule's report, the row or rule id, the parent row id (0xffffffff for none), mouse x \| y << 32 |
| -8 | MENU_EXPIRED | the id of a scoped rule nothing matched for 25 rebuilds, 0, 0, 0 |
| -9 | KEY | the window message, the key or character, action << 16 \| the key it became (0 passed, 1 remapped, 2 blocked), mode before \| mode after << 8 |

STATUS, FRAME and FPS are written by the writer thread straight to the socket, so they can arrive out
of `seq` order with ring messages; CALLS_DONE, SCRIPT, MENU_CLICK, MENU_EXPIRED and KEY go through the ring. FPS goes out about once a second
while the pump or present is hooked.

**Commands** (Java → DLL, after the handshake): `{u32 len, u8 type, body}`, `len` counting the type
byte and the body, at most 4 MiB.

- `1 CALLS`: `u32 batch, u16 count`, then per call
  `{u64 rva, u8 argc (≤ 8), argc × {u8 kind, i64 value}, u16 dataLen, data}`. Kinds: `0 IMM` (the
  value), `1 CLIENT` (the `Client*` at `base + clientGlobal` plus the value; the call is skipped while
  it is null), `2 DATA` (a pointer to `data + value`, in a 16-byte-aligned copy), `3 SCRATCH` (a pointer
  to the batch's 4096-byte zeroed scratch block plus the value, which every call of the batch shares),
  `4 INDIRECT` (the pointer at `Client* + (value & 0xffffffff)` plus `value >> 32`; the call is skipped
  while either pointer is null). A call whose rva lies outside the module, or whose data or scratch
  offset is out of range, is skipped. A malformed frame, or one that
  would take the queue past 4096 calls, is answered with every call skipped. Batches are dropped while
  no pump is hooked, and when the connection closes.
- `2 SURFACE`: `u32 generation, u16 nameLen, name`. Opens the named file mapping (`OpenFileMappingA`,
  read/write, since the DLL writes the `reading` word) and checks its header; the present thread takes
  it over at its next present. `nameLen` 0, a mapping that fails the check, or a dropped connection
  stops the overlay.
- `3 FRAME_LIMIT`: `u32 fps`, 0 (or over 1000) for none. The pump waits out each frame until it is due
  ([native-hook.md](../../../../../docs/developer/native-hook.md#the-pump-hook)). A new cap wakes a waiting pump; a dropped connection lifts it.
- `4 SCRIPTS`: `u8 flags`, `u8 count`, `count` × `i32` script id. Bit 0 asks for scripts starting,
  bit 1 for scripts returning, bit 2 for root runs of the listed scripts starting (at most 8; the API
  lists the XP drop script while `FakeXpDrop` has subscribers). Ignored until the script hooks are
  live; a dropped connection clears it.
- `5 MENU`: `u8 op` (0 replace, 1 upsert, 2 remove), `u16 count`, then rules or `u32` ids. A rule is
  `{u32 id, u8 type (1 edit, 2 swap, 3 add), u8 flags (1 shift down, 2 shift up, 4 scoped, 8 final),
  match, payload}`; a match `{u16 mask, u8 kind, 7 × i32 (type id, index, op, group, level, x, z), text
  option, text target}` with a text `{u8 mode, u8 len, bytes}`; an edit's payload `{u8 actions, str option,
  str target}`, a swap's `{u8 flags, match}`, an add's `{u8 position, row}` with a row
  `{u32 id, str option, str target, u8 children, children}` ([native-protocol.md](../../../../../docs/developer/native-protocol.md#menu)).
  Applied on the game thread at the next menu rebuild; ignored until the menu hooks are live; a dropped
  connection drops every rule.
- `6 KEYS`: `u8 op`: 0 the table (`u8 modes`, each `{u8 flags, u16 count, count × {u16 vk, u8 modifiers,
  u8 mask, u16 to, u8 next}}`), 1 the mode (`u8`), 2 reporting (`u8`). Ignored until the window procedure
  is hooked; a dropped connection drops the table and goes back to mode 0.
- `7 VISIBILITY`: `u8 kinds`, `u16 count`, `count` × `{u64 address, u8 decided, u8 hidden}`, `u8 graphics`,
  `graphics` × `i32` spot anim id ([native-protocol.md](../../../../../docs/developer/native-protocol.md#visibility)).
  Replaces the rules; applied at every frame's pump; a dropped connection empties them, showing what the DLL hid.
- `8 COMPONENTS`: `u16 count`, `count` × `{i32 packedId, i32 subId, u8 op (0 set i32, 1 clear listener), u16 offset,
  i32 value}`. Made at the next pump, each component looked up by id first; a dropped connection drops the queue.

The surface layout and its lock-free triple-buffer handshake are in
[in-game-drawing.md](../../../../../docs/developer/in-game-drawing.md).

## Flow

```
  Kraken client (JVM)                         osclient.exe (game process)
  -------------------                         ---------------------------
  API attaches -> mapping known
  ServerSocket 127.0.0.1:42069
  inject kraken_hook.dll  ──────────────────► DllMain -> init thread
     (CreateRemoteThread + LoadLibraryA)         connect() back to :42069
  send handshake (slots, pump, present) ──────► MinHook: hook each base+rva, the pump, present
  read messages -> GameSession.onNative() ◄───── detour(id, regs): capture plan, ring, writer thread
  send CALLS ─────────────────────────────────► reader thread -> queue -> pump (game thread) -> CALLS_DONE
  write overlay frames to the surface, SURFACE ► reader thread maps it -> present hook uploads + draws
```

- The detour runs on the game thread for about a microsecond: capture the id and planned values and
  push a message into a lock-free single-producer ring (4 MiB of variable-length records, each its
  own length frame). A writer thread drains the ring to the socket, so a slow reader never stalls the
  game; the game thread only signals it when it is about to wait, so a burst of messages costs no
  system call each. Pointer dereferences in the plan and the script captures are SEH-guarded.
- After the handshake a reader thread reads command frames: it queues call batches for the pump and
  hands opened surfaces to the present thread through an atomic swap (the present thread unmaps the
  old one). Only the writer thread writes to the socket, and only the game thread (raise and script
  detours, and the pump) pushes into the ring.
- Hooks stay installed for the life of the process. If the socket drops, the writer thread reconnects
  every second; the Kraken client re-sends the handshake and the DLL only adds slots it lacks. A
  second injection is refused by a named mutex.
- `client/.../callback/CallbackInjector.java` — the injection (the only code that writes to the game).
- `client/.../callback/NativeEventServer.java` — the server, handshake builder, message reader and the
  read-only `onEvent` tap; bound in `SessionModule` and installed by `KrakenClient`'s attach listener.
- `client/.../callback/NativeProtocol.java`, `NativeEvents.java` — the wire format and the capture plans.
- `src/` — the DLL: connects back, reads the handshake, installs the MinHook hooks, pushes messages,
  runs call batches in the pump and draws the overlay in the present hook (see Source layout).

## Source layout

C++17, one module per feature. Each module is a header, declaring what other modules call, and a
`.cpp`, holding the implementation and its state in an anonymous namespace so nothing else can touch
it. Everything is in namespace `kraken`, one nested namespace per module (`kraken::pump`,
`kraken::overlay`...).

| File | What it does |
|---|---|
| `src/dllmain.cpp` | `DllMain` refuses a second injection and starts the init thread, which sets up the modules and runs the connection |
| `src/protocol.h` | The wire format: every constant that `NativeProtocol`, `NativeEventServer` and `GameSurface` must match |
| `src/connection.*` | The socket: connecting back, the handshake, the reader thread (commands) and the writer thread (the ring, STATUS, FRAME and FPS) |
| `src/raises.*` | The raise hooks: one generic detour per slot and the capture plans |
| `src/calls.*` | `CALLS`: the call queue, and running each call on the game thread under a guard |
| `src/pump.*` | The pump hook: the frame cap (`FRAME_LIMIT`), then the queued calls, component writes and visibility rules |
| `src/scripts.*` | The script hooks, `SCRIPTS` and the SCRIPT messages |
| `src/menus.*` | The menu hooks, the rule engine, `MENU`, and the MENU_CLICK and MENU_EXPIRED messages |
| `src/keys.*` | The window procedure hook, the key table, `KEYS` and the KEY messages |
| `src/visibility.*` | `VISIBILITY` and the per-frame walk that hides and shows entities |
| `src/components.*` | `COMPONENTS`, the component lookup and the writes |
| `src/overlay/overlay.*` | The present hook: uploading and drawing the overlay frame, and what FRAME reports |
| `src/overlay/surface.*` | `SURFACE`: opening the shared-memory surface and handing it to the present thread |
| `src/overlay/gl.*`, `gl_state.*` | The GL constants and entry points beyond GL 1.1; saving and restoring the client's GL state |
| `src/core/ring.*` | The lock-free message ring between the game thread and the writer thread |
| `src/core/hooks.*` | MinHook's setup and `installOnce` |
| `src/core/memory.*`, `guard.h` | Guarded reads and writes of the game's memory, and the SEH guard macros |
| `src/core/process.*` | osclient.exe's base and image size, and the performance counter's frequency |
| `src/core/frame_reader.h` | A bounds-checked reader over a command frame |

A new source file goes in `add_library` in `CMakeLists.txt`. A new command takes a constant in
`protocol.h` (and `NativeProtocol`), a handler in its module that reads the body from a `FrameReader`,
and a `case` in `connection.cpp`'s reader thread. A guard (`GUARD_BEGIN`/`GUARD_END`) sits in a small
function of its own: MSVC refuses `__try` in a function that holds objects with destructors.

## Build

A CMake project (`CMakeLists.txt`, with named configurations in `CMakePresets.json`). It needs:

- a 64-bit C++ toolchain: Visual Studio or its Build Tools with the "Desktop development with C++"
  workload (preferred: only MSVC builds have the SEH guards), or a 64-bit MinGW `g++` and
  `mingw32-make` on PATH (e.g. TDM-GCC);
- CMake 3.25 or later. The Visual Studio workload installs one ("C++ CMake tools for Windows"), which
  the Gradle build finds by itself; a CMake on PATH is used first.

MinHook is downloaded on the first configure, at the commit `CMakeLists.txt` pins, and checked against
its SHA-256. To build offline, point CMake at a MinHook folder with
`-DFETCHCONTENT_SOURCE_DIR_MINHOOK=<path>`.

A client build compiles the DLL automatically: `processResources` depends on `buildNativeHook`
(`client/native-hook.gradle`), which runs the `msvc` preset when Visual Studio's C++ tools are installed
and `mingw` otherwise, then stages the DLL into the client resources, so every jar carries it as
`native/kraken_hook.dll`. Gradle skips the compile while `src/`, `CMakeLists.txt` and
`CMakePresets.json` are unchanged. To build it on its own:

```bash
./gradlew :client:buildNativeHook
./gradlew :client:buildNativeHook -PnativeHookPreset=mingw   # choose the preset
```

Everything the build writes goes to `client/build/native/kraken_hook/<preset>/`, which
`./gradlew clean` removes: the DLL and, for MSVC, its `.pdb` in `bin/<config>/`, the object files and
the MinHook download. Nothing is written next to the sources.

To use CMake directly, from this folder, in any shell (the `msvc` preset needs no "x64 Native Tools"
prompt):

```bash
cmake --workflow --preset msvc       # configure and build Release
cmake --build --preset msvc-debug    # a Debug build, once msvc is configured
cmake --workflow --preset mingw
```

Visual Studio (Open Folder), VS Code (the CMake Tools extension) and CLion read the presets, so opening
this folder in one of them gives code navigation and builds with no other setup.

### Updating MinHook

`CMakeLists.txt` names MinHook by commit in the download URL, with the archive's SHA-256 under it. To
move to another commit, change it in the URL and change the hash: take the new archive's SHA-256
(`certutil -hashfile <archive> SHA256`), or run a build and copy the actual hash from the mismatch
error CMake prints.

## Run

```bash
./gradlew :client:run --args="D:/path/to/osclient.exe"
```

The DLL is loaded from the jar (the `native/kraken_hook.dll` resource staged by `buildNativeHook`) and
extracted to a temporary file before injection; point `-Dkraken.hook.dll=<path>` at a built DLL on disk
to use that instead (handy when iterating on the DLL without re-staging), such as
`client/build/native/kraken_hook/msvc/bin/Debug/kraken_hook.dll` from the `msvc-debug` preset. An MSVC
DLL records where its `.pdb` is, so a debugger attached to `osclient.exe` (Visual Studio: Debug >
Attach to Process) shows the DLL's own functions and lines. Add
`-Dkraken.hook.projectileStep=true` to also hook the per-cycle projectile step (about 50 messages a
second per projectile; off by default). While the hook is connected the API's timer poll slows to a
safety-net rate and the hook drives the reads.

## Caveats

- x64 only.
- Live checks are still pending for some payloads and hook sites (see [events.md](../../../../../docs/developer/events.md)).
  The trackers keep their whole-read fallback, so an unconfirmed payload costs latency, not a missed
  event.
- The pump, the calls and the overlay are not checked against a running client yet (see
  [in-game-drawing.md](../../../../../docs/developer/in-game-drawing.md)). Under MinGW the calls, the capture plans and the GL drawing run
  without SEH guards; build with MSVC for anything but local testing.
