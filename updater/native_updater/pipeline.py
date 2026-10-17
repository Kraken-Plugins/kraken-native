"""The run: identify -> program model -> bindings & fingerprints -> strategies -> consensus -> verify -> emit."""
import json
import traceback
from pathlib import Path

from . import consensus, emit, fingerprints, ghidra, strategies, verify
from .identity import BinaryStore, default_root, identify
from .model import ProgramModel
from .session import Blocked, Candidate, Session
from .spec import KeySpec, load as load_spec

ROUNDS = 5


def run(binary, out_dir, cache_root=None, previous=None, spec_dir=None, log=print, probe_overlay=None, carry_only=False):
    cache_root = Path(cache_root or default_root())
    store = BinaryStore(cache_root)
    sha, stored = store.archive(binary)
    log(f"build {sha[:16]} archived at {stored}")
    db = ghidra.ensure_model(stored, sha, cache_root, log=log)
    if db is None:
        return 1, None
    model = ProgramModel(db, stored)
    identity = identify(stored, model)
    log(f"tag {identity.tag or '?'}, timestamp {identity.timestamp}, SizeOfImage {identity.size_of_image:#x}, client version {identity.client_version}")

    spec = load_spec(spec_dir) if spec_dir else load_spec()
    session = Session(model, spec, log)
    session.parse_bindings()
    log(f"bindings: {sum(1 for b in session.bindings.bindings.values() if b.handler or b.member_offset is not None)}/{len(session.bindings.bindings)} resolved, "
        f"{len(session.enum_tables)} enum tables")
    expand_member_tables(session)
    results = resolve_keys(session, log, anchors=not carry_only)
    log("fingerprints: " + ", ".join(f"{n}={rva:#x}" if rva else f"{n}=?" for n, rva in session.fingerprints.items()))

    carry_info = None
    prev_mapping = json.loads(Path(previous).read_text(encoding="utf-8")) if previous else None
    if prev_mapping:
        from . import carry
        carry_info = carry.apply(session, prev_mapping, results, cache_root, log)
        results = regrade(session, results)

    failures = verify.check_all(spec, session.values)
    results = regrade(session, results, failures)
    caps = capabilities(spec, results)
    if prev_mapping:
        from . import carry
        carry_info["repairBundle"] = carry.repair_bundle(session, prev_mapping, results, out_dir, cache_root, log)

    mapping = emit.build_mapping(session, identity, results, spec, caps, carry_info)
    if probe_overlay:
        apply_overlay(mapping, results, json.loads(Path(probe_overlay).read_text(encoding="utf-8")))
    changes = emit.diff(prev_mapping, mapping) if prev_mapping else None
    md = emit.report(mapping, results, spec, session.notes, changes, prev_mapping)
    path = emit.write_outputs(out_dir, mapping, md)
    code = consensus.exit_code(results, spec.keys)
    log(f"wrote {path}; summary {mapping['summary']}; exit {code}")
    return code, path


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


def capabilities(spec, results):
    by_key = {r.key: r for r in results}
    out = {}
    for name, cap in spec.capabilities.items():
        min_idx = consensus.ORDER.index(cap.get("min", "ANCHORED"))
        missing = [k for k in cap["keys"] if k not in by_key or consensus.ORDER.index(by_key[k].verdict) < min_idx]
        out[name] = {"satisfied": not missing, "missing": missing}
    return out


def apply_overlay(mapping, results, overlay):
    """Merge a live-probe overlay ({key: {ok: bool, detail: str}}) into verdict flags."""
    flat = {}
    for r in results:
        o = overlay.get("keys", {}).get(r.key)
        if o is not None:
            r.live = "LIVE_VERIFIED" if o.get("ok") else "LIVE_FAILED"
            flat[r.key] = r.live
    for section in ("globals", "functions"):
        for name, e in mapping.get(section, {}).items():
            k = f"{'global' if section == 'globals' else 'functions'}.{name}"
            if k in flat:
                e["live"] = flat[k]
    for struct, s in mapping.get("structs", {}).items():
        for f, e in s["fields"].items():
            if f"{struct}.{f}" in flat:
                e["live"] = flat[f"{struct}.{f}"]
    mapping["liveProbe"] = overlay.get("meta", {})
