"""Carry-forward and delta propagation (design §4.7).

Given the previous build's mapping and, when cached, its program model and analysis.json (evidence instruction RVAs,
bindings, fingerprints):
  1. match functions old -> new: seeds are identical binding names, fingerprints and unique anchor strings;
     the matches propagate through aligned call sequences;
  2. re-extract each key at the instruction of the matched function that corresponds to the old evidence
     instruction (bodies aligned by mnemonic sequence) -> class F candidates;
  3. delta propagation: fields of a struct whose resolved neighbours moved by the same delta -> class G;
  4. repair bundles for keys that are still FAILED or NEEDS_REVIEW.
Both carry-forward and delta propagation also run for keys the anchors already resolved, as a cross-check.
"""
import difflib
import json
from pathlib import Path

from . import consensus, emit, ghidra
from .model import ProgramModel
from .session import Candidate

MIN_ALIGN_RATIO = 0.6


def apply(session, prev, prev_analysis, results, cache_root, log):
    """prev is the previous mapping file; prev_analysis its analysis.json, or None when it is not cached."""
    prev_sha = prev["sha256"]
    info = {"previous": prev["version"], "matchedFunctions": 0, "carried": 0, "propagated": 0, "previousModel": False}
    old = _previous_model(cache_root, prev_sha) if prev_analysis else None
    if old is not None:
        info["previousModel"] = True
    else:
        log(f"previous model or analysis for {prev['version']} ({prev_sha[:16]}) not cached: carry-forward limited to delta propagation")

    by_key = {r.key: r for r in results}
    prev_values = _values(prev, prev_analysis)
    prev_evidence = _evidence_index(prev_analysis) if prev_analysis else {}

    if old is not None:
        needed = {ev["fn"] for key, ev in prev_evidence.items() if key in by_key and ev.get("fn") is not None}
        matches = match_functions(session, old, prev_analysis, needed)
        session.carry_matches = matches
        info["matchedFunctions"] = len(matches)
        for key, ev in prev_evidence.items():
            old_value = _as_int(prev_values.get(key))
            if key not in by_key or ev.get("fn") is None or ev.get("insn") is None or old_value is None:
                continue
            new_fn = matches.get(ev["fn"])
            if new_fn is None:
                continue
            value, new_insn = reextract(session, old, ev, new_fn, old_value)
            if value is None:
                continue
            session.candidates.setdefault(key, []).append(Candidate(
                value, "carry-forward", "F", [session.evidence(new_insn, new_fn, ev.get("slot", "disp"))],
                f"re-extracted at the instruction matching {ev['insn']:#x} in previous {ev['fn']:#x}"))
            info["carried"] += 1

    # Delta propagation per struct, using whatever is resolved so far (anchors + carry-forward).
    current = {}
    for key, cands in session.candidates.items():
        r = consensus.grade(key, cands)
        if isinstance(r.value, int):
            current[key] = r.value
    for struct, fields in _structs(prev_values).items():
        ordered = sorted(fields.items(), key=lambda kv: kv[1])
        for i, (key, old_off) in enumerate(ordered):
            if key in current or key not in by_key:
                continue
            lo = next(((k, v) for k, v in reversed(ordered[:i]) if k in current), None)
            hi = next(((k, v) for k, v in ordered[i + 1:] if k in current), None)
            if lo is None or hi is None:
                continue
            d_lo, d_hi = current[lo[0]] - lo[1], current[hi[0]] - hi[1]
            if d_lo == d_hi:
                session.candidates.setdefault(key, []).append(Candidate(
                    old_off + d_lo, "delta-propagation", "G", [],
                    f"neighbours {lo[0]} and {hi[0]} both moved by {d_lo:+#x}"))
                info["propagated"] += 1
    return info


def match_functions(session, old, prev_analysis, needed):
    """old fn -> new fn for the functions that carry evidence. Seeds: bindings and fingerprints by name, then
    unique strings referenced by a needed function. Propagation: a needed function is matched through a matched
    caller by aligning the two callers' call sequences; unmatched callers become needed in turn (3 levels)."""
    new_model = session.model
    matches = {}
    for key, b in prev_analysis.get("bindings", {}).items():
        nb = session.bindings.get(key)
        if b.get("handler") and nb is not None and nb.handler is not None:
            matches[int(b["handler"], 16)] = nb.handler
    for name, fp in prev_analysis.get("fingerprints", {}).items():
        rva = session.fingerprints.get(name)
        if fp.get("rva") and rva is not None:
            matches[int(fp["rva"], 16)] = rva
    needed = set(needed) - set(matches)
    for _ in range(4):
        if not needed:
            break
        for fn in list(needed):
            if fn in matches:
                needed.discard(fn)
                continue
            # Unique string anchors referenced by this function.
            for (dst,) in old.db.execute("SELECT DISTINCT dst FROM xref WHERE fn = ? AND kind IN ('lea', 'read')", (fn,)):
                text = old.string_at(dst)
                if not text or len(text) < 12:
                    continue
                if len({f for _, f in old.string_refs(text) if f is not None}) != 1:
                    continue
                refs_new = {f for _, f in new_model.string_refs(text) if f is not None}
                if len(refs_new) == 1:
                    matches[fn] = next(iter(refs_new))
                    break
            if fn in matches:
                needed.discard(fn)
                continue
            # Through a matched caller.
            for caller in old.callers(fn):
                if caller not in matches:
                    continue
                oc = [c for c in old.callees(caller) if old.function(c) is not None]
                nc = [c for c in new_model.callees(matches[caller]) if new_model.function(c) is not None]
                for a, b in _align(oc, nc, lambda x: _sig(old, x), lambda x: _sig(new_model, x)):
                    matches.setdefault(a, b)
                if fn in matches:
                    break
            if fn in matches:
                needed.discard(fn)
        # Climb: the unmatched functions' callers become needed (bounded).
        climb = set()
        for fn in needed:
            climb.update(old.callers(fn)[:8])
        needed = (needed | climb) - set(matches)
        if len(needed) > 2000:
            break
    return matches


def _sig(model, fn):
    """Coarse shape of a function: instruction count bucket and the mnemonic histogram's top entries."""
    body = model.insns(fn)
    counts = {}
    for i in body:
        counts[i.mnem] = counts.get(i.mnem, 0) + 1
    top = tuple(sorted(counts.items(), key=lambda kv: -kv[1])[:4])
    return (len(body) // 8, tuple(k for k, _ in top))


def _align(a, b, ka, kb):
    sa, sb = [ka(x) for x in a], [kb(x) for x in b]
    sm = difflib.SequenceMatcher(None, sa, sb, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            for k in range(i2 - i1):
                yield a[i1 + k], b[j1 + k]


def reextract(session, old, ev, new_fn, old_value=None):
    """Value at the instruction of new_fn that corresponds to ev['insn'] in the old function. Only instructions
    that directly encode the key's previous value are carried (hash-map parts, strides and derived values are not)."""
    old_body = old.insns(ev["fn"])
    new_body = session.model.insns(new_fn)
    old_idx = next((k for k, i in enumerate(old_body) if i.rva == ev["insn"]), None)
    if old_idx is None or not new_body:
        return None, None
    if old_value is not None and _slot_value(old, old_body[old_idx], ev.get("slot", "disp")) != old_value:
        return None, None
    sm = difflib.SequenceMatcher(None, [i.mnem for i in old_body], [i.mnem for i in new_body], autojunk=False)
    if sm.ratio() < MIN_ALIGN_RATIO:
        return None, None
    new_idx = None
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal" and i1 <= old_idx < i2:
            new_idx = j1 + (old_idx - i1)
            break
    if new_idx is None:
        return None, None
    o, n = old_body[old_idx], new_body[new_idx]
    if o.mnem != n.mnem or len(o.ops) != len(n.ops) or any(x.kind != y.kind for x, y in zip(o.ops, n.ops)):
        return None, None
    value = _slot_value(session.model, n, ev.get("slot", "disp"))
    return (value, n.rva) if value is not None else (None, None)


def _slot_value(model, insn, slot):
    """The value an instruction encodes in the given slot: a field displacement, a RIP-relative target or an immediate."""
    for op in insn.ops:
        if slot == "disp" and op.kind == "mem" and op.base != "RIP":
            return op.disp
        if slot == "rip" and op.kind == "mem" and op.base == "RIP":
            return op.disp
        if slot == "imm" and op.kind == "imm":
            return op.imm
    if slot == "rip":
        if insn.target is not None:
            return model.resolve(insn.target)
        return insn.rva if insn is not None and model.function(insn.rva) is not None else None
    return None


def _as_int(v):
    if isinstance(v, str) and v.startswith("0x"):
        return int(v, 16)
    return v if isinstance(v, int) else None


def _previous_model(cache_root, sha):
    p = ghidra.paths(cache_root, sha)
    binary = Path(cache_root) / "store" / sha / "osclient.exe"
    return ProgramModel(p["db"], binary) if p["db"].exists() and binary.exists() else None


def _values(prev, prev_analysis):
    """Previous values by key: every resolved key from the analysis when cached (internal keys included), else the mapping."""
    if prev_analysis:
        return {k: e["value"] for k, e in prev_analysis["keys"].items() if e["verdict"] in emit.TRUSTED}
    return emit.flatten(prev)


def _evidence_index(prev_analysis):
    """First evidence of every global, function and struct key (container and enum values are not carried)."""
    out = {}
    for key, e in prev_analysis.get("keys", {}).items():
        ev = (e.get("evidence") or [None])[0]
        if ev and not key.startswith(("container.", "enums.")):
            out[key] = _ev_ints(ev)
    return out


def _ev_ints(ev):
    out = dict(ev)
    for k in ("insn", "fn"):
        v = ev.get(k)
        out[k] = int(v, 16) if isinstance(v, str) and v else (v if isinstance(v, int) else None)
    return out


def _structs(flat):
    out = {}
    for key, v in flat.items():
        head = key.split(".")[0]
        if head in ("global", "functions", "enums", "container") or not isinstance(v, str) or not v.startswith("0x"):
            continue
        out.setdefault(head, {})[key] = int(v, 16)
    return out


def repair_bundle(session, prev, prev_analysis, results, out_dir, cache_root, log):
    """For each FAILED/NEEDS_REVIEW key with previous evidence: old and new disassembly side by side, in out_dir/repair."""
    prev_sha = prev["sha256"]
    old = _previous_model(cache_root, prev_sha) if prev_analysis else None
    if old is None:
        return None
    ev_index = _evidence_index(prev_analysis)
    prev_values = _values(prev, prev_analysis)
    matches = getattr(session, "carry_matches", None)
    if matches is None:
        matches = match_functions(session, old, prev_analysis, {ev["fn"] for ev in ev_index.values() if ev.get("fn") is not None})
    bundle_dir = Path(out_dir) / "repair"
    written = 0
    for r in results:
        if r.verdict not in ("FAILED", "NEEDS_REVIEW") or r.key not in ev_index:
            continue
        ev = ev_index[r.key]
        if ev.get("fn") is None:
            continue
        bundle_dir.mkdir(parents=True, exist_ok=True)
        new_fn = matches.get(ev["fn"])
        lines = [f"# Repair: {r.key}", "", f"Verdict: {r.verdict}. {'; '.join(r.reasons)}", "",
                 f"Previous build {prev['version']} ({prev_sha[:16]}): value {prev_values.get(r.key)} at `{ev['insn']:#x}` in `FUN_{ev['fn'] + old.image_base:x}`: `{ev.get('text', '')}`",
                 f"Matched new function: {'FUN_%x' % (new_fn + session.model.image_base) if new_fn else 'none'}", "",
                 "REA: `compare_functions` / `find_changed_behavior` between the two functions, then fix the recipe in spec/*.yaml.", "",
                 "## Previous function (around the evidence)", "```"]
        body = old.insns(ev["fn"])
        idx = next((k for k, i in enumerate(body) if i.rva == ev["insn"]), 0)
        lines += [f"{i.rva:#x}  {i.text}{'   <== evidence' if i.rva == ev['insn'] else ''}" for i in body[max(0, idx - 12):idx + 12]]
        lines += ["```", "", "## Candidate in the new build", "```"]
        if new_fn:
            lines += [f"{i.rva:#x}  {i.text}" for i in session.model.insns(new_fn)[:60]]
        else:
            lines.append("(no matched function; try the string anchors and bindings listed in the report)")
        lines.append("```")
        (bundle_dir / f"{r.key}.md").write_text("\n".join(lines), encoding="utf-8")
        written += 1
    if written:
        log(f"wrote {written} repair bundle(s) to {bundle_dir}")
    return str(bundle_dir) if written else None
