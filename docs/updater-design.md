# Kraken Native Updater: automated offset mapper design

Status: implemented in `updater/`. [updater/README.md](../updater/README.md) is the usage guide; this document
describes the design and what each stage does.

## 1. Purpose

Jagex ships a new `osclient.exe` every few weeks. Each build moves:

- **Function and global addresses**, every build. Example: the Client pointer slot (`global.client`).
- **Struct field offsets**, sometimes, in blocks.

The native API needs a correct offset table for the exact build that is running. The updater takes a client
binary and produces that table automatically.

### Goals

- **Coverage:** map every key in the spec (`updater/src/native_updater/spec/`) for a new build, P0 first, then P1 and P2.
- **Per-key verdicts:** grade every key with a verdict and evidence. Never emit a silent guess.
- **Diff reports:** say what moved, what was re-anchored, and what failed.
- **Build check:** each mapping records the exe's sha256 so the API can confirm it belongs to the loaded binary.
- **Speed and determinism:** analysis is cached per build, and tests run in seconds.

Keep it lean enough to read and carry forward. It does not have to be fully automated or production-ready.

The keys cover what the API reads: widgets, NPCs, players, actors, world points, local points, scene data,
game objects, ground items, projectiles, events and the minimenu. Graphics objects are not covered yet.

### Non-goals

- Building the API, the reader or the injector.
- Anti-cheat or detection evasion of any kind.
- Writing to client memory. The live probe is read-only.
- Committing client binaries to git.

## 2. Why the native client can be mapped automatically

The binary has no symbols and almost no RTTI. It does contain several kinds of anchor that survive rebuilds. The
table ranks them by how stable they are. Each key should be reachable through at least two of them, and a key
reaches PROVEN only when two anchor classes agree (§4.6).

| Class | Anchor | Why it is stable | Examples |
|---|---|---|---|
| **A** | **Lua API binding names.** sol2 registrations pair a public API name with a handler. | Jagex's published plugin API. Renaming breaks plugins. | `ClientOp.npcCoord`, `osrs.Entity.isInteracting`, `osrs.IfType.text` |
| **B** | **Diagnostic strings** | Rarely edited | `"Invalid NPC ID passed to npc_coord."` (anchors `global.client`) |
| **C** | **Cache protocol constants** | The cache format is shared with the Java client | config group ids passed to the loaders (`ConfigLoader.6`, `.9`, `.10`, `.12`, `.13`, `.14`, `.16`) |
| **C** | **Coordinate and bit-packing constants** | Wire and world format | the `0x10000` NPC id limit, `0x800` player limit, `0x80` tile fraction |
| **D** | **Constructor default signatures** | Defaults come from game semantics | Actor ctor: a run of -1 pose animation slots, `defaultHeight = 200`, overhead timer 100 |
| **E** | **Call-graph topology from already-resolved functions** | Structure survives recompiles | `NpcUpdateDecoder` is the caller of `setSequence` that also calls the NPC type loader |
| **F** | **Cross-build carry-forward** (function matching against the last good build) | Most functions change little between builds | Anything mapped last build (§4.7) |
| **G** | **Delta propagation** (fields shift in blocks) | Observed drift is block-shaped | A field between two neighbours that moved by the same amount (§4.7) |

The Lua API is the main anchor. Its handlers are small getters that load exactly the field the API needs. The
client contains the API code on every build; whether Lua is enabled at runtime (beta worlds only) does not matter
for static analysis. Keys the Lua API does not expose (animations, spot anims, hitsplats, orientation, widget
internals, inventories) fall back to classes C–G.

## 3. Pipeline

```
osclient.exe ─► identify (pe.py, identity.py) ─► archive to <root>/store/<sha256>/
             ─► Ghidra export (ghidra.py, ghidra/ExportProgramModel.java) ─► program.db (model.py)
             ─► bindings and fingerprints (analysis/bindings.py, fingerprints.py)
             ─► strategies, in up to 5 rounds (strategies.py, extractors.py, session.py)
             ─► with --previous: carry-forward and delta propagation (carry.py)
             ─► consensus (consensus.py) ─► static verification (verify.py)
             ─► emit (emit.py): api/.../mappings/<version>.json, cache/<sha256>/analysis.json, run summary, diff

native-updater probe (probe.py): read-only live check of a mapping against the running client, run separately
```

`run` exits with:

| Code | Meaning |
|---|---|
| 0 | No key is FAILED or NEEDS_REVIEW, and every P0 key is PROVEN or ANCHORED. |
| 1 | Acquisition failure, a missing Ghidra export when `GHIDRA_INSTALL_DIR` is not set, or a crash. |
| 2 | The mapping was written, but some key was left out (FAILED or NEEDS_REVIEW), or a P0 key is only INFERRED. |

## 4. Components

### 4.1 Acquire and identify

- `--binary` defaults to the Jagex Launcher install, `C:\Program Files (x86)\Jagex Launcher\Games\Old School RuneScape\Client\osclient.exe`.
  A missing file is reported and the run exits 1.
- `BinaryStore` (`identity.py`) archives each build the first time it is seen at `<root>/store/<sha256>/osclient.exe`,
  outside git. The root is `--cache`, else `KRAKEN_NATIVE_HOME`, else `.kraken-native` in the Windows profile.
- `identify` returns a `BuildIdentity` with the sha256, the version (major.minor of the exe's file version
  resource, which names the mapping file, e.g. `241.3`) and the PE link time. A binary without a version resource
  is rejected.

### 4.2 Program model

`ghidra.py` runs Ghidra 12.1 headless (`analyzeHeadless`) with `ghidra/ExportProgramModel.java`. The script writes
`meta.tsv`, `functions.tsv.gz` and `insns.tsv.gz` to `<root>/cache/<sha256>/export/`. The Ghidra project lives in
`<root>/cache/<sha256>/ghidra/`. `ghidra.PINNED_GHIDRA` records the version, but nothing checks the install against it.

`model.build` then creates `<root>/cache/<sha256>/program.db` from the export plus the PE file, which supplies the
sections, `.pdata` and strings. Strings are the printable runs in the PE's non-code sections, plus C strings at
`lea`/`read` targets that the first pass missed.

| Table | Contents |
|---|---|
| `meta` | export metadata (image base, Ghidra version) |
| `section` | name, rva, sizes, file offsets, perms |
| `function` | entry, name, end, size, is_thunk, thunk_target, `.pdata` flag, body ranges |
| `insn` | rva, function, length, bytes, mnemonic, text, branch target, fallthrough, flows |
| `operand` | memory and immediate operands: kind, base, index, scale, displacement, size, immediate |
| `string` | rva, value |
| `xref` | src, dst, kind (`call`, `jump`, `read`, `write`, `lea`), function |

Indexes cover displacements, immediates, xref targets and string values, so questions such as "who touches
`+0xCC70`" are one SQL query (`native-updater sql`).

Once `program.db` exists, the export is not repeated. The export runs only when `GHIDRA_INSTALL_DIR` is set, and
otherwise `run` prints the `analyzeHeadless` command.

### 4.3 Analysis primitives

| Module | Responsibility |
|---|---|
| `model.py` (`ProgramModel`) | Lookups over the program model: functions, instructions, callers and callees, xrefs, string references, displacement and immediate sites |
| `analysis/bindings.py` | `BindingTableParser` pairs sol2 registration names with handlers (shapes: name then handler, handler then name, factory, member offset, property getter) by maximum matching. Also parses enum tables (`osrs.Events` and friends) from `.rdata`. |
| `analysis/provenance.py` | `Provenance`: forward abstract interpretation over one function. Values are `Const`, `Global`, `Arg`, `StackAddr`, `Ret`, `Out`, `Deref`, `Add`, `Mul`, `Bin`, `Many`, `Unknown`. Recipes ask structural questions of these facts (which load of 4 bytes has the Actor pointer as its base), so they survive register reallocation and reordering. |
| `roles.py` | Names for the pointer a load is based on: `client`, `arg0`..`arg5`, `sp_result`, `node_value`, `sol_self`, `{ret: @F}`, `{field: …}` |
| `strategies.py` | The `getter` selectors and the EASTL `hash_map` recognizer |
| `events.py`, `hooks.py`, `minimenu.py` | Event raisers, hook targets (ground items, players) and the minimenu, each found from the functions and fields they touch |
| `x86.py` | Ghidra instruction text to structured operands |
| `pe.py` | PE32+ reader: headers, sections, `.pdata`, version resource, strings, reads by RVA |

### 4.4 Spec registry (`spec/*.yaml`)

The spec is the contract: one YAML entry per mapping key. `00-tables.yaml` holds the binding table anchors,
member tables and shared fingerprints. The other files hold keys by area. A key looks like this:

```yaml
- key: Actor.interacting
  type: i32
  priority: P0
  live: interacting
  strategies:
    - getter: { in: osrs.Entity.isInteracting, base: sp_result, load: i32, select: { returned: true } }
  invariants:
    - adjacent: { key: Actor.interactingType, delta: -1 }
```

Fields: `key` (`Struct.field`, `global.x`, `functions.x`, `enums.x`, `container.x.y`, `events.NAME`), `type`,
`priority` (P0, P1, P2), `strategies`, `invariants`, `live` (the probe check that covers it; required for P0),
`doc`, `alias` (may share an offset with a sibling field) and `internal` (resolved and verified, not written to the
mapping).

Strategy kinds (`strategies.py`, `extractors.py`): `getter`, `global-load`, `hash-map`, `const`, `member`, `binding`,
`fingerprint`, `enum-table`, `derive` and `extractor`. Carry-forward and delta propagation are not written in the
spec; they run for every key when `--previous` is given.

`lint` checks duplicate keys, priorities, strategy kinds, that each key has an anchor strategy (classes A–E), that
P0 keys have a live check, and that `@fingerprint` and `fn:` references exist.

Authoring guidance is in `updater/README.md` ("Writing a recipe").

### 4.5 Fingerprints and extractors

- **Fingerprints** locate functions that are not Lua handlers. They are rules in the spec's `fingerprints:` section,
  resolved by `fingerprints.py`: `callee_of`, `caller_of`, `common_callee_of`, `binding` and `extractor`, narrowed by
  filters such as `imm`, `string`, `calls`, `refs_global`, `min_args`, `arg0`, `min_size`/`max_size` and `unique`.
  A status is MATCHED, NOT_FOUND, AMBIGUOUS or BLOCKED. Specs refer to them as `@Name`.
- **Extractors** are Python recipes in `extractors.py` (and `events.py`, `hooks.py`, `minimenu.py`). Each returns
  `{field: (value, evidence, note)}` over provenance facts. Keys pick a field with `extractor: {name, field}`.
- **Ordering:** a rule whose reference is not yet resolved raises `Blocked`. `pipeline.py` repeats the rounds (up to
  5) until nothing changes. A key whose dependency never resolves is FAILED with the reason "blocked by X".
  A strategy that raises is recorded in the run notes and its key is FAILED.

### 4.6 Consensus and verdicts

Each strategy returns candidates `{value, strategy, class, evidence}`. Candidates are grouped by value. The value
backed by the most distinct anchor classes wins, ties going to the value with more candidates. Any second value
makes the key NEEDS_REVIEW.

| Verdict | Rule |
|---|---|
| **PROVEN** | Two or more anchor classes agree on the value, and no invariant or uniqueness check fails. |
| **ANCHORED** | One class A, B or C supports the value, and no invariant or uniqueness check fails. |
| **INFERRED** | Only classes D–G support the value, and no invariant or uniqueness check fails. |
| **NEEDS_REVIEW** | Candidates disagree, or an invariant or uniqueness check fails. All candidates are kept in `analysis.json`. |
| **FAILED** | No candidate. The reason is "no candidate", or "blocked by X" when a dependency is missing. |

Only PROVEN, ANCHORED and INFERRED values reach the mapping (§4.9). The API treats a missing key as "feature
unavailable for this build" rather than reading a doubtful value.

### 4.7 Carry-forward and delta propagation

These run only with `--previous <old>.json`. They need the previous build's `program.db` and `analysis.json` in the
cache, and its binary in the store. If the program model is missing, only delta propagation runs. Carry-forward
candidates count as class F and delta candidates as class G, and they cross-check the anchored results: a
disagreement makes the key NEEDS_REVIEW.

1. **Function matching** (`carry.match_functions`). Seeds are same-named Lua bindings and fingerprints, and
   functions that reference the same string (12 characters or more) once in each build. Matches spread to the callees of matched
   callers by aligning their call sequences with `difflib`, for up to four passes.
2. **Re-extraction** (`carry.reextract`). For a key whose previous evidence is in a matched function, take the
   corresponding instruction. The bodies must align (mnemonic similarity of at least 0.6), the instruction must still
   encode the previous value, and the result is a class F candidate.
3. **Delta propagation** (`carry.apply`). Within a struct, an unresolved field whose nearest resolved neighbours in
   the previous mapping both moved by the same delta is predicted as `old + delta`. The result is class G.

When a key stays FAILED or NEEDS_REVIEW and has previous evidence, `carry.repair_bundle` writes
`<root>/cache/<sha256>/repair/<key>.md`. It holds the previous value and instruction, the matched new function, the
previous disassembly around the evidence and the candidate disassembly, and points at REA's `compare_functions`
for the fix.

### 4.8 Verification

**Static** (`verify.check_all`), run on every build. A failed check makes the key NEEDS_REVIEW.

- **Type and alignment:** offsets are aligned to their type's size and fall in a plausible range; pointers are 8-aligned.
- **Invariants:** `equals` (an expression over other keys, e.g. `Actor.pathZ` = `Actor.pathX + 0x28`), `adjacent`
  (another key sits `delta` bytes before this one), `range`, `align`, `less_than`, `member_of` and
  `first_fn_equals` (the first hook site of an `events.*` key).
- **Uniqueness:** fields of one struct do not share an offset, unless the field is marked `alias`.

**Live probe** (`native-updater probe --mapping <json> [--display-name <name>]`, Windows only, opt-in). It is
read-only and checks a running, logged-in client whose process is `osclient.exe` and whose file sha256 matches the
mapping. Reads go through a `PssCaptureSnapshot` clone when available, with a bounded `ReadProcessMemory` fallback.
It prints PASS, FAIL or SKIP for each check, named after the spec's `live:` field: `clientRoot`, `gameState`,
`tick`, `cycle`, `skills`, `varps`, `worlds`, `worldBase`, `players`, `localPlayer`, `localPlayerName`, `npcs`,
`hashMap`, `fineVsPath`, `interacting`, `anim`, `actorWorld`, `headbars`, `projectiles`, `npcType`, `scene`,
`groundItems`, `camera`, `viewport`, `inventories`, `widgets` and `minimenu`. Checks that need the cache decoders or
in-process calls are SKIP. It exits 2 if any check fails and never modifies the mapping.

### 4.9 Output

Each build produces one file in the repo and one in the cache:

- `api/src/main/resources/mappings/<version>.json`, the contract the API reads (bundled on its classpath). The API
  reads the exe's version resource to pick the file and checks the recorded `sha256`, so a mapping is never applied
  to a different binary. It holds values only, for keys with a PROVEN, ANCHORED or INFERRED verdict. NEEDS_REVIEW and
  FAILED keys, and keys marked `internal: true`, are left out.
- `<root>/cache/<sha256>/analysis.json`, next to `program.db`: the verdict, anchor classes, evidence and notes per
  key, fingerprint and Lua binding RVAs, the diff against the previous build and the carry-forward stats. A reviewer
  reads it when a value looks wrong, and the next `--previous` run reads it for carry-forward. Repair bundles go in
  `repair/` beside it.

Example mapping, with values from the 241.3 golden corpus (trimmed):

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
    "string":   { "size": "0x18", "heapFlagByte": "0x17", "heapFlagMask": "0x80" }
  },
  "enums":      { "gameState": { "LOGGED_IN": 30 } },
  "functions":  { "worldToScreen": "0x21eb80", "setSequence": "0x9fe50", "dispatchEvent": "0xc44d0" },
  "events":     {
    "ON_GAME_TICK": [ { "fn": "0xc44d0", "idArg": 0, "args": 1 } ],
    "ON_LOC_LOAD":  [ { "fn": "0xc7b20", "id": 40, "args": 4 } ]
  }
}
```

`events` is the hook contract for the client module's injected DLL: per native event, the functions the client calls
to raise it (`fn`), how many integer register arguments each takes (`args`) and where the event id is (argument
`idArg`, or the fixed `id`). The recipe (`events.py`) seeds on the server-tick raise and classifies every wrapper
that tests the Lua plugin gate. `docs/event-hooks-design.md` covers what the hooks feed.

## 5. Testing

- **Unit tests** (`updater/tests/test_units.py`) cover x86 parsing, provenance, getter selectors, recipes, event
  raisers, hook targets, the minimenu, the binding parser, spec lint, file versions, mapping shape and diff, `verify`
  and Windows path handling. They run on small fixtures from `tests/fake_model.py`, with no Ghidra and no binary.
- **Golden tests** (`updater/tests/test_golden.py`) run the pipeline against the cached `program.db` and binary of each
  `updater/corpus/<version>.golden.json` build. They check the accepted keys, fingerprints and bindings, and that the
  mapping holds exactly the trusted, non-internal values. They skip (`skipTest`) when the build is not in the local
  cache. `test_carry_forward_reproduces_seed` runs a build against itself with anchors disabled and requires
  carry-forward to recover most trusted values.
- Run them from `updater/`: `uv run python -m unittest discover -s tests -t .`.

## 6. Authoring workflow

1. Add a spec entry with a generic strategy where possible.
2. If a custom extractor is needed, look at the facts with `bindings`, `sql`, or `Provenance(model).facts(fn)`
   in `uv run python`. Encode the rule in terms of provenance and roles, not raw addresses.
3. Run `uv run native-updater lint`, then `uv run native-updater run --binary <build>`. Read the run summary and
   `analysis.json`. Update the corpus golden file when a change legitimately moves a value.
4. For a later failure, start from the repair bundle: old evidence, new candidates and the REA commands it lists.

## 7. Risks and open questions

| Risk | Mitigation |
|---|---|
| Jagex strips or renames Lua API strings (class A and B anchors) | Two or more strategies per key; carry-forward does not need strings |
| More inlining or LTO changes a getter's shape | Provenance-based rules; sink-based selectors; carry-forward through instruction alignment |
| Ghidra version drift | `ghidra.PINNED_GHIDRA` is informational only; golden tests catch drift |
| The live probe needs a logged-in account and reads game memory | Opt-in, read-only, operator-run, not in CI |
| Static-only values may be wrong | Run the live probe before the API depends on a P0 value |
| Carry-forward needs the previous build's model and analysis | Both are kept in the cache; without them only delta propagation runs |

Open items:

- The probe has not been run against a logged-in client yet.
- Automatic download of the client binary is not built. The default is the local Jagex Launcher install, and
  `--binary` takes any path.
- Config definitions are not decoded from the cache. The updater maps the config archive's loaders and types by
  group id; the API reads them from memory.
- Graphics objects and the combat level are not mapped. See `updater/README.md` for the rest of the known gaps.
