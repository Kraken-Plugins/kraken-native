# kraken_hook.dll — push-based ON_GAME_TICK

A proof of concept that replaces `EventPoller`'s poll-and-diff for the game tick with a **push**
event: a DLL injected into `osclient.exe` inline-hooks the client's event dispatcher and signals the
Kraken client the instant a game tick is dispatched.

It is wired into the normal launch (`GameLauncher`) but is **off by default** — it is the one place
the client writes to the game process — and only arms with `-Dkraken.hook=true`.

## Why a socket, not a JNI/JNA callback

The injected DLL runs inside `osclient.exe`, which has no JVM. A JNA/JNI callback pointer only exists
in the Kraken client's address space, so native code in the game process cannot call it. The bridge
is IPC — here a loopback TCP socket. (Standing up a JVM inside `osclient.exe` with
`JNI_CreateJavaVM` is the heavier alternative, and the road toward a true in-process backend.)

## What it hooks

The client fires no-payload Lua events through one dispatcher (`functions.dispatchEvent` in the
mapping, `0xc44d0` in 241.3), called with the event id in `RCX`. In the per-tick code the client
does, right after incrementing its tick counter (`Client.tickCount`, `+0x3198`):

```
INC dword ptr [R13 + 0x3198]   ; advance the tick
MOV ECX, 0x2C                  ; 0x2C = 44 = ON_GAME_TICK
CALL 0x1400c44d0               ; dispatch the event
```

The hook forwards every dispatch untouched and signals Java only when the id is `ON_GAME_TICK`
(`enums.events.ON_GAME_TICK`). Both the dispatcher RVA and the tick id come from the mapping and are
sent to the DLL in a short handshake, so nothing here is hard-coded per build.

The updater carries the dispatcher forward across builds (`functions.dispatchEvent`, anchored on the
`INC [client+tickCount]` / `MOV ECX, <ON_GAME_TICK>` / `CALL` sequence above) and, from the same seed,
maps every other native event's raise function into the mapping's `events` section
(`updater/src/native_updater/events.py`). Extending this DLL from the game tick to all of them is
designed in `docs/event-hooks-design.md`.

## Flow

```
  Kraken client (JVM)                         osclient.exe (game process)
  -------------------                         ---------------------------
  API attaches -> mapping known
  ServerSocket 127.0.0.1:42069
  inject kraken_hook.dll  ──────────────────► DllMain -> init thread
     (CreateRemoteThread + LoadLibraryA)         connect() back to :42069
  send {dispatchRva, tickEventId}  ───────────► MinHook: hook base+rva
  socket.read() -> GameTickCallback.onTick() ◄───── detour(id): if id==tick, send 1 byte
```

- `client/.../callback/CallbackInjector.java` — the injection (the only code that writes to the game).
- `client/.../callback/GameTickCallback.java` — the server, handshake, and tick listeners; wired into
  `GameLauncher`.
- `kraken_hook.cpp` — connects back, reads the handshake, installs the MinHook hook, signals ticks.

## Build

Needs a 64-bit C++ toolchain: Visual Studio / Build Tools with the "Desktop development with C++"
workload, or a 64-bit MinGW `g++` (e.g. TDM-GCC) on PATH. The script finds the toolchain itself —
from any PowerShell, no "x64 Native Tools" prompt needed — preferring MSVC (located via `vswhere` and
initialised from `vcvars64.bat`) and falling back to `g++`. MinHook is cloned on first run.

```powershell
cd client\src\main\native\kraken_hook
powershell -ExecutionPolicy Bypass -File build.ps1
```

## Run

```bash
./gradlew :client:run --args="D:/path/to/osclient.exe" -Dkraken.hook=true
```

Point `-Dkraken.hook.dll=<path>` at the DLL if it is not at the default
`client/src/main/native/kraken_hook/kraken_hook.dll`. Log in and you will see `onGameTick (push) #n`
in the in-app console, one per server tick.

## Calling-convention note

`dispatchEvent` takes only the event id (an integer in `RCX`), so the detour's single-argument
`__fastcall` shape forwards it correctly. A different hook target that takes float/double arguments
(passed in XMM0–XMM3) would need a signature matching the real one, not this shape.

## Caveats

- The detour's `send()` runs on the client's thread. On loopback a 1-byte send is effectively
  instant; a production version would hand off to a queue so a slow reader can never stall the game.
- x64 only.
