"""The pull request description for a new mapping (`native-updater report`).

It names the build and the one it was carried forward from, compares their verdicts, lists the keys left out of the
mapping or only inferred and the keys whose verdict changed, and shows what moved: struct fields as bands of equal
shift (a field that moved differently from the fields around it is worth a look), then every changed value.
"""
from datetime import datetime, timezone

from . import emit
from .consensus import ORDER

# GitHub rejects a pull request description longer than 65536 characters; leave room for what the workflow adds.
MAX_BODY = 60000

MEANING = {
    "PROVEN": "two kinds of evidence agree and the invariants hold",
    "ANCHORED": "one strong anchor (a Lua binding, a diagnostic string or a cache constant) and the invariants hold",
    "INFERRED": "only weaker evidence: constructor defaults, the call graph, carry-forward or delta propagation",
    "NEEDS_REVIEW": "strategies disagree or an invariant failed, so the key is left out of the mapping",
    "FAILED": "no candidate, so the key is left out of the mapping",
}
CLASSES = {"A": "Lua binding", "B": "diagnostic string", "C": "cache constant", "D": "constructor default",
           "E": "call graph", "F": "carry-forward", "G": "delta propagation"}
LEFT_OUT = ("FAILED", "NEEDS_REVIEW")
SECTIONS = (("global.", "Globals"), ("functions.", "Functions"), ("events.", "Events"), ("enums.", "Enums"),
            ("container.", "Containers"))


def render(mapping, analysis, previous=None, previous_analysis=None, spec=None, exit_code=None, release=None,
           run_url=None, commit=None, mappings_dir="api/src/main/resources/mappings"):
    """The description as Markdown. previous and previous_analysis are the build carried forward from (either may be
    None), spec gives key priorities, release is what `native-updater fetch` printed for the build."""
    version = mapping["version"]
    prev_version = previous["version"] if previous else None
    priority = {k.key: k.priority for k in spec.keys} if spec else {}
    keys = analysis.get("keys", {})
    prev_keys = (previous_analysis or {}).get("keys", {})

    out = [f"Maps native client **{version}** (`osclient.exe` {_short(mapping['sha256'])}) into "
           f"`{mappings_dir}/{version}.json`"
           + (f", carried forward from {prev_version}." if prev_version else ".")]
    out += [""] + _build_table(mapping, analysis, previous, previous_analysis, release) + [""]
    out += _status(analysis, exit_code, previous)

    out += ["## Verdicts", "", "| Verdict | Meaning | " + (f"{prev_version} | " if previous_analysis else "") + f"{version} |",
            "|---|---|" + ("---:|" if previous_analysis else "") + "---:|"]
    now, before = analysis.get("verdicts", {}), (previous_analysis or {}).get("verdicts", {})
    for v in reversed(ORDER):
        out.append(f"| {v} | {MEANING[v]} | " + (f"{before.get(v, 0)} | " if previous_analysis else "") + f"{now.get(v, 0)} |")
    out += ["", "Evidence classes: " + ", ".join(f"{c} {name}" for c, name in CLASSES.items()) + ".", ""]

    left = sorted((k for k, e in keys.items() if e["verdict"] in LEFT_OUT), key=lambda k: (ORDER.index(keys[k]["verdict"]), k))
    if left:
        out += [f"## Left out of the mapping ({len(left)})", "", f"| Key | Priority | Verdict | {prev_version or 'Previous'} | Why |",
                "|---|---|---|---|---|"]
        for k in left:
            e, p = keys[k], prev_keys.get(k)
            was = f"{p['verdict']} {_cell(p.get('value'))}" if p else "_not in the previous analysis_"
            out.append(f"| `{k}` | {priority.get(k, '')} | {e['verdict']} | {was} | {_text('; '.join(e.get('reasons', [])))} |")
        out.append("")

    inferred = sorted(k for k, e in keys.items() if e["verdict"] == "INFERRED")
    if inferred:
        out += [f"## Only inferred ({len(inferred)})", "",
                "Written to the mapping, but backed only by weaker evidence. A P0 key here makes the run exit 2.", "",
                "| Key | Priority | Classes | Value | Note |", "|---|---|---|---|---|"]
        for k in inferred:
            e = keys[k]
            out.append(f"| `{k}` | {priority.get(k, '')} | {_classes(e.get('classes', ''))} | {_cell(e.get('value'))} | {_text(e.get('note', ''))} |")
        out.append("")

    if previous_analysis:
        out += _verdict_changes(keys, prev_keys, prev_version, version)

    changes = emit.diff(previous, mapping) if previous else None
    if changes is not None:
        old, new = emit.flatten(previous), emit.flatten(mapping)
        out += _what_moved(changes, old, new, prev_version, version)

    carry = analysis.get("carryForward")
    if carry:
        out += ["## Carry-forward", ""]
        if carry.get("previousModel"):
            out.append(f"Matched {carry.get('matchedFunctions', 0)} functions with {carry.get('previous')} and re-extracted "
                       f"{carry.get('carried', 0)} values at the matching instructions; delta propagation added "
                       f"{carry.get('propagated', 0)}.")
        else:
            out.append(f"The model of {carry.get('previous')} was not available, so only delta propagation ran "
                       f"({carry.get('propagated', 0)} values).")
        if carry.get("repairBundle"):
            out.append("Repair bundles for the keys left out are in the run's artifact (`repair/`).")
        out.append("")

    notes = analysis.get("notes") or []
    unmatched = {n: f for n, f in (analysis.get("fingerprints") or {}).items() if f.get("status") not in (None, "MATCHED")}
    if notes or unmatched:
        out += ["## Notes from the run", ""]
        out += [f"- {_text(n)}" for n in notes]
        out += [f"- Fingerprint `{n}` {f.get('status')}: {_text(f.get('note', ''))}" for n, f in sorted(unmatched.items())]
        out.append("")

    out += _checklist(version, prev_version, release, mappings_dir)
    footer = "Written by the native updater" + (f" in [this run]({run_url})" if run_url else "") + \
             (f" at `{commit[:12]}`" if commit else "") + "."
    out += ["", footer]
    return _fit("\n".join(out) + "\n")


def struct_bands(old, new):
    """{struct: [(delta, first old offset, last old offset, field count)]}: runs of fields that shifted by the same
    amount, in old offset order, over the struct fields both flattened mappings hold as offsets."""
    fields = {}
    for key, o in old.items():
        n = new.get(key)
        head = key.split(".", 1)[0]
        if any(key.startswith(p) for p, _ in SECTIONS) or not (_is_hex(o) and _is_hex(n)):
            continue
        fields.setdefault(head, []).append((int(o, 16), int(n, 16) - int(o, 16), key))
    bands = {}
    for struct, rows in fields.items():
        runs = []
        for off, delta, _ in sorted(rows):
            if runs and runs[-1][0] == delta:
                d, first, _, count = runs[-1]
                runs[-1] = (d, first, off, count + 1)
            else:
                runs.append((delta, off, off, 1))
        bands[struct] = runs
    return bands


def outliers(old, new):
    """[(key, old, new, delta, neighbours' delta)] for struct fields that moved differently from the fields on both
    sides of them, which moved together."""
    out = []
    by_struct = {}
    for key, o in old.items():
        n = new.get(key)
        if any(key.startswith(p) for p, _ in SECTIONS) or not (_is_hex(o) and _is_hex(n)):
            continue
        by_struct.setdefault(key.split(".", 1)[0], []).append((int(o, 16), int(n, 16) - int(o, 16), key, o, n))
    for rows in by_struct.values():
        rows.sort()
        for i in range(1, len(rows) - 1):
            before, here, after = rows[i - 1][1], rows[i][1], rows[i + 1][1]
            if before == after and here != before:
                _, delta, key, o, n = rows[i]
                out.append((key, o, n, delta, before))
    return sorted(out)


def _build_table(mapping, analysis, previous, previous_analysis, release):
    prev = previous or {}
    head = f"| | {prev.get('version', 'Previous')} | {mapping['version']} |" if previous else f"| | {mapping['version']} |"
    rows = [head, "|---|---|---|" if previous else "|---|---|"]

    def row(label, before, after):
        rows.append(f"| {label} | {before} | {after} |" if previous else f"| {label} | {after} |")

    row("SHA-256", f"`{_short(prev.get('sha256', ''))}`", f"`{_short(mapping['sha256'])}`")
    row("Linked", _date((previous_analysis or {}).get("linked")), _date(analysis.get("linked")))
    row("Keys in the mapping", len(emit.flatten(prev)) if previous else "", len(emit.flatten(mapping)))
    row("Ghidra", (previous_analysis or {}).get("ghidra") or "unknown", analysis.get("ghidra") or "unknown")
    if release:
        where = f" (`osrs-win.{release['environment']}`)" if release.get("environment") else ""
        row("Jagex CDN", "", f"catalog `{_short(release.get('catalog', ''))}`{where}")
        if release.get("scanTime"):
            row("Packaged by Jagex", "", _date(release["scanTime"]))
    return rows


def _status(analysis, exit_code, previous):
    out = []
    if exit_code == 2:
        out += ["> [!WARNING]",
                "> The updater exited 2: a key FAILED or NEEDS_REVIEW (and is left out of the mapping) or a P0 key is "
                "only INFERRED, so this mapping needs review before it is merged. The keys are listed below.", ""]
    elif exit_code == 0:
        out += ["The updater exited 0: no key failed or needs review, and every P0 key is PROVEN or ANCHORED.", ""]
    if previous and not (analysis.get("carryForward") or {}).get("previousModel"):
        out += ["> [!NOTE]",
                f"> The previous build's model and analysis were not in the build cache, so carry-forward from "
                f"{previous['version']} ran as delta propagation only. Fewer keys reach PROVEN, and keys that only "
                "carry-forward finds are missing.", ""]
    return out


def _verdict_changes(keys, prev_keys, prev_version, version):
    weaker, stronger, added, gone = [], [], [], []
    for k in sorted(set(keys) | set(prev_keys)):
        e, p = keys.get(k), prev_keys.get(k)
        if p is None:
            added.append(k)
        elif e is None:
            gone.append(k)
        elif e["verdict"] != p["verdict"]:
            row = (k, p["verdict"], e["verdict"], p.get("classes", ""), e.get("classes", ""))
            (weaker if ORDER.index(e["verdict"]) < ORDER.index(p["verdict"]) else stronger).append(row)
    if not (weaker or stronger or added or gone):
        return ["## Verdict changes", "", f"Every key kept its verdict from {prev_version}.", ""]
    out = ["## Verdict changes", ""]
    for title, rows in ((f"Weaker than in {prev_version}", weaker), (f"Stronger than in {prev_version}", stronger)):
        if rows:
            out += [f"{title} ({len(rows)}):", "", f"| Key | {prev_version} | {version} |", "|---|---|---|"]
            out += [f"| `{k}` | {b} ({_classes(cb)}) | {a} ({_classes(ca)}) |" for k, b, a, cb, ca in rows]
            out.append("")
    if added:
        out += [f"New keys ({len(added)}): " + ", ".join(f"`{k}`" for k in added), ""]
    if gone:
        out += [f"Keys no longer in the spec ({len(gone)}): " + ", ".join(f"`{k}`" for k in gone), ""]
    return out


def _what_moved(changes, old, new, prev_version, version):
    if not changes:
        return ["## What moved", "", f"Every value is the same as in {prev_version}.", ""]
    counts = {}
    for key, _, _, kind in changes:
        counts.setdefault(_section(key), {"moved": 0, "new": 0, "gone": 0})[kind] += 1
    out = ["## What moved", "", f"{len(changes)} values differ from {prev_version}.", "",
           "| Section | Moved | New | Gone |", "|---|---:|---:|---:|"]
    for section in sorted(counts, key=lambda s: (s not in dict(SECTIONS).values(), s)):
        c = counts[section]
        out.append(f"| {section} | {c['moved']} | {c['new']} | {c['gone']} |")
    out.append("")

    bands = {s: runs for s, runs in struct_bands(old, new).items() if any(d for d, *_ in runs)}
    if bands:
        out += ["Struct fields by shift, in old offset order:", "", "| Struct | Shifts |", "|---|---|"]
        for struct in sorted(bands):
            parts = [f"{_delta(d)} for {first:#x}" + (f" to {last:#x}" if last != first else "") + f" ({n})"
                     for d, first, last, n in bands[struct]]
            out.append(f"| {struct} | {'; '.join(parts)} |")
        out.append("")
    odd = outliers(old, new)
    if odd:
        out += ["Fields that moved differently from the fields on both sides of them. Line up the function their "
                "evidence names in both builds before merging:", ""]
        out += [f"- `{k}`: {o} to {n} ({_delta(d)}, its neighbours {_delta(nd)})" for k, o, n, d, nd in odd]
        out.append("")

    out += ["<details>", f"<summary>All {len(changes)} changed values</summary>", "",
            f"| Key | {prev_version} | {version} | Change |", "|---|---|---|---|"]
    out += [f"| `{k}` | {_cell(o)} | {_cell(n)} | {kind} |" for k, o, n, kind in changes]
    out += ["", "</details>", ""]
    return out


def _checklist(version, prev_version, release, mappings_dir):
    rel = "../" + mappings_dir
    catalog = (release or {}).get("catalog")
    out = ["## Before merging", "",
           "- [ ] Run the live probe from Windows against a logged-in client: "
           f"`uv run native-updater probe --mapping {rel}/{version}.json --display-name <name>`",
           "- [ ] Look over any keys left out, only inferred, weaker or moving differently from their neighbours above.",
           "", "To reproduce the run from `updater/`:", "", "```bash"]
    previous = f"{rel}/{prev_version}.json"
    if prev_version == version:
        # a rebuild of the same version: this branch replaces the file, so the previous mapping is master's
        previous = "<scratch>/previous.json"
        out.append(f"git show origin/master:{mappings_dir}/{version}.json > {previous}")
    out.append("uv run native-updater fetch " + (f"--catalog {catalog} " if catalog else "") + "--dest <scratch>")
    out.append("uv run native-updater run --binary <scratch>/osclient.exe "
               + (f"--previous {previous} " if prev_version else "") + "--out <scratch>")
    out.append("```")
    return out


def _fit(body):
    """Drop rows from the end of the full change table until the description fits GitHub's limit."""
    if len(body) <= MAX_BODY:
        return body
    lines = body.split("\n")
    start = next((i for i, l in enumerate(lines) if l.startswith("<summary>All ")), None)
    end = next((i for i, l in enumerate(lines) if l == "</details>"), None)
    if start is not None and end is not None:
        # lines[start + 2] is the header, lines[start + 3] the separator, the rows run to lines[end - 2]
        first, last = start + 4, end - 2
        excess, cut = len(body) - (MAX_BODY - 120), last + 1
        while cut > first and excess > 0:
            cut -= 1
            excess -= len(lines[cut]) + 1
        lines[cut:last + 1] = [f"| _{last + 1 - cut} more rows are in the run's artifact_ | | | |"]
        body = "\n".join(lines)
    if len(body) > MAX_BODY:
        body = body[:MAX_BODY - 40] + "\n\n_(description cut to fit)_\n"
    return body


def _section(key):
    for prefix, name in SECTIONS:
        if key.startswith(prefix):
            return name
    return "Struct " + key.split(".", 1)[0]


def _is_hex(v):
    return isinstance(v, str) and v.startswith("0x")


def _delta(d):
    return "0" if d == 0 else f"{'+' if d > 0 else '-'}{abs(d):#x}"


def _classes(c):
    return c or "none"


def _short(sha):
    return sha[:16] if sha else "unknown"


def _date(v):
    if v in (None, ""):
        return "unknown"
    if isinstance(v, (int, float)):
        return datetime.fromtimestamp(v, timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    return str(v).replace("T", " ").replace("Z", " UTC")


def _cell(v):
    if v is None:
        return "_absent_"
    if isinstance(v, list) and v and all(isinstance(s, dict) and "fn" in s for s in v):
        text = ", ".join(str(s["fn"]) for s in v)
    else:
        text = str(v)
    return "`" + text.replace("|", "\\|").replace("`", "'") + "`"


def _text(s):
    return str(s).replace("|", "\\|").replace("\n", " ")
