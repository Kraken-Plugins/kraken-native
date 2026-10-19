# Kraken Native Updater: automated offset mapper design

Status: **design, for review.** Nothing here is implemented yet.
Companion document: [client-surface.md](client-surface.md), the catalog of everything the updater must map.

## 1. Purpose

Jagex ships a new `osclient.exe` every few weeks. Each build moves:

- **Function and global addresses**, every build. Examples: the Client pointer slot, `playerFindSelf`.
- **Struct field offsets**, sometimes, in blocks. Example: every `Client` field past one point moved by −0x10 between `64d448bb` and `166231c8`.

A RuneLite-style native API needs a correct offset table for the exact build that is running. The updater
takes a client binary and produces that table automatically.

### Goals

- **Coverage:** map every key in client-surface.md for any new build without manual reverse engineering. P0 first, then P1 and P2.
- **Per-key verdicts:** grade every key with a verdict and evidence. Never emit a silent guess.
- **Diff reports:** say what moved, what was re-anchored, and what failed.
- **Build check:** each mapping records the exe's sha256 so the API can confirm it belongs to the loaded binary.
- **Speed and determinism:** analysis is cached per build, and tests run in seconds.

Keep it as lean as possible, we will be writing a lot of code and I need to review it all. I want the bare minimum, not extremely fancy,
not everything has to be fully automated or production ready. It does have to be understandable up to a point so I can carry this forward.

This does need to support everything that would be provided in RuneLites API like widgets, npcs, players, actors, world points, local points, scene information,
game objects, graphics objects, projectiles, events etc... 

### Non-goals

- Building the API, the reader or the injector.
- Anti-cheat or detection evasion of any kind.
- Writing to client memory. The optional live verifier is read-only.
- Committing client binaries to git.

## 2. Why the native client can be mapped automatically

The binary has no symbols and almost no RTTI. It does contain several kinds of anchor that survive
rebuilds. The table ranks them by how stable they are; each mapping key must be reachable through at least
two of them.

| Class | Anchor | Why it is stable | Examples |
|---|---|---|---|
| **A** | **Lua API binding names.** sol2 registrations pair a public API name with a handler. | Jagex's published plugin API (`api_version`). Renaming breaks plugins. | `getNpcIdAll`, `npcCoord`, `osrs.Entity.isInteracting`, `worldToScreenCoord`, `getVarp`, `osrs.IfType.text` |
| **A** | **sol2 type-name strings** from `ctti_get_type_name<…>` (`__FUNCSIG__`) | Generated from C++ type names | `jag::oldscape::LuaNpc`, `LuaCollisionMap` |
| **B** | **Diagnostic strings** | Rarely edited | `"Invalid NPC ID passed to npc_coord."`, `"FATAL - WorldEntity update is bigger than expected"` |
| **C** | **Cache protocol constants** | The cache format is shared with the Java client | config group ids passed to `FUN_140625a50` (6 loc, 9 npc, 10 obj, 12 seq, 13 spotanim, 14 varbit) |
| **C** | **Coordinate and bit-packing constants** | Wire and world format | `<<14`, `>>0x1c`, `&0x3fff`, `>>0x14` loc tag, `0x7fffff` 24-bit sign |
| **D** | **Constructor default signatures** | Defaults come from game semantics | Actor ctor: 14× −1 pose anims, `defaultHeight = 200`, overhead timer `100`, turn speed `32` |
| **E** | **Call-graph topology from already-resolved functions** | Structure survives recompiles | `setSequence` = the caller of the Seq loader that compares the new id with `[actor+X]` |
| **F** | **Cross-build carry-forward** (function matching against the last good build) | Most functions change little between builds | Anything mapped last build |
| **G** | **Delta propagation** (fields shift in blocks) | Observed drift is block-shaped | Predicting `Client.x` from its neighbours' shared −0x10 |
| **H** | **Byte signatures** generated from the last good build | Fast; breaks on register reallocation | Runtime guards |

The Lua API is the main anchor. Its handlers are small getters that load exactly the field the API needs.
The client contains the API code on every build; whether Lua is enabled at runtime (beta worlds only) does
not matter for static analysis. Keys the Lua API does not expose (animations, spot anims, hitsplats,
orientation, widgets internals, inventories) fall back to classes C–G.

## 3. Pipeline

```
           ┌────────────┐   ┌──────────────┐   ┌───────────────────┐
binary ──► │ 1 Acquire  │──►│ 2 Program    │──►│ 3 Fingerprint      │  functions & globals
           │  + identify│   │   Model      │   │   (binding parser, │
           └────────────┘   │ (Ghidra      │   │    loaders, ctors) │
                            │  export,     │   └─────────┬─────────┘
                            │  cached)     │             ▼
                            └──────────────┘   ┌───────────────────┐
  previous build ─────────────────────────────►│ 4 Extract          │  offsets via strategies
  (model + mapping + evidence)                 │   (+ carry-forward,│
                                               │    delta propagate)│
                                               └─────────┬─────────┘
                                                         ▼
           ┌────────────┐   ┌──────────────┐   ┌───────────────────┐
  publish◄─│ 8 Diff &   │◄──│ 7 Emit       │◄──│ 5 Consensus &      │
           │   publish  │   │ mapping JSON,│   │   static verify    │
           └────────────┘   │ analysis     │   └─────────┬─────────┘
                            └──────────────┘             ▼
                                               ┌───────────────────┐
                                               │ 6 Live verify     │ optional, read-only
                                               │   (probe)         │
                                               └───────────────────┘
```

Exit codes follow kraken-updater:

| Code | Meaning |
|---|---|
| 0 | Every P0 key is PROVEN or ANCHORED. |
| 1 | Acquisition failure or crash. |
| 2 | Output was written, but at least one key is FAILED or NEEDS_REVIEW. CI opens a draft PR. |

## 4. Components

### 4.1 Acquire and identify

Ways to get a binary:

- **`LocalInstallWatcher`** (the default) watches `…\Jagex Launcher\Games\Old School RuneScape\Client\osclient.exe`. The Jagex launcher rewrites it in place.
- **`--binary <path>`** for manual runs.
- **A CDN fetcher**, possibly later. The launcher download URL has not been researched (see §10).

`BinaryStore` is a content-addressed archive (`<store>/<sha256>/osclient.exe`) kept outside git, for
example in a private Seaweed bucket. Carry-forward and the regression corpus need old binaries, so every
build is archived the first time it is seen.

`BuildIdentity` records:

- sha256, PE timestamp, `SizeOfImage`, section table;
- the build tag parsed from embedded source paths (`OSRS-ORSM295-BWXF`);
- the client version, from the `"Client version: %d.%d"` call site.

### 4.2 Program Model

**Backend: Ghidra 12.1.x headless, pinned.** It has already been proven on this binary. It provides
function boundaries (including leaf functions and thunks), references, defined strings, and P-code and the
decompiler when needed.

A Ghidra script, runs once per build. It writes a self-contained **SQLite**
file to `<cache>/<sha256>/program.db`. Analysis and export take about 10–15 minutes; the result is cached.
Everything after that is pure Java over SQLite and finishes in seconds.

| Table | Contents |
|---|---|
| `section` | name, rva, sizes, perms |
| `function` | entry, body ranges, is_thunk, thunk_target, `.pdata` cross-check |
| `insn` | rva, length, bytes, mnemonic, function, fallthrough, branch targets |
| `operand` | insn, index, kind (`reg`/`mem`/`imm`/`rel`), reg, base, index, scale, disp, size, resolved RIP target |
| `string` | rva, value, encoding. Includes a raw scan for strings Ghidra did not define. |
| `xref` | from_insn, to_addr, kind (`call`/`jump`/`data_read`/`data_lea`/`data_write`) |
| indexes | by `to_addr`, by `imm`, by `(base_kind, disp)`. This answers "who touches `+0xCC70`", which REA cannot. |

SQLite was chosen because authors can query it ad hoc (`SELECT … WHERE disp = 0x3a8 AND mnemonic LIKE 'MOV%'`),
it is a single cacheable file, and its readable in many languages. A `DeepAnalysis` interface handles rare
decompiler or P-code needs by batching requests into one headless run against the retained Ghidra project.

Other backends, such as a Zydis or iced-x86 decoder plus `.pdata`, could fill the same tables later
without touching the analysis code.

### 4.3 Analysis primitives (`analysis/`)

| Primitive | Responsibility |
|---|---|
| `StringIndex` | Exact and regex lookup of strings to RVAs to referencing instructions |
| `XrefIndex`, `CallGraph` | Callers and callees, through thunks and `std::function` / sol2 wrapper stubs |
| `ImmediateIndex`, `DisplacementIndex` | Sites using a constant or a `[reg+disp]` offset |
| `CallArgs` | Recover argument values at a call site from preceding `MOV`/`LEA` into `RCX`/`RDX`/`R8`/`R9`/stack. Example: `MOV R8D, 0xC; CALL loader`. |
| **`BindingTableParser`** | Parse sol2 registrations into `BindingTable{namespace, name → handlerRva, kind}`. Three shapes are observed: (a) *name → `LEA handler` → register-call*; (b) *`LEA handler` → wrap → name → register-call*; (c) *stateful functor built on the stack → factory call (e.g. `CALL 0x1403C1F10`) → name → register-call*. In shape (c) the handler is found through the factory's callee or vtable. A prototype pairing per register-call window (`map_bindings2.py`) maps 66 ClientOps on the seed build. `playerFindSelf` uses shape (c) and needs the factory follow-through. A fourth shape, (d) *name → call to a setter template made for one `sol::property(lambda)`*, covers the `osrs.IfType` getters: the setter LEAs an index stub no other function references, and the binding behind it calls the lambda, which becomes the handler (kind `property`). Also parses usertype method tables (`osrs.Entity`, `osrs.NpcType`, `osrs.IfType`, …) and enum tables (event name → integer). |
| **`Provenance`** | Forward abstract interpretation over a function's CFG. Lattice: `Const`, `Global(rva)`, `Arg(i)`, `Ret(fn)`, `Deref(v, off, size)`, `Addr(v, off)`, `Typed(tag, v)`, `Unknown`. Seeds come from the spec, for example "`Client*` = `Deref(Global(global.client))`" or "`FUN_npcLookup` returns `Typed(Npc)`". Extractors ask questions like "which `Deref(Typed(Actor), off)` flows into the sol2 result sink?" |
| `Patterns` | A small DSL for instruction sequences with wildcards and captures, used for ctor signatures, block copies and loops |
| `ContainerRecognizers` | Detect EASTL hash_map iteration, list walks, vector loops and `eastl::string` SSO checks, and return their layout. This verifies the container contracts every build. |
| `StrideDetector` | Array and grid strides from `IMUL` / `SHL` / `LEA` index arithmetic, e.g. `(plane*W + x)*H + z`, `*0x10 + 8` |

Provenance tracking, rather than raw byte patterns, keeps recipes stable when the compiler reallocates
registers or reorders code. That is the most common change between builds.

### 4.4 Spec registry (`spec/*.yaml`)

The spec is the contract: one YAML entry per mapping key, like kraken-updater's `PacketType` registry. Most
keys are generic and need no code.

```yaml
- key: Actor.interacting
  struct: Actor
  type: i32
  priority: P0
  strategies:
    - binding-getter: { binding: "osrs.Entity.isInteracting", base: Actor, pick: { load: i32, nth: last } }
    - carry-forward
    - delta-propagation
  invariants:
    - adjacent: { key: Actor.interactingValid, delta: -1 }
    - live: "v == -1 || (v >= 0 && v < 0x10000 + 2048)"

- key: Actor.anim
  struct: Actor
  type: i32
  priority: P0
  strategies:
    - extractor: SetSequenceExtractor  
    - carry-forward
  invariants:
    - live: "v == -1 || seqTypeExists(v)"

- key: global.client
  type: ptr<Client>
  priority: P0
  strategies:
    - binding-global: { bindings: "ClientOp.*", pick: most-common-rip-qword-load }
    - extractor: ClientRootFromDiagnostics   # "Invalid NPC ID passed to npc_coord." function
```

Generic strategy kinds:

- `binding-getter`, `binding-global`, `binding-call-arg`
- `string-anchored` (diagnostic string → function → pick)
- `loader-caller` (config group → loader → callers → pick)
- `ctor-signature`, `pattern`
- `carry-forward`, `delta-propagation`

Custom logic goes in Java `Extractor` classes referenced by name.

### 4.5 Fingerprinters and extractors

These mirror kraken-updater:

- **`Fingerprinter`** locates functions and globals. Interface: `search(session)`, `dependencies()`. It returns `FunctionRef`/`GlobalRef` with a status (`MATCHED`, `NOT_FOUND`, `AMBIGUOUS`), a score and evidence. Examples:
  - `BindingTableFingerprinter`
  - `ConfigLoaderFingerprinter` (group id → loader)
  - `ActorCtorFingerprinter`
  - `NpcUpdateDecoderFingerprinter` (calls the NpcType loader and `setSequence`; reads a mask byte)
  - `ProjectileCtorFingerprinter` (calls the SpotAnim loader; allocates `make_shared` 0x310)
  - `SetSequenceFingerprinter`
  - `WorldToScreenFingerprinter`
- **`Extractor`** turns fingerprints into key values. It declares `requiredFingerprints()`, `requiredKeys()` and `providedKeys()`.
- **Registration and ordering:** classes are registered with Guice `Multibinder` and sorted topologically. Duplicates, missing dependencies and cycles fail fast. An extractor whose prerequisites failed is skipped and its keys are marked `FAILED (blocked by X)`. That makes failures visible, unlike kraken-updater's silent nulls.

### 4.6 Consensus and verdicts

Each strategy returns candidates `{value, strategyClass, evidence[]}`. Candidates are grouped by value and
graded:

| Verdict | Rule |
|---|---|
| **PROVEN** | At least 2 independent strategies (different anchor classes) agree, and all static invariants pass. |
| **ANCHORED** | One class A/B/C strategy, invariants pass, no disagreement. |
| **INFERRED** | Only D–H strategies (e.g. carry-forward or delta propagation), invariants pass. |
| **NEEDS_REVIEW** | Strategies disagree, or an invariant fails. All candidates are listed in the report. |
| **FAILED** | No candidate. |

Only PROVEN, ANCHORED and INFERRED values reach the mapping (§4.9). The API treats a missing key as "feature
unavailable for this build" rather than reading a doubtful value.

**Definitions from cache.** Where possible, config definitions (NpcType, LocType, ObjType, SeqType,
SpotAnimType, VarbitType, enums) are decoded from the local cache. Memory then only needs to supply type
ids. A cache library such as `osrs-wiki-cache-utils` would provide the decoders. This removes dozens of
config-struct offsets from the per-build burden and gives the verifier an oracle.

### 4.7 Carry-forward and delta propagation

These recover keys whose primary anchors broke. Both are always on, so they also cross-check anchored
results.

1. **Function matching.** Previous build (model + accepted mapping + evidence instruction RVAs) to new build:
   - Seed pairs are certain: identical binding names, identical anchored strings, identical config-group loaders.
   - Propagate in the style of BinDiff/Diaphora. Features: masked-byte hash (RIP and disp masked), string-ref set, immediate set, callee and caller pairs already matched, size, CFG shape, and a mnemonic n-gram hash.
   - Output `FunctionMatch{old, new, score}`.
2. **Re-extraction.** For a key whose evidence lived in a matched function, re-run its original rule at the corresponding instruction. Instruction correspondence comes from aligning the two bodies by mnemonic sequence.
3. **Delta propagation.** Within a struct, sort the previous build's fields by offset. Compute deltas for keys already resolved this build. Predict each unresolved key as `old + delta` of its bracketing neighbours, but only when both neighbours agree. The result is `INFERRED` and must pass invariants (live checks for P0). This targets the observed block shifts, such as `Client` −0x10.

When the updater fails, it writes a **repair bundle** for a human or agent: the old function and
instruction, matched candidates in the new build, and the old and new disassembly side by side. It is
formatted for REA's `compare_functions`, `find_changed_behavior` and `procedure_pseudo_code`, so fixing a
broken recipe takes minutes.

### 4.8 Verification

**Static** checks run every time:

- **Type and alignment:** offsets suit the type and fall inside a known struct size. Allocation sizes come from `make_shared` / `operator new` sites, e.g. Projectile 0x2F0, GraphicsObject 0x348, LocType 0x1E8.
- **Relational invariants:**
  - `pathZ = pathX + 0x28`;
  - `World.npcs.buckets = World.npcs + 8`;
  - fields in a struct are unique and do not overlap;
  - `tag >> 20` agrees between wall and game-object paths.
- **Container contracts:** re-detected by the recognizers.

**Cache oracle:**

- loader group ids exist in the cache index;
- enum and event values are consistent with the binding enum table.

**Live probe** (opt-in, read-only) runs against a running, logged-in client whose sha256 matches. It reuses
the safety model of the earlier native work: PID and path check, module base and image size check, file
sha256, a `PssCaptureSnapshot` clone, bounded reads, and no writes. Each check covers a group of keys (the spec's `live:` field names it):

| Check | Keys proven |
|---|---|
| `level(xp[i]) == base[i]` for all 26 skills; levels in 1..99; xp ≤ 200M | skill arrays |
| Local player name decodes as printable text and equals the logged-in display name (operator-supplied) | `Actor.name`, local index, player table |
| `fineX >> 7 == pathX[0]`, `fineZ >> 7 == pathZ[0]` for every actor | fine and tile coords |
| NPC `typeId` exists in the cache, and the cache NPC name equals the in-memory `NpcType.name`; `NpcType.size` equals the cache size | NPC table, `Npc.type`, NpcType fields |
| Hash-map structure: every node key hashes to its bucket; counts match | container contracts |
| Loc `tag >> 20` ids exist in the cache LocType index; game-object origin lies inside the scene | scene keys |
| Varbit values recomputed from the varp array match the in-memory varbit read | varp arrays, varbit decode |
| Camera angles lie in [0, 16384); angles change as the operator rotates the camera (scripted prompt) | camera keys, pitch vs yaw |
| `tickCount` increases by about 1 every 600 ms; `cycle` increases continuously | tick and cycle |
| `worldToScreen(localPlayer)` lands inside the viewport near screen centre | projection keys |
| Projectile `endCycle > cycle` while active; `spotAnimId` exists in the cache | projectile keys |
| Widget tree: root interfaces include known group ids (e.g. inventory 149, chatbox 162); text fields decode | widget keys |

The probe (`native-updater probe`, Python over Windows APIs) prints PASS/FAIL/SKIP per check and exits non-zero
on a failure. It does not modify the mapping.

### 4.9 Output

Each build produces one file in the repo and one in the cache:

- `api/src/main/resources/mappings/<version>.json`, the contract consumed by the API (bundled on its classpath). `<version>` is major.minor of the exe's file version
  resource (`241.3`). The API reads that resource to pick the file and checks the recorded `sha256`, so a mapping is
  never applied to a different binary. It holds values only, for keys with a PROVEN, ANCHORED or INFERRED verdict;
  NEEDS_REVIEW and FAILED keys, and spec keys marked `internal: true`, are left out.
- `~/.kraken-native/cache/<sha256>/analysis.json`, next to the build's `program.db`: verdict, anchor classes,
  evidence and notes per key, fingerprint and binding RVAs, the diff against the previous build and carry-forward
  stats. It is what a reviewer reads, and the next build's carry-forward input. Repair bundles go to `repair/` beside it.

Example of the mappings file (real seed-build values, trimmed):

```json
{
  "version": "241.3",
  "sha256": "166231c8f6bd05c7542b8f0d35eb64b0aad701a87ba6ba41f60c4706de946944",
  "globals":    { "client": "0xe98c18", "cycle": "0xe98c14" },
  "structs": {
    "Client": { "worlds": "0xc9b8", "localPlayerIndex": "0xcc4c", "camera.pos": "0x895c8" },
    "Actor":  { "interacting": "0x3a8", "anim": "0x4d8" }
  },
  "containers": {
    "hash_map": { "buckets": "0x8", "bucketCount": "0x10", "node.key": "0x0", "node.value": "0x10", "node.next": "0x18" },
    "string":   { "size": "0x18", "ssoCapacity": "0x17", "heapFlagByte": "0x17", "heapFlagMask": "0x80" }
  },
  "enums":      { "gameState": { "LOGGED_IN": 30 }, "events": { "ON_GAME_TICK": 9 } },
  "functions":  { "worldToScreen": "0x21eb80", "setSequence": "0x9fe50" }
}
```

## 5. Representative recipes

These are written against the seed build to show that each key class can be extracted mechanically. RVAs
are illustrative; the recipes never hard-code them.

### 5.1 `global.client`, `Client.worlds`, `World.npcs` (binding-global, binding-call-arg)

```
h = bindings["ClientOp.npcCoord"]                     // handler; seed 0x3AE4B0
g = provenance(h).firstLoad(Global(*), qword)         // MOV RAX,[rip+X] → X = global.client
call = provenance(h).calls().first(argCount >= 3)     // npc lookup FUN_1401878b0(out, id, mgr)
Client.worlds = call.arg(2) as Addr(Deref(Global(g)), off) → off    // 0xC9B8
look = call.target                                    // fingerprint: NpcLookup
World.npcs = provenance(look).loads(Typed(World)).pair(bucketsOff, countOff)  // 0x98 / 0xA0 → map at 0x90
invariant: look contains CMP id, 0x10000 (immediate)
```

### 5.2 `Actor.interacting` (binding-getter, sol2 usertype)

```
h = bindings["osrs.Entity.isInteracting"]             // seed FUN_140219f30
a = provenance(h).valueOf(call FUN_resolveEntity).as(Typed(Actor))
sink = provenance(h).resultSinkArg()                  // value pushed to Lua
Actor.interacting      = sink.provenance == Deref(a, off, i32) → off       // 0x3A8
Actor.interactingValid = guarding branch: CMP byte [a+off2], 0 → off2      // 0x3A7
```

### 5.3 `Actor.anim` (loader-caller + topology)

```
seqLoader = configLoaders[12]                         // MOV R8D,0xC; CALL FUN_140625a50 inside it
for f in callers(seqLoader):
    if f has  CMP EDX,[RCX+X] ; JNZ  and  loads [RCX+X-8] (SeqType*) and reads [seq+P] (priority):
        setSequence = f; Actor.anim = X; Actor.animSeqType = X-8   // 0x4D8 / 0x4D0
cross-check: setSequence ∈ callees(NpcUpdateDecoder)   // fingerprint E
```

### 5.4 `Client.projectiles` and `Projectile.*` (binding getter + list walker)

Implemented in `spec/57-projectiles.yaml` and the `projectiles` extractor; offsets are build 241.3's.

```
Client.projectiles = sentinel load in ClientOp.areProjectilesRendered / renderProjectiles     // 0xCC70
Projectile.hidden  = [[node] + list.node.value] + X compared with 0 / written there          // 0x1C
walker = target of a call passing (client + Client.projectiles), found via the imm/disp index
         P = [[Arg0] + list.node.value]; two P fields compared with [global.cycle]           // 0x288, 0x28C
step   = walker's call with P as arg0; the field it compares with an argument is startCycle   // 0x288 (end 0x28C)
started = byte field the step compares with 0 and sets to 1                                    // 0x2C0
endpoint = the step's callee that calls @WorldBounds; its args are labelled by what it does:
         x, z rebased into out[0]/out[2] as (c - base) * 128 + 64; actor decremented into an NPC index
target fields = endpoint call before the started test; source fields = the one after it        // 0x274.., 0x260..
heights = next field loaded after each endpoint call (subtracted from its height)              // 0x280, 0x26C
slope   = field loaded into XMM before the 2π/256 constant (read from .rdata); startDistance = the other  // 0x290, 0x294
fineX/Y/Z = last double field loaded before each store to the step's out[0]/[1]/[2]            // 0x2A0, 0x2A8, 0x2B0
yaw = stored angle the walker reads (hands to the renderer); pitch = the other                // 0x2B8, 0x2BC
spotAnimId = arg the SpotAnim loader gets from its caller that clears the started flag (the ctor)  // 0x298
```

### 5.5 Scene grid (string-anchored + stride detection)

```
h = bindings["ClientOp.locFind"] → tileLookup (callee with 6 args)        // FUN_1406a2cb0
stride = StrideDetector(tileLookup): idx = (plane*[S+a] + x)*[S+b] + z; entry = base[S+c] + idx*0x10 + 8
Scene.sizeX=a, Scene.sizeZ=b, Scene.tiles=c (0x948/0x94C/0x968); plane==-1 branch → Scene.bridgeTiles
Tile slots: loads [T+d] whose tag (>>0x14) is compared, return code 0x100|layer → layer→offset map
```

### 5.6 `Actor.poseAnims`, `Actor.defaultHeight` (ctor signature)

```
actorCtor = function storing ≥7 consecutive qword -1 at [RBX+o..o+0x30] and dword 0xC8 nearby
Actor.poseAnims = o (count 14)        Actor.defaultHeight = offset of the 0xC8 store
cross-check: offsets written by NpcUpdateDecoder include [o..o+0x38]
```

## 7. Testing

- **Golden corpus.** Hash-addressed binaries in `BinaryStore` plus accepted mappings. Seed it with `166231c8` (today's findings) and the two older builds, if their binaries can be recovered (the old Ghidra project may contain one).
- **Golden tests.** For every corpus build, run the full pipeline against the cached `program.db` and assert each key equals the accepted value. Tests skip via `Assume` when the corpus is absent, matching kraken-updater's jar tests.
- **Unit tests** run `Provenance`, `BindingTableParser`, `StrideDetector` and the recognizers on small instruction fixtures stored as SQL dumps of real functions, so they need neither Ghidra nor the full binary.
- **Carry-forward tests:** run build N → N+1 with anchor strategies disabled, and assert that carry-forward plus delta propagation reproduce the accepted N+1 mapping.
- **Spec lint:** every key has at least 2 strategies of different classes, and every P0 key has a live check.

## 8. Authoring workflow

1. Add a YAML spec entry with a generic strategy where possible.
2. If a custom extractor is needed, investigate with REA/Ghidra on the cached project, then encode the rule in terms of `Provenance` and `Patterns`, not raw addresses.
3. Run `updater run --binary <corpus build>` and inspect the report. Add a golden expectation.
4. On a future failure, start from the generated repair bundle: old evidence, new candidates, and REA diff commands.

## 9. Milestones

| M | Scope | Exit criterion |
|---|---|---|
| **M0** | Seed. `ExportProgramModel` + SQLite model; import seed findings as golden mapping for `166231c8`; start `BinaryStore`. | Program model for the seed build; golden file reviewed. |
| **M1** | Primitives, binding parser, P0 keys reachable from bindings (client root, worlds, NPC/player tables, actor coords/name/type/interacting/headbars, scene/locs, ground items, skills, tick, game state, camera, varps/varbits, worldToScreen), static verification, schema v2, report. | Seed build: all those keys PROVEN or ANCHORED. |
| **M2** | Live probe. Settle conflicts (`topLevelWorld`, pitch vs yaw). Close the P0 unknowns: inventories, widgets, collision, tile heights, orientation, player index and combat level, run energy. | All P0 keys LIVE_VERIFIED on the seed build. |
| **M3** | Carry-forward, delta propagation, guards, CI and publishing, repair bundles. | Next Jagex build mapped with exit 0, or exit 2 with only P1/P2 gaps. |
| **M4** | P1: projectiles, graphics objects, hitsplats, spot anims, appearance, chat, world list, varcs, WorldEntity. P2: event raise sites and action targets. | Capability coverage matches client-surface.md. |

## 10. Risks and open questions

| Risk | Mitigation |
|---|---|
| Jagex strips or renames Lua API strings (class A anchors) | Two or more strategies per key; carry-forward does not need strings; watch `api_version` |
| More inlining or LTO changes a getter's shape | Provenance-based rules; sink-based picks; carry-forward through instruction alignment |
| EASTL or container layout changes | Container contracts re-derived and verified per build; the API traverses via `containers` |
| Ghidra non-determinism or version drift | Pin the Ghidra version in the spec hash; cache the export; golden tests catch drift |
| The live probe needs a logged-in account and reads game memory | Opt-in only, read-only, operator-run; not in CI |
| No archived old binaries yet | Start archiving now. Carry-forward starts from the first archived build. |
| Static-only seed values may be wrong | M2 live verification before any API depends on P0 |

**Decisions**

1. **Updater language.** Language choice is up to you. Choose what makes the most sense
2. **Analysis backend.** Ghidra export to SQLite (if this needs to be a manual process that's okay for now) If this creates a ton of code and complexity manual is okay for the short term. 
3. **Definitions source.** Decode the cache
5. **Runtime model** for the future API: external reader or in-process. This only affects how P2 (events, actions) is prioritized. Hasn't been decided yet.
