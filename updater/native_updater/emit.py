"""Outputs: mappings/<sha16>.json (schema kraken-native-mappings/2), <sha16>.report.md, index.json, diffs."""
import json
from datetime import datetime, timezone
from pathlib import Path

from . import __version__
from .consensus import ORDER

GUARD_LEN = 16


def hx(v):
    return f"{v:#x}" if isinstance(v, int) else v


def guard_for(model, rva, length=GUARD_LEN):
    """Bytes at rva with RIP-relative displacements and call/jump targets masked ('??')."""
    out = []
    pos = rva
    while pos < rva + length:
        insn = model.insn(pos)
        if insn is None or insn.length == 0:
            break
        raw = model.insn_bytes(pos)
        masked = [f"{b:02X}" for b in raw]
        if any(op.kind == "mem" and op.base == "RIP" for op in insn.ops) or insn.target is not None:
            for k in range(max(0, len(raw) - 4), len(raw)):
                masked[k] = "??"
        out.extend(masked)
        pos += insn.length
    return {"rva": hx(rva), "bytes": " ".join(out)}


def build_mapping(session, identity, results, spec, capabilities, carry_info=None):
    structs, globals_, functions, enums, containers = {}, {}, {}, {}, {}
    by_key = {r.key: r for r in results}
    specs = {k.key: k for k in spec.keys}
    for r in results:
        k = specs.get(r.key)
        entry = {"verdict": r.verdict}
        if k and k.live:
            entry["check"] = k.live
        if r.live:
            entry["live"] = r.live
        if r.value is None:
            entry["reason"] = "; ".join(r.reasons)
        ev = [e for c in r.candidates if c.value == r.value for e in c.evidence][:4]
        if ev:
            entry["evidence"] = [{"strategy": c.strategy, "cls": c.cls, "insn": hx(e.get("insn")), "fn": hx(e.get("fn")), "text": e.get("text", ""), "slot": e.get("slot", "")}
                                 for c in r.candidates if c.value == r.value for e in c.evidence][:4]
        notes = [c.note for c in r.candidates if c.note and c.value == r.value]
        if notes:
            entry["note"] = "; ".join(dict.fromkeys(notes))
        if r.reasons and r.value is not None:
            entry["reasons"] = r.reasons
        head, _, rest = r.key.partition(".")
        if head == "global":
            entry = {"rva": hx(r.value), "type": k.type if k else "", **entry}
            if r.value is not None and ev:
                entry["guard"] = guard_for(session.model, ev[0]["insn"], 7) if ev[0].get("insn") else None
            globals_[rest] = entry
        elif head == "functions":
            entry = {"rva": hx(r.value), "signature": k.type if k else "", **entry}
            if isinstance(r.value, int):
                entry["guard"] = guard_for(session.model, r.value)
            functions[rest] = entry
        elif head == "enums":
            if isinstance(r.value, dict):
                enums[rest] = {"values": r.value, "verdict": r.verdict}
            else:
                group, _, name = rest.partition(".")
                enums.setdefault(group, {"values": {}, "verdict": r.verdict})["values"][name] = r.value
        elif head == "container":
            group, _, name = rest.partition(".")
            containers.setdefault(group, {})[name] = {"value": hx(r.value), "verdict": r.verdict}
        else:
            structs.setdefault(head, {"fields": {}})["fields"][rest] = {"offset": hx(r.value), "type": k.type if k else "member", **entry}
    bindings = {b.key: {"handler": hx(b.handler), "member": hx(b.member_offset), "kind": b.kind}
                for b in session.bindings.bindings.values()}
    fps = {name: {"rva": hx(rva), **{k: v for k, v in session.fp_evidence.get(name, {}).items() if k in ("status", "note")}}
           for name, rva in session.fingerprints.items()}
    counts = {v: sum(1 for r in results if r.verdict == v) for v in ORDER}
    return {
        "schema": "kraken-native-mappings/2",
        "build": identity.to_json(),
        "updater": {"version": __version__, "ghidra": session.model.meta.get("ghidra_version"), "specSha256": spec.sha256,
                    "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"), "carryForward": carry_info},
        "summary": counts,
        "containers": containers,
        "globals": globals_,
        "functions": functions,
        "structs": structs,
        "enums": enums,
        "capabilities": capabilities,
        "fingerprints": fps,
        "bindings": bindings,
    }


def write_outputs(out_dir, mapping, report_md):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    prefix = mapping["build"]["sha256"][:16]
    json_path = out_dir / f"{prefix}.json"
    json_path.write_text(json.dumps(mapping, indent=2), encoding="utf-8")
    (out_dir / f"{prefix}.report.md").write_text(report_md, encoding="utf-8")
    index_path = out_dir / "index.json"
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else {"builds": {}}
    index["builds"][mapping["build"]["sha256"]] = {"file": json_path.name, "timestamp": mapping["build"]["timestamp"],
                                                   "tag": mapping["build"]["tag"], "summary": mapping["summary"]}
    latest = max(index["builds"].items(), key=lambda kv: kv[1]["timestamp"])
    index["latest"] = latest[0]
    index_path.write_text(json.dumps(index, indent=2), encoding="utf-8")
    return json_path


# ---- diff --------------------------------------------------------------------------------------------------------
def flatten(mapping):
    """{key: value} over globals, functions, struct fields, containers, enum tables."""
    out = {}
    for name, e in mapping.get("globals", {}).items():
        out[f"global.{name}"] = e.get("rva")
    for name, e in mapping.get("functions", {}).items():
        out[f"functions.{name}"] = e.get("rva")
    for struct, s in mapping.get("structs", {}).items():
        for f, e in s.get("fields", {}).items():
            out[f"{struct}.{f}"] = e.get("offset")
    for group, fields in mapping.get("containers", {}).items():
        for f, e in fields.items():
            out[f"container.{group}.{f}"] = e.get("value")
    for group, e in mapping.get("enums", {}).items():
        for name, v in e.get("values", {}).items():
            out[f"enums.{group}.{name}"] = v
    return out


def verdicts(mapping):
    out = {}
    for name, e in mapping.get("globals", {}).items():
        out[f"global.{name}"] = e.get("verdict")
    for name, e in mapping.get("functions", {}).items():
        out[f"functions.{name}"] = e.get("verdict")
    for struct, s in mapping.get("structs", {}).items():
        for f, e in s.get("fields", {}).items():
            out[f"{struct}.{f}"] = e.get("verdict")
    return out


def diff(previous, current):
    """[(key, old, new, kind)] with kind in moved | new | gone | failed."""
    old, new = flatten(previous), flatten(current)
    changes = []
    for key in sorted(set(old) | set(new)):
        o, n = old.get(key), new.get(key)
        if key not in new:
            changes.append((key, o, None, "gone"))
        elif key not in old:
            changes.append((key, None, n, "new"))
        elif n is None and o is not None:
            changes.append((key, o, None, "failed"))
        elif o != n:
            changes.append((key, o, n, "moved"))
    return changes


# ---- report ------------------------------------------------------------------------------------------------------
def report(mapping, results, spec, notes, changes=None, previous=None):
    b = mapping["build"]
    lines = [f"# Native mappings for {b['sha256'][:16]} ({b.get('tag') or 'no tag'}, {b['timestamp']})", ""]
    s = mapping["summary"]
    lines.append("| " + " | ".join(ORDER) + " |")
    lines.append("|" + "---|" * len(ORDER))
    lines.append("| " + " | ".join(str(s[v]) for v in ORDER) + " |")
    lines.append("")
    caps = mapping["capabilities"]
    lines.append("## Capabilities")
    lines.append("")
    for name, ok in caps.items():
        lines.append(f"- {'✅' if ok['satisfied'] else '❌'} `{name}`" + ("" if ok["satisfied"] else f": missing {ok['missing']}"))
    lines.append("")
    if changes is not None:
        lines.append(f"## Diff against {previous['build']['sha256'][:16] if previous else 'previous'}")
        lines.append("")
        if not changes:
            lines.append("No value changed.")
        for key, o, n, kind in changes:
            lines.append(f"- `{key}`: {kind} {o} → {n}")
        lines.append("")
    specs = {k.key: k for k in spec.keys}
    lines.append("## Keys")
    lines.append("")
    lines.append("| key | pri | value | verdict | classes | evidence / notes |")
    lines.append("|---|---|---|---|---|---|")
    for r in sorted(results, key=lambda r: (ORDER.index(r.verdict), r.key)):
        k = specs.get(r.key)
        val = hx(r.value) if not isinstance(r.value, dict) else f"table[{len(r.value)}]"
        ev = next((e for c in r.candidates if c.value == r.value for e in c.evidence), None)
        where = f"`{hx(ev.get('insn'))}` {ev.get('text', '')}" if ev else ""
        notes_ = "; ".join(dict.fromkeys([c.note for c in r.candidates if c.note] + r.reasons))
        lines.append(f"| `{r.key}` | {k.priority if k else ''} | {val} | {r.verdict} | {''.join(sorted(r.classes))} | {where} {notes_} |".replace("\n", " "))
    lines.append("")
    if notes:
        lines.append("## Notes")
        lines.append("")
        lines.extend(f"- {n}" for n in notes)
        lines.append("")
    fps = mapping["fingerprints"]
    lines.append("## Fingerprints")
    lines.append("")
    for name, fp in sorted(fps.items()):
        lines.append(f"- `{name}`: {fp.get('rva')} {fp.get('status', '')} {fp.get('note', '')}")
    lines.append("")
    tables = {}
    for key, bnd in mapping["bindings"].items():
        tables.setdefault(key.rsplit(".", 1)[0], []).append((key.rsplit(".", 1)[1], bnd))
    lines.append("## Lua binding tables")
    lines.append("")
    for table, items in sorted(tables.items()):
        resolved = sum(1 for _, bnd in items if bnd["handler"] or bnd["member"])
        lines.append(f"- `{table}`: {resolved}/{len(items)} resolved; unresolved: {[n for n, bnd in items if not (bnd['handler'] or bnd['member'])]}")
    lines.append("")
    return "\n".join(lines)
