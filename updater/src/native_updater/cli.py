"""uv run native-updater <command>   (or: python -m native_updater <command> inside the venv)

  run         map a binary into <out>/<version>.json (default out: the API's bundled mappings,
              api/src/main/resources/mappings): --binary PATH [--previous <old>.json] [--out DIR]
  export      print or run the Ghidra headless export for a binary
  build-model import an existing Ghidra export into program.db
  lint        check the spec
  bindings    dump the parsed Lua binding tables for a binary
  sql         ad hoc query against a build's program.db
  probe       read-only live check of a mapping against the running client (see probe.py)
  fetch       download a build's osclient.exe from the Jagex CDN and print where it stands against the mappings
  report      write the pull request description for a mapping (see report.py)
Exit codes: 0 all P0 keys PROVEN/ANCHORED, 1 acquisition failure or crash, 2 mapping written but some key is
FAILED/NEEDS_REVIEW (left out of the mapping) or a P0 key is only INFERRED.
"""
import argparse
import json
import os
import sys
import traceback
from pathlib import Path

from . import ghidra, model, pipeline
from .identity import DEFAULT_INSTALL, BinaryStore, default_root, identify, sha256_of
from .spec import lint, load as load_spec
from .winpaths import local

# updater/src/native_updater/cli.py -> repo root. Valid because uv installs the project in editable mode.
REPO = Path(__file__).resolve().parents[3]
# The API consumes the mappings and bundles them as classpath resources (GameSession.attach loads mappings/<version>.json).
MAPPINGS = REPO / "api" / "src" / "main" / "resources" / "mappings"
# Arguments that take a path; C:\... values are translated so they also work under WSL.
PATH_ARGS = ("cache", "binary", "out", "previous", "spec", "export", "mapping", "ghidra", "java_home", "dest", "mappings",
             "analysis", "previous_analysis", "release", "output")


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

    p = sub.add_parser("fetch")
    p.add_argument("--environment", default="production",
                   help="osrs-win alias to resolve: production (default), production-last (the build before it), staging")
    p.add_argument("--catalog", help="a catalog id to fetch instead of an alias")
    p.add_argument("--dest", required=True, help="folder to write osclient.exe to")
    p.add_argument("--mappings", default=str(MAPPINGS), help=f"mappings to compare the build with (default {MAPPINGS})")

    p = sub.add_parser("report")
    p.add_argument("--mapping", required=True, help="the new mapping")
    p.add_argument("--previous", help="the mapping it was carried forward from")
    p.add_argument("--analysis", help="the run's analysis.json (default: the build's cache directory)")
    p.add_argument("--previous-analysis", help="the previous build's analysis.json (default: its cache directory, if there)")
    p.add_argument("--exit-code", type=int, help="the exit code of the run that wrote the mapping")
    p.add_argument("--release", help="the JSON `fetch` printed for the build")
    p.add_argument("--run-url", help="link to the CI run")
    p.add_argument("--commit", help="the commit the updater ran at")
    p.add_argument("--output", help="file to write the Markdown to (default: stdout)")

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
    if args.cmd == "fetch":
        return fetch(args)
    if args.cmd == "report":
        return report(args, root)
    if args.cmd == "probe":
        if os.name != "nt":
            print("probe reads the running client's memory through Windows APIs: run it from PowerShell or cmd, not WSL.")
            return 1
        from . import probe
        return probe.main(args.mapping, args.display_name)
    return 1


def fetch(args):
    """Download the build, then print it as JSON with the mapping made from it and the one it would carry forward
    from (each null when there is none). Progress goes to stderr, so stdout stays JSON."""
    from . import cdn, mappings
    release = cdn.resolve(args.environment, args.catalog)
    print(f"build {release.version}, catalog {release.catalog}: downloading osclient.exe", file=sys.stderr)
    path = cdn.download(release, args.dest)
    build = identify(path)
    if build.version != release.version:
        print(f"the CDN calls this build {release.version}, its version resource says {build.version}; "
              f"mappings use {build.version}", file=sys.stderr)

    def entry(e):
        return {"version": e.version, "sha256": e.sha256, "path": str(e.path)} if e else None

    print(json.dumps({
        "environment": release.environment, "catalog": release.catalog, "version": build.version,
        "cdnVersion": release.version, "scanTime": release.scan_time, "sha256": build.sha256, "linked": build.timestamp,
        "path": str(path), "size": path.stat().st_size,
        "mapping": entry(mappings.for_build(args.mappings, build.sha256)),
        "previous": entry(mappings.previous(args.mappings, build.version, build.sha256)),
    }, indent=2))
    return 0


def report(args, root):
    from . import report as rep

    def read(path):
        return json.loads(Path(path).read_text(encoding="utf-8"))

    mapping = read(args.mapping)
    previous = read(args.previous) if args.previous else None
    analysis = read(args.analysis or ghidra.paths(root, mapping["sha256"])["base"] / "analysis.json")
    if analysis.get("sha256") != mapping["sha256"]:
        print(f"the analysis is for build {str(analysis.get('sha256'))[:16]}, the mapping for {mapping['sha256'][:16]}")
        return 1
    prev_analysis = args.previous_analysis or (ghidra.paths(root, previous["sha256"])["base"] / "analysis.json" if previous else None)
    prev_analysis = read(prev_analysis) if prev_analysis and Path(prev_analysis).is_file() else None
    release = read(args.release) if args.release else None
    text = rep.render(mapping, analysis, previous, prev_analysis, load_spec(), args.exit_code, release, args.run_url,
                      args.commit)
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(text)
    return 0
