# Kraken native updater

Maps struct offsets, globals and functions of a new `osclient.exe` build and writes the contract the native API
consumes (`mappings/<sha16>.json`). Implements [docs/updater-design.md](../docs/updater-design.md).

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

## Flow

```
osclient.exe ──(Ghidra headless, 10-15 min, once per build)──► export/*.tsv.gz ──► program.db (SQLite)
program.db ──► bindings + fingerprints ──► strategies ──► consensus + invariants ──► mappings/<sha16>.json + .report.md
```

```bash
cd updater
uv run native-updater lint                                        # check the spec
uv run native-updater export --binary <osclient.exe> --ghidra <GHIDRA_INSTALL_DIR> --java-home <JDK21>
uv run native-updater run --binary <osclient.exe>                 # writes ../mappings/<sha16>.json + report
uv run native-updater run --binary <new.exe> --previous ../mappings/<old>.json   # + carry-forward, diff, repair bundles
uv run native-updater bindings --binary <osclient.exe> --table osrs.IfType       # dump a Lua binding table
uv run native-updater sql --binary <osclient.exe> "SELECT i.rva, i.text FROM operand o JOIN insn i USING(rva) WHERE o.kind='mem' AND o.disp=0xcc70"
uv run native-updater probe --mapping ../mappings/<sha16>.json --display-name <name> [--merge]   # read-only live check
uv run python -m unittest discover -s tests -t .                  # unit + golden tests (golden skip without the cached model)
```

Exit codes: `0` every P0 key PROVEN/ANCHORED, `1` acquisition failure or crash, `2` output written but some key is
FAILED/NEEDS_REVIEW (or a P0 key is only INFERRED).

The Ghidra export is manual-or-automatic: with `GHIDRA_INSTALL_DIR` (and `JAVA_HOME` pointing at JDK 21) set,
`run` performs it; otherwise it prints the exact `analyzeHeadless` command. Binaries and models live outside git in
`~/.kraken-native/{store,cache}/<sha256>/` (override with `KRAKEN_NATIVE_HOME` or `--cache`).

## Layout

| Path | What |
|---|---|
| `pyproject.toml`, `uv.lock`, `.python-version` | Project metadata, dependencies and the `native-updater` command; pinned versions; interpreter (see Setup) |
| `ghidra/ExportProgramModel.java` | Ghidra post-script: functions, instructions (bytes, text, flows) → TSV |
| `src/native_updater/model.py` | TSV → `program.db`; read-only query API (functions, insns, xrefs, strings, disp/imm indexes) |
| `src/native_updater/pe.py`, `x86.py` | PE32+ reader; Ghidra instruction text → structured operands |
| `src/native_updater/analysis/provenance.py` | Forward abstract interpretation per function: what each register/stack slot holds (`[[client]+0xC9B8]`, `Arg0`, `Ret(fn)`, …). Leaf getters are inlined, tail jumps are calls, registers survive calls (LTCG build). |
| `src/native_updater/analysis/bindings.py` | sol2 registration parser (name/handler/member-offset pairing by maximum matching) and `.rdata` enum tables |
| `src/native_updater/roles.py` | Names for pointer bases used by recipes: `client`, `sp_result`, `node_value`, `{field: …}` |
| `src/native_updater/strategies.py` | Generic strategies: `getter`, `global-load`, `hash-map`, `const`, `member`, `binding`, `fingerprint`, `enum-table`, `derive`, `extractor` |
| `src/native_updater/fingerprints.py` | Function locators: `callee_of`, `caller_of`, `common_callee_of`, `binding`, `extractor` |
| `src/native_updater/extractors.py` | Custom recipes over provenance facts: config loaders, setSequence, Actor ctor, scene grid, obj piles, containers |
| `src/native_updater/spec/*.yaml` | The contract: tables, fingerprints, one entry per key (strategies, invariants, live check), capabilities |
| `src/native_updater/consensus.py`, `verify.py` | Verdicts (PROVEN/ANCHORED/INFERRED/NEEDS_REVIEW/FAILED); type, relational and uniqueness checks; guards |
| `src/native_updater/carry.py` | Carry-forward (function matching + re-extraction), delta propagation, repair bundles |
| `src/native_updater/emit.py` | mapping JSON (schema `kraken-native-mappings/2`), report, `index.json`, diff |
| `src/native_updater/probe.py` | Read-only live verification → `<sha16>.live.json` overlay (`LIVE_VERIFIED`) |
| `corpus/*.golden.json` | Accepted values per build for the golden tests |

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

- IfType getter-style properties (`id`, `text`, `children`, `colour`, …) are registered through per-property
  functions without a visible lambda; only data members are mapped. Interface group record layout (`IfGroupNode.value`)
  is not decoded.
- Collision flag bitmap, tile heights, player combat level/index, projectiles and graphics objects (P1) are not mapped yet.
- Pose animation slot order, pitch vs yaw and the two varp arrays need the live probe.
- The live probe has not been run against a logged-in client yet.
