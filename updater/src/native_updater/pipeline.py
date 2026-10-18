"""The run: identify -> program model -> bindings & fingerprints -> strategies -> consensus -> verify -> emit."""
import json
import traceback
from pathlib import Path

from . import consensus, emit, fingerprints, ghidra, strategies, verify
from .identity import BinaryStore, default_root, identify
from .model import ProgramModel
from .session import Blocked, Session
from .spec import KeySpec, load as load_spec

ROUNDS = 5


def run(binary, out_dir, cache_root=None, previous=None, spec_dir=None, log=print, carry_only=False, diag_root=None):
    """Map a binary into out_dir/<version>.json. Diagnostics (analysis.json, repair bundles) go to
    diag_root/<sha256>/, by default the build's cache directory next to program.db. Returns (exit code, mapping path)."""
    cache_root = Path(cache_root or default_root())
    diag_root = Path(diag_root) if diag_root else cache_root / "cache"
    sha, stored = BinaryStore(cache_root).archive(binary)
    identity = identify(stored)
    log(f"client {identity.version} (build {sha[:16]}, linked {identity.timestamp}), archived at {stored}")
    db = ghidra.ensure_model(stored, sha, cache_root, log=log)
    if db is None:
        return 1, None
    model = ProgramModel(db, stored)

    spec = load_spec(spec_dir) if spec_dir else load_spec()
    session = Session(model, spec, log)
    session.parse_bindings()
    log(f"bindings: {sum(1 for b in session.bindings.bindings.values() if b.handler or b.member_offset is not None)}/{len(session.bindings.bindings)} resolved, "
        f"{len(session.enum_tables)} enum tables")
    expand_member_tables(session)
    results = resolve_keys(session, log, anchors=not carry_only)
    log("fingerprints: " + ", ".join(f"{n}={rva:#x}" if rva else f"{n}=?" for n, rva in session.fingerprints.items()))

    carry_info = None
    prev = json.loads(Path(previous).read_text(encoding="utf-8")) if previous else None
    prev_analysis = load_analysis(diag_root, prev["sha256"]) if prev else None
    if prev:
        from . import carry
        carry_info = carry.apply(session, prev, prev_analysis, results, cache_root, log)
        results = regrade(session, results)

    failures = verify.check_all(spec, session.values)
    results = regrade(session, results, failures)
    diag = diag_root / sha
    if prev:
        from . import carry
        carry_info["repairBundle"] = carry.repair_bundle(session, prev, prev_analysis, results, diag, cache_root, log)

    mapping = emit.build_mapping(identity, results, spec)
    changes = emit.diff(prev, mapping) if prev else None
    path = emit.write_json(Path(out_dir) / f"{identity.version}.json", mapping)
    analysis = emit.write_json(diag / "analysis.json", emit.build_analysis(session, identity, results, changes, carry_info))
    emit.summarize(log, results, changes)
    code = consensus.exit_code(results, spec.keys)
    log(f"wrote {path} ({len(emit.flatten(mapping))} values) and {analysis}; exit {code}")
    return code, path


def load_analysis(diag_root, sha):
    path = Path(diag_root) / sha / "analysis.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def expand_member_tables(session):
    """Every data-member binding of a configured usertype becomes a key."""
    for mt in session.spec.member_tables:
        for b in session.bindings.by_table.get(mt["table"], ()):
            if b.member_offset is None:
                continue
            key = f"{mt['struct']}.{b.name}"
            if session.spec.key(key) is None:
                session.spec.keys.append(KeySpec(key, f"member(reg {b.register_call:#x})" if b.register_call else "member", mt.get("priority", "P1"),
                                                 [("member", {"in": b.key})], [], mt.get("live", ""), "data member registered with sol2"))


def resolve_keys(session, log, anchors=True):
    """Run every strategy of every key, in rounds, so keys that depend on others resolve once those exist.
    With anchors=False only fingerprints run (carry-forward test mode)."""
    spec = session.spec
    for rnd in range(ROUNDS):
        fingerprints.run_all(session)
        blocked = {}
        for k in spec.keys:
            cands = []
            for kind, params in k.strategies:
                if kind in ("carry-forward", "delta-propagation") or not anchors:
                    continue
                try:
                    cands.extend(strategies.run(session, kind, params, k.key))
                except Blocked as b:
                    blocked.setdefault(k.key, []).append(f"{kind}: {b.what}")
                except Exception as e:  # a broken recipe must not kill the run; it shows up as FAILED with the error
                    session.notes.append(f"{k.key}: strategy {kind} raised {e.__class__.__name__}: {e}")
                    log(traceback.format_exc()) if rnd == ROUNDS - 1 else None
            session.candidates[k.key] = cands
            r = consensus.grade(k.key, cands)
            if r.value is not None:
                session.values[k.key] = r.value
            else:
                session.values.pop(k.key, None)
        if blocked == session.blocked:
            break
        session.blocked = blocked
    return regrade(session, [])


def regrade(session, _results, failures=None):
    out = []
    for k in session.spec.keys:
        blocked = "; ".join(session.blocked.get(k.key, [])) or None
        r = consensus.grade(k.key, session.candidates.get(k.key, []), blocked, (failures or {}).get(k.key, ()))
        out.append(r)
        if r.value is not None:
            session.values[k.key] = r.value
    return out
