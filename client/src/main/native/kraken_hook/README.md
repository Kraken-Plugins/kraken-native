# kraken_hook.dll — native events, client calls and in-game drawing

A DLL injected into `osclient.exe`. It does three things for the Kraken client:

- turns the client's own event raises into **push** events, replacing `EventSource`'s poll-and-diff as
  the thing that decides when the API reads: it inline-hooks the raise functions named in a handshake
  and signals the Kraken client the instant the client raises one;
- runs the client functions the Kraken client asks for (the highlight functions) **on the game
  thread**, at the start of each frame;
- draws the Kraken client's overlay **inside the game's own OpenGL frame**, just before it is
  presented. See `docs/in-game-drawing.md`.

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
> poll meanwhile. See `docs/event-hooks-design.md` §2.5.

### The pump and present hooks

Two more functions are hooked with dedicated detours, once each, from the first handshake that names
them:

- **The pump** (`functions.appUpdate`, the client's per-frame step, `void(App*, float)`). Before the
  original runs, the detour runs every queued call batch on the game thread, each call under an SEH
  guard, and reports each batch with a `CALLS_DONE` message. The queue is taken with a try-lock, so the
  game thread never waits on the reader thread.
- **Present** (`functions.present`, `void(GlContext*)`, which calls `SwapBuffers`). Before the
  original runs, the detour uploads the newest overlay frame from the surface and draws it over
  framebuffer 0 as one full-screen triangle with premultiplied-alpha blending (GLSL 330 core on GL 3.3+,
  150 on 3.2, 130 on 3.0 and 3.1; nothing below 3.0). It saves every piece of GL state it changes and
  restores it after, under SEH guards; a fault turns the overlay off for the session. It also reports
  the framebuffer size, GL version and the surface it draws in `FRAME` messages.

## Protocol v3 (little-endian)

**Handshake** (Java → DLL): magic `KRK3`, a `u16` slot count, then per slot
`{u64 rva, i32 fixedId (-1 = read idArg), u8 idArg, u8 args, u8 planLen, plan[planLen]}`, where a
plan entry is `{u8 arg, u8 op}` with ops `RAW, I32, I64, I32X2, CSTR` = 0..4; then
`u64 clientGlobal, u64 pumpFn, u64 presentFn` (RVAs; 0 = none).

**Messages** (DLL → Java): a `u32` length frame, then
`{u32 seq, i32 eventId, u64 regs[4], u8 nValues, values...}`, where a value is `{u8 arg, u8 op, payload}`.
`seq` climbs by one per message, so the Kraken client sees a ring-buffer drop as a gap. Reserved ids:

| id | Name | regs |
|---|---|---|
| -2 | STATUS | installed slots, requested slots, features (1 = pump hooked, 2 = present hooked), protocol version 3 |
| -3 | FRAME | framebuffer width, height, GL version (`major << 16 \| minor`), surface generation drawn (0 = none) |
| -4 | CALLS_DONE | batch id, calls run, calls faulted or skipped, last return value |

STATUS and FRAME are written by the writer thread straight to the socket, so they can arrive out of
`seq` order with ring messages; CALLS_DONE goes through the ring.

**Commands** (Java → DLL, after the handshake): `{u32 len, u8 type, body}`, `len` counting the type
byte and the body, at most 4 MiB.

- `1 CALLS`: `u32 batch, u16 count`, then per call
  `{u64 rva, u8 argc (≤ 8), argc × {u8 kind, i64 value}, u16 dataLen, data}`. Kinds: `0 IMM` (the
  value), `1 CLIENT` (the `Client*` at `base + clientGlobal` plus the value; the call is skipped while
  it is null), `2 DATA` (a pointer to `data + value`, in a 16-byte-aligned copy). A call whose rva lies
  outside the module, or whose data offset is out of range, is skipped. A malformed frame, or one that
  would take the queue past 4096 calls, is answered with every call skipped. Batches are dropped while
  no pump is hooked, and when the connection closes.
- `2 SURFACE`: `u32 generation, u16 nameLen, name`. Opens the named file mapping (`OpenFileMappingA`,
  read/write, since the DLL writes the `reading` word) and checks its header; the present thread takes
  it over at its next present. `nameLen` 0, a mapping that fails the check, or a dropped connection
  stops the overlay.

The surface layout and its lock-free triple-buffer handshake are in `docs/in-game-drawing.md` §2.4.

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
  push a message into a lock-free single-producer ring. A writer thread drains the ring to the
  socket, so a slow reader never stalls the game. Pointer dereferences in the plan are SEH-guarded.
- After the handshake a reader thread reads command frames: it queues call batches for the pump and
  hands opened surfaces to the present thread through an atomic swap (the present thread unmaps the
  old one). Only the writer thread writes to the socket, and only the game thread (raise detours and
  the pump) pushes into the ring.
- Hooks stay installed for the life of the process. If the socket drops, the writer thread reconnects
  every second; the Kraken client re-sends the handshake and the DLL only adds slots it lacks. A
  second injection is refused by a named mutex.
- `client/.../callback/CallbackInjector.java` — the injection (the only code that writes to the game).
- `client/.../callback/NativeEventServer.java` — the server, handshake builder, message reader and the
  read-only `onEvent` tap; bound in `SessionModule` and installed by `KrakenClient`'s attach listener.
- `client/.../callback/NativeProtocol.java`, `NativeEvents.java` — the wire format and the capture plans.
- `kraken_hook.cpp` — connects back, reads the handshake, installs the MinHook hooks, pushes messages,
  runs call batches in the pump and draws the overlay in the present hook.

## Build

Needs a 64-bit C++ toolchain: Visual Studio / Build Tools with the "Desktop development with C++"
workload, or a 64-bit MinGW `g++` (e.g. TDM-GCC) on PATH. The script finds the toolchain itself —
from any PowerShell, no "x64 Native Tools" prompt needed — preferring MSVC (located via `vswhere` and
initialised from `vcvars64.bat`) and falling back to `g++`. MinHook is cloned on first run. It is
built as C++17.

A client build runs the script automatically: `processResources` depends on the `buildNativeHook` Gradle
task, which compiles the DLL and stages it into the client resources, so every jar carries it as
`native/kraken_hook.dll`. The compile is incremental — it re-runs only when `kraken_hook.cpp` or
`build.ps1` changes — so it costs nothing on an unchanged tree. To build it on its own:

```bash
./gradlew :client:buildNativeHook
```

To run the script directly instead:

```powershell
cd client\src\main\native\kraken_hook
powershell -ExecutionPolicy Bypass -File build.ps1
```

## Run

```bash
./gradlew :client:run --args="D:/path/to/osclient.exe"
```

The DLL is loaded from the jar (the `native/kraken_hook.dll` resource staged by `buildNativeHook`) and
extracted to a temporary file before injection; point `-Dkraken.hook.dll=<path>` at a built DLL on disk
to use that instead (handy when iterating on the DLL without re-staging). Add
`-Dkraken.hook.projectileStep=true` to also hook the per-cycle projectile step (about 50 messages a
second per projectile; off by default). While the hook is connected the API's timer poll slows to a
safety-net rate and the hook drives the reads.

## Caveats

- x64 only.
- Live checks are still pending for some payloads and hook sites (see `docs/event-hooks-design.md` §7).
  The trackers keep their whole-read fallback, so an unconfirmed payload costs latency, not a missed
  event.
- The pump, the calls and the overlay are not checked against a running client yet (see
  `docs/in-game-drawing.md` §4). Under MinGW the calls, the capture plans and the GL drawing run
  without SEH guards; build with MSVC for anything but local testing.
