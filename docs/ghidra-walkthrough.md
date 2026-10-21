# Mapping the native client by hand in Ghidra

A step-by-step guide to finding player names, player coordinates, NPC names and NPC coordinates in
`osclient.exe`, starting from an empty Ghidra install. It teaches the method the [updater](../updater) automates, so
when a new build ships you can find the same values yourself and understand what the updater is doing.

Each step says what to click, what you should see, and what it means. Every address and code snippet below was
checked against build `166231c8f6bd05c7` (2026-09-30, `OSRS-ORSM295-BWXF`) with Ghidra 12.1.4. On a different build
the addresses will differ but the method stays the same; see [Part 9](#part-9--when-jagex-ships-a-new-build).

**Contents**

- [Part 0 — Why it looks like gibberish, and how to read it](#part-0--why-it-looks-like-gibberish-and-how-to-read-it)
- [Part 1 — Set up Ghidra](#part-1--set-up-ghidra)
- [Part 2 — Find the "Rosetta stone": the script function table](#part-2--find-the-rosetta-stone-the-script-function-table)
- [Part 3 — Find the root pointer (the Client object)](#part-3--find-the-root-pointer-the-client-object)
- [Part 4 — Player coordinates](#part-4--player-coordinates)
- [Part 5 — Finding your own player (and how the player table works)](#part-5--finding-your-own-player-and-how-the-player-table-works)
- [Part 6 — Player names](#part-6--player-names)
- [Part 7 — NPCs: list, name, definition, coordinates](#part-7--npcs-list-name-definition-coordinates)
- [Part 8 — Make the decompiler readable with structs](#part-8--make-the-decompiler-readable-with-structs)
- [Part 9 — When Jagex ships a new build](#part-9--when-jagex-ships-a-new-build)
- [Part 10 — Checking your work against the running client](#part-10--checking-your-work-against-the-running-client)
- [Appendix A — Everything found in this guide](#appendix-a--everything-found-in-this-guide)
- [Appendix B — Ghidra keys and menus](#appendix-b--ghidra-keys-and-menus)
- [Appendix C — Glossary](#appendix-c--glossary)

---

## Part 0 — Why it looks like gibberish, and how to read it

Read this part once before opening Ghidra. Most of the "gibberish" comes from five things, and once you can name
them they stop being noise.

### 0.1 What you are looking for

While the game runs, everything it knows lives in memory as **objects**. An object is a block of bytes. Each piece
of data in it (a field) sits at a fixed distance from the start of the block. That distance is the field's
**offset**. The offset of a field is the same for every object of that kind, and stays the same until Jagex
recompiles the client.

The objects point at each other, and everything hangs off one global pointer:

```
osclient.exe + 0xE98C18 ──► Client object (one per game)
                              +0xCA80  pointer to the current World
                              +0xCC4C  int: your player's index (-1 when logged out)
                              +0xC9B8  world manager (all worlds, used by the lookups)

                            World object
                              +0x18    int: plane (0-3)
                              +0x60    hash map: player index ──► Player object
                              +0x90    hash map: NPC index    ──► Npc object
                              +0xD0    int[]: the NPC indices currently loaded (count at +0xD8)

                            Player / Npc object (both are an "Actor")
                              +0x3F0   int: tile x
                              +0x418   int: tile z (RuneLite calls this y)
                              ...
```

"Mapping the client" means filling in that picture: for each piece of data, which object holds it, and at which
offset. That is all `mappings/<version>.json` (for example `api/src/main/resources/mappings/241.3.json`) contains.

### 0.2 Why names are missing

The client is C++ compiled with Microsoft's compiler. Compilation throws away every name: there is no
`Player`, no `name`, no `getX()`. What is left is machine code and data. Ghidra invents placeholder names so it can
show you anything at all:

| You see | It means |
|---|---|
| `FUN_1401ecc00` | a function that starts at address `0x1401ecc00` |
| `DAT_140e98c18` | a global variable at address `0x140e98c18` |
| `s_npcName_140bcd2e8` | a text string (`"npcName"`) at that address |
| `PTR_FUN_140b97bb8` | a table of function pointers, usually a C++ vtable |
| `LAB_1403a82d1` | a jump target inside a function (the target of a `goto`) |
| `param_1`, `param_2` | the function's arguments, in order |
| `lVar4`, `uVar3`, `iVar1`, `pcVar8`, `puVar11`, `plVar7` | local variables. The prefix guesses the type: `l` = 64-bit integer, `i` = int, `u` = unknown, `pc` = `char*`, `pu` = pointer to unknown, `pl` = pointer to 64-bit |
| `local_38` | a variable on the stack, 0x38 bytes below the frame |
| `undefined4`, `undefined8` | "4 (or 8) bytes, and Ghidra doesn't know the type yet" |
| `longlong` | 64-bit integer. Very often an untyped **pointer** |

Your job in Ghidra is to replace those placeholders with real names as you work them out. Each rename makes the
next function easier to read.

### 0.3 Anchors: what survives compilation

You can't search for `Player.name` because that name no longer exists. You search for things the compiler had to
keep:

1. **Text strings.** Error messages, log text, and names the program looks up at run time.
2. **Constants.** Numbers like `0x800` (2048, the maximum player index) or `0x10000` (the NPC index limit).
3. **Shape.** "This function reads a global, calls a lookup, then writes three ints."

This client has an unusually good anchor. It contains a Lua plugin system, and it registers its script functions
**by name**: `npcName`, `playerName`, `npcCoord`, `playerCoord`, `playerFindSelf` and about 60 more. Each name is
stored as a string next to a pointer to a small function that reads exactly that piece of data. Find the string,
and you've found the function that reads the field. Part 2 shows how.

### 0.4 Reading x64 assembly from zero

You'll mostly read the Decompiler window, which turns assembly into C-like code. But the decompiler sometimes drops
details (Part 6 has an example), and the Listing window always shows assembly. This section explains assembly
starting from nothing. You don't need to memorize it; come back to it as a reference while you work.

Every example below is a real line from this client, with its address so you can find it in Ghidra (press **G**
and type the address).

#### 0.4.1 Three things: memory, registers, instructions

**Memory** is one enormous row of bytes. Each byte has a number: its **address**. "Address `0x140e98c18`" just
means "byte number 0x140e98c18 in that row". Everything the game knows (the Client object, every Player, every NPC)
is stored somewhere in that row.

A value bigger than one byte uses several bytes next to each other. An `int` uses 4 bytes. An address uses 8.
A **pointer** is an 8-byte number that is the address of something else. "Following a pointer" means reading those
8 bytes, then going to the address they contain.

**Registers** are a few storage slots built into the processor (CPU) itself. Each one holds one 8-byte number.
There are only 16 of them, but they are where the CPU does its work: comparing, adding, deciding. Nearly all code
follows the same rhythm:

1. copy a value from memory into a register,
2. do something with it,
3. copy the result back into memory, or use it to decide what to run next.

An analogy: memory is a warehouse full of shelves, and registers are your hands. You can't open a box while it's
on the shelf. You take it down, work on it, and put it back.

**Instructions** are the individual steps the CPU follows. Each line in Ghidra's Listing is one instruction:

```
1403ae97d   8b 86 f0 03 00 00   MOV   EAX,dword ptr [RSI + 0x3f0]
│           │                   │     │
│           │                   │     └─ operands: what the instruction works on
│           │                   └─ mnemonic: the instruction's name
│           └─ raw bytes: the machine code itself (Ghidra translates it for you; ignore it)
└─ address: where this instruction is stored
```

Operands are separated by commas. **The first operand is the destination and the second is the source.**
`MOV A, B` means "A = B": B is copied into A. Read it right to left.

So the line above says: "take the 4 bytes stored at address (RSI + 0x3f0) and put them in EAX." In this
function RSI holds a Player, so in C it's `eax = player->pathX[0];` (Part 4).

#### 0.4.2 The registers, one by one

There are 16 general-purpose registers. Their names are historical: the letters come from Intel processors of the
1970s, when each register had a special job. Today the compiler can put almost anything in almost any register, so
**a register's name doesn't tell you what's in it. You work that out from the code.** Only a few registers have a
fixed job, marked ★ below.

| Register | Name comes from | What it holds in this client |
|---|---|---|
| **RAX** | "accumulator" | ★ **The return value.** After a `CALL`, the called function's answer is in RAX. Also used as scratch space. |
| **RCX** | "counter" | ★ **Argument 1** to a function. When the function is a C++ method, argument 1 is the object itself (`this`). |
| **RDX** | "data" | ★ **Argument 2.** |
| **R8** | (added later, so just numbered) | ★ **Argument 3.** |
| **R9** | | ★ **Argument 4.** |
| **RBX** | "base" | General storage that **survives calls** (see below). `op_npcName` keeps the Client pointer in RBX. |
| **RSI** | "source index" | General, survives calls. In `op_playerCoord` RSI holds the Player for most of the function. |
| **RDI** | "destination index" | General, survives calls. In `op_playerFindSelf` RDI holds the Client. |
| **RBP** | "base pointer" | Originally pointed at the current function's local variables, and sometimes still does (`[RBP + -0x58]` is a local variable). In this optimized code it's often just another survives-calls register: in `op_playerCoord`, RBP holds the Client. |
| **RSP** | "stack pointer" | ★ Points at the current function's local variables (the "stack", 0.4.6). `[RSP + 0x20]` is always a local variable, never a game object. |
| **R10, R11** | | Scratch space. Overwritten by calls. |
| **R12–R15** | | General, survive calls. |

Two more you'll run into:

- **RIP**, the instruction pointer, holds the address of the instruction that's running. You rarely see it written.
  Code that reads a global variable actually stores a distance, e.g. "0xaea33d bytes past the end of this
  instruction". Ghidra does that sum for you and shows the final address, e.g. `[0x140e98c18]`.
- **XMM0–XMM15** are separate registers for decimal numbers (floats), such as camera angles. Instructions like
  `MOVSS` and `MOVD` use them. You won't need them for names or coordinates.

**"Survives calls": why it matters.** When code calls a function, that function is allowed to overwrite RAX, RCX,
RDX and R8–R11. It must leave RBX, RSI, RDI, RBP and R12–R15 exactly as it found them. So the compiler keeps values
it needs for a long time (the Client pointer, the Player it's working on) in RBX, RSI, RDI, RBP or R12–R15.

Practical tip: **when one of those registers appears all over a function, scroll to where it's first set, near the
top. That line tells you what it holds for the whole function.** For example, the top of `op_playerCoord`:

```
1403ae8d4  MOV  RBP,qword ptr [0x140e98c18]   ; RBP = g_client  → "RBP is the Client" from here on
1403ae8db  MOV  RBX,RCX                        ; RBX = argument 1 → "RBX is where the answer goes"
```

#### 0.4.3 One register, four sizes

Every register can be used whole or in part. RAX is 8 bytes. Its lower 4 bytes are called EAX, its lower 2 bytes
AX, and its lowest byte AL. These are not separate registers. They're the same slot, viewed at different sizes.

```
RAX  ├──────────────────────── all 8 bytes (64 bits) ───────────────────────┤
EAX                                       ├──────── lower 4 bytes (32) ──────┤
AX                                                        ├── lower 2 (16) ──┤
AL                                                                  ├ low 1 ┤
```

| 8 bytes | 4 bytes | 2 bytes | 1 byte |
|---|---|---|---|
| RAX | EAX | AX | AL |
| RBX | EBX | BX | BL |
| RCX | ECX | CX | CL |
| RDX | EDX | DX | DL |
| RSI | ESI | SI | SIL |
| RDI | EDI | DI | DIL |
| RBP | EBP | BP | BPL |
| RSP | ESP | SP | SPL |
| R8 … R15 | R8D … R15D | R8W … R15W | R8B … R15B |

Rule of thumb: an **R** at the front means 8 bytes; an **E** at the front, or a **D** ("double word") at the end,
means 4 bytes.

**Why you care:** the size of the register tells you the size of the data, which is a strong hint about what a
field is:

| Line | Size | So the field is probably… |
|---|---|---|
| `MOV EAX,dword ptr [RSI + 0x3f0]` (`1403ae97d`) | 4 bytes | an `int` (here, a coordinate) |
| `MOV RAX,qword ptr [RSI + 0x730]` (`1400a004f`) | 8 bytes | a **pointer** (here, to the NPC's definition) |
| `MOVZX EAX,byte ptr [RCX + 0x3a7]` (`14009f6c0`) | 1 byte | a `bool` or a small number |

The word in front of `ptr` gives the same size for memory: **byte** = 1, **word** = 2, **dword** = 4,
**qword** = 8. "ptr" just means "in memory at": `dword ptr [X]` = "the 4 bytes stored at address X".

#### 0.4.4 Operands: a register, a number, or memory

Every operand is one of three kinds:

| Kind | Looks like | Means |
|---|---|---|
| Register | `RAX`, `ECX` | the value in that register |
| Immediate (a constant) | `0x1e`, `0x800`, `0xffffffff` | that number, written into the instruction itself |
| Memory | anything in **square brackets** | the value stored in memory **at the address worked out inside the brackets** |

**Square brackets mean "go to memory".** Without brackets you're dealing with the number itself. Inside the
brackets is a small formula for the address. These are the forms you'll see:

| Memory operand | Address it means | Usually means |
|---|---|---|
| `[0x140e98c18]` | exactly 0x140e98c18 | a global variable |
| `[RAX]` | the number in RAX | the first field (offset 0) of the object RAX points to |
| `[RSI + 0x3f0]` | RSI + 0x3f0 | **the field at offset 0x3f0 of the object in RSI.** This is what you're hunting for. |
| `[R9 + RAX*0x8]` | R9 + RAX × 8 | item number RAX of an array of 8-byte items that starts at R9 |
| `[RCX + RAX*0x4 + 0x3350]` | RCX + 0x3350 + RAX × 4 | item RAX of an array of 4-byte ints at offset 0x3350 inside the object in RCX |

The last one comes from the skill-level getter (`1400f5828`): `MOV EAX,dword ptr [RCX + RAX*0x4 + 0x3350]`.
RCX is the Client, `0x3350` is where the `int skillLevel[]` array starts, RAX is the skill number, and `*0x4` is
there because each level takes 4 bytes. In C: `eax = client->skillLevel[skill];`.

#### 0.4.5 The instructions, one by one

Each one below has a plain explanation, real lines from the client, and the same thing written in C.

##### MOV: copy

`MOV destination, source` copies the source into the destination. The source doesn't change. (Despite the name,
nothing is "moved"; it's a copy.)

| Line | In C | Notes |
|---|---|---|
| `MOV RBP,qword ptr [0x140e98c18]` | `rbp = g_client;` | read 8 bytes from a fixed address: a global pointer |
| `MOV EAX,dword ptr [RSI + 0x3f0]` | `eax = player->pathX[0];` | read a 4-byte field |
| `MOV dword ptr [RBX + 0x4],EAX` | `out->x = eax;` | **write**: memory is the destination this time |
| `MOV RBX,RCX` | `rbx = rcx;` | register to register (here: keep argument 1 for later) |
| `MOV ECX,0xffffffff` | `ecx = -1;` | put a constant in a register |

One MOV can't copy from memory straight to memory. Copying a field from one object to another takes two lines:
read it into a register, then write it out. That's exactly what `op_playerCoord` does:

```
1403ae97d  MOV  EAX,dword ptr [RSI + 0x3f0]    ; eax = player->pathX[0]
1403ae983  MOV  dword ptr [RBX + 0x4],EAX      ; out->x = eax
```

##### MOVZX, MOVSX, MOVSXD: copy a small value into a bigger register

These read 1, 2 or 4 bytes and stretch the value to fill the register. **ZX** = "zero-extend", for unsigned
values: fill the top with zeros. **SX** = "sign-extend", for signed values: keep negative numbers negative.

| Line | In C |
|---|---|
| `MOVZX EAX,byte ptr [RCX + 0x3a7]` (`14009f6c0`) | `eax = (unsigned char)actor->interactingValid;` |
| `MOVSXD RDI,dword ptr [RBX + 0xccd0]` (`1403ad83a`) | `rdi = (long long)client->playerCount;` |

A bonus clue: MOVSX instead of MOVZX tells you the field is signed, so it can be negative.

##### LEA: work out an address, but don't read it

LEA ("load effective address") calculates the formula inside the brackets and puts **the resulting address** in
the register. **It never goes to memory.** It's the one instruction where square brackets don't mean "read memory".

Side by side, if RCX = 0x2000 and the 8 bytes stored at address 0x2008 are 0x5555:

| Instruction | Result | In C |
|---|---|---|
| `MOV RAX,qword ptr [RCX + 0x8]` | RAX = **0x5555**, the value stored there | `rax = obj->field_8;` |
| `LEA RAX,[RCX + 0x8]` | RAX = **0x2008**, the address itself | `rax = &obj->field_8;` |

You'll see LEA used for four things:

| Line | In C | Why |
|---|---|---|
| `LEA R8,[RBP + 0xc9b8]` (`1403ae8e3`) | `r8 = &client->worldManager;` | pass part of an object to a function |
| `LEA RCX,[RSP + 0x20]` (`1403ae8de`) | `rcx = &result;` | pass the address of a local variable, so the called function can write its answer there |
| `LEA RDX,[0x140bcd2e8]` (`1403ab2d0`) | `rdx = "npcName";` | a pointer to a string |
| `LEA RDX,[0x1401ec5c0]` (`1403ab2c0`) | `rdx = &op_npcName;` | a pointer to a function (Part 2) |

For mapping, the difference matters. `MOV …,[X + 0x3f0]` reads the field at 0x3f0. `LEA …,[X + 0xc9b8]` says
"something lives *at* 0xc9b8 inside X", usually a whole sub-object (like the world manager) whose fields the next
function will read.

Compilers also use LEA as a quick calculator: `LEA EAX,[RCX + RCX*0x2]` computes RCX × 3. If the result is then
used as a number rather than an address, that's what's going on.

##### Arithmetic: ADD, SUB, INC, IMUL, XOR, SHR

All of these work as `destination = destination <operation> source`.

| Line | In C | Where it's from |
|---|---|---|
| `ADD RBX,0xccd4` (`1403ad84b`) | `rbx += 0xccd4;` | `op_getPlayerIdAll`: RBX now points at the player-index array inside the Client |
| `SUB R8D,dword ptr [RAX + 0x24]` (`1403af179`) | `x -= world->baseX;` | `locFind`: turns a world coordinate into a local one |
| `INC ESI` (`1403ad59d`) | `esi++;` | a loop counter |
| `IMUL EAX,dword ptr [RCX + 0x948]` (`1406a2cec`) | `eax *= scene->sizeX;` | working out an array position in the tile lookup |
| `XOR EAX,EAX` (`1400d09ba`) | `eax = 0;` | anything XOR itself is 0; compilers use this to zero a register |
| `SHR AL,0x7` (`1401ec6a3`) | `al >>= 7;` | shift right by 7 bits, leaving only the top bit (the string flag from Part 6) |

##### CMP and TEST: compare, and remember the answer

CMP and TEST compare two things but **don't store a result anywhere you can see**. Instead they set the CPU's
**flags**: a few hidden yes/no switches such as "was the result zero?", "was it negative?", "was the first one
smaller?". The next instruction, almost always a conditional jump, reads those switches.

- `CMP A, B` works out A − B, throws the answer away, and keeps the flags. Afterwards the CPU knows whether A was
  equal to, less than, or greater than B.
- `TEST A, B` does the same with A AND B. Its most common form, `TEST RAX,RAX`, simply asks **"is RAX zero?"**.
  It's how assembly writes `if (ptr == NULL)`.

| Line | The question it asks |
|---|---|
| `CMP dword ptr [RDI + 0xcc4c],0x0` (`1403a8294`) | how does `client->localPlayerIndex` compare with 0? |
| `CMP dword ptr [RAX + 0x2160],0x1e` (`1400f5941`) | is `client->gameState` equal to 30 (logged in)? |
| `TEST RSI,RSI` (`1403ae93d`) | is the Player pointer null? |
| `TEST AL,0x1` (`1401ec6a6`) | is the lowest bit of AL set? |

##### Jumps (JMP, JZ, JNZ, JL, JA, …): choose what runs next

Normally the CPU runs instructions from top to bottom. A jump sends it to a different address instead.

- `JMP` always jumps. It's a `goto`.
- The others are **conditional**. They jump only if the flags from the last CMP or TEST say so. If not, the CPU
  carries on with the next line as normal. This is how `if`, `else` and loops are built.

| Jump | Stands for | After `CMP A,B` it jumps when | After `TEST X,X` it jumps when |
|---|---|---|---|
| `JMP` | jump | always | always |
| `JZ` (also written `JE`) | jump if zero / equal | A == B | X is 0 (null) |
| `JNZ` (also written `JNE`) | jump if not zero / not equal | A != B | X is not 0 (not null) |
| `JL` | jump if less | A < B, **signed** | X < 0 |
| `JLE` | jump if less or equal | A <= B, signed | |
| `JG`, `JGE` | jump if greater (or equal) | A > B, A >= B, signed | |
| `JA` | jump if above | A > B, **unsigned** | |
| `JB`, `JBE`, `JAE` | below, below or equal, above or equal | the unsigned versions | |

JZ and JE are two names for the same instruction (Ghidra prints `JZ`); the same goes for JNZ and JNE.

**Signed vs. unsigned.** "Less" and "greater" (JL, JG) allow negative numbers. "Below" and "above" (JB, JA) treat
every number as positive. Why that matters: in 4 bytes, −1 is stored as `0xffffffff`. Read as signed, that's −1,
which is less than 0. Read as unsigned, it's 4,294,967,295, the largest possible value.

Compilers use this as a trick. From `getNpc`:

```
14009d43c  CMP  R8D,0xffff       ; compare the NPC index with 65535
14009d443  JA   0x14009d45f      ; above 65535 (unsigned)? → jump to "not found"
```

Because JA is unsigned, a negative index looks enormous and fails the check too. One test catches "too big" and
"negative". `findPlayer` does the same with `CMP R14D,0x800` / `JA` (`1401872a2`): player indices above 2048 are
rejected.

Jumps are where the decompiler's `if`s come from. Here's the start of `op_playerFindSelf` next to its decompiled
version:

```
1403a828a  MOV  RDI,qword ptr [0x140e98c18]   ; rdi = g_client
1403a8291  MOV  RBX,RCX                        ; rbx = argument 1 (the script context)
1403a8294  CMP  dword ptr [RDI + 0xcc4c],0x0   ; compare client->localPlayerIndex with 0
1403a829b  JL   0x1403a82c0                    ; less than 0? → jump to the "return -1" code
1403a829d  MOV  RCX,RDI                        ; argument 1 = client
1403a82a0  CALL 0x14009d510                    ; rax = getLocalPlayer(client)
1403a82a5  TEST RAX,RAX                        ; did it return null?
1403a82a8  JZ   0x1403a82c0                    ; yes → jump to the "return -1" code
```

```c
if (-1 < *(int *)(DAT_140e98c18 + 0xcc4c)) {     // the CMP + JL
  lVar2 = FUN_14009d510(DAT_140e98c18);          // the MOV RCX + CALL
  if (lVar2 != 0) {                              // the TEST + JZ
```

Notice the conditions are flipped. The assembly says "jump **away** if less than 0"; the C says "**carry on** if
not less than 0". The decompiler usually flips conditions like this, so don't be thrown when the two look opposite.

##### CALL and RET: run another function

`CALL target` runs the function at `target`, then comes back to the line after the CALL. Before a CALL, the code
puts the arguments in **RCX, RDX, R8, R9**, in that order (a 5th argument or later goes on the stack). After the
CALL, the answer is in **RAX** (or EAX if it's an int).

From `op_playerCoord`:

```
1403ae8de  LEA  RCX,[RSP + 0x20]     ; argument 1: &result, a local variable where findPlayer writes its answer
1403ae8e3  LEA  R8,[RBP + 0xc9b8]    ; argument 3: &client->worldManager
1403ae8ea  CALL 0x140187280          ; findPlayer(&result, <argument 2>, &client->worldManager)
```

```c
FUN_140187280(local_38, param_2, DAT_140e98c18 + 0xc9b8);
```

Where's argument 2 (RDX)? Nothing sets it here. RDX still holds the player index that `op_playerCoord` itself
received as *its* argument 2, so it's passed straight through. When an argument register isn't set just before a
CALL, look further up for the last place it was set. That may be "it came in that way".

Two other forms you'll see:

- `CALL qword ptr [RAX + 0x28]` (`1401ec5dc`) reads the function's address from memory first. That's a C++
  **virtual method**: the address comes from a table attached to the object (its vtable). Ghidra usually can't tell
  which function will run.
- `JMP 0x1400ec3d0` as the last line of a function (`14009f667`, in `actorGetWorld`) is a "tail call": "run that
  function, and return whatever it returns".

`RET` ends the function and goes back to whoever called it. Whatever is in RAX at that moment is the return value.

##### Lines you can skip

| Line | What it is |
|---|---|
| `PUSH RDI` … `POP RDI` | save a register at the start of a function, restore it at the end |
| `MOV qword ptr [RSP + 0x8],RBX` in the first few lines | also saving a register |
| `SUB RSP,0x50` … `ADD RSP,0x50` | make room for local variables, then give it back |
| `NOP` | does nothing (padding) |
| `XADD.LOCK …` | a thread-safe add; here it's always reference counting (see the noise box in Part 4) |

#### 0.4.6 The stack: where local variables live

Every function gets a scratch area of memory for its local variables, called the **stack**. RSP points into it.
Anything written `[RSP + something]` (and often `[RBP - something]`) is a **local variable of the current
function**, not a field of a game object, even though it looks the same. Ghidra names these `local_38`,
`local_res10` and so on. Don't put them in your mapping.

#### 0.4.7 Putting it together: `op_playerCoord`, line by line

Here's the core of `op_playerCoord` with every line translated. The reference-counting lines
(`1403ae8ef`–`1403ae93a`) are left out.

```
; --- setup --------------------------------------------------------------------------------------
1403ae8d4  MOV  RBP,qword ptr [0x140e98c18]   ; RBP = g_client                  (RBP = Client from now on)
1403ae8db  MOV  RBX,RCX                        ; RBX = argument 1 = out          (where the answer goes)
; --- look up the player ------------------------------------------------------------------------
1403ae8de  LEA  RCX,[RSP + 0x20]              ; argument 1 = &result            (a local variable)
1403ae8e3  LEA  R8,[RBP + 0xc9b8]             ; argument 3 = &client->worldManager
1403ae8ea  CALL 0x140187280                   ; findPlayer(&result, index, &client->worldManager)
1403ae8f4  MOV  RSI,qword ptr [RAX + 0x8]     ; RSI = result.object = the Player  (RAX = &result)
; --- if (player == NULL) → error ---------------------------------------------------------------
1403ae93d  TEST RSI,RSI                       ; is the player null?
1403ae940  JZ   0x1403ae994                   ;   yes → "Invalid Player ID passed to player_coord."
; --- world = actorGetWorld(player) -------------------------------------------------------------
1403ae942  MOV  RCX,RSI                       ; argument 1 = player
1403ae945  CALL 0x14009f640                   ; RAX = actorGetWorld(player)
1403ae94a  TEST RAX,RAX                       ; is the world null?
1403ae94d  JNZ  0x1403ae978                   ;   no → skip to "write the answer"
;            (… the error for "Unable to find Player's world instance" is here …)
; --- write the answer ---------------------------------------------------------------------------
1403ae978  MOV  EAX,dword ptr [RAX + 0x18]    ; EAX = world->plane
1403ae97b  MOV  dword ptr [RBX],EAX           ; out->plane = EAX
1403ae97d  MOV  EAX,dword ptr [RSI + 0x3f0]   ; EAX = player->pathX[0]
1403ae983  MOV  dword ptr [RBX + 0x4],EAX     ; out->x = EAX
1403ae986  MOV  EAX,dword ptr [RSI + 0x418]   ; EAX = player->pathZ[0]
1403ae98c  MOV  dword ptr [RBX + 0x8],EAX     ; out->z = EAX
1403ae98f  JMP  0x1403aea49                   ; → return
```

The same function as C:

```c
Coord *op_playerCoord(Coord *out, int index) {
    Client *client = g_client;
    Player *player = findPlayer(&result, index, &client->worldManager)->object;
    if (player == NULL) { error("Invalid Player ID passed to player_coord."); ... }
    World *world = actorGetWorld(player);
    if (world == NULL)  { error("Unable to find Player's world instance in player_coord."); ... }
    out->plane = world->plane;          // [world + 0x18]
    out->x     = player->pathX[0];      // [player + 0x3f0]
    out->z     = player->pathZ[0];      // [player + 0x418]
    return out;
}
```

Every offset in the mapping file comes from a line like `MOV EAX,dword ptr [RSI + 0x3f0]`, plus working out
what RSI holds at that point.

#### 0.4.8 Practice tip: use both windows together

Click a line in the **Decompiler** window and the **Listing** jumps to the matching instructions; click in the
Listing and the Decompiler follows. Pick a line like `*(undefined4 *)(lVar4 + 0x3f0)`, click it, and look at which
`MOV` it came from. Doing this for a few functions is the fastest way to get comfortable reading both.

### 0.5 Reading the decompiler

| Decompiler text | Plain meaning |
|---|---|
| `*(int *)(p + 0x3f0)` | the int field at offset 0x3f0 of object `p` |
| `*(longlong *)(p + 0x718)` | the 8-byte field (often a pointer) at offset 0x718 |
| `**(undefined4 **)(p + 0x730)` | follow the pointer at 0x730, then read 4 bytes at offset 0 of what it points to |
| `(**(code **)(*p + 0x28))()` | a virtual method call: `p`'s vtable, slot at byte 0x28 |
| `*(char *)(p + 0x727) < '\0'` | "is the top bit of the byte at 0x727 set?" (a negative signed byte) |

> **The pointer arithmetic trap.** This trips up everyone. Ghidra writes `ptr + N` and `ptr[N]` C-style, so N is
> multiplied by the size of the type `ptr` points to:
>
> | Ghidra shows | Type of the variable | Byte offset |
> |---|---|---|
> | `param_1[0xe2]` | `undefined8 *` (8-byte items) | 0xe2 × 8 = **0x710** |
> | `puVar11 + 6` | `uint *` (4-byte items) | 6 × 4 = **0x18** |
> | `piVar14 + 4` | `int *` | 4 × 4 = **0x10** |
> | `local_38 + 1` | `longlong *` | 1 × 8 = **0x8** |
> | `(longlong)param_1 + 0x727` | cast to an integer first | **0x727** exactly |
>
> When an offset looks strange, check the variable's type before trusting the number.

### 0.6 Addresses vs. RVAs

Ghidra loads the exe at its preferred address, `0x140000000` (the **image base**). The real game is loaded at a
random address each run (Windows ASLR). So mappings store **RVAs**: the address minus the image base.

```
RVA            = Ghidra address − 0x140000000      e.g. 0x140e98c18 → 0xE98C18
runtime address = osclient.exe module base + RVA
```

Struct offsets (`+0x3F0`) don't change between runs; only the module base moves.

---

## Part 1 — Set up Ghidra

You already have Ghidra 12.1.4 at `C:\Users\<user>\Downloads\ghidra_12.1.4_PUBLIC_20260921\ghidra_12.1.4_PUBLIC` and
JDK 21 (Temurin). Start it with `ghidraRun.bat` in that folder.

### Step 1.1 — Note which build you have

In PowerShell:

```powershell
$exe = "C:\Program Files (x86)\Jagex Launcher\Games\Old School RuneScape\Client\osclient.exe"
(Get-Item $exe).VersionInfo.FileVersionRaw   # 241.3.0.0
Get-FileHash $exe                             # 166231C8F6BD05C7…
```

This guide was written against client version 241.3, whose SHA-256 starts `166231C8F6BD05C7…`. If yours differs,
Jagex has updated the client and the addresses below will be different. The steps still work. The first two parts of
the version (`241.3`) name the mapping file, `api/src/main/resources/mappings/241.3.json`; the hash identifies the exact binary, and the
mapping records it so it's never used with a different one.

### Step 1.2 — Create a project and import

1. **File → New Project… → Non-Shared Project.** Name it after the build, e.g. `osrs-166231c8`.
2. **File → Import File…** and pick `osclient.exe`.
   - Format: **Portable Executable (PE)**
   - Language: **x86:LE:64:default:windows** (Ghidra picks this automatically)
3. Ghidra copies the file's bytes into the project. A later client update won't change your project.

### Step 1.3 — Run auto-analysis

1. Double-click `osclient.exe` in the project window. The **CodeBrowser** opens.
2. Ghidra asks "*…has not been analyzed. Would you like to analyze it now?*" → **Yes**.
3. Keep the default analyzers and click **Analyze**.
4. Wait for the progress bar in the bottom-right corner to finish. On this 16 MB binary that takes about
   **10–20 minutes**. Do not start reading until it's done; half-analyzed code is missing functions and references.
5. **File → Save**.

> **Shortcut:** `C:\Users\<user>\osrs-re\ghidra_proj\osclient.gpr` is a project you analyzed on Oct 11. Open it with
> **File → Open Project**, then check **Help → About osclient.exe**. If the *Executable SHA256* starts with
> `166231c8`, you can skip the import and the wait.

### Step 1.4 — Check the image base

**Window → Memory Map.** The first `.text` row should start at `140001000`, so the image base is `0x140000000`. If
not, the addresses in this guide won't line up.

### Step 1.5 — The windows you'll use

| Window | Open with | Use it for |
|---|---|---|
| **Listing** | (open by default) | The assembly. Shows "XREF" notes listing who uses each address. |
| **Decompiler** | Window → Decompiler (Ctrl+E) | C-like version of the function under your cursor. |
| **Defined Strings** | Window → Defined Strings | Every text string Ghidra found. Has a filter box at the bottom. |
| **Symbol Tree** | (left side) | Functions and labels, including the ones you rename. |
| **Data Type Manager** | (bottom-left) | Where you create structs in Part 8. |

Navigation: **G** jumps to an address, **Alt+←** goes back, double-click follows a reference.

---

## Part 2 — Find the "Rosetta stone": the script function table

### Step 2.1 — Find the string `npcName`

1. **Window → Defined Strings.**
2. Type `npcName` in the filter box at the bottom.
3. Look for the row whose value is exactly `"npcName"`:

   ```
   140bcd2e8   "npcName"   string
   ```

4. Double-click it. The Listing jumps to the string:

   ```
                        s_npcName_140bcd2e8                         XREF[1]: FUN_1403a82f0:1403ab2d0(*)
   140bcd2e8  ds  "npcName"
   ```

`XREF[1]` means one place in the program uses this string. It's inside function `FUN_1403a82f0`, at instruction
`1403ab2d0`.

### Step 2.2 — Go to the line that uses the string

1. Double-click the XREF text `FUN_1403a82f0:1403ab2d0`. Both the Listing and the Decompiler jump there.
2. **Check where you landed.** The highlighted line in the Listing should be:

   ```
   1403ab2d0   48 8d 15 11 20 82 00   LEA   RDX,[s_npcName_140bcd2e8]       = "npcName"
   ```

   If you're instead at the very top of `FUN_1403a82f0` (address `1403a82f0`), press **G**, type `1403ab2d0`
   and press Enter.
3. **Give the Decompiler a moment.** `FUN_1403a82f0` is huge: about 3,300 instructions that register around 80
   script functions one after another. Its decompiled version is about 1,800 lines, so the Decompiler window can take
   10–30 seconds to fill in. The warning at the top, `/* WARNING: Type propagation algorithm not settling */`, is
   harmless.
4. In the Decompiler, the line `FUN_14004b640(local_6b0,"npcName");` should be highlighted. If you can't see it,
   click inside the Decompiler window, press **Ctrl+F**, and search for `"npcName"` (with the quotes).

### Step 2.3 — What this code is doing

`FUN_1403a82f0` fills in a lookup table for the plugin system. For each script function it does the equivalent of:

```c
registry.add("npcName", FUN_1401ec5c0);   // "when a plugin calls npcName, run FUN_1401ec5c0"
registry.add("npcCoord", FUN_1403ae4b0);
...                                       // ~80 of these, one after another
```

You already have the name. You want the **second thing in that pair: the function**.

### Step 2.4 — The key idea: "called" vs. "handed over"

A function's name can appear in the code in two different ways, and telling them apart is the whole trick:

| | In the Decompiler | In the Listing | What it means |
|---|---|---|---|
| **Called** | `FUN_14004b640(local_6b0,"npcName");`: the name **is followed by `(`** | `CALL FUN_14004b640` | runs that function right now |
| **Handed over** | `FUN_1403bcc80(local_680,FUN_1401ec5c0);`: `FUN_1401ec5c0` is **not followed by `(`**; it sits inside another call's brackets | `LEA RDX,[FUN_1401ec5c0]` | doesn't run it; passes its **address** along so it can be stored and run later |

Registering a script function means handing its address to the registry. So:

> **The handler is the function that is handed over, not called.** In the Decompiler, it's the `FUN_…` with no `(`
> after it. In the Listing, it's the `FUN_…` inside the square brackets of an `LEA`. Every `FUN_…` that is
> followed by `(` (Decompiler) or comes after the word `CALL` (Listing) is a helper: building strings, wrapping the
> function, registering it. Ignore those.

### Step 2.5 — Method A: find the handler in the Decompiler (easiest)

**The rule:**

1. Find the line with your name in quotes, e.g. `"npcName"`.
   If the name appears **twice** a few lines apart, use the **second** one. The first, `pcVar8 = "…";`, only measures
   the text's length.
2. Look at **that line, the line just above it, and the line just below it**. The handler is the `FUN_…` that has
   **no `(` right after it**.
3. If none of those three lines has one, look **upward** for a line shaped like `something = FUN_…;` (again, no
   `(`). That's the handler.

This finds the right function for all 11 names in this guide. If it ever comes up empty, use Method B.

**Example 1: `npcName` (handler on the line above).** This is what the Decompiler shows:

```c
FUN_1403bcc80(local_680,FUN_1401ec5c0);     // ◄── FUN_1401ec5c0 has no "(" after it → THE HANDLER
FUN_14004b640(local_6b0,"npcName");          // ◄── the name
FUN_1403c1290(param_1,param_2,local_6b0,local_680,local_6c8,&local_6e0);   // register: every FUN_ here has "("
FUN_140029f40(local_6b0);                    // clean-up calls that end the block
FUN_14020d4d0(local_680);
FUN_140043950(local_6c8);
FUN_140043950(&local_6e0);
```

Line above the name: `FUN_1403bcc80(…)` is called (it has `(`), but `FUN_1401ec5c0` inside its brackets isn't.
**Handler: `FUN_1401ec5c0`.**

**Example 2: `npcCoord` (handler on the line below).**

```c
uVar10 = FUN_14004b4d0(local_6b0,&DAT_140d074c8);
uVar5 = FUN_14004b640(local_6c8,"npcCoord");          // ◄── the name
FUN_1403c0cf0(param_2,FUN_1403ae4b0,uVar5,uVar10);    // ◄── FUN_1403ae4b0 has no "(" after it → THE HANDLER
uVar10 = FUN_14004b4d0(local_6b0,&DAT_140d074c8);
uVar5 = FUN_14004b640(local_6c8,"npcCoordFine");      //     (the next registration starts here)
FUN_1403c0fc0(param_2,FUN_1403ae660,uVar5,uVar10);
```

**Handler: `FUN_1403ae4b0`.** Two lines further down is `FUN_1403ae660`, but that belongs to the next name,
`npcCoordFine`. That's why you only look one line either side.

**Example 3: `getNpcIdAll` (name appears twice).**

```c
pcVar8 = "getNpcIdAll";                      // first mention: only measuring the length, skip it
local_6f8 = (undefined8 *)((ulonglong)local_6f8 & 0xffffffffffffff00);
local_6e1 = 0x17;
do {
  pcVar8 = pcVar8 + 1;
} while (*pcVar8 != '\0');
FUN_14004b5b0(&local_6f8,"getNpcIdAll");     // ◄── second mention: use this one
FUN_1403bc9b0(param_2,FUN_1403ad4b0,&local_6f8,&local_6e0);   // ◄── THE HANDLER
```

**Handler: `FUN_1403ad4b0`.**

**Example 4: `playerFindSelf` (handler stored in a variable first, further up).**

```c
local_res8 = 0;
local_res10 = FUN_1403a8280;                 // ◄── "something = FUN_…;" with no "(" → THE HANDLER
local_6f8 = (undefined8 *)&local_res8;
local_resc = 1;
puStack_6f0 = &local_res10;
FUN_140040280(local_6c8,&local_6f8);
local_6d8 = 0;
local_6d0 = 0;
uVar10 = FUN_1403c1f10(local_680,&local_res10);
FUN_14004b640(local_6b0,"playerFindSelf");   // ◄── the name
FUN_1403c1f50(param_1,param_2,local_6b0,uVar10,&local_6e0,local_6c8);
```

The name's line and its neighbours contain only called functions, so look upward. Eight lines up:
`local_res10 = FUN_1403a8280;`. **Handler: `FUN_1403a8280`.** (The client wraps this one in an extra helper object
first, which is why it's further away.)

### Step 2.6 — Method B: find the handler in the Listing (works every time)

Use this if the Decompiler is slow, shows an error, or Method A comes up empty. It's the same idea in assembly.

**The rule:**

1. Start on the line with the name (`LEA …,[s_npcName_…]`).
2. **Look down.** Read downward until you've passed **two** `CALL` lines. If you see an `LEA …,[FUN_…]` before the
   second `CALL`, that's the handler. Stop.
3. **Otherwise, look up.** Read upward from the name. The first `LEA …,[FUN_…]` you meet is the handler.

I checked this rule against every script name in this build: it finds the right function for all 67 that are
registered directly.

Here is the `npcName` block in the Listing, with each line labelled:

```
1403ab2c0  LEA   RDX,[FUN_1401ec5c0]             ◄── FUN_ inside [ ] of an LEA = HANDED OVER = THE HANDLER
1403ab2c7  LEA   RCX,[RBP + -0x58]
1403ab2cb  CALL  FUN_1403bcc80                       helper (called): wraps the handler into an object
1403ab2d0  LEA   RDX,[s_npcName_140bcd2e8]       ◄── you land here: the name
1403ab2d7  LEA   RCX,[RSP + 0x78]
1403ab2dc  CALL  FUN_14004b640                       first CALL: makes a string object from "npcName"
1403ab2e1  LEA   RAX,[RSP + 0x48]
1403ab2e6  MOV   RDX,RSI
1403ab2e9  MOV   qword ptr [RSP + 0x28],RAX
1403ab2ee  LEA   R9,[RBP + -0x58]
1403ab2f2  LEA   RAX,[RSP + 0x60]
1403ab2f7  MOV   qword ptr [RSP + 0x20],RAX
1403ab2fc  LEA   R8,[RSP + 0x78]
1403ab301  MOV   RCX,R14
1403ab304  CALL  FUN_1403c1290                       second CALL: adds name + handler to the registry
```

Most `LEA` lines have registers inside the brackets (`[RSP + 0x78]`, `[RBP + -0x58]`). Those are local variables,
not functions. You're looking for the one `LEA` whose brackets contain a **`FUN_` name**.

Looking down from `1403ab2d0`: first CALL, then second CALL, and no `LEA …,[FUN_…]` in between. So look up: three
lines up is `LEA RDX,[FUN_1401ec5c0]`. **Handler: `FUN_1401ec5c0`.**

For `npcCoord`, looking down finds it right away:

```
1403ab1fb  LEA   RDX,[s_npcCoord_140bcd2c0]      ◄── the name
1403ab202  MOV   RDI,RAX
1403ab205  LEA   RCX,[RSP + 0x60]
1403ab20a  CALL  FUN_14004b640                       first CALL
1403ab20f  MOV   R9,RDI
1403ab212  LEA   RDX,[FUN_1403ae4b0]             ◄── LEA of a FUN_ before the second CALL = THE HANDLER
1403ab219  MOV   R8,RAX
1403ab21c  MOV   RCX,RSI
1403ab21f  CALL  FUN_1403c0cf0                       second CALL (register)
```

### Step 2.7 — Check that you picked the right function

Do at least one of these checks every time. They take a few seconds each.

1. **Count its references.** Right-click the candidate → **References → Show References to**. A handler appears in
   only **2–5 places**: the line in `FUN_1403a82f0` you came from, plus entries in data tables that every function
   gets (addresses starting `1422…` or `140c…`; ignore those), and sometimes one small wrapper function that calls
   it. Real counts in this build: `npcCoord`'s handler has 2, `playerFindSelf`'s has 3, `npcName`'s has 5.
   Helpers are used far more: `FUN_1403bcc80` (the wrapper) has 23 and `FUN_14004b640` (make a string) has 57.
   **A long list means you picked a helper.**
2. **Look at it.** Double-click it. A handler is short (a screen or two in the Decompiler) and does what its name
   says: `npcName` builds a string, `npcCoord` writes three ints and contains the message
   `"Invalid NPC ID passed to npc_coord."`, `playerFindSelf` reads `DAT_140e98c18 + 0xcc4c`.
3. **Compare with the answer key.** The updater already parses this whole table. In a terminal:

   ```bash
   cd updater
   uv run native-updater bindings --binary "C:/Program Files (x86)/Jagex Launcher/Games/Old School RuneScape/Client/osclient.exe" --table ClientOp
   ```

   This reads the build's `program.db`, so run `uv run native-updater export --binary <osclient.exe>` first if the build
   hasn't been exported yet. It prints one line per script function:

   ```
   ClientOp.npcName                                         0x1ec5c0 direct
   ClientOp.npcCoord                                        0x3ae4b0 direct
   ClientOp.getNpcObj                                       0x3add60 factory
   ```

   The number is an RVA (Part 0.6). Add `0x140000000` to get the Ghidra address: `0x1ec5c0` → `FUN_1401ec5c0`.
   `direct` means registered the normal way, so the rules above work. `factory` means it's wrapped in an extra helper
   object and the rules don't apply. None of the functions in this guide are `factory` ones.

### Step 2.8 — Rename as you go

Renaming is how you build understanding. Each name you add shows up everywhere that function is used.

1. Click on `FUN_1403a82f0` (the function's name at its start, or any reference to it) and press **L**. Rename it
   `register_ClientOps`.
2. Double-click `FUN_1401ec5c0` to go there, press **L**, rename it `op_npcName`.

### Step 2.9 — Repeat for the others

Do the same for each name below:

1. **Defined Strings** → filter for the name → double-click it.
2. Double-click its XREF (or press **G** and type the "Name used at" address).
3. Find the handler with Method A (Decompiler, Step 2.5) or Method B (Listing, Step 2.6). The "Handler is" column
   tells you which direction to look, and "Handler LEA at" is the exact Listing line, so you can check yourself.
4. Check it (Step 2.7), then rename it `op_<name>`.

| Script name | String address | Name used at | Handler LEA at | Handler is | Handler (rename to `op_…`) | What it gives you |
|---|---|---|---|---|---|---|
| `npcName` | `140bcd2e8` | `1403ab2d0` | `1403ab2c0` | above | `FUN_1401ec5c0` | an NPC's name |
| `npcTypeId` | `140bcd3e8` | `1403ab546` | `1403ab536` | above | `FUN_1401ec890` | the NPC's definition id |
| `npcCoord` | `140bcd2c0` | `1403ab1fb` | `1403ab212` | below | `FUN_1403ae4b0` | an NPC's tile |
| `npcCoordFine` | `140bcd2f0` | `1403ab235` | `1403ab24c` | below | `FUN_1403ae660` | an NPC's precise position |
| `getNpcIdAll` | `140bccf08` | `1403a8529` | `1403a8547` | below | `FUN_1403ad4b0` | every loaded NPC index |
| `ncName` | `140bcd3dc` | `1403ab618` | `1403ab608` | above | `FUN_1401ec950` | a name from an NPC *definition* id |
| `playerName` | `140bcd3a0` | `1403ab80e` | `1403ab7fe` | above | `FUN_1401ecc00` | a player's name |
| `playerCoord` | `140bcd388` | `1403ab739` | `1403ab750` | below | `FUN_1403ae8c0` | a player's tile |
| `playerCoordFine` | `140bcd3b0` | `1403ab773` | `1403ab78a` | below | `FUN_1403aea70` | a player's precise position |
| `playerFindSelf` | `140bcd428` | `1403ab985` | `1403ab91e` | further up | `FUN_1403a8280` | your own player's index |
| `getPlayerIdAll` | `140bccf98` | `1403a8639` | `1403a8657` | below | `FUN_1403ad810` | every loaded player index |

`getNpcIdAll` and `getPlayerIdAll` each show **two** XREFs, both in the same block (the code first measures the
string's length, then copies it). In the Decompiler, use the second mention of the name (Example 3 in Step 2.5). In
the Listing, the rule works from either one.

> **A second set of anchors.** Some handlers also contain error messages, and those lead to the same functions:
> `"Invalid Player ID passed to player_coord."`, `"…player_coord_fine."`, `"Invalid NPC ID passed to npc_coord."`,
> `"…npc_coord_fine."`. Search for `passed to` in Defined Strings. Their XREF lands *inside* the handler. That's
> useful if the registration code ever changes shape.

---

## Part 3 — Find the root pointer (the Client object)

### Step 3.1 — Look at the first line of any handler

Open `op_playerName` (`FUN_1401ecc00`) and look at the Decompiler window. The function starts like this:

```c
undefined8 FUN_1401ecc00(longlong *param_1)
{
  ...
  lVar6 = DAT_140e98c18;
  uVar3 = (**(code **)(*param_1 + 0x28))();
  FUN_140187280(&local_38, uVar3, lVar6 + 0xc9b8);
  ...
```

Now open `op_npcName`, `op_playerCoord`, `op_playerFindSelf`. Every one of them starts by reading
**`DAT_140e98c18`**. It's a global variable that holds the address of the main game object: the `Client`.

### Step 3.2 — Confirm it's the root

1. Double-click `DAT_140e98c18`.
2. Right-click it → **References → Show References to DAT_140e98c18** (or press Ctrl+Shift+F).
3. You'll get a list of several hundred places. Code that reads the same global from hundreds of places is almost
   always the program's central object.
4. Press **L** and rename it `g_client`.

**Result:** `Client* = read 8 bytes at (module base + 0xE98C18)`. Everything else is reached from here.

### Step 3.3 — Recognize the plugin-API calls (so you can ignore them)

`param_1` in these handlers is the Lua call context. It's not game data. From how the handlers use it:

| Call | Meaning (inferred from use) |
|---|---|
| `(**(code **)(*param_1 + 0x28))()` | read the integer argument the script passed (e.g. the NPC or player index) |
| `(**(code **)(*param_1 + 0x10))(param_1, value)` | return an integer to the script |
| `(**(code **)(*param_1 + 8))(param_1, string)` | return a string to the script |

So in `op_playerName`, `uVar3` is "the player index the script asked about". Rename it `playerIndex`.

---

## Part 4 — Player coordinates

### Step 4.1 — Read `op_playerCoord`

Open `FUN_1403ae8c0`. This one has a different signature: `param_1` is where the result goes (3 ints), and
`param_2` is the player index. Here is the decompiler output, with the reference-count cleanup cut (see the
"noise" box below):

```c
undefined8 * FUN_1403ae8c0(undefined8 *param_1, undefined8 param_2)
{
  lVar3 = DAT_140e98c18;                                            // g_client
  lVar4 = FUN_140187280(local_38, param_2, DAT_140e98c18 + 0xc9b8); // look up the player
  lVar4 = *(longlong *)(lVar4 + 8);                                 // the Player* from the result
  /* ... reference-count cleanup ... */
  if (lVar4 == 0) {
    pcVar8 = "Invalid Player ID passed to player_coord.";
  }
  else {
    lVar6 = FUN_14009f640(lVar4);                                   // which World is this player in?
    if (lVar6 != 0) {
      *(undefined4 *)param_1               = *(undefined4 *)(lVar6 + 0x18);   // out[0] = world+0x18
      *(undefined4 *)((longlong)param_1+4) = *(undefined4 *)(lVar4 + 0x3f0);  // out[1] = player+0x3f0
      *(undefined4 *)(param_1 + 1)         = *(undefined4 *)(lVar4 + 0x418);  // out[2] = player+0x418
      return param_1;
    }
    pcVar8 = "Unable to find Player\'s world instance in player_coord.";
  }
  ...
```

Translate it line by line:

1. `FUN_140187280(result, index, g_client + 0xc9b8)` looks up a player by index. The third argument is the
   address of the **world manager** at `Client+0xC9B8`. Rename `FUN_140187280` → `findPlayer`.
2. The lookup returns a two-pointer pair `{control block, object}` (a reference-counted pointer). The object
   pointer is the second one, hence `+ 8`. So `lVar4` is the **Player**. Rename it `player`.
3. `FUN_14009f640(player)` returns the **World** the player is in. Rename it `actorGetWorld`, and `lVar6` → `world`.
4. The output is three ints: `{ world+0x18, player+0x3f0, player+0x418 }`. A coordinate is `{plane, x, z}`, so:
   - **`World+0x18` = plane**
   - **`Actor+0x3F0` = tile x**
   - **`Actor+0x418` = tile z** (RuneLite calls this axis y)

### Step 4.2 — Why `+0x3F0` and `+0x418` are arrays

`0x418 − 0x3F0 = 0x28` = 40 bytes = room for **10 ints**. The game keeps a short queue of the tiles an actor is
walking through; entry `[0]` is the tile it's on now. That's why the updater calls these `Actor.pathX` /
`Actor.pathZ` with type `i32[10]`. The queue length comes from the gap between the two fields; it's an inference,
not something the code states.

### Step 4.3 — World coordinates or local?

Inferred: these are **world coordinates** (the ones on the world map). Reason: `locFind` (`FUN_1403af050`) takes a
coordinate of the same `{plane, x, z}` shape and subtracts `World+0x24` and `World+0x28` before looking up a tile.
Those two are the world position of the loaded area's corner (`World.baseX` / `baseZ`). `playerCoord` returns
`+0x3F0` unchanged, so the actor already stores world coordinates.

Check it in game: in Lumbridge castle courtyard you should read roughly x ≈ 3222, z ≈ 3218. Values below ~200 would
mean local coordinates instead.

### Step 4.4 — Look at `actorGetWorld`

```c
undefined8 FUN_14009f640(longlong param_1)
{
  if (*(int *)(param_1 + 0x4a0) == 0) {
    return *(undefined8 *)(DAT_140e98c18 + 0xca80);
  }
  return FUN_1400ec3d0(DAT_140e98c18 + 0xc9b8);
}
```

- **`Actor+0x4A0`** is a world id. `0` means "the main world".
- **`Client+0xCA80`** holds a pointer to the main world (the updater calls it `Client.topLevelWorld`).
- Non-zero ids belong to other worlds, such as boats (looked up through the world manager).

For most purposes: `world = read_ptr(client + 0xCA80)` and `plane = read_i32(world + 0x18)`.

### Step 4.5 — Precise ("fine") coordinates

Open `op_playerCoordFine` (`FUN_1403aea70`). The useful part:

```c
uVar4 = FUN_14002a300(lVar7 + 0x260);
uVar5 = FUN_140051f10(lVar7 + 0x260);
uVar6 = FUN_140097960(*(undefined4 *)(lVar9 + 0x18), lVar9, uVar4, uVar5, ...);
*(undefined4 *)((longlong)param_1 + 4) = uVar6;   // y = height
*(undefined4 *)param_1 = uVar4;                   // x
*(undefined4 *)(param_1 + 1) = uVar5;             // z
```

Double-click the two small functions:

```c
undefined4 FUN_14002a300(longlong param_1) { return *(undefined4 *)(param_1 + 8); }
undefined4 FUN_140051f10(longlong param_1) { return *(undefined4 *)(param_1 + 0xc); }
```

They're "getters": each returns one field. Called with `player + 0x260`, they read **`Actor+0x268` = fine x** and
**`Actor+0x26C` = fine z**. Fine units are 1/128 of a tile, so `fineX >> 7` should equal `pathX[0]`. The
`+0x260` is a small sub-object inside every actor (it has its own vtable pointer at `+0x260`), which is why the
code passes `player + 0x260` instead of `player`.

`FUN_140097960(plane, world, x, z, …)` returns the **ground height** at that point. It's a good lead for tile
heights, which the updater hasn't mapped yet.

> **Noise you can skip.** These patterns appear in almost every handler and never contain game data:
>
> | Pattern | What it is |
> |---|---|
> | `LOCK(); iVar1 = *piVar5; *piVar5 = *piVar5 + -1; UNLOCK(); if (iVar1 == 1) { (**(code **)(*x + 8))(x); …` | releasing a reference-counted pointer |
> | `FUN_140042b90(x)` | returns its argument unchanged (`MOV RAX,RCX; RET`) |
> | `local_28 = 0; local_11 = '\x17'; FUN_14004b5b0(&local_28, …)` | building a string object |
> | the block using `DAT_1415497e8 + 0x100 / 0x198 / 0x1a8` | freeing a string's memory |
> | `CONCAT71(uStack_27, local_28)` | Ghidra gluing bytes of one variable back together |
> | `do { pcVar8 = pcVar8 + 1; } while (*pcVar8 != '\0');` | measuring a string's length |

---

## Part 5 — Finding your own player (and how the player table works)

### Step 5.1 — `op_playerFindSelf`

Open `FUN_1403a8280`:

```c
undefined8 FUN_1403a8280(longlong *param_1)
{
  lVar1 = DAT_140e98c18;
  if (-1 < *(int *)(DAT_140e98c18 + 0xcc4c)) {
    lVar2 = FUN_14009d510(DAT_140e98c18);
    if (lVar2 != 0) {
      (**(code **)(*param_1 + 0x10))(param_1, 1);
      uVar3 = *(undefined4 *)(lVar1 + 0xcc4c);
      goto LAB_1403a82d1;
    }
  }
  uVar3 = 0xffffffff;
  ...
  (**(code **)(*param_1 + 0x10))(param_1, uVar3);     // return the index (or -1)
```

**`Client+0xCC4C` = your player's index.** It's negative (-1) when there's no local player (logged out or loading).
The script gets back that index and then calls `playerCoord(index)` / `playerName(index)` with it. You do the same.

### Step 5.2 — The list of all players

`op_getPlayerIdAll` (`FUN_1403ad810`) is short:

```c
lVar2 = (longlong)*(int *)(DAT_140e98c18 + 0xccd0);      // count
if (0 < lVar2) {
  puVar1 = (undefined4 *)(DAT_140e98c18 + 0xccd4);      // first element
  do {
    local_res10[0] = *puVar1;                            // copy one index
    ...
    puVar1 = puVar1 + 1;
```

**`Client+0xCCD0` = number of players**, followed directly by an **int array of player indices at
`Client+0xCCD4`**.

### Step 5.3 — From an index to a Player object: the hash map

`findPlayer` (`FUN_140187280`) turns an index into a `Player*`. It's long, but the part that matters is:

```c
if (0x800 < param_2) { ... return empty; }               // player index must be <= 2048
puVar2 = *(undefined8 **)(param_3 + 0x20);              // world manager's bucket array
...
lVar16 = *(longlong *)(piVar14 + 4);                    // a World*  (int* + 4 = byte 0x10)
lVar4  = *(longlong *)(lVar16 + 0x68);                  // World+0x68: bucket array
for (puVar11 = *(uint **)(lVar4 + (index % *(uint *)(lVar16 + 0x70)) * 8);   // World+0x70: bucket count
     puVar11 != 0;
     puVar11 = *(uint **)(puVar11 + 6)) {               // next node (uint* + 6 = byte 0x18)
  if (param_2 == *puVar11) {                            // node key (byte 0) == index?
     ... *(longlong *)(puVar11 + 4) ...                 // node value (uint* + 4 = byte 0x10) = Player*
```

Remember the pointer-arithmetic trap from Part 0: `puVar11` is a `uint *`, so `+ 6` is 24 bytes (0x18) and
`+ 4` is 16 bytes (0x10).

This is a standard hash map. Its layout:

```
World + 0x60   the map
World + 0x68   ──► bucket array: bucketCount pointers to nodes
World + 0x70   bucketCount

bucket = index % bucketCount

node:  +0x00  u32  key (the player index)
       +0x08  ptr  control block (reference counts; ignore)
       +0x10  ptr  value: Player*
       +0x18  ptr  next node in the same bucket (null = end)
```

So: `node = buckets[index % count]`, then follow `next` until `key == index`; `node+0x10` is the Player. The NPC map
in Part 7 has exactly the same layout at `World+0x90`.

`findPlayer` loops over *every* world in the world manager (bucket array at `Client+0xC9B8+0x20`, count at
`+0x28`). For ordinary gameplay you can just use the main world from `Client+0xCA80`.

### Step 5.4 — The whole chain

```
client      = read_ptr (base + 0xE98C18)
index       = read_i32 (client + 0xCC4C)          // -1 → not logged in
world       = read_ptr (client + 0xCA80)
player      = hashmap_get(world + 0x60, index)    // buckets world+0x68, count world+0x70
plane       = read_i32 (world  + 0x18)
x           = read_i32 (player + 0x3F0)
z           = read_i32 (player + 0x418)
```

---

## Part 6 — Player names

### Step 6.1 — `op_playerName`

Back in `FUN_1401ecc00`:

```c
FUN_140187280(&local_38, uVar3, lVar6 + 0xc9b8);            // findPlayer → {local_38, local_30}
if ((local_30 == 0) || (*(longlong *)(local_30 + 0x718) == 0)) {
  ... return an empty string ...
}
else {
  lVar6 = *param_1;
  uVar4 = FUN_140042b90();                                   // returns its argument unchanged
  (**(code **)(lVar6 + 8))(param_1, uVar4);                  // return a string to the script
}
```

The decompiler lost the argument to `FUN_140042b90`. Switch to the Listing to see it:

```
1401ecc2d  MOV   RCX,qword ptr [RSP + 0x28]     ; RCX = Player*
1401ecc37  MOV   RCX,qword ptr [RCX + 0x718]    ; RCX = 8-byte value at Player+0x718
1401ecc46  CALL  FUN_140042b90                  ; RAX = RCX (does nothing)
1401ecc4b  MOV   RDX,RAX                        ; argument 2 = that value
1401ecc51  CALL  qword ptr [R8 + 0x8]           ; "return a string"
```

So the value at **`Player+0x718` is a pointer, and what it points to is the name string**. (When the decompiler
output looks incomplete, the Listing is the ground truth.)

### Step 6.2 — How strings are stored

The client uses a 24-byte string object (EASTL `string`). You'll see the same pattern in many places:

```
byte 23 (offset +0x17) has its top bit set (negative as a signed char)?
  yes → "long" form:  +0x00 char* text,  +0x08 length,  +0x10 capacity
  no  → "short" form: the text is stored inline at +0x00, length = 23 − byte 23
```

Strings of up to 23 characters fit inline, which covers almost every player name. The tell-tale code is
`local_11 = '\x17'` (23) and tests like `*(char *)(x + 0x17) < '\0'`.

```
nameObj = read_ptr(player + 0x718)
b23     = read_u8 (nameObj + 0x17)
if b23 & 0x80:  name = read_bytes(read_ptr(nameObj), read_u64(nameObj + 8))
else:           name = read_bytes(nameObj, 23 - b23)
```

### Step 6.3 — Confirm with the constructors

When you're unsure what a field is, find the code that creates the object. A constructor writes a starting value
into most fields, so it shows the object's layout.

All actors share a common constructor, `FUN_14009dcd0`. Right-click it →
**References → Show References to**. There are exactly two callers, one per kind of actor:

**Player constructor, `FUN_1400a1110`:**

```c
FUN_14009dcd0();                          // build the shared Actor part first
param_1[0xe6] = 0xffffffffffffffff;       // +0x730 = -1
*param_1 = &PTR_FUN_140b97bb8;            // +0x000 = Player vtable
...
param_1[0xe2] = 0;                        // +0x710 = 0  (8 bytes)
param_1[0xe3] = 0;                        // +0x718 = 0  (8 bytes)  ← the name pointer
param_1[0xe4] = 0;                        // +0x720 = 0
param_1[0xe5] = 0;                        // +0x728 = 0
do { *(char*)p = 0; *((char*)p + 0x17) = 0x17; p += 3; } while (--n);  // 3 empty strings from +0x738
```

**NPC constructor, `FUN_1400d0970`:**

```c
FUN_14009dcd0();
*param_1 = &PTR_FUN_140b97f78;                       // Npc vtable
param_1[0xe6] = 0;                                   // +0x730 = null (the NPC definition pointer)
*(undefined1 *)(param_1 + 0xe2) = 0;                 // +0x710: first char = 0
*(undefined1 *)((longlong)param_1 + 0x727) = 0x17;   // +0x727 = 23 → empty inline string at +0x710
```

(`param_1` is `undefined8 *` here, so `[0xe2]` is byte 0x710. See the pointer trap in Part 0.)

Compare the two:

- On an **NPC**, `+0x710` gets the empty-string pattern (`byte 0 = 0`, `byte 23 = 0x17`). It's a 24-byte string
  stored inline.
- On a **Player**, `+0x710..+0x72F` is cleared as four plain 8-byte values. `+0x718` is the pointer to the name
  object. `+0x710` is most likely its reference-count control block (the `{control block, object}` pair from
  Part 4). That part is inferred from the layout; nothing reads `+0x710` as a control block in the code shown here.

**The same offset can mean different things in different subclasses.** Players and NPCs share everything up to
about `+0x710`; after that their layouts differ.

> **Mapping note.** `241.3.json` has a shared `Actor.name = 0x710` (an inline string, right for NPCs; see Part 7)
> and a separate `Player.name = 0x718`, the pointer to the `PlayerName` pair `{display, normalised}`. The probe's
> `localPlayerName` check follows `Player.name`, not `Actor.name`.

---

## Part 7 — NPCs: list, name, definition, coordinates

### Step 7.1 — Listing every NPC: `op_getNpcIdAll`

```c
plVar1 = *(longlong **)(DAT_140e98c18 + 0xc9d8);         // world manager: bucket array
...
lVar2 = plVar1[*(longlong *)(DAT_140e98c18 + 0xc9e0)];   // bucket count → end marker
do {
  lVar3 = *(longlong *)(lVar7 + 0x10);                   // node value: World*
  if ((lVar3 != 0) && (0 < *(int *)(lVar3 + 0xd8))) {    // World+0xD8: NPC count
    do {
      local_res10[0] = *(undefined4 *)(lVar9 + *(longlong *)(lVar3 + 0xd0));  // World+0xD0: int[] of NPC indices
      ...
      lVar9 = lVar9 + 4;
    } while (iVar6 < *(int *)(lVar3 + 0xd8));
  }
  lVar7 = *(longlong *)(lVar7 + 0x18);                   // next world node
} while (lVar7 != lVar2);
```

For each World: **`World+0xD0` points to an int array of NPC indices**, and **`World+0xD8` is how many** there are.

### Step 7.2 — From an index to an Npc object

`op_npcName` and `op_npcTypeId` both call `FUN_14009d430`:

```c
undefined8 * FUN_14009d430(longlong param_1, undefined8 *param_2, uint param_3)
{
  if (param_3 < 0x10000) {
    FUN_1401878b0(param_2, param_3, param_1 + 0xc9b8);
    return param_2;
  }
  ...
```

NPC indices must be below 65536. Rename `FUN_14009d430` → `getNpc` and `FUN_1401878b0` → `findNpc`. `findNpc` is the
twin of `findPlayer` and uses the NPC hash map at **`World+0x90`** (buckets `+0x98`, count `+0xA0`), with the same
node layout as Part 5.

### Step 7.3 — NPC definition id: `op_npcTypeId`

```c
lVar4 = FUN_14009d430(uVar2, local_18, uVar3);        // getNpc
lVar4 = *(longlong *)(lVar4 + 8);                     // Npc*
...
uVar3 = **(undefined4 **)(lVar4 + 0x730);             // follow Npc+0x730, read 4 bytes at +0
```

- **`Npc+0x730` = pointer to the NPC's definition** (the "type": name, size, options, … shared by every goblin).
- **`NpcType+0x0` = definition id** (the id you'd look up on the wiki).

### Step 7.4 — NPC name: `op_npcName` → `FUN_14009ffc0`

`op_npcName` calls `FUN_14009ffc0(npc, &out)`. Rename it `npcGetName`:

```c
undefined1 * FUN_14009ffc0(longlong param_1, undefined1 *param_2)
{
  // 1) The NPC's own name string at +0x710: what is its length?
  if (*(char *)(param_1 + 0x727) < '\0') lVar2 = *(longlong *)(param_1 + 0x718);   // long form: length field
  else                                   lVar2 = 0x17 - (longlong)*(char *)(param_1 + 0x727);  // short form
  if (lVar2 != 0) {
    ... copy the string at param_1 + 0x710 and return it ...
  }
  // 2) Otherwise use the definition's name
  if (*(longlong *)(*(longlong *)(param_1 + 0x730) + 0x140) != 0) {
    lVar3 = FUN_1405f3970(*(longlong *)(param_1 + 0x730), local_28);   // transformed definition
    lVar3 = *(longlong *)(lVar3 + 8);
  }
  if (lVar3 == 0) lVar3 = *(longlong *)(param_1 + 0x730);               // NpcType*
  plVar5 = (longlong *)(lVar3 + 8);                                     // NpcType+0x8: name string
  ...
```

This is the string pattern from Part 6, applied to the string at `Npc+0x710` (its length field is at
`+0x710+8 = +0x718`, which has nothing to do with the player's `+0x718` pointer). In plain words:

1. If the NPC has its own name at **`Npc+0x710`** (non-empty), use it. Inferred: this is an override set by the
   server; for most NPCs it's empty.
2. Otherwise take the definition at **`Npc+0x730`**. If **`NpcType+0x140`** is non-zero, the NPC can change form
   (it has transforms), and `FUN_1405f3970` picks the current form.
3. The name is the string at **`NpcType+0x8`**.

For a simple reader, `name = string at (read_ptr(npc + 0x730) + 0x8)` is right for nearly every NPC.

### Step 7.5 — NPC coordinates

`op_npcCoord` (`FUN_1403ae4b0`) has the same structure as `op_playerCoord`, but calls `findNpc` instead of
`findPlayer`. It writes `{World+0x18, Npc+0x3F0, Npc+0x418}`, the same Actor offsets as players. That's how you know
those fields belong to the shared Actor part.

### Step 7.6 — The whole chain

```
world = read_ptr(client + 0xCA80)
list  = read_ptr(world + 0xD0);  n = read_i32(world + 0xD8)
for i in 0 .. n-1:
    index = read_i32(list + 4*i)
    npc   = hashmap_get(world + 0x90, index)   // buckets +0x98, count +0xA0
    type  = read_ptr(npc + 0x730)
    id    = read_u32(type + 0x0)
    name  = string(npc + 0x710) if non-empty else string(type + 0x8)
    x, z  = read_i32(npc + 0x3F0), read_i32(npc + 0x418)
```

---

## Part 8 — Make the decompiler readable with structs

So far you've been reading `*(int *)(lVar4 + 0x3f0)`. Ghidra can show `player->pathX[0]` instead once you tell it
what the object looks like. This is what turns the gibberish into readable code.

### Step 8.1 — The quick way: Auto Create Structure

1. Open `op_playerCoord`.
2. Right-click the variable holding the player (`lVar4`, or `player` if you renamed it) → **Auto Create Structure**.
3. Ghidra builds a struct from the offsets this function uses (`field_0x3f0`, `field_0x418`, …) and retypes the
   variable as a pointer to it.
4. In the Data Type Manager, find the new struct (named like `astruct`), right-click → **Edit…**, rename it `Actor`
   and rename the fields: `field_0x3f0` → `pathX`, `field_0x418` → `pathZ`.

### Step 8.2 — The careful way: build it yourself

1. **Data Type Manager** → right-click `osclient.exe` → **New → Structure…**
2. Name it `Actor`. Set the size to `0x7C0` (big enough for both Player and NPC fields).
3. Click the row at each offset and type the data type in the *DataType* column:

   | Offset | Type | Name |
   |---|---|---|
   | `0x268` | `int` | `fineX` |
   | `0x26C` | `int` | `fineZ` |
   | `0x3F0` | `int[10]` | `pathX` |
   | `0x418` | `int[10]` | `pathZ` |
   | `0x4A0` | `int` | `worldId` |
   | `0x710` | `byte[24]` | `npcName` (NPCs; see Part 6 for players) |
   | `0x718` | `pointer` | `name` (players only; the name pair from Part 6) |
   | `0x730` | `pointer` | `npcType` |

4. Do the same for `World` (`0x18 plane`, `0x24 baseX`, `0x28 baseZ`, `0x60` player map, `0x90` npc map,
   `0xD0 npcIndices`, `0xD8 npcIndexCount`).
5. In the decompiler, click a variable → **Ctrl+L (Retype Variable)** → `Actor *`.

**Before:**

```c
*(undefined4 *)((longlong)param_1 + 4) = *(undefined4 *)(lVar4 + 0x3f0);
*(undefined4 *)(param_1 + 1) = *(undefined4 *)(lVar4 + 0x418);
```

**After** (with `player` typed `Actor *`, `world` typed `World *`, and the output retyped as a 3-int struct):

```c
out->plane = world->plane;
out->x     = player->pathX[0];
out->z     = player->pathZ[0];
```

You can also fix function signatures: right-click a function name → **Edit Function Signature**, e.g.
`Actor * actorGetWorld(Actor * actor)`. Every caller updates automatically.

---

## Part 9 — When Jagex ships a new build

The anchors you used don't change between builds. The numbers do.

| Anchor | Survives updates? |
|---|---|
| Strings: `"npcName"`, `"playerCoord"`, `"Invalid Player ID passed to player_coord."` | Yes |
| The shape: handler reads `g_client`, calls a lookup with `g_client + <world manager>`, writes 3 ints | Yes |
| Constants: `0x800` (player limit), `0x10000` (NPC limit), `0x17` (string flag) | Yes |
| Function addresses (`FUN_…`) | **No, they move every build** |
| `Client` field offsets | Sometimes. In one past update every field after a certain point moved by −0x10 |
| `World` / `Actor` field offsets | Stable so far |

To re-map a new build by hand: repeat Part 2 (string → XREF → handler) and read the handler (Parts 3–7). Expect
about an hour the first time and much less after.

**This is exactly what the updater automates.** Each recipe in `updater/src/native_updater/spec/*.yaml` is one of the
steps above, written down so a program can repeat it:

| Manual step in this guide | Updater recipe |
|---|---|
| Part 3: every handler first reads the same global | `global.client`: `global-load: { in: "ClientOp.*", size: ptr }` |
| Part 4: `playerCoord` writes `world+0x18` to `out[0]` | `World.plane`: `getter: { in: ClientOp.playerCoord, base: { ret: "@ActorWorld" }, load: i32, select: { stored_to: { arg: 0, offset: 0 } } }` |
| Part 4: `playerCoord` writes `player+0x3F0` to `out+4` | `Actor.pathX`: `getter: { in: ClientOp.playerCoord, base: sp_result, load: i32, select: { stored_to: { arg: 0, offset: 4 } } }` |
| Part 5: `playerFindSelf` compares `client+0xCC4C` with 0 | `Client.localPlayerIndex`: `getter: { in: ClientOp.playerFindSelf, base: client, load: i32, select: { compared_with: 0 } }` |

Read a recipe as "in function X, find the load whose base is Y and which is used like Z". When the updater marks a
key `FAILED` or `NEEDS_REVIEW` on a new build, open the named function in Ghidra and look at it the way this guide
does.

The updater can also answer "who uses offset N?" without Ghidra, using the build's instruction database (`program.db`,
written by `export`):

```bash
cd updater
uv run native-updater sql --binary "<path to osclient.exe>" \
  "SELECT printf('0x%x', i.rva), i.text FROM operand o JOIN insn i USING(rva) WHERE o.kind='mem' AND o.disp=0x718"
```

It prints RVAs (e.g. `0xa116c`); add `0x140000000` and press **G** in Ghidra to jump there (`1400a116c` is the
Player constructor from Part 6). In Ghidra itself, **Search → Program Text…** for `0x718]` does the same thing
more slowly.

---

## Part 10 — Checking your work against the running client

Static analysis tells you what the code *does*. Reading memory from the running game tells you if you understood it
correctly. Do this before trusting a new offset.

> Reading the live client's memory may break Jagex's rules. Use an account you can afford to lose.

**Option A: the repo's probe.** Read-only; it checks the exe hash and reads from a process snapshot where possible:

```bash
cd updater
uv run native-updater probe --mapping ../api/src/main/resources/mappings/241.3.json --display-name "<your name>"
```

Run `probe` from Windows: it reads the client's memory through Windows APIs. It prints PASS/FAIL per check
(`localPlayer`, `fineVsPath`, `worldBase`, …). `localPlayerName` compares the name against `--display-name` when you
pass it.

**Option B: a memory viewer** such as ReClass.NET or Cheat Engine, attached read-only. Add the pointer
`"osclient.exe"+E98C18`, then add offsets by hand: `+CC4C` should show your player index; follow `+CA80` to the World
and check `+18` is 0–3. Seeing the numbers change as you walk is the fastest way to build intuition.

Sanity checks that catch most mistakes:

| Check | Expected |
|---|---|
| `Client+0xCC4C` | 0–2047 when logged in, −1 when logged out |
| `World+0x18` (plane) | 0–3 |
| `fineX >> 7` vs `pathX[0]` | equal when standing still |
| `pathX[0]`, `pathZ[0]` at Lumbridge | about 3222, 3218 |
| `World+0x24`, `+0x28` (baseX/Z) | multiples of 8 |
| NPC `type+0x0` | matches the NPC's wiki id |

---

## Appendix A — Everything found in this guide

Build `166231c8f6bd05c7`. Addresses are Ghidra addresses (RVA = address − `0x140000000`).

**Globals and functions**

| What | Address | RVA |
|---|---|---|
| `g_client` (Client pointer) | `DAT_140e98c18` | `0xE98C18` |
| `register_ClientOps` | `FUN_1403a82f0` | `0x3A82F0` |
| `findPlayer(out, index, client+0xC9B8)` | `FUN_140187280` | `0x187280` |
| `findNpc(out, index, client+0xC9B8)` | `FUN_1401878b0` | `0x1878B0` |
| `getNpc(client, out, index)` | `FUN_14009d430` | `0x9D430` |
| `actorGetWorld(actor)` | `FUN_14009f640` | `0x9F640` |
| `npcGetName(npc, out)` | `FUN_14009ffc0` | `0x9FFC0` |
| Actor constructor | `FUN_14009dcd0` | `0x9DCD0` |
| Player constructor | `FUN_1400a1110` | `0xA1110` |
| NPC constructor | `FUN_1400d0970` | `0xD0970` |
| ground height (lead) | `FUN_140097960` | `0x97960` |

**Offsets**

| Object | Offset | Field |
|---|---|---|
| Client | `+0xC9B8` | world manager (its bucket array at `+0xC9D8`, count `+0xC9E0`) |
| Client | `+0xCA80` | main World pointer |
| Client | `+0xCC4C` | local player index (−1 = none) |
| Client | `+0xCCD0` / `+0xCCD4` | player count / int array of player indices |
| World | `+0x18` | plane |
| World | `+0x24` / `+0x28` | world x/z of the loaded area's corner |
| World | `+0x60` (`+0x68` buckets, `+0x70` count) | players hash map |
| World | `+0x90` (`+0x98` buckets, `+0xA0` count) | NPCs hash map |
| World | `+0xD0` / `+0xD8` | int array of NPC indices / count |
| Actor | `+0x268` / `+0x26C` | fine x / fine z (128 per tile) |
| Actor | `+0x3F0` / `+0x418` | `pathX[10]` / `pathZ[10]` (`[0]` = current tile) |
| Actor | `+0x4A0` | world id (0 = main world) |
| Player | `+0x718` | pointer to the name pair `{display, normalised}` (`Player.name`, `PlayerName`) |
| Npc | `+0x710` | own name string (usually empty) |
| Npc | `+0x730` | NpcType pointer |
| NpcType | `+0x0` / `+0x8` / `+0x140` | id / name string / transform data (non-zero = can change form) |
| hash map node | `+0x0` / `+0x8` / `+0x10` / `+0x18` | key / control block / value / next |
| string (24 bytes) | `+0x17` top bit | 1 = heap form `{ptr, len, cap}`; 0 = inline, len = 23 − byte |

Everything else the updater maps (animations, skills, widgets, ground items, the camera) is in `api/src/main/resources/mappings/241.3.json`.
To see where a value came from, open `analysis.json` in the build's cache folder
(`%USERPROFILE%\.kraken-native\cache\<sha256>\`, written each time you run the updater): every key lists its
`evidence`, the instruction address (`insn`) and text it was read from. For practice, pick any key, jump to the listed address with **G**, and work out why that instruction
proves the value.

## Appendix B — Ghidra keys and menus

| Action | How |
|---|---|
| Go to an address or name | **G** |
| Go back / forward | **Alt+←** / **Alt+→** |
| Rename function, label or variable | **L** |
| Retype a variable (decompiler) | **Ctrl+L** |
| Who uses this address? | right-click → **References → Show References to…** (Ctrl+Shift+F) |
| All strings | **Window → Defined Strings** (filter at the bottom) |
| Search the listing text | **Search → Program Text…** |
| Find text in the function shown in the Decompiler | click in the Decompiler, then **Ctrl+F** |
| Open the decompiler | **Window → Decompiler** (Ctrl+E) |
| Add a comment | right-click → **Comments**, or **;** in the Listing |
| Make a struct from a variable's uses | decompiler: right-click variable → **Auto Create Structure** |
| Change a function's signature | decompiler: right-click name → **Edit Function Signature** |
| Program info / SHA-256 | **Help → About osclient.exe** |

## Appendix C — Glossary

| Term | Meaning |
|---|---|
| **Anchor** | Something that survives compilation and leads you to code: a string, a constant, a call pattern |
| **Image base** | The address Ghidra loads the exe at (`0x140000000`) |
| **RVA** | Address minus image base. Add it to the module base at run time |
| **Offset** | Distance in bytes from the start of an object to one of its fields |
| **Struct** | A description of an object's layout: which field is at which offset |
| **XREF** | Cross-reference: a place in the program that uses an address |
| **Handler** | Here: the function that runs when a plugin calls a named script function |
| **vtable** | A table of function pointers stored at offset 0 of a C++ object; it identifies the object's class |
| **Constructor** | The function that sets up a new object; shows its layout and default values |
| **Reference-counted pointer** | A `{control block, object}` pair. The object pointer is the second 8 bytes |
| **Hash map** | Lookup table: `buckets[key % count]` → chain of nodes `{key, ctrl, value, next}` |
| **SSO string** | A string object that stores short text inline (up to 23 chars here) and long text on the heap |
| **Fine coordinates** | Positions in 1/128 of a tile; `>> 7` converts to tiles |
| **Plane** | Floor level, 0–3 |
