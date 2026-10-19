"""Fingerprints locate functions that are not Lua handlers. Each kind is a small rule over the model:

  callee_of:   {callee_of: REF, imm: N, min_args: N, calls: REF, arg0: ROLE}   a callee of REF with extra filters
  common_callee_of: [REF, ...]                                                 the callee shared by all of them
  caller_of:   {caller_of: REF, ...filters}                                    a caller of REF
  binding:     {binding: "Table.name"}                                         a handler (for function keys)
  extractor:   {extractor: name}                                               custom logic in extractors.py
Filters: imm (an immediate used in the function), min_args (call site passes at least N arguments),
calls (the function calls REF), string (references the exact string), refs_global (reads or addresses the global
a resolved key names, e.g. global.varps), unique (fail when several match).
"""
from .analysis.provenance import Const, Arg
from .session import Blocked
from . import roles


LEAF_HELPER_INSNS = 12


def run_all(session):
    """Resolve every fingerprint not yet matched. Called once per key round, because some rules depend on
    resolved keys (for example `arg0: {addr: client}` needs global.client)."""
    pending = {n: r for n, r in session.spec.fingerprints.items() if session.fingerprints.get(n) is None}
    for _ in range(6):
        progressed = False
        for name, rule in list(pending.items()):
            try:
                rva, evidence = resolve(session, name, rule)
            except Blocked as b:
                session.fingerprints[name] = None
                session.fp_evidence[name] = {"status": "BLOCKED", "note": f"blocked by {b.what}"}
                continue
            session.fingerprints[name] = rva
            session.fp_evidence[name] = evidence
            if rva is not None:
                del pending[name]
                progressed = True
        if not pending or not progressed:
            break


def resolve(session, name, rule):
    if "binding" in rule:
        rva = session.fn(rule["binding"])
        return rva, {"status": "MATCHED", "cls": "A", "evidence": [session.evidence(rva, rva, "rip")], "note": rule["binding"]}
    if "extractor" in rule:
        from . import extractors
        out = extractors.run(session, rule["extractor"], rule)
        key = rule.get("field", name)
        if key not in out:
            return None, {"status": "NOT_FOUND", "note": f"extractor {rule['extractor']} produced nothing for {key}"}
        value, evidence, note = out[key]
        return value, {"status": "MATCHED", "cls": rule.get("cls", "E"), "evidence": evidence, "note": note}
    if "common_callee_of" in rule:
        sets = [set(_callees(session, session.fn(ref))) for ref in rule["common_callee_of"]]
        common = set.intersection(*sets) if sets else set()
        common = {c for c in common if _filters_ok(session, c, rule, None) and len(session.model.insns(c)) > LEAF_HELPER_INSNS}
        return _pick(session, name, common, rule)
    if "callee_of" in rule:
        fn = session.fn(rule["callee_of"])
        found = {}
        if rule.get("include_self") and _filters_ok(session, fn, rule, None):
            found[fn] = fn
        for call in session.prov.facts(fn).calls:
            if call.target is None:
                continue
            if "min_args" in rule and len(call.args) < rule["min_args"]:
                continue
            if "arg0" in rule and not roles.match(session, rule["arg0"], call.args[0]):
                continue
            if _filters_ok(session, call.target, rule, call):
                found.setdefault(call.target, call.rva)
        return _pick(session, name, found, rule)
    if "caller_of" in rule:
        fn = session.fn(rule["caller_of"])
        found = {c for c in session.model.callers(fn) if _filters_ok(session, c, rule, None)}
        return _pick(session, name, found, rule)
    raise ValueError(f"fingerprint {name}: unknown rule {rule}")


def _callees(session, fn):
    return [c.target for c in session.prov.facts(fn).calls if c.target is not None]


def _filters_ok(session, target, rule, call):
    model = session.model
    if "imm" in rule and not any(fn == target for _, fn in model.insns_with_imm(rule["imm"])):
        return False
    if "string" in rule and not any(fn == target for _, fn in model.string_refs(rule["string"])):
        return False
    if "calls" in rule:
        want = session.fn(rule["calls"])
        if want not in _callees(session, target):
            return False
    if "refs_global" in rule:
        rva = session.value(rule["refs_global"])        # Blocked until that key resolves; retried next round
        if not any(fn == target for _, fn in model.insns_with_disp(rva)):
            return False
    if "min_size" in rule:
        f = model.function(target)
        if f is None or f.size < rule["min_size"]:
            return False
    return True


def _pick(session, name, found, rule):
    found = sorted(found) if not isinstance(found, dict) else sorted(found)
    if not found:
        return None, {"status": "NOT_FOUND", "note": f"no function matches {rule}"}
    if len(found) > 1 and rule.get("unique", True):
        return None, {"status": "AMBIGUOUS", "note": f"{len(found)} functions match: {[hex(f) for f in found]}"}
    rva = found[0]
    # A rule rooted in a Lua binding inherits that class A anchor; one rooted in another fingerprint inherits its class.
    root = rule.get("callee_of") or rule.get("caller_of") or (rule.get("common_callee_of") or [""])[0]
    if isinstance(root, str) and root.startswith("@"):
        cls = session.fp_evidence.get(root[1:], {}).get("cls", "E")
    elif isinstance(root, str) and root:
        cls = "A"
    else:
        cls = "E"
    if "imm" in rule and cls == "E":
        cls = "C"
    return rva, {"status": "MATCHED", "cls": cls, "evidence": [session.evidence(rva, rva, "rip")], "note": f"{len(found)} match; rule {rule}"}
