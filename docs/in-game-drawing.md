# Drawing in the game

Overlays, tile markers and model outlines are drawn inside `osclient.exe`'s own frame instead of on a transparent
window laid over it. Two paths, both through `kraken_hook.dll`:

- **Highlights.** The client has a highlight system of its own (`jag::oldscape::highlight::HighlightManager`, behind
  the Lua `ClientOp.highlight*` functions). It outlines and fills models and tiles inside the 3D scene with the scene's
  depth: NPC, NPC type, player, loc (scenery), loc type, obj (ground item), obj type and tile highlights, 32 channels
  per category, each channel with a style (`HighlightConfig`). The DLL calls those functions on the game thread; the
  API's `Highlights` decides what to call (`com.kraken.api.highlight`).
- **2D overlays.** The client's `Overlay`s (panels, labels, anything drawn with `Graphics2D`) are still drawn by Java,
  into an image the size of the game's framebuffer, which goes to the DLL through shared memory. The DLL draws it over
  the finished frame inside the game's OpenGL context, just before the frame is presented. The transparent window
  (`GameOverlay`) is the fallback while the hook isn't connected.

What this buys over the window: overlays are composited into the game's frame (no layered-window copy through DWM,
no window to keep in step with the game, no tearing between two windows), the highlights follow the camera and moving
actors frame by frame with correct depth, and models can be outlined, which a 2D overlay can't do.

Addresses below are for build 241.3; the updater re-finds all of them (`spec/90-draw.yaml`).

## 1. What the client gives us

### 1.1 Threads and the frame

`jag::BaseApp::Update(float)` (`functions.appUpdate`, `0x918950`) is the main loop's per-frame step. It runs on the
game thread, times the frame, runs the logic cycles (Client vtable slot `0xf0`, up to 100 per frame), renders
(slot `0xf8`) and presents. Its entry is the game-thread pump: the DLL runs queued calls there, before the frame's
logic and rendering, where nothing is walking the highlight maps.

`present` (`functions.present`, `0xa555c0`, `void(GlContext*)`) sets the swap interval if it changed and calls
`GDI32!SwapBuffers` on the context's HDC. The frame step calls it directly when the GL device belongs to the calling
thread; otherwise it queues it for the device's render thread (`0xa60830` jumps to it). Either way the GL context is
current when it runs, and the back buffer holds the finished frame, interfaces included. The client renders with
OpenGL (`OPENGL32.dll`; GLSL `#version 130` to `460` by context). The DLL's own shader is `#version 330 core` on GL
3.3 and up, `150` on 3.2 and `130` on 3.0 and 3.1; below 3.0 it draws nothing.

### 1.2 The highlight functions

The Lua handlers read their arguments and call one native function with `RCX = Client + Client.highlightManager`
(`0x89680`). The DLL calls those functions directly. Coordinates are a `CoordGrid` `{i32 level, i32 x, i32 z}` of world
tiles; `useSourceCoord` only changes tiles with x of 6400 and up (a sub-world's space), and the API passes false.

| Mapping key | 241.3 | Signature |
|---|---|---|
| `functions.highlightNpcOn` / `Off` | `0x1b9ef0` / `0x1bbdf0` | `(mgr, int index, int creationCycle, int channel)` |
| `functions.highlightNpcTypeOn` / `Off` | `0x1ba460` / `0x1bc2e0` | `(mgr, int typeId, int channel, bool 0)` / `(mgr, int typeId, int channel)` |
| `functions.highlightPlayerOn` / `Off` | `0x1ba210` / `0x1bc070` | `(mgr, const eastl::string* name, int channel, bool 0)` / without the bool |
| `functions.highlightTileOn` / `Off` | `0x1ba2e0` / `0x1bc0d0` | `(mgr, const CoordGrid*, int channel, bool useSourceCoord)` |
| `functions.highlightLocOn` / `Off` | `0x1b9fb0` / `0x1bbe50` | `(mgr, int locId, const CoordGrid*, int channel, bool useSourceCoord)`, the bool on the stack |
| `functions.highlightLocTypeOn` / `Off` | `0x1ba780` / `0x1bc330` | as NPC type |
| `functions.highlightObjOn` / `Off` | `0x1ba0e0` / `0x1bbf60` | as loc |
| `functions.highlightObjTypeOn` / `Off` | `0x1bae10` / `0x1bc380` | as NPC type |
| `functions.highlightChannelClear` | `0x1b9550` | `(mgr, int category, int channel)`: drops every highlight on one channel |
| `functions.highlightChannelConfigure` | `0x3ad8c0` | `(void* unused, int category, int channel, const HighlightConfig*)`: the Lua function itself (a plain C++ function sol2 wraps); it packs the colour and calls the store `0x1b8ac0` |

Loc and loc type highlights have no Lua binding; the clientscript ops 7010 to 7019 reach them through the script op
dispatcher (`0x1f77b0`, a jump table on `opcode - 7000`), which is how the updater finds them and `highlightChannelClear`
(ops 7004, 7009, ... 7044). The dispatcher also calls the NPC, tile and obj functions the Lua handlers do, which the
updater checks.

`HighlightConfig` (the struct `highlightChannelConfigure` reads; `HighlightConfig.*` in the mapping, offsets from the
sol2 registration of `osrs.ClientOp.HighlightConfig`): `colour` `{u8 r, g, b, a}` at 0 (alpha is the fill opacity),
`u16 outlineWidth` at 4, then one bool each for `enableModelOutline` 6, `enableTileOutline` 7, `enableModelFill` 8,
`enableTileFill` 9, `alwaysOnTop` 0xa, `showInsideEdges` 0xb, `saveToFile` 0xc, `snapToGrid` 0xd, `swOnly` 0xe,
`ignoreRebuild` 0xf, `disableModel` 0x10. Categories (`enums.highlightCategory`): NPC 0, NPCTYPE 1, LOC 2, LOCTYPE 3,
OBJ 4, OBJTYPE 5, PLAYER 6, TILE 7, OPGROUP 8. Channels 0 to 31 (`HighlightManager.channelMax`); when an entity is lit
on several channels, the lowest visible channel's style wins.

Player names are `eastl::string`s (`container.string.*`): 24 bytes, short strings inline with byte 23 holding
`23 - length`.

## 2. Protocol v3

The event socket (`docs/event-hooks-design.md` §4.1) carries the drawing too. Little-endian throughout.

### 2.1 Handshake (Java → DLL)

```
"KRK3"
u16 slotCount, then the v2 slots: {u64 rva, i32 fixedId, u8 idArg, u8 args, u8 planLen, plan[planLen]{u8 arg, u8 op}}
u64 clientGlobal     globals.client (RVA of the Client* slot), 0 if unknown
u64 pumpFn           functions.appUpdate, 0 = run no calls
u64 presentFn        functions.present, 0 = draw no overlay
```

The DLL hooks the slots as before, the pump and present functions once each (the first handshake that names them;
later handshakes can't move them), and answers with the status message.

### 2.2 Messages (DLL → Java)

The v2 message `{u32 len, u32 seq, i32 eventId, u64 regs[4], u8 nValues, values...}`. Reserved ids:

| id | Name | regs |
|---|---|---|
| -2 | STATUS | installed slots, requested slots, features (bit 0 = pump hooked, bit 1 = present hooked, bit 2 = frame rate: sends FPS and honours FRAME_LIMIT), protocol version (3) |
| -3 | FRAME | framebuffer width, height, GL version (`major << 16 \| minor`), surface generation being drawn (0 = none). Sent when any of them changes, from the first present on; the size is the window's client area, 0 while it is minimized |
| -4 | CALLS_DONE | batch id, calls run, calls that faulted or were skipped, the last call's return value |
| -5 | FPS | frames since the last FPS, microseconds since it, the frame cap in force, 1 if presents were counted (0: frame steps, when present isn't hooked). Sent about once a second while the pump or present is hooked |

STATUS, FRAME and FPS come from the writer thread straight to the socket; CALLS_DONE from the pump on the game thread, the
ring's one producer. So the reserved ids' sequence numbers interleave with the raises', and Java leaves them out of
its drop count. CALLS are only sent once STATUS has the pump bit (without a pump the DLL drops them unanswered); a
batch id from before a reconnect can still be answered after it.

### 2.3 Commands (Java → DLL)

After the handshake Java may send frames `{u32 len, u8 type, body}`, the length counting the type byte and the body
(as for the DLL's messages):

**type 1, CALLS**: `u32 batch, u16 count`, then per call
`{u64 rva, u8 argc, argc × {u8 kind, i64 value}, u16 dataLen, u8 data[dataLen]}`, at most 8 arguments. Argument kinds:

| kind | Passed as |
|---|---|
| 0 IMM | the value |
| 1 CLIENT | the `Client*` read from `base + clientGlobal`, plus the value (the call is skipped while the pointer is null) |
| 2 DATA | a pointer to `data + value`; the data is copied into a 16-byte-aligned buffer that lives for the call |

The reader thread queues the batch; the pump runs every queued call, in order, under an SEH guard (a faulting call is
counted and the rest still run), then posts CALLS_DONE. The DLL never interprets a call: which function, which
arguments and what the data holds all come from Java and the mapping. At most 4096 calls wait at once; a batch that
doesn't fit, or a malformed one, is answered with every call skipped. Calls still queued when the connection drops are
dropped, so Kraken waits up to a second on exit for the last batch (the plugins turning their highlights off) to be
answered.

**type 2, SURFACE**: `u32 generation, u16 nameLen, name[nameLen]` (ASCII). Opens the named file mapping (§2.4) and
draws its frames from the next present; a `nameLen` of 0 stops drawing and unmaps it. A dropped socket also stops it.
The DLL checks the magic, the sizes (at most 16384 a side, 1 to 3 slots) and the mapping's length; a surface it
refuses, or a GL fault while drawing (which turns the overlay off for the session), leaves FRAME's generation at 0.
Java gives a surface two seconds to show up there, then goes back to its window for the rest of the connection.

**type 3, FRAME_LIMIT**: `u32 fps`, 0 (or anything over 1000) for no cap. The pump holds each frame step until one
interval (`1/fps`) after the last, on a high-resolution waitable timer, before running the queued calls and the
original. A step that comes more than an interval late restarts the schedule instead of letting the next ones run
back to back. The client times its own logic cycles, so a held frame runs more cycles rather than slowing the game. A
new cap wakes a pump that is waiting, so lifting it takes effect at once. Each connection starts uncapped, and a
dropped socket lifts the cap, so a Kraken client that closes never leaves the game capped. Java sends it once STATUS
has the pump and frame-rate bits, and again whenever the cap changes.

### 2.4 The overlay surface

A file mapping Java creates (`Local\KrakenOverlay-<pid>-<generation>`; a new generation whenever the size changes):

```
0    u32 magic        "KOV1"
4    u32 width        pixels per row
8    u32 height
12   u32 slots        3
16   u32 published    Java: slot | sequence << 4, 0xffffffff = none yet
20   i32 reading      DLL: the slot it is uploading from, -1 = none
24   reserved to 64
64   slot headers     3 x {u32 top, u32 bottom, u32 0, u32 0}: the rows [top, bottom) that may hold pixels
112  reserved to 128
128  slot pixels      3 x width x height x 4 bytes: premultiplied BGRA, rows top to bottom
```

Triple buffering, lock-free, each side writing only its own word:

- **Java** draws a frame into its image, finds the rows that hold pixels, writes into a slot that is neither
  `published` nor `reading` the rows that changed (this frame's rows plus the rows the slot last held, which become
  clear), sets the slot's header, then stores `published` and runs a full fence before it next reads `reading`.
- **The DLL**, in the present hook, reads `published`; if its sequence is new, stores `reading = slot`, runs a full
  fence and re-reads `published` (if it moved, it tries again next frame), uploads the rows that changed against its
  texture (the union of the texture's rows and the slot's), and stores `reading = -1`.

Java picks its slot after its fence, so a slot the DLL confirmed is never written under it.

The DLL draws the texture over the whole framebuffer with premultiplied-alpha blending, after saving the GL state it
touches and before calling the original present, and restores the state after. An empty texture isn't drawn. Java
sizes the surface by the FRAME message's framebuffer and scales its canvas-pixel overlays to it.

## 3. The Java side

- `com.kraken.api.highlight`: `Highlights` (on `GameSession` and `Client.getHighlights()`) remembers each channel's
  style and lit targets, sends the differences, and applies everything again whenever the hook (re)connects.
  `HighlightChannel` is a channel a plugin owns: `configure`, `set` (exactly these targets), `add`, `remove`,
  `clear`, `release`. `HighlightTarget` builds targets from API objects (`NPC`, `Player`, `TileObject`, `TileItem`,
  `WorldPoint`). Encoding a call needs only the mapping, so a build the updater can map needs no Java changes.
- `com.kraken.api.internal.invoke`: `NativeCall` and the `NativeInvoker` the client's `NativeEventServer` provides.
- Client: `NativeEventServer` sends the v3 handshake and the commands, hands the API a `NativeInvoker` once STATUS
  has the pump bit, and the `GameSurface` its link once it has the present bit. `ui.overlay.GameSurface` writes the
  surface; `OverlayRenderer` draws into it while the DLL draws it (framebuffer reported, GL 3+, surface taken), and
  through `GameOverlay` otherwise. A frame for the game is drawn at the framebuffer's size, and only the rows holding
  pixels (plus the rows the last frame held) are cleared and copied.
- Plugins: NPC Highlight (NPC channel) and Object Markers (loc channel) outline models in the game and keep their
  overlays for names, and for tile outlines while the hook isn't connected; Tile Markers marks listed world tiles on a
  tile channel. Each takes its channel in `startUp` and releases it in `shutDown`.
- Still on the window: the dev tools' overlays, which paint on the EDT from their snapshots.
- Frame rates: `NativeEventServer` hands FPS reports to the client's `FrameRates`, which also counts the overlay
  frames `OverlayRenderer` hands over. `FrameRates` decides the cap: `unfocusedFps` while no Kraken window is active
  and `limitUnfocusedFps` is on, none otherwise; the server sends it to the DLL. The Kraken page shows both rates, and
  `FpsOverlay` draws them over the game when `showFps` is on.

## 4. Live checks pending

- The pump runs once per frame on the game thread, also on the login screen; highlight calls show on the next frame.
- `present` runs with the client's context current in both the direct and the render-thread path, and the overlay
  lines up with the canvas in fixed, resizable and stretched modes and with UI scaling.
- Each highlight kind lights the right thing: NPCs by index and creation cycle, players by display name, locs by id
  and origin tile, objs by id and tile; `highlightChannelClear` drops them; styles change live.
- Highlights survive a map rebuild with `ignoreRebuild`, and what happens to them at logout.
- The overlay costs: upload time per frame at 1080p and 4K, and that an empty overlay costs nothing.
- FPS matches the game's own frame rate, counted at present and, without the present hook, at the pump.
- The unfocused cap holds the game at the rate asked, lifts as soon as Kraken is focused again (clicking into the
  embedded game keeps Kraken's frame the active window), and leaves logic cycles, input and highlights working while
  held.
