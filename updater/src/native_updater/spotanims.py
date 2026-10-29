"""The spot animations an actor shows, for GraphicChanged (docs/event-hooks-design.md §2.11).

The NPC and player update masks hand each spot animation of an update to one function, (actor, slot, id, height,
delay, cycle, a loop flag), the cycle being the client cycle; the server's spot animation packets for one NPC or player
call it too. The actor keeps its spot animations in an eastl::vector_map sorted by slot, each element the slot and a
jag::shared_ptr to a spot animation object. An id of 65535 (or -1) erases the slot's element; any other makes a new
object (the id, the height, the start cycle = cycle + delay, the flag, and an animation block set to the SpotAnimType's
sequence) and puts it in the slot, in place of the one there. Once a cycle the actor step plays the animations that have
started and erases those whose sequence finished (unless the flag keeps them looping). Every frame the actor's draw
walks the elements, skips those that haven't started and loads each one's SpotAnimType to draw it.

  actor_spot_anims   functions.setSpotAnim: a function the NPC update decoder (or one of its callees) calls with
                     global.cycle as its sixth argument, which looks its second argument up in a vector on its first
                     (a call taking [Arg0 + V] and [Arg0 + V + 8]) and compares it with the found element's key.
                     The draw: a SpotAnim loader caller that passes [[[A + V] + O] + I] to the loader and compares
                     [[[A + V] + O] + C] with global.cycle; the register it loads [A + V] into steps by S.
                     Actor.spotAnims / spotAnimsEnd: V and V + 8. SpotAnimSlot.slot: the key offset the setter
                     compares; SpotAnimSlot.spotAnim: O; SpotAnimSlot.size: S. ActorSpotAnim.id: I; startCycle: C.
                     The constructor: the SpotAnim loader caller storing its second argument at I and its fourth at C;
                     ActorSpotAnim.height is where it stores its third, ActorSpotAnim.animState the block it hands to a
                     SeqType loader caller (the sequence setter).

Output keys: functions.setSpotAnim (internal), Actor.spotAnims, Actor.spotAnimsEnd, SpotAnimSlot.{slot, spotAnim, size},
ActorSpotAnim.{id, startCycle, height, animState (internal)}.
"""
from .analysis.provenance import Add, Arg, Deref, Global, Ret
from .hitmarks import _below

DEPTH = 2                   # calls from the NPC update decoder down to the mask decoder setting spot animations
CYCLE_ARG = 5               # the setter's sixth argument is the client cycle
VECTOR_END = 8              # an eastl::vector's end pointer follows its begin pointer
POINTER = 8


def actor_spot_anims(session, p):
    model = session.model
    cycle = Deref(Global(session.value("global.cycle")), 0, 4)
    loader = session.fn("@ConfigLoader.13")
    decoder = session.value("functions.npcInfoDecode")

    setters = {}
    for fn in [decoder] + _below(model, decoder, DEPTH):
        for call in session.prov.facts(fn).calls:
            if call.target is None or len(call.args) <= CYCLE_ARG or call.args[CYCLE_ARG] != cycle:
                continue
            target = model.resolve(call.target)
            if target not in setters:
                found = slot_lookup(session.prov.facts(target))
                if found is not None:
                    setters[target] = (found, call)
    if len(setters) != 1:
        session.notes.append(f"spot anims: {len(setters)} functions under the NPC update decoder take the client "
                             f"cycle as their sixth argument and look a slot up in a vector on the actor")
        return {}
    setter, ((vector, key, compare), call) = next(iter(setters.items()))
    out = {"functions.setSpotAnim": (setter, [session.evidence(call.rva, call.fn, "rip")],
                                     f"called with global.cycle as its sixth argument from "
                                     f"{len(model.callers(setter))} functions; looks the slot up in a vector on the "
                                     "actor"),
           "SpotAnimSlot.slot": (key, [session.evidence(compare, setter)],
                                 "the element key the setter compares the slot with")}

    draws = [(fn, layout) for fn in model.callers(loader)
             if (layout := draw_layout(session, model, fn, loader, cycle, vector)) is not None]
    if len(draws) != 1:
        session.notes.append(f"spot anims: {len(draws)} SpotAnim loader callers walk the vector at "
                             f"Actor + {vector:#x} and compare a start cycle with the client cycle")
        return out
    draw, (obj, spot, start, stride, rvas) = draws[0]
    out["Actor.spotAnims"] = (vector, [session.evidence(rvas["begin"], draw)],
                              "the vector the setter looks slots up in and the draw walks")
    out["Actor.spotAnimsEnd"] = (vector + VECTOR_END, [session.evidence(rvas["begin"], draw)],
                                 "the vector's end pointer")
    out["SpotAnimSlot.spotAnim"] = (obj, [session.evidence(rvas["id"], draw)],
                                    "the pointer in each element the draw reads the spot animation through")
    out["ActorSpotAnim.id"] = (spot, [session.evidence(rvas["id"], draw)],
                               "the field the draw passes to the SpotAnim loader")
    out["ActorSpotAnim.startCycle"] = (start, [session.evidence(rvas["start"], draw)],
                                       "the field the draw compares with the client cycle")
    if stride is not None:
        out["SpotAnimSlot.size"] = (stride, [session.evidence(rvas["step"], draw, "imm")],
                                    "the step of the pointer the draw walks the elements with")
    else:
        session.notes.append(f"spot anims: the draw {draw:#x} walks the elements with no fixed step")

    sequences = set(model.callers(session.fn("@ConfigLoader.12")))
    ctors = [(fn, fields) for fn in model.callers(loader)
             if (fields := ctor_fields(session.prov.facts(fn), loader, sequences, spot, start)) is not None]
    if len(ctors) != 1:
        session.notes.append(f"spot anims: {len(ctors)} SpotAnim loader callers store an id at {spot:#x} and a start "
                             f"cycle at {start:#x} from their arguments")
        return out
    ctor, fields = ctors[0]
    notes = {"ActorSpotAnim.height": "where the constructor stores its third argument (the height)",
             "ActorSpotAnim.animState": "the animation block the constructor hands to the sequence setter"}
    for name, (off, rva) in fields.items():
        out[name] = (off, [session.evidence(rva, ctor)], notes[name])
    return out


def slot_lookup(facts):
    """(vector offset, element key offset, compare rva) when the function calls a lookup with the begin and end of a
    vector on its first argument and compares its second argument with a field of the element that lookup returns;
    None otherwise."""
    for call in facts.calls:
        if len(call.args) < 2 or call.target is None:
            continue
        begin, end = call.args[0], call.args[1]
        if not (_field(begin, Arg(0)) and _field(end, Arg(0)) and end.off == begin.off + VECTOR_END):
            continue
        for cmp in facts.cmps:
            for a, b in ((cmp.a, cmp.b), (cmp.b, cmp.a)):
                if a == Arg(1) and isinstance(b, Deref) and isinstance(b.base, Ret) and b.base.target == call.target \
                        and b.index is None and b.size == 4:
                    return begin.off, b.off, cmp.rva
    return None


def draw_layout(session, model, fn, loader, cycle, vector):
    """(object offset, id offset, start cycle offset, element size or None, {what: rva}) for a SpotAnim loader caller
    that walks the vector at `vector` on one of its arguments: it passes [[[A + vector] + O] + I] to the loader and
    compares [[[A + vector] + O] + C] with the client cycle. None if it isn't one."""
    facts = session.prov.facts(fn)
    found = None
    for call in facts.calls:
        if call.target is None or model.resolve(call.target) != loader or len(call.args) < 2:
            continue
        parts = _element_field(call.args[1], vector)
        if parts is not None:
            found = (parts, call.rva)
            break
    if found is None:
        return None
    (arg, obj, spot), id_rva = found
    start = None
    for cmp in facts.cmps:
        for a, b in ((cmp.a, cmp.b), (cmp.b, cmp.a)):
            parts = _element_field(b, vector) if a == cycle else None
            if parts is not None and parts[:2] == (arg, obj):
                start = (parts[2], cmp.rva)
    if start is None:
        return None
    begin = next((l for l in facts.loads if l.value == Deref(arg, vector, POINTER) and l.dest), None)
    if begin is None:
        return None
    steps = {(i.ops[1].imm, i.rva) for i in model.insns(fn) if i.mnem == "ADD" and len(i.ops) == 2
             and i.ops[0].kind == "reg" and i.ops[0].reg == begin.dest and i.ops[1].kind == "imm"}
    stride, step = next(iter(steps)) if len({s for s, _ in steps}) == 1 else (None, None)
    return obj, spot, start[0], stride, {"begin": begin.rva, "id": id_rva, "start": start[1], "step": step}


def ctor_fields(facts, loader, sequences, spot, start):
    """{key: (offset, rva)} for the height and the animation block, from a SpotAnim loader caller that stores its
    second argument at `spot` and its fourth at `start`; None if it isn't the spot animation constructor."""
    stores = {s.value: s for s in facts.stores
              if _field(s.target, Arg(0)) and s.target.size == 4 and isinstance(s.value, Arg)}
    if not (Arg(1) in stores and stores[Arg(1)].target.off == spot and Arg(3) in stores
            and stores[Arg(3)].target.off == start and Arg(2) in stores):
        return None
    if not any(c.target == loader and len(c.args) > 1 and c.args[1] == Arg(1) for c in facts.calls):
        return None
    blocks = sorted({(c.args[0].off, c.rva) for c in facts.calls
                     if c.target in sequences and c.args and isinstance(c.args[0], Add) and c.args[0].base == Arg(0)})
    if len({off for off, _ in blocks}) != 1:
        return None
    height = stores[Arg(2)]
    return {"ActorSpotAnim.height": (height.target.off, height.rva), "ActorSpotAnim.animState": blocks[0]}


def _element_field(value, vector):
    """(A, O, F) for value = [[[A + vector] + O] + F], a 4-byte field of the object an element of the vector at
    A + vector points at, A an argument; None otherwise."""
    if not (isinstance(value, Deref) and value.index is None and value.size == 4):
        return None
    obj = value.base
    if not (isinstance(obj, Deref) and obj.index is None and obj.size == POINTER):
        return None
    begin = obj.base
    if not (isinstance(begin, Deref) and begin.index is None and begin.size == POINTER and begin.off == vector
            and isinstance(begin.base, Arg)):
        return None
    return begin.base, obj.off, value.off


def _field(value, base):
    return isinstance(value, Deref) and value.base == base and value.index is None
