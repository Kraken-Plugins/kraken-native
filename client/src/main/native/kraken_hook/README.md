# kraken_hook.dll — push-based native events

A DLL injected into `osclient.exe` that turns the client's own event raises into **push** events for
the Kraken client, replacing `EventSource`'s poll-and-diff as the thing that decides when the API
reads. It inline-hooks the raise functions named in a handshake and signals the Kraken client the
instant the client raises one.

It is installed on every launch, once the API attaches (`NativeEventServer`, created by the session
injector). It is the one place the client writes to the game process. If the DLL can't be found or
injected, the launch carries on and the API's timer poll drives the reads alone.

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

## Protocol v2 (little-endian)

**Handshake** (Java → DLL): magic `KRK2`, a `u16` slot count, then per slot
`{u64 rva, i32 fixedId (-1 = read idArg), u8 idArg, u8 args, u8 planLen, plan[planLen]}`, where a
plan entry is `{u8 arg, u8 op}` with ops `RAW, I32, I64, I32X2, CSTR` = 0..4.

**Message** (DLL → Java): a `u32` length frame, then
`{u32 seq, i32 eventId, u64 regs[4], u8 nValues, values...}`, where a value is `{u8 arg, u8 op, payload}`.
`seq` climbs by one per raise, so the Kraken client sees a ring-buffer drop as a gap. A reserved
`eventId` of `-2` is a status message reporting how many hooks installed.

## Flow

```
  Kraken client (JVM)                         osclient.exe (game process)
  -------------------                         ---------------------------
  API attaches -> mapping known
  ServerSocket 127.0.0.1:42069
  inject kraken_hook.dll  ──────────────────► DllMain -> init thread
     (CreateRemoteThread + LoadLibraryA)         connect() back to :42069
  send handshake (slot table)  ───────────────► MinHook: hook each base+rva
  read messages -> GameSession.onNative() ◄───── detour(id, regs): capture plan, ring, writer thread
```

- The detour runs on the game thread for about a microsecond: capture the id and planned values and
  push a message into a lock-free single-producer ring. A writer thread drains the ring to the
  socket, so a slow reader never stalls the game. Pointer dereferences in the plan are SEH-guarded.
- Hooks stay installed for the life of the process. If the socket drops, the writer thread reconnects
  every second; the Kraken client re-sends the handshake and the DLL only adds slots it lacks. A
  second injection is refused by a named mutex.
- `client/.../callback/CallbackInjector.java` — the injection (the only code that writes to the game).
- `client/.../callback/NativeEventServer.java` — the server, handshake builder, message reader and the
  read-only `onEvent` tap; wired into `GameLauncher`.
- `client/.../callback/NativeProtocol.java`, `NativeEvents.java` — the wire format and the capture plans.
- `kraken_hook.cpp` — connects back, reads the handshake, installs the MinHook hooks, pushes messages.

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
