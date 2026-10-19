"""Generic strategies: small functions from (session, params) to candidates, driven entirely by the YAML spec.

  getter        offsets/globals read in a function, filtered by base role, size and selectors
  global-load   the RIP-relative load most handlers of a table start with (the Client pointer)
  hash-map      EASTL hash_map lookups/iterations recognised from a DIV by the bucket count
  const         the constant a load is compared against (enum values such as LOGGED_IN)
  member        the data-member offset a sol2 property binding was registered with
  binding       a binding handler's RVA (function keys)
  fingerprint   a fingerprinted function's RVA (function keys)
  enum-table    a whole {name: value} enum table
  extractor     one field of a custom extractor's output (see extractors.py)

Selectors for `getter` (all optional, combined with AND):
  nth, stored_to {arg, offset}, returned, passed_to {fn, arg, indirect}, compared_with N, used_in_cmp, used_as_base,
  used_as_index_base, indexed, index_scale N, deref_off N, combined_with {key}, cmp_with {arg | key}, index_uses {arg},
  min, max

  cmp_with {arg: N}      the value is compared directly with argument N (e.g. the plane checked against a level count)
  cmp_with {key: K}      ... with the field at value(K) of the same base, e.g. a vector's end compared with its begin
  index_uses {arg: N}    the value is the base pointer of an indexed load whose index involves argument N
"""
from . import roles
from .analysis.bindings import select_enum_table
from .analysis.provenance import Add, Arg, Bin, Const, Deref, Global, Many, Mul, Ret, Out, contains, walk
from .session import Blocked, CLASS_OF, Candidate

SIZES = {"i8": 1, "u8": 1, "i16": 2, "u16": 2, "i32": 4, "u32": 4, "f32": 4, "i64": 8, "u64": 8, "f64": 8, "ptr": 8, "any": 0}


def run(session, kind, params, key):
    fn = STRATEGIES.get(kind)
    if fn is None:
        raise ValueError(f"{key}: unknown strategy {kind}")
    return fn(session, params, key)


def _cand(session, value, kind, facts_or_evidence, note=""):
    ev = facts_or_evidence if isinstance(facts_or_evidence, list) else [facts_or_evidence]
    return Candidate(value, kind, CLASS_OF[kind], ev, note)


# ---- getter --------------------------------------------------------------------------------------------------
def getter(session, p, key):
    out = []
    for fn in session.fns(p["in"]):
        facts = session.prov.facts(fn)
        matches = _select(session, facts, p)
        for value, fact in matches:
            if p.get("kind") in ("global", "global_addr"):
                rva = value.rva if isinstance(value, Global) else value.base.rva
                out.append(_cand(session, rva, "getter", session.evidence(fact.rva, fact.fn, "rip")))
            elif p.get("kind") == "stride":
                factor = value.index.factor.n if isinstance(value.index, Mul) else 1
                out.append(_cand(session, value.scale * factor, "getter", session.evidence(fact.rva, fact.fn, "imm")))
            else:
                out.append(_cand(session, value.off, "getter", session.evidence(fact.rva, fact.fn, "disp")))
    if isinstance(p.get("in"), dict) and "string" in p["in"]:
        for c in out:
            c.cls, c.strategy = "B", "string-anchored"
    if isinstance(p.get("in"), str) and p["in"].endswith(".*") and out:
        # Majority vote over a table of handlers.
        best = max({c.value for c in out}, key=lambda v: sum(1 for c in out if c.value == v))
        agree = [c for c in out if c.value == best]
        return [Candidate(best, "getter", "A", agree[0].evidence, f"{len(agree)}/{len(out)} handlers agree")]
    return _dedupe(out)


def _select(session, facts, p):
    """(value, fact) pairs in a function matching the base role, kind, size and selectors."""
    kind = p.get("kind", "load")
    size = SIZES[p.get("load", p.get("size", "any"))] if kind in ("load", "global", "global_addr") else 0
    role = p.get("base", "any")
    sel = p.get("select", {})
    seen = {}
    for value, fact in _values(facts, kind):
        if kind == "global":            # a pointer-sized value loaded from the global
            ok = isinstance(value, Deref) and isinstance(value.base, Global) and value.off == 0 and value.index is None \
                and (size == 0 or value.size in (0, size))
        elif kind == "global_addr":     # data located at the global itself (LEA, or an indexed load off it)
            ok = isinstance(value, Global) or (isinstance(value, Deref) and isinstance(value.base, Global) and value.off == 0
                                                and (size == 0 or value.size in (0, size)))
        elif kind == "addr":
            ok = isinstance(value, Add) and roles.match(session, role, value.base)
        elif kind == "stride":          # element size of an indexed access: scale * constant factor of the index
            ok = isinstance(value, Deref) and value.index is not None and roles.match(session, role, value.base) \
                and (not isinstance(value.index, Mul) or isinstance(value.index.factor, Const))
        else:
            ok = (isinstance(value, Deref) and (size == 0 or value.size in (0, size)) and roles.match(session, role, value.base)
                  and (value.index is None or sel.get("indexed") or sel.get("index_scale")))
        if ok and _selectors_ok(session, facts, value, sel) and value not in seen:
            seen[value] = fact
    pairs = sorted(seen.items(), key=lambda kv: kv[1].rva)
    if "nth" in sel:
        return pairs[sel["nth"]:sel["nth"] + 1] if sel["nth"] < len(pairs) else []
    return pairs


def _values(facts, kind):
    if kind == "addr":
        for a in facts.addrs:
            for sub in walk(a.value):
                if isinstance(sub, Add):
                    yield sub, a
        for c in facts.calls:
            for arg in c.args:
                for sub in walk(arg):
                    if isinstance(sub, Add):
                        yield sub, c
        return
    for value, fact in facts.values():
        for sub in walk(value):
            if isinstance(sub, (Deref, Global)):
                yield sub, fact


def _selectors_ok(session, facts, v, sel):
    if "min" in sel and v.off < sel["min"]:
        return False
    if "max" in sel and v.off > sel["max"]:
        return False
    if sel.get("indexed") and getattr(v, "index", None) is None:
        return False
    if "index_scale" in sel and (getattr(v, "index", None) is None or v.scale != sel["index_scale"]):
        return False
    if "stored_to" in sel:
        t = sel["stored_to"]
        if not any(isinstance(s.target, Deref) and s.target.base == Arg(t.get("arg", 0)) and s.target.off == t.get("offset", 0)
                   and s.target.index is None and contains(s.value, v) for s in facts.stores):
            return False
    if sel.get("returned") and not any(contains(r.value, v) for r in facts.returns):
        return False
    if "passed_to" in sel:
        t = sel["passed_to"]
        target = session.fn(t["fn"]) if "fn" in t else None
        if not any((target is None or c.target == target) and (not t.get("indirect") or c.target is None)
                   and len(c.args) > t["arg"] and contains(c.args[t["arg"]], v) for c in facts.calls):
            return False
    if "compared_with" in sel:
        n = sel["compared_with"]
        if not any((_direct(c.a, v) and c.b == Const(n)) or (_direct(c.b, v) and c.a == Const(n)) for c in facts.cmps):
            return False
    if sel.get("used_in_cmp") and not any(_direct(c.a, v) or _direct(c.b, v) for c in facts.cmps):
        return False
    if sel.get("div_operand") and not any(_direct(d.a, v) for d in facts.divs):
        return False
    if sel.get("sentinel"):
        # An empty intrusive list: head->next == &head, i.e. [base+off] compared with base+off.
        if not (isinstance(v, Deref) and any((c.a == v and c.b == Add(v.base, v.off)) or (c.b == v and c.a == Add(v.base, v.off)) for c in facts.cmps)):
            return False
    if sel.get("used_as_base") or sel.get("used_as_index_base") or "deref_off" in sel:
        derefs = [d for d, _ in facts.derefs() if contains(d.base, v)]
        if sel.get("used_as_base") and not derefs:
            return False
        if sel.get("used_as_index_base") and not any(d.index is not None for d in derefs):
            return False
        if "deref_off" in sel and not any(d.off == sel["deref_off"] for d in derefs):
            return False
    if "cmp_with" in sel:
        t = sel["cmp_with"]
        if "arg" in t:
            other_ok = lambda o: o == Arg(t["arg"])
        else:
            other_off = session.value(t["key"])
            other_ok = lambda o: any(isinstance(x, Deref) and x.base == v.base and x.off == other_off and x.index is None
                                     and x.off != v.off for x in walk(o))
        if not any((_direct(c.a, v) and other_ok(c.b)) or (_direct(c.b, v) and other_ok(c.a)) for c in facts.cmps):
            return False
    if "index_uses" in sel:
        arg = Arg(sel["index_uses"]["arg"])
        if not any(d.index is not None and contains(d.base, v) and contains(d.index, arg) for d, _ in facts.derefs()):
            return False
    if "combined_with" in sel:
        other_off = session.value(sel["combined_with"]["key"])
        found = False
        for value, _ in facts.values():
            for sub in walk(value):
                if isinstance(sub, Bin) and contains(sub, v) and any(
                        isinstance(x, Deref) and x.off == other_off and x.base == v.base and x is not v for x in walk(sub)):
                    found = True
        if not found:
            return False
    return True


def _direct(operand, v):
    """v is the operand itself (or one alternative of a Many), not something nested inside it."""
    return operand == v or (isinstance(operand, Many) and v in operand.values)


def _dedupe(cands):
    out = {}
    for c in cands:
        if c.value in out:
            out[c.value].evidence.extend(e for e in c.evidence if e not in out[c.value].evidence)
        else:
            out[c.value] = c
    return list(out.values())


# ---- global-load -----------------------------------------------------------------------------------------------
def global_load(session, p, key):
    """The first RIP-relative load of the given size in each handler of a table; majority wins."""
    size = SIZES[p.get("size", "ptr")]
    votes = {}
    for fn in session.fns(p["in"]):
        for l in session.prov.facts(fn).loads:
            d = l.value
            if isinstance(d.base, Global) and d.off == 0 and d.index is None and d.size == size:
                votes.setdefault(d.base.rva, []).append(l)
                break
    if not votes:
        return []
    rva, ls = max(votes.items(), key=lambda kv: len(kv[1]))
    total = sum(len(v) for v in votes.values())
    return [Candidate(rva, "global-load", "A", [session.evidence(ls[0].rva, ls[0].fn, "rip")], f"{len(ls)}/{total} handlers agree")]


# ---- hash-map ---------------------------------------------------------------------------------------------------
class HashMap:
    def __init__(self, base, buckets, count, rva, fn):
        self.base, self.buckets, self.count, self.rva, self.fn = base, buckets, count, rva, fn
        self.node_key = self.node_next = self.node_value = None


def _loops_back(model, cmp_rva):
    """The conditional jump right after a compare targets an earlier address (a loop)."""
    if model is None:
        return True
    insn = model.insn(cmp_rva)
    nxt = model.insn(insn.fallthrough) if insn and insn.fallthrough else None
    return bool(nxt and nxt.is_cond_jump and nxt.target is not None and nxt.target < cmp_rva)


def recognize_hash_maps(facts, session_model=None):
    """EASTL hash_map lookups: DIV by [B + count]; load [[B + buckets] + idx*8]; node key at +0, next at +0x18."""
    maps = []
    for d, fact in facts.derefs():
        if d.index is not None and d.scale == 8 and isinstance(d.base, Deref) and d.base.size in (0, 8) and d.base.index is None:
            b = d.base
            # bucket count: a 4-byte load from the same base used as a DIV operand or in the modulo computation
            counts = [c for c, _ in facts.derefs() if isinstance(c, Deref) and c.base == b.base and c.index is None
                      and c.size in (4, 8) and c.off != b.off and 0 < c.off - b.off <= 16]
            if not counts:
                continue
            hm = HashMap(b.base, b.off, counts[0].off, fact.rva, fact.fn)
            for n, _ in facts.derefs():
                if isinstance(n.base, Deref) and n.base == d and n.index is None:
                    if n.size == 4 and n.off == 0:
                        hm.node_key = 0
                    elif n.size in (0, 8):
                        # The chain pointer is the node field tested against itself and followed by a backward jump
                        # (the loop over the bucket chain); the control-block pointer is tested too, but forwards.
                        if any(c.a == n and c.b == n and _loops_back(session_model, c.rva) for c in facts.cmps):
                            hm.node_next = n.off
                        elif n.off == 0x10:
                            hm.node_value = 0x10
            if not any(m.base == hm.base and m.buckets == hm.buckets for m in maps):
                maps.append(hm)
    return maps


def hash_map(session, p, key):
    out = []
    for fn in session.fns(p["in"]):
        facts = session.prov.facts(fn)
        maps = [m for m in recognize_hash_maps(facts, session.model) if roles.match(session, p.get("base", "any"), m.base)]
        maps.sort(key=lambda m: m.rva)
        if "nth" in p:
            maps = maps[p["nth"]:p["nth"] + 1]
        for m in maps:
            part = p.get("part", "map")
            value = {"map": m.buckets - 8, "buckets": m.buckets, "count": m.count, "node_key": m.node_key,
                     "node_next": m.node_next, "node_value": m.node_value,
                     "base_off": m.base.off if isinstance(m.base, Deref) else None}[part]
            if value is None:
                continue
            out.append(_cand(session, value, "hash-map", session.evidence(m.rva, m.fn, "disp"), f"buckets +{m.buckets:#x}, count +{m.count:#x}"))
    return _dedupe(out)


# ---- const --------------------------------------------------------------------------------------------------------
def const(session, p, key):
    """The constant a matching load is compared with: CMP dword [client + gameState], 30 -> 30."""
    out = []
    size = SIZES[p.get("load", "any")]
    for fn in session.fns(p["in"]):
        facts = session.prov.facts(fn)
        for c in facts.cmps:
            for a, b in ((c.a, c.b), (c.b, c.a)):
                if not isinstance(b, Const):
                    continue
                if "arg" in p:
                    if a == Arg(p["arg"]):
                        out.append(_cand(session, b.n, "const", session.evidence(c.rva, c.fn, "imm")))
                    continue
                if isinstance(a, Deref) and (size == 0 or a.size == size) and roles.match(session, p.get("base", "any"), a.base) \
                        and ("offset_of" not in p or a.off == session.value(p["offset_of"])):
                    out.append(_cand(session, b.n, "const", session.evidence(c.rva, c.fn, "imm")))
    return _dedupe(out)


# ---- bindings, fingerprints, enums, members ---------------------------------------------------------------------------
def member(session, p, key):
    b = session.bindings.get(p["in"])
    if b is None:
        raise Blocked(f"binding {p['in']} not found")
    if b.member_offset is None:
        return []
    return [_cand(session, b.member_offset, "member", session.evidence(b.name_insn, b.register_fn, "imm"))]


def binding(session, p, key):
    b = session.bindings.get(p["in"])
    if b is None or b.handler is None:
        raise Blocked(f"binding {p['in']} not found")
    return [_cand(session, b.handler, "binding", session.evidence(b.name_insn, b.register_fn, "rip"), b.kind)]


def fingerprint(session, p, key):
    rva = session.fn("@" + p["name"])
    ev = session.fp_evidence.get(p["name"], {})
    return [Candidate(rva, "fingerprint", ev.get("cls", "E"), ev.get("evidence", []), ev.get("note", ""))]


def enum_table(session, p, key):
    t = select_enum_table(session.enum_tables, p["match"])
    if t is None:
        return []
    return [Candidate(dict(t.entries), "enum-table", "A", [{"insn": t.rva, "fn": None, "text": f"{len(t.entries)} records", "slot": "data"}])]


def extractor(session, p, key):
    from . import extractors
    result = extractors.run(session, p["name"], p)
    field = p.get("field", key)
    if field not in result:
        return []
    value, evidence, note = result[field]
    return [Candidate(value, "extractor", p.get("cls", "E"), evidence, note)]


def derive(session, p, key):
    """Arithmetic over other keys, e.g. the string field offset from its SSO flag byte: 'Actor.nameSsoFlag - 23'."""
    from .verify import evaluate
    value = evaluate(p["expr"], session.values)
    if value is None:
        raise Blocked(f"inputs of {p['expr']!r} unresolved")
    inputs = [k for k in session.values if k in p["expr"]]
    ev = [e for k in inputs for c in session.candidates.get(k, []) if c.value == session.values[k] for e in c.evidence][:2]
    return [Candidate(value, "derive", p.get("cls", "A"), ev, f"= {p['expr']}")]


STRATEGIES = {
    "getter": getter, "global-load": global_load, "hash-map": hash_map, "const": const, "member": member,
    "binding": binding, "fingerprint": fingerprint, "enum-table": enum_table, "extractor": extractor, "derive": derive,
}
