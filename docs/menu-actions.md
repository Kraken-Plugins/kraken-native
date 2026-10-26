# Menu actions without the menu

A plugin can run a menu action the way a click on the minimenu would, without the mouse being over anything: attack
an NPC, open a door, take a ground item, trade a player. The client's own code does the work, called on the game
thread by `kraken_hook.dll` with the arguments a menu row would have captured, so what goes out to the server is what
a real click sends. A mouse click packet can go first, as the game sends one for a real click.

API: `com.kraken.api.action.Actions` (`Client.getActions()`), and `Client.menuAction(...)` with its usual shape.
Addresses below are for build 241.3; the updater re-finds all of them (`spec/62-menu-actions.yaml`, `menuops.py`).

## 1. What a click does in the client

### 1.1 Rows and their callbacks

The minimenu's rows (`docs/event-hooks-design.md`, `spec/56-minimenu.yaml`) are built every cycle from what the mouse
is over. Each row is a `MinimenuEntry` (the text, the subject's kind, type id, index and world tile, the op index)
plus a callback the row's builder made when it added the row: an object of 0xa0 bytes holding a trampoline
(`0x108ae0`), a pointer to a small struct of captured arguments and the body function to call with them. The entry
list keeps the callbacks in one vector (stride 0xa0) and the entries in another (`container.minimenu.list.entries`,
stride `MinimenuEntry.size`), in step.

Clicking a row runs `MinimenuView::run(view, path, x, y, flag)` (`0x37d280`, vtable slot 1): it looks the row up by
its path, copies the entry into `Minimenu.clicked`, dispatches the op event (`0x3803d0`: `ON_NPC_OP`, `ON_LOC_OP`,
... by subject kind) and then invokes the row's callback, `body(context, &captures)`. Only the callback changes the
game; the dispatch is the Lua API's notification.

### 1.2 The bodies the API calls

The builders are the callers of the subject setters, the functions storing a kind into `MinimenuEntry.subjectType`
(`0x3800b0` stores NPC = 2, `0x380050` LOC = 3, `0x380240` OBJ = 4, `0x380170` PLAYER = 6). Each builder adds its
rows through adder helpers that take the body's address, and the bodies that run an op all look alike: read the
captures, then call a sender that switches on the op and sends one packet per case. The API calls the bodies, with a
captures struct of its own, since they do everything a row's click does after the event: the packet, and the
client's own interaction (facing and pathing to the target).

| Mapping key | 241.3 | Captures (little-endian i32s) | Sender |
|---|---|---|---|
| `functions.menuNpcOp` | `0x388870` | `{index, op 1-5, flag 0}` | `0x388910(unused, Npc*, op, flag)`: looks the NPC up by index (a stale index sends nothing), OPNPC1-5 |
| `functions.menuLocOp` | `0x387cd0` | `{level, x, z, locId, op 1-5, flag 0}` | `0x387cf0`: OPLOC1-5 for the loc at the world tile |
| `functions.menuObjOp` | `0x3893d0` | `{level, x, z, objId, count, op 1-5, flag 0}` | `0x389400`: OPOBJ1-5; only the fifth op's case passes the count on |
| `functions.menuPlayerOp` | `0x38a0f0` | `{index, op 1-8}` | `0x38a100`: looks the player up by index, OPPLAYER1-8 |

The first argument (`context`, the minimenu owner + 0x893 in the real rows) is never read by these bodies or their
senders, which take the client from its global; the API passes 0. The world tiles are world coordinates, level
first, as `MinimenuEntry.level/x/z` hold them. `flag` is 0 in every row the builders make; the senders fold it into a
byte of the packet. The ctrl key's state is read from the client (`+0x4131ae`) when the packet is built, so holding
ctrl while an action runs makes it a ctrl-click.

Not covered: Walk here (the tile rows are built elsewhere), Examine (the senders send nothing for an op outside
1-5), component ops (their captures and senders differ per component kind) and entries aimed by a selected item or
spell (the targeted bodies read the selection from the client). `Actions.isSupported` says which `MenuAction`s have
a body.

### 1.3 The mouse click packet

The per-cycle network step (`0x6b400`, the caller of the server packet handler `0x8e500`) sends `EVENT_MOUSE_CLICK`
(`enums.clientProt.EVENT_MOUSE_CLICK`, opcode 7 in 241.3, six bytes) when a click is pending: three big-endian u16s,
`(milliseconds since the last click, at most 32767) << 1 | right button`, the canvas x and the canvas y. It builds the
packet inline with the helpers every packet uses:

| Mapping key | 241.3 | Signature |
|---|---|---|
| `functions.packetCreate` | `0x6795c0` | `Packet*(Packet* out, int opcode, Cipher* outCipher)`: takes a pooled buffer, writes the ISAAC-ciphered opcode |
| `functions.packetWriteShort` | `0x45c20` | `void(Buffer* buffer, int value)`: `buffer = packet + 8` |
| `functions.packetSend` | `0x202600` | `void(Connection*, Packet*)`: queues the packet for the network thread, taking the buffer's contents |
| `functions.packetRelease` | `0x679720` | `void(Buffer*)`: returns the buffer to the pool |

`Connection*` is `[Client + Client.connection]` (`0x31a0`), the cipher `Connection + Connection.outCipher`
(`0xa08`). A `Packet` is a 0x40-byte struct the caller owns; the API builds it in the batch's scratch block (§2).

## 2. Protocol v4

Protocol v3 (`docs/in-game-drawing.md` §2) gains two call argument kinds, and the magic becomes `KRK4`:

| kind | Passed as |
|---|---|
| 3 SCRATCH | a pointer to the batch's scratch block plus the value: 4096 zeroed bytes, 16-byte aligned, that every call of one batch shares, so a sequence of calls can build an object and hand it on |
| 4 INDIRECT | the pointer read from `Client* + (value & 0xffffffff)`, plus `value >> 32` (signed); the call is skipped while the client pointer or the pointer read is null |

A mouse click is then six calls in one batch, and a click with an action seven:

```
packetCreate(SCRATCH 0, IMM opcode, INDIRECT {Client.connection, Connection.outCipher})
packetWriteShort(SCRATCH 8, IMM delay << 1 | right)
packetWriteShort(SCRATCH 8, IMM x)
packetWriteShort(SCRATCH 8, IMM y)
packetSend(INDIRECT {Client.connection, 0}, SCRATCH 0)
packetRelease(SCRATCH 8)
menuNpcOp(IMM 0, DATA {index, op, 0})
```

The DLL still interprets nothing: the functions, offsets and opcode all come from the mapping through Java.

## 3. The Java side

- `Actions` (`Client.getActions()`): `mouseClick(x, y, right)`, `menuAction(entry)` and
  `menuAction(param0, param1, action, identifier)` (an NPC's or player's index, a loc's or item's id with the tile's
  scene x and y on the top-level plane), `click(x, y, ...)` for the click followed by the action in one batch.
  `isSupported(action)` and `isMouseClickSupported()` say what the build's mapping backs; `isAvailable()` whether the
  hook is connected. An action asked for without the hook is dropped, not queued: it is meant for the moment it is
  asked. `Client.menuAction(p0, p1, action, id, itemId, option, target)` keeps its usual shape; the native entry has
  no item id, option or target, so those are unused.
- `NativeCall` gains `Kind.SCRATCH` and `Kind.INDIRECT`; `NativeProtocol` sends the kinds as before (the ordinal).
- The NPC Clicker plugin (`plugins.npcclicker`) is the example: nearest NPC by name, the op from its composition's
  actions, the canvas point from `Perspective.localToCanvas`, then `Actions.click`.

## 4. Live checks pending

- A click on a Moss giant with the NPC Clicker: the OPNPC packet is accepted (the player attacks) and the game's own
  pathing starts; the mouse click packet before it is accepted.
- `packetRelease` leaves nothing behind: the buffer pool count is unchanged after many clicks (no leak, no double
  free).
- Loc and ground item actions at their world tiles, and player actions by index.
- Calling a body while logged out or on the login screen is a no-op (the senders check nothing before building the
  packet; `packetSend` drops it while the connection isn't up).
