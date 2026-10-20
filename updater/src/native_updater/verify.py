"""Static verification: type/alignment, relational invariants, uniqueness within a struct.

Invariant forms (in the spec):
  - equals: "World.npcs + 8"                 expression over other keys, hex/decimal ints, + - *
  - adjacent: { key: K, delta: -1 }          value(K) == value - delta  (K sits `delta` bytes before this key)
  - range: { min: 0, max: 0x10000 }
  - align: 4
  - less_than: Projectile.size
"""
import re

ALIGN = {"i16": 2, "u16": 2, "i32": 4, "u32": 4, "f32": 4, "i64": 8, "u64": 8, "f64": 8}
_TOKEN = re.compile(r"\s*(0x[0-9a-fA-F]+|\d+|[A-Za-z_][\w.]*|[-+*()])")


def check_all(spec, values):
    """{key: [failure, ...]} for every key whose invariants or type checks fail. `values` is key -> value."""
    failures = {}
    for k in spec.keys:
        v = values.get(k.key)
        if v is None:
            continue
        probs = []
        if isinstance(v, int):
            a = ALIGN.get(k.type.split("[")[0])
            if a and v % a:
                probs.append(f"offset {v:#x} not aligned to {a} for type {k.type}")
            if k.struct and (v < 0 or v > 0x1000000):
                probs.append(f"offset {v:#x} outside any plausible struct")
            if k.type.startswith("ptr") and not k.struct and not k.key.startswith("global.") and v % 8:
                probs.append("pointer not 8-aligned")
        for inv in k.invariants:
            (kind, arg), = inv.items()
            try:
                if kind == "equals":
                    want = evaluate(arg, values)
                    if want is not None and want != v:
                        probs.append(f"expected {arg} = {want:#x}, got {v:#x}")
                elif kind == "adjacent":
                    other = values.get(arg["key"])
                    if other is not None and other != v + arg["delta"]:
                        probs.append(f"{arg['key']} is {other:#x}, expected {v + arg['delta']:#x}")
                elif kind == "range":
                    if not arg.get("min", v) <= v <= arg.get("max", v):
                        probs.append(f"{v:#x} outside [{arg.get('min')}, {arg.get('max')}]")
                elif kind == "align":
                    if v % arg:
                        probs.append(f"{v:#x} not aligned to {arg}")
                elif kind == "less_than":
                    lim = values.get(arg)
                    if lim is not None and v >= lim:
                        probs.append(f"{v:#x} >= {arg} ({lim:#x})")
                elif kind == "member_of":
                    members = values.get(arg)
                    if isinstance(members, list) and v not in members:
                        probs.append(f"{v:#x} not among {arg} {[hex(m) for m in members]}")
                elif kind == "first_fn_equals":          # an events.* hook list whose first site is the named function
                    want = values.get(arg)
                    got = v[0].get("fn") if isinstance(v, list) and v and isinstance(v[0], dict) else None
                    if want is not None and got != want:
                        probs.append(f"first hook site is {got}, expected {arg} = {want:#x}")
            except (TypeError, KeyError) as e:
                probs.append(f"invariant {inv} not evaluable: {e}")
        if probs:
            failures[k.key] = probs
    # Fields of one struct must not share an offset (unless marked alias).
    by_struct = {}
    for k in spec.keys:
        v = values.get(k.key)
        if k.struct and isinstance(v, int) and not k.alias and k.type != "const":
            by_struct.setdefault(k.struct, {}).setdefault(v, []).append(k.key)
    for struct, offs in by_struct.items():
        for off, keys in offs.items():
            if len(keys) > 1:
                for key in keys:
                    failures.setdefault(key, []).append(f"shares offset {off:#x} with {[x for x in keys if x != key]}")
    return failures


def evaluate(expr, values):
    """Evaluate a tiny arithmetic expression over key names. Returns None if a key is unresolved."""
    tokens = _TOKEN.findall(expr)
    out = []
    for t in tokens:
        if re.match(r"^[A-Za-z_]", t):
            if t not in values or not isinstance(values[t], int):
                return None
            out.append(str(values[t]))
        else:
            out.append(t)
    return int(eval("".join(out), {"__builtins__": {}}, {}))
