# Kraken native updater

Maps struct offsets, globals and functions of a new `osclient.exe` build and writes the contract the native API
consumes (`api/src/main/resources/mappings/<version>.json`, e.g. `241.3.json`). Implements [docs/updater-design.md](../docs/updater-design.md).

Python 3.10+, PyYAML, SQLite (stdlib). Ghidra 12.1 + JDK 21 only for the one-off export per build.

## Setup

Dependencies are managed with [uv](https://docs.astral.sh/uv/). `pyproject.toml` declares them, `uv.lock` pins the
exact versions, and `.python-version` picks the interpreter (3.12; uv downloads it if it isn't installed):

```bash
cd updater
uv sync                        # creates .venv and installs PyYAML + this package (editable)
uv run native-updater --help   # any command below; uv run keeps .venv in sync first
```

`uv add <pkg>` adds a dependency and updates the lock file. Commit `uv.lock`; `.venv/` is ignored. Inside an activated
venv (`.venv\Scripts\activate`), `native-updater ...` and `python -m native_updater ...` work without `uv run`.

### From WSL

The updater also runs from WSL against the Windows install. `C:\...` paths (the default binary, `--binary`, `--out`, …)
are translated to `/mnt/c/...`, and the cache defaults to your Windows profile's `.kraken-native` (found through
`%USERPROFILE%`, so the WSL and Windows user names don't have to match). WSL and Windows therefore share one Ghidra
export.

A venv holds one platform's Python, so WSL needs its own folder or it will replace the Windows `.venv`. Add this to
`~/.zshrc` (or `~/.bashrc`) once:

```bash
# uv projects on the Windows drive: keep WSL's venv (.venv-linux) apart from Windows' .venv
uv() { if [[ $PWD == /mnt/* ]]; then UV_PROJECT_ENVIRONMENT=.venv-linux command uv "$@"; else command uv "$@"; fi }
```

Run `probe` from Windows: it reads the client's memory through Windows APIs. Run a new build's Ghidra `export` from
Windows too; WSL then reuses it from the shared cache.

## Flow

```
osclient.exe ──(Ghidra headless, 10-15 min, once per build)──► export/*.tsv.gz ──► program.db (SQLite)
program.db ──► bindings + fingerprints ──► strategies ──► consensus + invariants ──► mappings/<version>.json + analysis.json
```

```bash
cd updater
uv run native-updater lint                                        # check the spec
uv run native-updater export --binary <osclient.exe> --ghidra <GHIDRA_INSTALL_DIR> --java-home <JDK21>
uv run native-updater run --binary <osclient.exe>                 # writes ../api/src/main/resources/mappings/<version>.json
uv run native-updater run --binary <new.exe> --previous ../api/src/main/resources/mappings/<old>.json   # + carry-forward, diff, repair bundles
uv run native-updater bindings --binary <osclient.exe> --table osrs.IfType       # dump a Lua binding table
uv run native-updater sql --binary <osclient.exe> "SELECT i.rva, i.text FROM operand o JOIN insn i USING(rva) WHERE o.kind='mem' AND o.disp=0xcc70"
uv run native-updater probe --mapping ../api/src/main/resources/mappings/<version>.json --display-name <name>   # read-only live check
uv run python -m unittest discover -s tests -t .                  # unit + golden tests (golden skip without the cached model)
```

Exit codes: `0` every P0 key PROVEN/ANCHORED, `1` acquisition failure or crash, `2` mapping written but some key is
FAILED/NEEDS_REVIEW (and so left out of it) or a P0 key is only INFERRED. The run prints verdict counts, every key
left out and why, and with `--previous` the values that moved.

The Ghidra export is manual-or-automatic: with `GHIDRA_INSTALL_DIR` (and `JAVA_HOME` pointing at JDK 21) set,
`run` performs it; otherwise it prints the exact `analyzeHeadless` command. Binaries and models live outside git in
`~/.kraken-native/{store,cache}/<sha256>/` (override with `KRAKEN_NATIVE_HOME` or `--cache`).

## Output

`mappings/<version>.json` is the only file written to the repo. It goes to `api/src/main/resources/mappings/` by default (`--out`
changes it), because the API ships its mappings as classpath resources. The version is the major.minor of the exe's
file version resource (`241.3`); the Java API reads the same resource to pick the file, then checks `sha256` so a mapping is
never applied to a different binary. It holds values only, keyed like the spec:

```json
{
  "version": "241.3",
  "sha256": "166231c8…",
  "globals":    { "client": "0xe98c18" },
  "structs":    { "Actor": { "pathX": "0x3f0" }, "Client": { "camera.pos": "0x895c8" } },
  "containers": { "hash_map": { "node.value": "0x10" } },
  "enums":      { "gameState": { "LOGGED_IN": 30 } },
  "functions":  { "worldToScreen": "0x21eb80" },
  "events":     { "ON_NPC_LOAD": [ { "fn": "0xc7930", "idArg": 0, "args": 3 } ] }
}
```

`events` lists, per native event id (`enums.events`), the functions the client calls to raise it: hook `fn`,
forward `args` integer register arguments, and take the event id from argument `idArg` (0 = RCX) or as the fixed
`id`. These feed the client module's injected hook DLL (`docs/event-hooks-design.md`).

Only PROVEN, ANCHORED and INFERRED keys are written; NEEDS_REVIEW and FAILED keys are left out, so the API fails
loudly on a missing key rather than reading a doubtful one. Keys marked `internal: true` in the spec (inputs to other
keys, such as `Actor.nameSsoFlag`) are resolved and verified but not written either.

Everything behind the values goes to `~/.kraken-native/cache/<sha256>/analysis.json`, next to the build's
`program.db`: verdict, anchor classes, evidence instructions and notes per key, fingerprint and Lua binding RVAs, the
diff and carry-forward stats. That's the file to read when a key looks wrong, and the next build's `--previous` run
reads it for carry-forward. Repair bundles (`repair/<key>.md`) go in the same folder.

## Layout

| Path | What |
|---|---|
| `pyproject.toml`, `uv.lock`, `.python-version` | Project metadata, dependencies and the `native-updater` command; pinned versions; interpreter (see Setup) |
| `ghidra/ExportProgramModel.java` | Ghidra post-script: functions, instructions (bytes, text, flows) → TSV |
| `src/native_updater/model.py` | TSV → `program.db`; read-only query API (functions, insns, xrefs, strings, disp/imm indexes) |
| `src/native_updater/pe.py`, `x86.py` | PE32+ reader (sections, version resource); Ghidra instruction text → structured operands |
| `src/native_updater/analysis/provenance.py` | Forward abstract interpretation per function: what each register/stack slot holds (`[[client]+0xC9B8]`, `Arg0`, `Ret(fn)`, …). Leaf getters are inlined, tail jumps are calls, registers survive calls (LTCG build). |
| `src/native_updater/analysis/bindings.py` | sol2 registration parser (name/handler/member-offset pairing by maximum matching) and `.rdata` enum tables |
| `src/native_updater/roles.py` | Names for pointer bases used by recipes: `client`, `sp_result`, `node_value`, `{field: …}` |
| `src/native_updater/strategies.py` | Generic strategies: `getter`, `global-load`, `hash-map`, `const`, `member`, `binding`, `fingerprint`, `enum-table`, `derive`, `extractor` |
| `src/native_updater/fingerprints.py` | Function locators: `callee_of`, `caller_of`, `common_callee_of`, `binding`, `extractor` |
| `src/native_updater/extractors.py` | Custom recipes over provenance facts: config loaders, setSequence, Actor ctor, scene grid, obj piles, containers, projectiles, the cycle and game-state hook targets |
| `src/native_updater/events.py` | The native event raisers: seeds on the server-tick dispatch, then classifies every wrapper that tests the plugin gate into `events.<NAME>` hook sites |
| `src/native_updater/hooks.py` | Hook targets without a Lua event: the ground item add/delete/count handlers, player add/remove and the player info decoder, from the fields they write |
| `src/native_updater/spec/*.yaml` | The contract: tables, fingerprints, one entry per key (strategies, invariants, live check) |
| `src/native_updater/consensus.py`, `verify.py` | Verdicts (PROVEN/ANCHORED/INFERRED/NEEDS_REVIEW/FAILED); type, relational and uniqueness checks |
| `src/native_updater/carry.py` | Carry-forward (function matching + re-extraction), delta propagation, repair bundles |
| `src/native_updater/identity.py` | Build identity (client version, sha256), the binary store, the cache root |
| `src/native_updater/emit.py` | `mappings/<version>.json`, `analysis.json`, the run summary and the diff |
| `src/native_updater/probe.py` | Read-only live checks of a mapping against the running client (PASS/FAIL per `live:` check) |
| `corpus/<version>.golden.json` | Accepted values per client version for the golden tests |

## Writing a recipe

```yaml
- key: Actor.interacting
  type: i32
  priority: P0
  live: interacting
  strategies:
    - getter: { in: osrs.Entity.isInteracting, base: sp_result, load: i32, select: { returned: true } }
  invariants:
    - adjacent: { key: Actor.interactingValid, delta: -1 }
```

`in` names the function: a Lua binding (`Table.name`), a fingerprint (`@Name`), a string anchor
(`{string: "..."}`) or a function key (`fn:functions.x`). `base` is the pointer role the load hangs off, `load` the
access size, `select` narrows by how the value is used (stored to the out-struct, returned, passed to a call, compared,
used as an array base, …). Run `bindings`/`sql`, or print `Provenance(model).facts(fn)` in a REPL (`uv run python`),
to see the facts a recipe can match. Never name a YAML key `off` (YAML reads it as `false`); use `offset`.

Anchor classes: A Lua names/types, B diagnostic strings, C cache constants, D ctor defaults, E call-graph topology,
F carry-forward, G delta propagation. PROVEN needs two classes agreeing; a seed build with no previous mapping is
therefore mostly ANCHORED.

## Known gaps (seed build)

- IfType getter-style properties (`id`, `text`, `children`, `colour`, …) are registered through per-property sol2
  setter templates; the binding parser follows each to its lambda (kind `property`), and recipes read the component
  through the `sol_self` role. `type` and `isIf3` go through a generic member accessor instead: `type` is mapped from
  the children helper, `isIf3` (offset 0) is not mapped.
- Collision record layout and column addressing are mapped; the meaning of the flag bits (the masks lineOfWalk and
  lineOfSight test) is not. Tile heights, player combat level/index and graphics objects (P1) are not mapped yet.
- Pose animation slot order, pitch vs yaw, the two varp arrays and the x/y pair order of the viewport offsets and centres
  (ProjectionCore loads offsetX before centerX, offsetY before centerY) need the live probe.
- The live probe has not been run against a logged-in client yet.
