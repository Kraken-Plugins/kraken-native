# Menu Action Walkthrough: From Java to "Attack Goblin"

This page follows one menu action, attacking a Goblin, from a Java call to your character walking at the NPC. It
assumes no background in x64, C++, DLL injection or the game client, and introduces each idea where it is first
needed. For the full reference (every row function, the packet helpers, the updater recipes) see
[Menu actions](menu-actions.md).

**In one sentence:** Java works out *which* function inside the game handles an "Attack" click and *what data* that
function expects. A small helper DLL that lives inside the game then calls that function, exactly as if the menu
had been clicked.

## Part 1: What the Game Does on a Click

When you right-click a Goblin, the game builds the **minimenu** (the right-click menu). Each row in that menu is
two things:

1. **A function to run** (the "row body"), for example "the NPC option function".
2. **A small bundle of numbers** captured when the menu was built (the "captures"). For an NPC row that is
   `{npc index, which option, flag}`.

```
Right-click menu            What the game stores per row
┌────────────────────┐
│ Attack Goblin      │  →  body = <NPC option function>,  captures = {index 1234, op 2, flag 0}
│ Talk-to Goblin     │  →  body = <NPC option function>,  captures = {index 1234, op 1, flag 0}
│ Walk here          │  →  body = <Walk function>,        captures = {plane, x, y}
│ Examine Goblin     │  →  ...
└────────────────────┘
```

Clicking "Attack" makes the game run `body(something, &captures)`. That function:

- sends a packet to the server ("the player wants to do NPC option 2 on NPC #1234"), and
- starts the character facing and pathing toward the Goblin.

If something else calls that same function with the same captures, the game can't tell it apart from a real menu
click. Actions skips the menu and goes straight to what the menu would have done.

## Part 2: The Vocabulary

### The Game Is a Program in Memory

`osclient.exe` is a C++ program. When it runs, Windows loads it into memory at some starting address, the
**module base**, for example `0x7FF6_1200_0000`. Every function in the game sits at a fixed distance from that base.

### RVA: Distance From the Start

An **RVA** (Relative Virtual Address) is that distance. The base changes every launch because Windows randomises
it, but the distance stays the same for one build of the game.

```
real address = module base       + RVA
             = 0x7FF6_1200_0000  + 0x388870   (menuNpcOp in build 241.3)
             = 0x7FF6_1238_8870
```

Think of the base as the street a building is on (it moves) and the RVA as "3rd floor, room 870" (fixed).

### The Mapping: The Address Book

Every game update moves functions around, so the RVAs change. The **updater** (Python) analyses a new
`osclient.exe` and writes `api/src/main/resources/mappings/<version>.json`:

```json
"globals":   { "client": "0xe98c18" },
"functions": { "menuNpcOp": "0x388870", "appUpdate": "0x918950" },
"EVENT_MOUSE_CLICK": 7
```

The binary has no function names, so the updater finds `menuNpcOp` by a pattern described in
`updater/src/native_updater/spec/62-menu-actions.yaml`: find the code that builds NPC menu rows, and the function
address it hands to the row adder is the NPC row body. Java never hard-codes `0x388870`; it asks
`mapping.function("menuNpcOp")`.

### The x64 Calling Convention: How a Function Gets Its Arguments

On 64-bit Windows a C++ function receives its first four arguments in CPU **registers** named `RCX`, `RDX`, `R8`
and `R9`, in that order. Any more go on the stack. A register holds one 64-bit number, so a struct is passed by
putting it somewhere in memory and passing its **address** (a pointer).

`menuNpcOp`'s signature, from the spec YAML:

```
void menuNpcOp(void* unused, const struct {i32 index, i32 op, i32 flag}* args)
                ↑ RCX = 0       ↑ RDX = pointer to 12 bytes
```

### Byte Buffers and Little-Endian

The struct `{i32 index, i32 op, i32 flag}` is 12 bytes in memory, 4 bytes per `int`. x64 stores numbers
**little-endian** (lowest byte first). Java's `ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)` builds
exactly those bytes:

```
index = 1234 (0x4D2)   op = 2        flag = 0
D2 04 00 00            02 00 00 00   00 00 00 00
```

That is all `captures.putInt(identifier).putInt(op.op()).putInt(0)` in `Actions.actionCall` does: it writes the
same 12 bytes the game's own menu would have written.

### The DLL and Injection

Java runs in its own process. It can *read* the game's memory from outside (`ReadProcessMemory`, which is how every
getter works), but it can't make the game *run a function*. Only code inside the game process can do that.

So the client loads a small DLL, `kraken_hook.dll`, into the game. `CallbackInjector` uses the classic technique:

1. Allocate a bit of memory inside the game (`VirtualAllocEx`) and write the DLL's file path into it
   (`WriteProcessMemory`).
2. Start a thread in the game (`CreateRemoteThread`) that calls Windows' `LoadLibraryA(path)`.
3. Windows loads the DLL into the game, and the DLL's startup code runs.

The DLL then connects back to Java over a local socket (`127.0.0.1:42069`, `KRAKEN_PORT` in `protocol.h`). That
connection carries Java's commands. See [Native hook](native-hook.md) and [Native protocol](native-protocol.md).

### The Game Thread and the Pump Hook

Calling game code from just any thread isn't safe: if the game is halfway through updating, it can crash. So the
DLL **hooks** the game's per-frame function (`appUpdate`, RVA `0x918950`). It patches the start of that function
to jump into `pumpDetour` first (`pump.cpp`):

```cpp
void pumpDetour(void *self, float dt) {
    paceFrame();
    calls::runPending();          // run the queued calls here, on the game thread
    originalAppUpdate(self, dt);  // then the game's normal frame
}
```

Every frame, before the game does its own work, the DLL runs whatever calls Java has queued.

### NativeCall: "Call This Function With These Arguments"

`NativeCall` (`api/src/main/java/com/kraken/api/internal/invoke/NativeCall.java`) describes one function call:

- `rva`: which function
- `args`: up to 8 arguments
- `data`: a block of bytes that arguments can point into

The DLL knows nothing about NPCs or "Attack"; it only follows instructions. Each argument's **Kind** tells it how
to turn a number from Java into the real value it passes:

| Kind | Meaning | Example |
|---|---|---|
| `IMM` ("immediate") | Pass the number as it is | `imm(0)` → RCX = 0 |
| `DATA` | A pointer to the bytes sent with this call | `data(captures)` → RDX = address of those 12 bytes inside the game |
| `CLIENT` | The game's `Client` object's address plus an offset | A pointer to a field of `Client` |
| `INDIRECT` | Read a pointer stored in `Client`, then add an offset | `Client->connection + outCipher` |
| `SCRATCH` | A pointer into a 4 KB zeroed block every call of the batch shares | Lets call 1 build something call 2 uses |

"Immediate" is assembly slang: a constant written straight into the instruction, as opposed to a value loaded from
memory.

## Part 3: The Full Trip for "Attack Goblin"

The Goblin is NPC index 1234 and "Attack" is its 2nd option:

```java
client.menuAction(0, 0, MenuAction.NPC_SECOND_OPTION, 1234);
```

**Step 1: Actions picks the function** (`Actions.actionCall`)

- `NPC_SECOND_OPTION` → `Op(Subject.NPC, 2)`, from the `ops` table the constructor fills.
- `Subject.NPC` → function name `"menuNpcOp"`, 12 capture bytes.
- The mapping gives `menuNpcOp` = `0x388870`.

**Step 2: Actions builds the captures**

```
D2 04 00 00 | 02 00 00 00 | 00 00 00 00
```

**Step 3: Actions builds the NativeCall**

```java
NativeCall.to(0x388870).imm(0).data(captures).build()
// NativeCall[0x388870 [IMM 0, DATA 0] data=12]
// "call 0x388870 with RCX = 0 and RDX = a pointer to these 12 bytes"
```

**Step 4: The call goes over the socket** (`NativeProtocol.calls`)

The call is packed into bytes and sent to the DLL:

```
30 00 00 00                   length of what follows (48)
01                            command = CALLS
xx xx xx xx                   batch id
01 00                         1 call
70 88 38 00 00 00 00 00       rva 0x388870
02                            2 arguments
00  00 00 00 00 00 00 00 00   kind 0 = IMM,  value 0
02  00 00 00 00 00 00 00 00   kind 2 = DATA, offset 0 into the data
0C 00                         12 bytes of data:
D2 04 00 00 02 00 00 00 00 00 00 00
```

**Step 5: The DLL queues it** (`calls::queue` in `calls.cpp`)

The DLL's reader thread parses the message, copies the 12 bytes into memory inside the game, and puts the call in
a queue.

**Step 6: Next frame, on the game thread** (`runCall` in `calls.cpp`)

```
RCX    = 0                          (IMM)
RDX    = 0x0000_01A3_5F00_1230      (DATA: where the 12 bytes now live)
target = module base + 0x388870
```

`guardedCall` then calls the function. "Guarded" means that if the function faults (on bad arguments, say), the DLL
catches it instead of crashing the game.

**Step 7: The game does the rest**

`menuNpcOp` is the game's own code. It looks up NPC #1234, sends the OPNPC2 packet to the server, and starts the
character walking toward the Goblin. The server answers and the attack begins.

**Step 8: The receipt**

The DLL sends back `CALLS_DONE` with the batch id and how many calls ran and how many were skipped.

## Part 4: More Examples

### Walk to a Tile

```java
actions.walk(new WorldPoint(3222, 3218, 0));   // Lumbridge
```

This calls `menuWalk` with the captures `{plane 0, x 3222, y 3218}`:

```
00 00 00 00 | 96 0C 00 00 | 92 0C 00 00
```

It has the same shape as the NPC call, with a different function and different bytes.

### The Mouse Click Packets: SCRATCH and INDIRECT

A real click also sends two packets, `EVENT_MOUSE_CLICK` and then `EVENT_MOUSE_CLICK_V2`, and `Actions.click(...)`
sends both before the action. The game has no single "send a click" function, so Actions puts each packet together
from the game's own packet helpers. That takes **several calls in one batch** (`Actions.packetCalls`):

```
 1. packetCreate(SCRATCH+0, IMM 7, INDIRECT Client->connection + outCipher)
       the game builds a packet object in the scratch block
 2. packetWriteShort(SCRATCH+8, IMM flags)    writes into that same packet
 3. packetWriteShort(SCRATCH+8, IMM x)
 4. packetWriteShort(SCRATCH+8, IMM y)
 5. packetSend(INDIRECT Client->connection, SCRATCH+0)
 6. packetRelease(SCRATCH+8)
 7. packetCreate(SCRATCH+0, IMM 27, INDIRECT Client->connection + outCipher)
 8. packetWriteByte(SCRATCH+8, IMM byte 0)    seven bytes in this build's order
    ...
14. packetWriteByte(SCRATCH+8, IMM byte 6)
15. packetSend(INDIRECT Client->connection, SCRATCH+0)
16. packetRelease(SCRATCH+8)
17. menuNpcOp(IMM 0, DATA {1234, 2, 0})       the attack itself
```

SCRATCH is a shared scratchpad: call 1 builds the packet there and calls 2 to 6 find it; the first packet is released
before call 7 builds the second in the same place. INDIRECT is needed because the connection object's address is only
known inside the game at that moment, so the DLL reads it from `Client` right before the call. The second packet's
byte order changes from build to build, so the mapping says where each byte goes
([menu-actions.md](menu-actions.md#the-mouse-clicks)).

### Use an Item on an NPC

`useItemOn(...)` sends a batch of two calls:

1. `menuComponentSelect`: select the item in its inventory slot, as clicking "Use" does.
2. `menuNpcTargetOp`: use the selection on NPC #1234.

They go in one batch so they run back to back in the same frame, in order.

## Part 5: Why It's Split This Way

```
 updater (Python)          api (Java)                    kraken_hook.dll (C++, inside the game)
 ────────────────          ──────────                    ──────────────────────────────────────
 finds RVAs per build  →   mapping.json
                           Actions: knows WHAT to call
                           and WHAT bytes to pass   ──►  executor: resolves the arguments and
                           NativeCall over the socket    calls the function on the game thread
                                                                      │
                                                         the game's own menuNpcOp sends the
                                                         packet and paths the character
```

- **The DLL doesn't change when the game updates.** It knows nothing about NPCs, only "call address X with these
  arguments".
- **All the knowledge lives in Java and the mapping**, so a new game build needs only a new `mapping.json`.
- **Only the game's own functions are called, with the data the game itself would build**, so the packets look like
  normal clicks.

## Trying It

The [Action Tester](../plugins.md#action-tester) plugin (`plugins.actiontester`) calls these methods from a settings
page. Put a breakpoint in `Actions.actionCall`, fire "Attack" on an NPC, and look at the `captures` bytes: they show
the `D2 04 00 00 02 00 00 00 ...` pattern from the example above, with that NPC's index in place of 1234.
