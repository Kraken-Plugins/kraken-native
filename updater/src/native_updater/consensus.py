"""Grade every key from its candidates (design §4.6).

  PROVEN        >= 2 strategies of different anchor classes agree and the invariants hold
  ANCHORED      one class A/B/C strategy, invariants hold, no disagreement
  INFERRED      only D-H strategies (ctor signatures, topology, carry-forward, delta propagation)
  NEEDS_REVIEW  strategies disagree, or an invariant fails
  FAILED        no candidate
"""
from dataclasses import dataclass, field

from .session import STRONG_CLASSES

ORDER = ["FAILED", "NEEDS_REVIEW", "INFERRED", "ANCHORED", "PROVEN"]


@dataclass
class Result:
    key: str
    value: object = None
    verdict: str = "FAILED"
    candidates: list = field(default_factory=list)
    classes: set = field(default_factory=set)
    reasons: list = field(default_factory=list)

    @property
    def ok(self):
        return self.verdict in ("PROVEN", "ANCHORED")


def grade(key, candidates, blocked_reason=None, invariant_failures=()):
    r = Result(key, candidates=list(candidates))
    if not candidates:
        r.reasons.append(f"blocked by {blocked_reason}" if blocked_reason else "no candidate")
        return r
    groups = {}
    for c in candidates:
        groups.setdefault(_hashable(c.value), []).append(c)
    best_key, best = max(groups.items(), key=lambda kv: (len({c.cls for c in kv[1]}), len(kv[1])))
    r.value = best[0].value
    r.classes = {c.cls for c in best}
    if len(groups) > 1:
        r.verdict = "NEEDS_REVIEW"
        others = {k: [c.strategy for c in v] for k, v in groups.items() if k != best_key}
        r.reasons.append(f"strategies disagree: {_fmt(r.value)} from {[c.strategy for c in best]} vs {others}")
    elif invariant_failures:
        r.verdict = "NEEDS_REVIEW"
        r.reasons.extend(invariant_failures)
    elif len(r.classes) >= 2:
        r.verdict = "PROVEN"
    elif r.classes & STRONG_CLASSES:
        r.verdict = "ANCHORED"
    else:
        r.verdict = "INFERRED"
    return r


def _hashable(v):
    if isinstance(v, dict):
        return tuple(sorted((k, _hashable(x)) for k, x in v.items()))
    if isinstance(v, list):
        return tuple(_hashable(x) for x in v)
    return v


def _fmt(v):
    if isinstance(v, int):
        return hex(v)
    if isinstance(v, dict):
        return f"table[{len(v)}]"
    return str(v)


def exit_code(results, key_specs):
    """0: every P0 key PROVEN/ANCHORED. 2: something FAILED or NEEDS_REVIEW (or a P0 key only INFERRED)."""
    by_key = {r.key: r for r in results}
    code = 0
    for k in key_specs:
        r = by_key.get(k.key)
        if r is None:
            continue
        if r.verdict in ("FAILED", "NEEDS_REVIEW") or (k.priority == "P0" and not r.ok):
            code = 2
    return code
