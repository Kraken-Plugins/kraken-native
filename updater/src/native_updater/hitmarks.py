"""The hitmarks (hitsplats) an actor shows, for HitsplatApplied (docs/event-hooks-design.md §2.8).

The NPC and player update decoders hand each hitmark of an update to one function, (actor, type, value, delay, cycle,
a count from the packet), the cycle being the client cycle. The actor holds its hitmarks in an eastl::vector of
fixed-size entries, {type, value, end cycle}, reused in place: the function writes the new hitmark over the first
entry that has ended, appends one while there is room, or else replaces the entry the hitmark type's rule picks (or
none). It builds the entry with a small constructor, (entry, type, value, cycle + delay + the type's duration), then
copies it into the vector.

  actor_hitmarks   functions.addHitmark: a function the NPC update decoder (or one of its callees) calls with
                   global.cycle as the fifth argument, which stores into the entries of a vector on its first argument
                   (an index scaled by the entry size off [Arg0 + V]) and builds an entry from its second and third
                   arguments and a sum holding its fifth.
                   Actor.hitmarks / hitmarksEnd: V and V + 8, the vector's begin and end. Hitmark.size: the scale.
                   Hitmark.{type, value, endCycle}: where the entry constructor stores its second, third and fourth
                   arguments.

Output keys: functions.addHitmark (internal), Actor.hitmarks, Actor.hitmarksEnd, Hitmark.{type, value, endCycle, size}.
"""
from .analysis.provenance import Arg, Const, Deref, Global, Mul, contains

DEPTH = 2                   # calls from the NPC update decoder down to the mask decoder adding hitmarks
VECTOR_END = 8              # an eastl::vector's end pointer follows its begin pointer


def actor_hitmarks(session, p):
    model = session.model
    cycle = Deref(Global(session.value("global.cycle")), 0, 4)
    decoder = session.value("functions.npcInfoDecode")
    found = {}
    for fn in [decoder] + _below(model, decoder, DEPTH):
        for call in session.prov.facts(fn).calls:
            if call.target is None or len(call.args) < 5 or call.args[4] != cycle:
                continue
            target = model.resolve(call.target)
            if target not in found:
                layout = hitmark_layout(session, target)
                if layout is not None:
                    found[target] = (layout, call)
    if len(found) != 1:
        session.notes.append(f"hitmarks: {len(found)} functions under the NPC update decoder take the client cycle "
                             f"and fill a vector of hitmarks")
        return {}
    add, (layout, call) = next(iter(found.items()))
    callers = model.callers(add)
    out = {"functions.addHitmark": (add, [session.evidence(call.rva, call.fn, "rip")],
                                    f"called with global.cycle as its fifth argument from {len(callers)} functions "
                                    "(the NPC and player update masks); fills a vector of hitmarks on the actor")}
    vector, stride, store, entry, ctor = layout
    out["Actor.hitmarks"] = (vector, [session.evidence(store, add)], "the vector the function stores hitmarks into")
    out["Actor.hitmarksEnd"] = (vector + VECTOR_END, [session.evidence(store, add)], "the vector's end pointer")
    out["Hitmark.size"] = (stride, [session.evidence(store, add, "imm")], "the scale of the entry index")
    notes = {"type": "where the entry constructor stores the hitmark's type",
             "value": "where it stores the value",
             "endCycle": "where it stores the sum holding the client cycle"}
    for name, (off, rva) in entry.items():
        out[f"Hitmark.{name}"] = (off, [session.evidence(rva, ctor)], notes[name])
    return out


def hitmark_layout(session, fn):
    """(vector offset, entry size, store rva, {field: (offset, rva)}, constructor) for a function filling a vector of
    hitmarks on its first argument, or None."""
    model = session.model
    facts = session.prov.facts(fn)
    vectors = set()
    for store in facts.stores:
        t = _entry_address(store.target)
        if t is not None and isinstance(t.base, Deref) and t.base.base == Arg(0) and t.base.index is None \
                and t.base.size == 8:
            stride = _scale(t)
            if stride is not None and _loads(facts, Deref(Arg(0), t.base.off + VECTOR_END, 8)):
                vectors.add((t.base.off, stride, store.rva))
    if len({(v, s) for v, s, _ in vectors}) != 1:
        return None
    vector, stride, store = min(vectors, key=lambda v: v[2])
    ctors = {model.resolve(c.target) for c in facts.calls
             if c.target is not None and len(c.args) > 3 and c.args[1] == Arg(1) and c.args[2] == Arg(2)
             and contains(c.args[3], Arg(4))}
    if len(ctors) != 1:
        return None
    ctor, = ctors
    entry = {}
    for s in session.prov.facts(ctor).stores:
        t = s.target
        if isinstance(t, Deref) and t.base == Arg(0) and t.index is None and t.size == 4:
            name = {Arg(1): "type", Arg(2): "value", Arg(3): "endCycle"}.get(s.value)
            if name is not None:
                entry.setdefault(name, (t.off, s.rva))
    if len(entry) != 3 or max(off for off, _ in entry.values()) + 4 > stride:
        return None
    return vector, stride, store, entry, ctor


def _entry_address(target):
    """The indexed address a store writes through: the store's own [base + index*scale], or the address a LEA computed
    that way and the store writes at; None for a store without an index."""
    if not isinstance(target, Deref):
        return None
    if target.index is not None:
        return target
    base = target.base
    if isinstance(base, Deref) and base.size == 0 and base.index is not None:
        return base
    return None


def _scale(deref):
    """The bytes per index step of an indexed load or store: its scale, times the index's constant factor."""
    index = deref.index
    if isinstance(index, Mul) and isinstance(index.factor, Const):
        return index.factor.n * deref.scale
    return deref.scale if deref.scale > 1 else None


def _loads(facts, value):
    return any(d == value for d, _ in facts.derefs())


def _below(model, fn, depth):
    """Every function reached from fn by at most depth direct calls, fn excluded."""
    seen, frontier = set(), {fn}
    for _ in range(depth):
        frontier = {model.resolve(c) for f in frontier for c in model.callees(f)} - seen - {fn}
        seen |= frontier
    return sorted(seen)
