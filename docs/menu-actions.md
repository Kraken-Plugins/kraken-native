# Menu actions without the menu

A plugin can run a menu action the way a click on the minimenu would, without the mouse being over anything: attack
an NPC, open a door, take a ground item, trade a player, walk to a tile, eat or drop an inventory item, use an item on
another, click a pause button. The client's own code does the work, called on the game thread by `kraken_hook.dll`
with the arguments a menu row would have captured, so what goes out to the server is what a real click sends. A mouse
click packet can go first, as the game sends one for a real click. The dialogs the server asks the player to answer
(an amount, a name, a text, an item) are answered with the packets the client's scripts send, built with the client's
own packet helpers.

API: `com.kraken.api.action.Actions` (`Client.getActions()`), and `Client.menuAction(...)` with its usual shape.
Addresses below are for build 241.3; the updater re-finds all of them (`spec/62-menu-actions.yaml`,
`spec/63-dialog-packets.yaml`, `menuops.py`, `dialogs.py`).

## 1. What a click does in the client

### 1.1 Rows and their callbacks

The minimenu's rows (`docs/event-hooks-design.md`, `spec/56-minimenu.yaml`) are built every cycle from what the mouse
is over. Each row is a `MinimenuEntry` (the text, the subject's kind, type id, index and world tile, the op index)
plus a callback the row's builder made when it added the row: an object of 0xa0 bytes holding a trampoline
(`0x108ae0`: `body([callback + 8], [callback + 0])`), a pointer to a small struct of captured arguments and the body
function to call with them. The entry list keeps the callbacks in one vector (stride 0xa0) and the entries in another
(`container.minimenu.list.entries`, stride `MinimenuEntry.size`), in step.

Clicking a row runs `MinimenuView::run(view, path, x, y, flag)` (`0x37d280`, vtable slot 1): it looks the row up by
its path, copies the entry into `Minimenu.clicked`, dispatches the op event (`0x3803d0`: `ON_NPC_OP`, `ON_LOC_OP`,
... by subject kind) and then invokes the row's callback, `body(context, &captures)`. Only the callback changes the
game; the dispatch is the Lua API's notification.

### 1.2 The bodies the API calls

The builders are the callers of the subject setters, the functions storing a kind into `MinimenuEntry.subjectType`
(`0x3800b0` stores NPC = 2, `0x380050` LOC = 3, `0x380240` OBJ = 4, `0x380170` PLAYER = 6, `0x37fff0` COMPONENT = 7,
from three component builders). Each builder adds its rows through adder helpers that take the body's address, and
the bodies that run an op all look alike: read the captures, then call a sender that switches on the op and sends one
packet per case. The API calls the bodies, with a captures struct of its own, since they do everything a row's click
does after the event: the packet, and the client's own interaction (facing and pathing to the target).

| Mapping key | 241.3 | Captures (little-endian i32s) | What it does |
|---|---|---|---|
| `functions.menuNpcOp` | `0x388870` | `{index, op 1-5, flag 0}` | sender `0x388910(unused, Npc*, op, flag)`: looks the NPC up by index (a stale index sends nothing), OPNPC1-5 |
| `functions.menuLocOp` | `0x387cd0` | `{level, x, z, locId, op 1-5, flag 0}` | sender `0x387cf0`: OPLOC1-5 for the loc at the world tile |
| `functions.menuObjOp` | `0x3893d0` | `{level, x, z, objId, count, op 1-5, flag 0}` | sender `0x389400`: OPOBJ1-5; only the fifth op's case passes the count on |
| `functions.menuPlayerOp` | `0x38a0f0` | `{index, op 1-8}` | sender `0x38a100`: looks the player up by index, OPPLAYER1-8 |
| `functions.menuWalk` | `0x387610` (stub to `0x387620`) | `{level, x, z}` | Walk here: skips the tile if it equals the last walk target (three globals at `0x155e590`), else sends the walk packet (opcode `0x37`, variable size: the ctrl/shift state byte, z and x as shorts, each obfuscated) and sets the destination marker (`0x6a2e0`) |
| `functions.menuNpcTargetOp` | `0x388630` | `{index}` | "Use X -> NPC": looks the NPC up, reads the selection (§1.3), sends the targeted op (opcode `0x3e`, 11 bytes) |
| `functions.menuLocTargetOp` | `0x387b30` | `{level, x, z, locId}` | the loc targeted op (`0x08`, 15 bytes) |
| `functions.menuObjTargetOp` | `0x389240` | `{level, x, z, objId}` | the ground item targeted op (`0x5e`, 15 bytes) |
| `functions.menuPlayerTargetOp` | `0x389ef0` | `{index}` | the player targeted op (`0x3d`, 11 bytes) |
| `functions.menuComponentOp` | `0x38d360` | `{component, slot, op 1-based, subOp, objId, u8 flag}` | a component's op (an inventory item's Eat or Drop, a button): looks the component up by packed id and slot, checks the op is enabled on it, sends opcode `0x26` (9 bytes: component, slot, item, op) or, with a sub-option (`subOp` = index + 1), `0x42` (10 bytes). `flag` is 0 in the rows the builders make; a nonzero flag looks the component up elsewhere and sends nothing |
| `functions.menuComponentTargetOp` | `0x38cf70` | `{component, slot, objId}` | "Use X -> Y" on a component (an item on an item): reads the selection, sends opcode `0x0f` (16 bytes: the two components with slots and items) |
| `functions.menuComponentSelect` | `0x38c810` | `{component, slot, objId}` | "Use": stores the component, slot and item as the selection (`0x372870`) and the "Use X ->" text; sends nothing |
| `functions.menuComponentPause` | `0x38ce20` (tail to `0x38ce50`) | `{component, slot}` | "Click here to continue": while no answer is pending (`Client + 0x413b80`), sends opcode `0x10` (6 bytes: slot, component) and marks the answer pending |

The first argument (`context`, the minimenu owner + 0x893 in the real rows) is never read by these bodies or their
senders, which take the client from its global; the API passes 0. The world tiles are world coordinates, level
first, as `MinimenuEntry.level/x/z` hold them. `flag` is 0 in every row the builders make; the senders fold it into a
byte of the packet. The ctrl key's state is read from the client (`+0x4131ae`, shift `+0x4131ad`) when the packet is
built, so holding ctrl while an action runs makes it a ctrl-click.

Not covered: Examine (the senders send nothing for an op outside 1-5; the examine bodies build text), the old-style
button types other than Use and Continue (three bodies send `IF_BUTTON`, `0x4e`, and differ only in the var they
toggle), spells cast from the spellbook (a component op on the spellbook selects the spell; the targeted bodies then
work for it as for an item) and the minimap walk. `Actions.isSupported` says which `MenuAction`s have a body.

### 1.3 The selection

A selected item or spell lives in the minimenu owner (`[Client + Client.minimenu]`) at `+0x7d0`; every targeted body
reads it through the getter `0x83c70` (`[Client + Client.minimenu] + 0x7d0`): the selected component at `+0x90`, its
slot at `+0x94`, its item at `+0x98`. The select body stores it; the targeted bodies read it when they build their
packet, so a select call followed by a targeted call in one batch uses an item on something as two clicks would. The
updater finds the getter as the short function, called from the bodies of several subject kinds, that calls the
function loading `Client.minimenu`.

### 1.4 The mouse click packet and the dialog answers

The per-cycle network step (`0x6b400`, the caller of the server packet handler `0x8e500`) sends `EVENT_MOUSE_CLICK`
(`enums.clientProt.EVENT_MOUSE_CLICK`, opcode 7 in 241.3, six bytes) when a click is pending: three big-endian u16s,
`(milliseconds since the last click, at most 32767) << 1 | right button`, the canvas x and the canvas y. It builds the
packet inline with the helpers every packet uses:

| Mapping key | 241.3 | Signature |
|---|---|---|
| `functions.packetCreate` | `0x6795c0` | `Packet*(Packet* out, int opcode, Cipher* outCipher)`: takes a pooled buffer, writes the ISAAC-ciphered opcode |
| `functions.packetWriteByte` | `0x45b30` | `void(Buffer* buffer, int value)`: `buffer = packet + 8`; the position is `buffer + 0x18`, the data pointer `buffer + 0x10` |
| `functions.packetWriteShort` | `0x45c20` | `void(Buffer* buffer, int value)`: big-endian |
| `functions.packetWriteInt` | `0x45b50` | `void(Buffer* buffer, int value)`: big-endian |
| `functions.packetWriteString` | `0x3eb20` | `void(Buffer* buffer, const char* utf8, size_t length, bool versioned)`: transcodes each UTF-8 character to the game's code page, ends with a zero byte; `versioned` (0 everywhere) prefixes a zero byte |
| `functions.packetSend` | `0x202600` | `void(Connection*, Packet*)`: queues the packet for the network thread, taking the buffer's contents |
| `functions.packetRelease` | `0x679720` | `void(Buffer*)`: returns the buffer to the pool |

`Connection*` is `[Client + Client.connection]` (`0x31a0`), the cipher `Connection + Connection.outCipher`
(`0xa08`). A `Packet` is a 0x40-byte struct the caller owns; the API builds it in the batch's scratch block (§2). The
updater tells the writers apart by how far each advances the position field the short writer advances by two; the
string writer is the one the packet builders call `(buffer, chars, length, 0)`.

The client's writers are plain big-endian; the obfuscated fields (add 0x80, little-endian, mixed-endian) are byte
stores inlined into each sender. The API therefore never builds an op packet itself, only the four whose layout is
plain: the mouse click, and the dialog answers the cs2 runtime sends. The runtime handles the ops 3100-3198 in one
function (`0x1a59d0`: `op - 3100`, a jump table of 0x63 cases at `0x1a8838`); its cases build the packets with the
helpers above on the runtime's stack:

| cs2 op | Mapping key | 241.3 | Packet |
|---|---|---|---|
| 3104 `resume_countdialog` | `enums.clientProt.RESUME_P_COUNTDIALOG` | `0x44` | `writeInt(count)`, 4 bytes |
| 3105 `resume_namedialog` | `enums.clientProt.RESUME_P_NAMEDIALOG` | `0x21` | `writeByte(length + 1)`, the characters, a zero byte (variable size) |
| 3106 `resume_stringdialog` | `enums.clientProt.RESUME_P_STRINGDIALOG` | `0x3a` | as the name dialog |
| 3115 `resume_objdialog` | `enums.clientProt.RESUME_P_OBJDIALOG` | `0x03` | `writeShort(itemId)`, 2 bytes |

The updater (`dialogs.py`) finds the handler by its `op - 3100` subtraction and jump table, walks each case's control
flow to its `packetCreate` and checks the single writer called on the buffer is the expected one. The API writes the
string with `packetWriteString` (the case copies the bytes inline, to the same effect) and the size byte as the
character count plus one.

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

A dialog answer is the same shape with its writer (`packetWriteInt(SCRATCH 8, IMM count)`; for text,
`packetWriteByte(SCRATCH 8, IMM length + 1)` then `packetWriteString(SCRATCH 8, DATA utf8, IMM bytes, IMM 0)`). Using
an item on something is two calls: `menuComponentSelect(IMM 0, DATA {component, slot, item})` then the subject's
targeted body. The DLL still interprets nothing: the functions, offsets and opcodes all come from the mapping through
Java; nothing in the DLL changed for this.

## 3. The Java side

- `Actions` (`Client.getActions()`): `mouseClick(x, y, right)`; `walk(WorldPoint)`; `menuAction(entry)` and
  `menuAction(param0, param1, action, identifier[, itemId])` (an NPC's or player's index; a loc's or item's id with
  the tile's scene x and y on the top-level plane; a walk's scene x and y; a component's slot and packed id with the
  op as the identifier for `CC_OP`, the item read from the widget when not given); `click(x, y, ...)` for the click
  followed by the action in one batch; `useItemOnItem(component, slot, item, targetSlot, targetItem)` and
  `useItemOn(component, slot, item, entry | parameters)` for the select and the targeted op in one batch (a plain
  entry of the subject stands for its targeted one); `resumeCountDialog`, `resumeNameDialog`, `resumeStringDialog`,
  `resumeObjDialog` and `resumePause(component, slot)`. `isSupported(action)`, `isMouseClickSupported()` and
  `isDialogSupported()` say what the build's mapping backs; `isAvailable()` whether the hook is connected. An action
  asked for without the hook is dropped, not queued: it is meant for the moment it is asked.
  `Client.menuAction(p0, p1, action, id, itemId, option, target)` keeps its usual shape; the native entry has no
  option or target, so those are unused.
- The `MenuAction`s with a body: the NPC, loc, ground item and player numbered ops, `WALK`, `CC_OP` and
  `CC_OP_LOW_PRIORITY`, `WIDGET_TARGET` (select), `WIDGET_CONTINUE` (pause) and the `WIDGET_TARGET_ON_*` actions.
  Their parameters follow the entries the menu reader builds, so an entry from the menu or a menu event runs as it is.
- `NativeCall` has `Kind.SCRATCH` and `Kind.INDIRECT`; `NativeProtocol` sends the kinds as before (the ordinal).
- The NPC Clicker plugin (`plugins.npcclicker`) is the small example: nearest NPC by name, the op from its
  composition's actions, the canvas point from `Perspective.localToCanvas`, then `Actions.click`. The Action Tester
  (`plugins.actiontester`) runs every action and packet from its settings page, one test at a time (tick Run now):
  NPC, player, object, ground item, inventory and widget options by name, walking, using an item on anything, pause
  buttons, the four dialog answers, the bare mouse click, and listing or replaying the menu's own entries, with what
  it sent or why it couldn't in the console. It is the tool for the live checks below.

## 4. Live checks pending

- A click on a Moss giant with the NPC Clicker: the OPNPC packet is accepted (the player attacks) and the game's own
  pathing starts; the mouse click packet before it is accepted.
- `packetRelease` leaves nothing behind: the buffer pool count is unchanged after many clicks (no leak, no double
  free).
- Loc and ground item actions at their world tiles, and player actions by index.
- `walk` to a tile a few steps away: the player walks and the destination marker shows; walking to the same tile
  twice sends once.
- `CC_OP` on an inventory item (Eat on food, Drop) with the item id from the widget; a `subOp` of 0.
- `useItemOnItem` (a tinderbox on logs) and `useItemOn` an NPC or loc: the "Use" row's effects (the selection, the
  "Use X ->" text) are undone by the client once the targeted op is sent.
- `resumeCountDialog` on an "Enter amount" prompt, `resumeObjDialog` on an item search, `resumeNameDialog` and
  `resumeStringDialog` on their prompts (the client's own input box stays open until the server closes the dialog),
  `resumePause` on a chat continue.
- Calling a body while logged out or on the login screen is a no-op (the senders check nothing before building the
  packet; `packetSend` drops it while the connection isn't up).
