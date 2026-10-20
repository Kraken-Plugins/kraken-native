"""Outputs.

  mappings/<version>.json        what the API reads: values only (see build_mapping)
  <cache>/<sha256>/analysis.json  everything behind those values: verdicts, evidence, fingerprints, bindings, notes
                                  and the diff. Next to program.db, out of git; the next build's carry-forward reads it.
"""
import json
from datetime import datetime, timezone
from pathlib import Path

from . import __version__
from .consensus import ORDER

# Keys with these verdicts go into the mapping. NEEDS_REVIEW and FAILED stay out, so the API never reads a value
# the updater could not stand behind; they are listed in the run output and analysis.json instead.
TRUSTED = ("PROVEN", "ANCHORED", "INFERRED")


def hx(v):
    return f"{v:#x}" if isinstance(v, int) else v


def fmt(key, v):
    """How a value is written: offsets and RVAs as hex strings, enum values as plain ints, event hook sites as
    {fn: hex, id | idArg: int, args: int} records."""
    if key.startswith("enums."):
        return v
    if key.startswith("events.") and isinstance(v, list):
        return [{**site, "fn": hx(site["fn"])} for site in v]
    return hx(v)


def build_mapping(identity, results, spec):
    """The API contract, e.g.
      {"version": "241.3", "sha256": "...",
       "globals": {"client": "0xe98c18"}, "structs": {"Actor": {"pathX": "0x3f0"}},
       "containers": {"hash_map": {"buckets": "0x8"}}, "enums": {"gameState": {"LOGGED_IN": 30}},
       "functions": {"worldToScreen": "0x21eb80"},
       "events": {"ON_GAME_TICK": [{"fn": "0xc44d0", "idArg": 0, "args": 1}]}}
    Internal keys (spec `internal: true`) are resolved and verified but not written."""
    internal = {k.key for k in spec.keys if k.internal}
    out = {"version": identity.version, "sha256": identity.sha256,
           "globals": {}, "structs": {}, "containers": {}, "enums": {}, "functions": {}, "events": {}}
    for r in results:
        if r.verdict not in TRUSTED or r.key in internal:
            continue
        head, _, rest = r.key.partition(".")
        value = fmt(r.key, r.value)
        if head == "global":
            out["globals"][rest] = value
        elif head == "functions":
            out["functions"][rest] = value
        elif head == "events":
            out["events"][rest] = value
        elif head == "container":
            group, _, name = rest.partition(".")
            out["containers"].setdefault(group, {})[name] = value
        elif head == "enums":
            group, _, name = rest.partition(".")
            if name:
                out["enums"].setdefault(group, {})[name] = value
            else:
                out["enums"][group] = value         # a whole {name: value} table
        else:
            out["structs"].setdefault(head, {})[rest] = value
    return out


def flatten(mapping):
    """{spec key: value} from a mapping file (the inverse of build_mapping)."""
    out = {}
    for name, v in mapping.get("globals", {}).items():
        out[f"global.{name}"] = v
    for name, v in mapping.get("functions", {}).items():
        out[f"functions.{name}"] = v
    for name, v in mapping.get("events", {}).items():
        out[f"events.{name}"] = v
    for struct, fields in mapping.get("structs", {}).items():
        for f, v in fields.items():
            out[f"{struct}.{f}"] = v
    for group, fields in mapping.get("containers", {}).items():
        for f, v in fields.items():
            out[f"container.{group}.{f}"] = v
    for group, values in mapping.get("enums", {}).items():
        for name, v in values.items():
            out[f"enums.{group}.{name}"] = v
    return out


def build_analysis(session, identity, results, changes=None, carry_info=None):
    keys = {}
    for r in results:
        entry = {"value": fmt(r.key, r.value), "verdict": r.verdict, "classes": "".join(sorted(r.classes))}
        agreeing = [c for c in r.candidates if c.value == r.value]
        ev = [{"strategy": c.strategy, "cls": c.cls, "insn": hx(e.get("insn")), "fn": hx(e.get("fn")), "text": e.get("text", ""),
               "slot": e.get("slot", "")} for c in agreeing for e in c.evidence][:4]
        if ev:
            entry["evidence"] = ev
        notes = list(dict.fromkeys(c.note for c in agreeing if c.note))
        if notes:
            entry["note"] = "; ".join(notes)
        if r.reasons:
            entry["reasons"] = r.reasons
        keys[r.key] = entry
    return {
        "version": identity.version,
        "sha256": identity.sha256,
        "linked": identity.timestamp,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "updater": __version__,
        "ghidra": session.model.meta.get("ghidra_version"),
        "verdicts": verdict_counts(results),
        "keys": keys,
        "diff": [{"key": k, "old": o, "new": n, "kind": kind} for k, o, n, kind in changes] if changes is not None else None,
        "carryForward": carry_info,
        "notes": session.notes,
        "fingerprints": {name: {"rva": hx(rva), **{k: v for k, v in session.fp_evidence.get(name, {}).items() if k in ("status", "note")}}
                         for name, rva in session.fingerprints.items()},
        "bindings": {b.key: {"handler": hx(b.handler), "member": hx(b.member_offset), "kind": b.kind}
                     for b in session.bindings.bindings.values()},
    }


def verdict_counts(results):
    return {v: sum(1 for r in results if r.verdict == v) for v in reversed(ORDER)}


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")  # same bytes on Windows and WSL
    return path


def summarize(log, results, changes=None):
    """What the operator needs from a run: verdict counts, keys left out of the mapping and why, and the diff."""
    log("verdicts: " + ", ".join(f"{n} {v}" for v, n in verdict_counts(results).items()))
    for r in sorted(results, key=lambda r: (ORDER.index(r.verdict), r.key)):
        if r.verdict not in TRUSTED:
            log(f"  {r.verdict} {r.key}: {'; '.join(r.reasons)}")
    if changes is not None:
        log(f"diff against the previous build: {len(changes)} change(s)")
        for key, o, n, kind in changes:
            log(f"  {kind:6s} {key}: {o} -> {n}")


def diff(previous, current):
    """[(key, old, new, kind)] with kind in moved | new | gone, between two mapping files."""
    old, new = flatten(previous), flatten(current)
    changes = []
    for key in sorted(set(old) | set(new)):
        o, n = old.get(key), new.get(key)
        if key not in new:
            changes.append((key, o, None, "gone"))
        elif key not in old:
            changes.append((key, None, n, "new"))
        elif o != n:
            changes.append((key, o, n, "moved"))
    return changes
