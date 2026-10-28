# Menu actions without the menu

A plugin can run a menu action the way a click on the minimenu would, without the mouse being over anything: attack
an NPC, open a door, take a ground item, trade a player, walk to a tile, eat or drop an inventory item, pick a
sub-option of an item's op, use an item on another, cast a spell on an item or an NPC, click an old-style button or a
pause button, close an interface. The client's own code does the work, called on the game thread by `kraken_hook.dll`
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
| `functions.menuComponentButton` | `0x38c730` | `{component}` | an old-style button of type 1: looks the component up by packed id with no slot (-1), and, when it has ops, checks `0x85280` first; sends `IF_BUTTON` (`0x4e`, 4 bytes: the packed id as an int) and stores nothing after |
| `functions.menuComponentToggle` | `0x38cc10` | `{component}` | type 4: sends `IF_BUTTON`, then, when the component's script op (`+0xa90`/`+0xa98`) is a var op (type 5), stores `1 - value` into that var in the varp array (`0x155e540`) and marks it changed (`0x847e0`) |
| `functions.menuComponentSet` | `0x38cd10` | `{component}` | type 5: sends `IF_BUTTON`, then stores the component's own value (`+0xac8`) into the var the same way, when it differs |
| `functions.menuComponentClose` | `0x38cc00` (tail to `0x84e40`) | none | type 3, a close button: loads the client and jumps to the close function, which sends `CLOSE_MODAL` (`0x29`, size 0), then closes the modal interface and its sub-interfaces |

The first argument (`context`, the minimenu owner + 0x893 in the real rows) is never read by these bodies or their
senders, which take the client from its global; the API passes 0. The world tiles are world coordinates, level
first, as `MinimenuEntry.level/x/z` hold them. `flag` is 0 in every row the builders make; the senders fold it into a
byte of the packet. The ctrl key's state is read from the client (`+0x4131ae`, shift `+0x4131ad`) when the packet is
built, so holding ctrl while an action runs makes it a ctrl-click.

The old-style button builder (`0x38b5c0`) switches on the component's button type (`IfType.event`, 1-6, the jump
table at `0x38bad4`): type 1 adds the plain button body, 2 the select body (§1.3), 3 the close body, 4 the toggle, 5
the set and 6 the pause body, through adders that store `{component}` (the packed id at `IfType + 4`) for the three
`IF_BUTTON` bodies, which read `[RDX]` and nothing else. The updater tells the three apart by what follows the send
(no var store, a store of `1 - value`, a store of a dereferenced component field) and finds the close body as the
stub whose tail call creates the size-0 packet.

Not covered: Examine (the senders send nothing for an op outside 1-5; the examine bodies build text) and the minimap
walk (§1.6, its packet is built inline by the click handler). `Actions.isSupported` says which `MenuAction`s have a
body.

### 1.3 The selection

A selected item or spell lives in the minimenu owner (`[Client + Client.minimenu]`) at `+0x7d0`; every targeted body
reads it through the getter `0x83c70` (`[Client + Client.minimenu] + 0x7d0`): the selected component at `+0x90`, its
slot at `+0x94`, its item at `+0x98`. The select body stores it; the targeted bodies read it when they build their
packet, so a select call followed by a targeted call in one batch uses an item on something as two clicks would. The
updater finds the getter as the short function, called from the bodies of several subject kinds, that calls the
function loading `Client.minimenu`.

The store (`0x372870(selection, component, slot, item, u8 mask, string* verb, string* name)`) writes the three
ints, bumps a u16 serial at `+0x9c`, keeps a target mask at `+0x9e` and sets the selected flag at `+0x9f`; the
"Use X ->" verb and the name go to `+0` and `+0x48`. There is no item-or-spell kind: the mask is bits 11-16 of the
component's click mask (`0x856b0`), which say what the selection can be used on, and the builders test it before
adding a targeted row (bit 1, `0x2`, in the NPC builder; bit 5, `0x20`, in the if3 component builder, which also
needs bit 21 of the target component's click mask).

A spell is selected the same way as an item. The if3 component builder (`0x38baf0`) adds the select body for any
targetable component with the captures `{IfType.packedId, IfType.subId, IfType.linkObjType}`, so a spell's row
selects its spellbook component with slot -1 and item -1, and an inventory item's row its slot and item; the old-style
builder's type-2 button does the same. The targeted bodies have no spell branch: each builds one packet whatever is
selected (`OPNPCT` `0x3e` 11 bytes, `OPLOCT` `0x08` 15, `OPOBJT` `0x5e` 15, `OPPLAYERT` `0x3d` 11, `IF_BUTTONT` `0x0f`
16), with the selection's component, slot and item written as they are, which for a spell means -1 for the slot and
the item. `Actions.castSpellOn` is therefore the select call with the spell's component and no slot or item, followed
by the subject's targeted body.

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

### 1.5 Sub-options

An op can open a sub-menu (a bank's Withdraw-X, an item's Drop with an amount). The op row builder (`0x38bfe0`,
called per op) adds the op's row with the captures `{component, slot, op + 1, 0, item, flag}`, then, when the op
index is below `IfType.subOpCount` (`0x2f8`), walks the op's vector of strings in `IfType.subOpNames` (`0x300`, an
array indexed by the 0-based op of `{begin, end, capacity}` vectors of 0x18-byte strings) and adds one row per name
with `subOp` = its index + 1, through the same adder (`0x39a080`) with the op body. The sender then takes the
`IF_BUTTONX`-style branch: opcode `0x42` (10 bytes: the plain op packet plus `subOp - 1` as a byte) instead of
`0x26`. The sub-rows are the children of the op row's node, each a node of the sub-menu's own list whose entry holds
the sub-option index in `MinimenuEntry.opIndex`; the client's `ON_IF_OP` event for such a row reports that index. The
updater finds the two fields in the builder that takes the op body's address: the other 4-byte field of the component
compared with the op argument, next to `IfType.opCount`, that is followed, as far along as `opNames` is after
`opCount`, by a table the builder indexes with the op.

`Widget.getSubOptions(op)` reads the names; the menu reader lists a component op's sub-rows right after the op's row
as entries of the op with `MenuEntry.getSubOp()` set (1-based), and `Actions.menuAction(entry)` replays them.

### 1.6 The minimap

The minimap walk is not a row: the minimap's click handler (`0xef460(minimap, mouseX, mouseY, u8 clicked)`, called
from `0xed810`, itself called from the frame's input step `0x7deb0` with the minimap object at `Client + 0x4187c8`)
turns the pixel into a tile with the minimap's zoom (`+0xf8`) and centre offsets, through `0x60dbe0`, keeps it at
`+0x2a8` and, unless it equals the last walk target, builds `MOVE_MINIMAPCLICK` inline: opcode `0x1c`, 4 bytes, the
packed coordinate `level << 28 | x << 14 | z` stored as four obfuscated bytes (`v >> 8`, `v`, `v >> 24`, `v >> 16`).
When the minimap's double-click flag (`+0x188`) is set, the click is dropped unless it follows one within 500 ms and
25 pixels. With ctrl and shift held the handler sends the teleport packet instead (`0x3879f0`, opcode `0x12`, 9
bytes, also sent from the network step for the ctrl-shift plane change). The build has no walk packet with the Java
client's 14 extra bytes; both walks are 0x37 (Walk here, 5 bytes) and 0x1c.

The API doesn't build this packet: its fields are obfuscated byte stores, like every op packet, and the only
function sending it takes a minimap pixel and the minimap object, not a tile, so the pixel would have to be worked
back from the tile through the zoom and rotation. The layout above is what a later build of the hook would need.

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
targeted body; casting a spell is the same with `DATA {spell component, -1, -1}`. The button bodies take
`DATA {component}`, the close body `(IMM 0, IMM 0)`, as it reads nothing. The DLL still interprets nothing: the
functions, offsets and opcodes all come from the mapping through Java; nothing in the DLL changed for this.

## 3. The Java side

- `Actions` (`Client.getActions()`): `mouseClick(x, y, right)`; `walk(WorldPoint)`; `menuAction(entry)` and
  `menuAction(param0, param1, action, identifier[, itemId])` (an NPC's or player's index; a loc's or item's id with
  the tile's scene x and y on the top-level plane; a walk's scene x and y; a component's slot and packed id with the
  op as the identifier for `CC_OP`, the item read from the widget when not given); `click(x, y, ...)` for the click
  followed by the action in one batch; `useItemOnItem(component, slot, item, targetSlot, targetItem)` and
  `useItemOn(component, slot, item, entry | parameters)` for the select and the targeted op in one batch (a plain
  entry of the subject stands for its targeted one); `castSpellOn(spellComponent, entry | parameters)` for a spell
  the same way (High Alchemy on an inventory item is `WIDGET_TARGET_ON_WIDGET` with the inventory, slot and item; a
  spell on an NPC `WIDGET_TARGET_ON_NPC` with the index); `menuAction(p0, p1, action, id, itemId, subOp)` and
  `click(..., subOp)` for a component op's sub-option; `closeInterface()`; `resumeCountDialog`, `resumeNameDialog`,
  `resumeStringDialog`, `resumeObjDialog` and `resumePause(component, slot)`. `isSupported(action)`,
  `isMouseClickSupported()` and `isDialogSupported()` say what the build's mapping backs; `isAvailable()` whether the
  hook is connected. An action asked for without the hook is dropped, not queued: it is meant for the moment it is
  asked. `Client.menuAction(p0, p1, action, id, itemId, option, target)` keeps its usual shape; the native entry has no
  option or target, so those are unused.
- The `MenuAction`s with a body: the NPC, loc, ground item and player numbered ops, `WALK`, `CC_OP` and
  `CC_OP_LOW_PRIORITY`, `WIDGET_TARGET` (select), `WIDGET_CONTINUE` (pause), `WIDGET_TYPE_1`, `WIDGET_TYPE_4`,
  `WIDGET_TYPE_5` and `WIDGET_CLOSE` (the old-style buttons, by the component's button type as the menu reader maps
  it) and the `WIDGET_TARGET_ON_*` actions. Their parameters follow the entries the menu reader builds, so an entry
  from the menu or a menu event runs as it is; a sub-menu row's entry carries its sub-option (`MenuEntry.getSubOp()`)
  and `Widget.getSubOptions(op)` lists an op's sub-options.
- `NativeCall` has `Kind.SCRATCH` and `Kind.INDIRECT`; `NativeProtocol` sends the kinds as before (the ordinal).
- The Action Tester (`plugins.actiontester`) is the worked example. It runs every action and packet from its
  settings page, one at a time (pick the action, click Run): NPC, player, object, ground item, inventory and widget
  options by name (with a sub-option after a dot, `Drop.All` or `3.2`; a widget also as `Group.COMPONENT`, e.g.
  `Orbs.RUNBUTTON`), Walk here near the player or to a world tile in the loaded area, using an item or casting a
  spell (by name or as group:child) on an NPC, player, object, ground item or inventory item, casting a spell that
  takes no target (a teleport: the spell component's own op, Cast unless another is chosen), turning a prayer on or
  off (the prayer book component's op), the run, quick-prayer and special attack orbs (the orb button's first op, by
  the minimap or without it), old-style buttons by component, closing the interface, pause buttons, the four dialog
  answers, the bare mouse click, and listing or replaying the menu's own entries, with what it sent or why it
  couldn't in the console. NPC and player options click above the actor's tile (`Perspective.localToCanvas`), and
  component ops at the component's middle while it shows. The page shows only the chosen action's settings, and
  their drop-downs list what is loaded (nearby NPCs, objects and ground items, the inventory, the subject's options,
  the shown components, the spellbook's spells, the prayer book's prayers, the menu's entries). It is the tool for
  the live checks below.

## 4. Live checks pending

- A click on a Moss giant with the Action Tester's NPC option: the OPNPC packet is accepted (the player attacks) and
  the game's own pathing starts; the mouse click packet before it is accepted.
- The untargeted component ops: Varrock Teleport cast from the spellbook (its op is Cast, the spell has ops of its
  own), a prayer turned on and off from the prayer book whether or not its tab is open, and the run, quick-prayer and
  special attack orbs toggled; the prayer book components show the prayers' names (the suggestions and lookups read
  them).
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
- `castSpellOn` High Alchemy on an inventory item (Cast a spell on an inventory item, target `High Alchemy`): the
  item is alched and the spellbook's "Cast" selection is cleared by the client; the same spell on a non-alchable item
  gets the server's refusal, not a disconnect. A combat spell on an NPC (Cast a spell on an NPC, target `Wind Strike`):
  the player casts and the game's own pathing starts.
- List the menu entries with the spellbook open and a spell hovered: the Cast row reads as `WIDGET_TARGET` on the
  spell's component, and running it by index selects the spell (the "Cast X ->" text shows).
- An old-style button of each type (Old-style button, target `group:child`): a plain button's server-side effect, a
  toggle's var flipping (`client.getVarpValue` before and after), a set's var taking the component's value, a close
  button closing its interface; Close the open interface with a modal open (a bank, a shop) closes it, and with none
  open sends `CLOSE_MODAL` harmlessly.
- Sub-options (Inventory item option with `Drop.All`, or a bank's `Withdraw-X.All` as the sub-menu names them):
  `Widget.getSubOptions` lists what the sub-menu shows, the op packet is the 10-byte `0x42` and the server does the
  sub-option; List the menu entries with the sub-menu hovered shows the sub-rows after their op with their
  sub-option, and running one by index does the same.
- The minimap: a click on it sends the 4-byte `0x1c` packet (watch the packet log of the DLL, when it has one) and
  the player walks without the line-of-walk check Walk here has; the handler's fields above hold what §1.6 says.
