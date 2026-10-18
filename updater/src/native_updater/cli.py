"""uv run native-updater <command>   (or: python -m native_updater <command> inside the venv)

  run         map a binary into <out>/<version>.json (default out: the API's bundled mappings,
              api/src/main/resources/mappings): --binary PATH [--previous <old>.json] [--out DIR]
  export      print or run the Ghidra headless export for a binary
  build-model import an existing Ghidra export into program.db
  lint        check the spec
  bindings    dump the parsed Lua binding tables for a binary
  sql         ad hoc query against a build's program.db
  probe       read-only live check of a mapping against the running client (see probe.py)
Exit codes: 0 all P0 keys PROVEN/ANCHORED, 1 acquisition failure or crash, 2 mapping written but some key is
FAILED/NEEDS_REVIEW (left out of the mapping) or a P0 key is only INFERRED.
"""
import argparse
import os
import traceback
from pathlib import Path

from . import ghidra, model, pipeline
from .identity import DEFAULT_INSTALL, BinaryStore, default_root, sha256_of
from .spec import lint, load as load_spec
from .winpaths import local

# updater/src/native_updater/cli.py -> repo root. Valid because uv installs the project in editable mode.
REPO = Path(__file__).resolve().parents[3]
# The API consumes the mappings and bundles them as classpath resources (KrakenApi.attach loads mappings/<version>.json).
MAPPINGS = REPO / "api" / "src" / "main" / "resources" / "mappings"
# Arguments that take a path; C:\... values are translated so they also work under WSL.
PATH_ARGS = ("cache", "binary", "out", "previous", "spec", "export", "mapping", "ghidra", "java_home")


def main(argv=None):
    ap = argparse.ArgumentParser(prog="native-updater", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache", default=None, help=f"store/cache root (default {default_root()})")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("run")
    p.add_argument("--binary", default=str(DEFAULT_INSTALL))
    p.add_argument("--out", default=str(MAPPINGS), help=f"mapping output dir (default {MAPPINGS})")
    p.add_argument("--previous", help="previous mapping JSON for carry-forward, delta propagation and the diff")
    p.add_argument("--spec", help="alternative spec directory")

    p = sub.add_parser("export")
    p.add_argument("--binary", default=str(DEFAULT_INSTALL))
    p.add_argument("--ghidra", help="Ghidra install dir (or GHIDRA_INSTALL_DIR)")
    p.add_argument("--java-home", help="JDK 21 home (or GHIDRA_JAVA_HOME / JAVA_HOME)")

    p = sub.add_parser("build-model")
    p.add_argument("--binary", default=str(DEFAULT_INSTALL))
    p.add_argument("--export", help="export directory (default: the cache's export dir for this build)")

    sub.add_parser("lint")

    p = sub.add_parser("bindings")
    p.add_argument("--binary", default=str(DEFAULT_INSTALL))
    p.add_argument("--table")

    p = sub.add_parser("sql")
    p.add_argument("--binary", default=str(DEFAULT_INSTALL))
    p.add_argument("query")

    p = sub.add_parser("probe")
    p.add_argument("--mapping", required=True)
    p.add_argument("--display-name", help="the logged-in character's display name, for the name check")

    args = ap.parse_args(argv)
    try:
        return dispatch(args)
    except Exception:
        traceback.print_exc()
        return 1


def dispatch(args):
    for name in PATH_ARGS:
        if getattr(args, name, None):
            setattr(args, name, str(local(getattr(args, name))))
    if getattr(args, "binary", None) and not Path(args.binary).is_file():
        print(f"osclient.exe not found at {args.binary}\n"
              "Install Old School RuneScape through the Jagex Launcher, or pass --binary <path to osclient.exe>.")
        return 1
    root = Path(args.cache or default_root())
    if args.cmd == "lint":
        problems = lint(load_spec())
        for p in problems:
            print(p)
        print(f"{len(problems)} problem(s)")
        return 2 if problems else 0
    if args.cmd == "run":
        code, _ = pipeline.run(args.binary, args.out, root, args.previous, args.spec)
        return code
    if args.cmd == "export":
        sha, stored = BinaryStore(root).archive(args.binary)
        db = ghidra.ensure_model(stored, sha, root, ghidra_dir=args.ghidra, java_home=args.java_home)
        print(db or "no model produced")
        return 0 if db else 1
    if args.cmd == "build-model":
        sha, stored = BinaryStore(root).archive(args.binary)
        p = ghidra.paths(root, sha)
        model.build(args.export or p["export"], stored, p["db"])
        print(p["db"])
        return 0
    if args.cmd in ("bindings", "sql"):
        sha = sha256_of(args.binary)
        p = ghidra.paths(root, sha)
        if not p["db"].exists():
            print(f"no program.db for {sha[:16]}; run `export` first")
            return 1
        m = model.ProgramModel(p["db"], args.binary)
        if args.cmd == "sql":
            for row in m.db.execute(args.query):
                print("\t".join(f"{v:#x}" if isinstance(v, int) and v > 9 else str(v) for v in row))
            return 0
        from .session import Session
        s = Session(m, load_spec(), print)
        s.parse_bindings()
        for b in s.bindings.bindings.values():
            if args.table and b.table != args.table:
                continue
            val = f"{b.handler:#x}" if b.handler else (f"member +{b.member_offset:#x}" if b.member_offset is not None else "-")
            print(f"{b.key:50s} {val:>14s} {b.kind}")
        return 0
    if args.cmd == "probe":
        if os.name != "nt":
            print("probe reads the running client's memory through Windows APIs: run it from PowerShell or cmd, not WSL.")
            return 1
        from . import probe
        return probe.main(args.mapping, args.display_name)
    return 1
