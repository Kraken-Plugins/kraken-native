"""What a component's drag waits for, which components the cs2 runtime's ops act on, and how a script sets a
component's text, for the writes the hook DLL makes to components.

The cs2 runtime's handler of ops 1300-1399 (cc_setop, cc_setdraggable, ...; the if_ ops 2300-2399 arrive mapped down
by 1000) first resolves the component the op acts on, then subtracts 1300 from the op and jumps through a byte table
of case indices and a table of case addresses. The resolver returns a jag::shared_ptr<IfType>: for an if_ op the
component whose id it popped, otherwise one of the runner's two current components, picked by the instruction's int
operand: the component cc_* ops act on (operand 0) and the one .cc_* ops act on (operand 1). They sit on the runner
one shared_ptr apart, so the resolver returns runner + offset + operand * stride. Op 1303 (cc_setdragdeadzone) and op
1304 (cc_setdragdeadtime) pop the int stack into the component: a press only becomes a drag once the client's drag
counter passes the dead time and the mouse has moved past the dead zone.

  cs2_components   IfType.dragDeadZone, IfType.dragDeadTime: the field of the resolved component (the pointer in the
                   shared_ptr the resolver returned) that the case of op 1303 or 1304 stores the popped int to;
                   ScriptRunner.activeComponent, ScriptRunner.dotComponent: from the resolver's return
                   runner + offset + operand * stride, the slots for operand 0 and operand 1.

The handler of ops 1100-1199 (cc_setscrollpos, cc_setcolour, ...) resolves its component the same way, then subtracts
1100 and jumps through a plain table of case addresses. The case of op 1112 (cc_settext) copies the popped string,
compares it with the component's IfType.text and, when they differ, assigns it to IfType.text with the client's
string assignment, then calls the invalidate with the client and the resolver's shared_ptr. The cases of the other
set ops call the invalidate the same way after their store.

  cs2_set_text     functions.stringAssign: the one function the case calls with the resolved component plus
                   IfType.text first; functions.ifInvalidate: the one function the case then calls with the client
                   ([global.client]) and the resolver's return.

A component's click mask (its events: which ops go to the server, what a selected spell or item can be used on, how it
drags) is the server's for its slot when the server set one, else the component's own. The server's are a
hash_multimap on the Client from a component's packed id to slot ranges, each with the events and the op mask the
server set for the slots from..to (IF_SETEVENTS; a static component's slot is -1). The handler of ops 1800-1899
(cc_gettargetmask, cc_getop, ...) dispatches through a byte table like the 1300 handler; the case of op 1800
(cc_gettargetmask) calls one function with the client and the runner's active component and pushes bits 11-16 of what
it returns. That getter passes the client plus the map's offset to the map's equal_range lookup, walks the nodes it
returns comparing the component's IfType.subId with each node's slot bounds, and returns the node's events from the
first range holding the slot, else the component's own events field.

  cs2_click_mask   Client.ifEvents.buckets, Client.ifEvents.bucketCount: the map's offset (the getter's client plus
                   offset argument to the lookup) plus the lookup's bucket array and bucket count, recognised from its
                   division by the count; IfEventsNode.key, IfEventsNode.next: the lookup's node key and chain
                   pointer; IfEventsNode.from, IfEventsNode.to: the two node fields the getter compares the slot with,
                   told apart by the jump after each compare; IfEventsNode.events: the node field the getter returns;
                   IfType.events: the component field it returns otherwise.

Output keys: IfType.dragDeadZone, IfType.dragDeadTime, ScriptRunner.activeComponent, ScriptRunner.dotComponent,
functions.stringAssign, functions.ifInvalidate, Client.ifEvents.buckets, Client.ifEvents.bucketCount,
IfEventsNode.key, IfEventsNode.next, IfEventsNode.from, IfEventsNode.to, IfEventsNode.events, IfType.events.
"""
from .analysis.provenance import Add, Arg, Bin, Const, Deref, Global, Mul, Ret
from .scripts import _case_facts, _int_stack, _op_cases, _table_cases
from .strategies import recognize_hash_maps

OP_RANGE = 1300                  # the cs2 ops this handler runs
DRAG = {                         # the ops that set a drag field, with their names
    "IfType.dragDeadZone": (1303, "cc_setdragdeadzone"),
    "IfType.dragDeadTime": (1304, "cc_setdragdeadtime"),
}
SET_RANGE = 1100                 # the cs2 ops of the handler that sets a component's look
SET_TEXT = (1112, "cc_settext")
GET_RANGE = 1800                 # the cs2 ops of the handler that reads a component's ops and events
TARGET_MASK = (1800, "cc_gettargetmask")
LOWER_BOUND = {"JL", "JGE"}      # after CMP slot, field: the field is the range's first slot
UPPER_BOUND = {"JLE", "JG"}      # after CMP slot, field: the field is the range's last slot


def cs2_components(session, p):
    model = session.model
    ptr = session.value("container.shared_ptr.ptr")
    stack = session.value("ScriptRunner.intStack")
    size = session.value("ScriptRunner.intStackSize")
    handler = _handler(model)
    if handler is None:
        session.notes.append(f"components: no cs2 handler subtracts {OP_RANGE} and jumps through a byte table")
        return {}
    fn, cases, bounds = handler
    facts = session.prov.facts(fn)
    out, holders = {}, {}
    for key, (op, name) in DRAG.items():
        if op - OP_RANGE not in cases:
            session.notes.append(f"components: the {OP_RANGE} handler {fn:#x} has no case for op {op}")
            continue
        stores = [(s, field) for s in _case_facts(facts, cases[op - OP_RANGE], bounds)["stores"]
                  if (field := _component_field(s.target, ptr)) is not None and _pops_int(s.value, stack, size)]
        if len(stores) != 1:
            session.notes.append(f"components: {name}'s case (op {op}) stores the popped int to {len(stores)} "
                                 f"component fields")
            continue
        store, (offset, holder) = stores[0]
        holders.setdefault(holder, store.rva)
        out[key] = (offset, [session.evidence(store.rva, fn)],
                    f"{name}'s case (op {op}) stores the popped int to the resolved component plus this")
    if len(holders) != 1:
        if holders:
            session.notes.append(f"components: the drag cases resolve their component {len(holders)} ways")
        return out
    (holder, rva), = holders.items()
    out.update(_current_components(session, fn, facts, holder, rva, ptr))
    return out


def cs2_set_text(session, p):
    model = session.model
    ptr = session.value("container.shared_ptr.ptr")
    text = session.value("IfType.text")
    client = Deref(Global(session.value("global.client")), 0, 8)
    op, name = SET_TEXT
    handler = _table_cases(model, SET_RANGE)
    if handler is None:
        session.notes.append(f"components: no cs2 handler subtracts {SET_RANGE} and jumps through a case table")
        return {}
    fn, cases = handler
    if op - SET_RANGE >= len(cases):
        session.notes.append(f"components: the {SET_RANGE} handler {fn:#x} has {len(cases)} cases, no op {op}")
        return {}
    calls = _case_facts(session.prov.facts(fn), cases[op - SET_RANGE], sorted(set(cases)))["calls"]
    assigns = {}
    for call in calls:
        if call.target is not None and len(call.args) >= 3 \
                and (holder := _field_holder(call.args[0], ptr, text)) is not None:
            assigns.setdefault((model.resolve(call.target), holder), call.rva)
    if len(assigns) != 1:
        session.notes.append(f"components: {name}'s case (op {op}) assigns IfType.text with {len(assigns)} functions")
        return {}
    ((assign, holder), assign_rva), = assigns.items()
    out = {"functions.stringAssign": (assign, [session.evidence(assign_rva, fn, "rip")],
                                      f"{name}'s case (op {op}) assigns the new text to the resolved component's "
                                      f"IfType.text with it")}
    invalidates = {}
    for call in calls:
        if call.target is not None and call.rva > assign_rva and len(call.args) >= 2 and call.args[0] == client \
                and call.args[1] == holder:
            invalidates.setdefault(model.resolve(call.target), call.rva)
    if len(invalidates) != 1:
        session.notes.append(f"components: after assigning the text, {name}'s case calls {len(invalidates)} "
                             f"functions with the client and the component")
        return out
    (invalidate, rva), = invalidates.items()
    out["functions.ifInvalidate"] = (invalidate, [session.evidence(rva, fn, "rip")],
                                     f"{name}'s case (op {op}) calls it with the client and the resolved component's "
                                     f"shared_ptr after assigning the text")
    return out


def cs2_click_mask(session, p):
    model = session.model
    ptr = session.value("container.shared_ptr.ptr")
    sub_id = session.value("IfType.subId")
    client = Deref(Global(session.value("global.client")), 0, 8)
    op, name = TARGET_MASK
    handler = _byte_table_handler(model, GET_RANGE, [op])
    if handler is None:
        session.notes.append(f"components: no cs2 handler subtracts {GET_RANGE} and jumps through a byte table")
        return {}
    fn, cases, bounds = handler
    calls = [c for c in _case_facts(session.prov.facts(fn), cases[op - GET_RANGE], bounds)["calls"]
             if c.target is not None and c.args and c.args[0] == client]
    getters = {model.resolve(c.target) for c in calls}
    if len(getters) != 1:
        session.notes.append(f"components: {name}'s case (op {op}) calls {len(getters)} functions with the client")
        return {}
    getter, call_rva = getters.pop(), calls[0].rva
    facts = session.prov.facts(getter)
    lookups = [(c, c.args[0].off) for c in facts.calls if c.target is not None and c.args
               and isinstance(c.args[0], Add) and c.args[0].base == Arg(0) and c.args[0].off > 0]
    if len(lookups) != 1:
        session.notes.append(f"components: the click mask getter {getter:#x} passes the client plus an offset to "
                             f"{len(lookups)} functions")
        return {}
    lookup_call, map_off = lookups[0]
    lookup = model.resolve(lookup_call.target)
    maps = [m for m in recognize_hash_maps(session.prov.facts(lookup), model) if m.base == Arg(0)]
    if len(maps) != 1 or maps[0].node_key is None or maps[0].node_next is None:
        session.notes.append(f"components: the lookup {lookup:#x} the click mask getter calls is not one hash map "
                             f"lookup with a node key and chain")
        return {}
    hm = maps[0]
    component = Deref(Arg(1), ptr, 8)
    slot = Deref(component, sub_id, 4)
    ranges = _slot_bounds(model, facts, slot)
    if ranges is None:
        session.notes.append(f"components: the click mask getter {getter:#x} doesn't compare the slot with a first "
                             f"and a last slot of one node")
        return {}
    node, (lower, lower_rva), (upper, upper_rva) = ranges
    node_events = [(r.value.off, r.rva) for r in facts.returns
                   if isinstance(r.value, Deref) and r.value.base == node and r.value.size == 4
                   and r.value.index is None]
    own_events = [(r.value.off, r.rva) for r in facts.returns
                  if isinstance(r.value, Deref) and r.value.base == component and r.value.size == 4
                  and r.value.index is None]
    steps = [l for l in facts.loads if l.value == Deref(node, hm.node_next, 8)]
    if len(node_events) != 1 or len(own_events) != 1 or not steps:
        session.notes.append(f"components: the click mask getter {getter:#x} returns {len(node_events)} node fields "
                             f"and {len(own_events)} component fields, and steps along the chain {len(steps)} times")
        return {}
    (events, events_rva), = node_events
    (own, own_rva), = own_events
    anchor = [session.evidence(call_rva, fn, "rip")]
    map_rva = next((a.rva for a in facts.addrs if a.value == lookup_call.args[0]), lookup_call.rva)
    return {
        "Client.ifEvents.buckets": (map_off + hm.buckets, [session.evidence(map_rva, getter, "imm")],
                                    f"the click mask getter passes the client plus {map_off:#x} to the lookup, which "
                                    f"loads the bucket array at +{hm.buckets:#x}"),
        "Client.ifEvents.bucketCount": (map_off + hm.count, [session.evidence(hm.rva, lookup, "disp")],
                                        f"the lookup divides the key by the count at +{hm.count:#x} of the map"),
        "IfEventsNode.key": (hm.node_key, [session.evidence(hm.rva, lookup, "disp")],
                             "the lookup compares the component id with the node's key"),
        "IfEventsNode.next": (hm.node_next, [session.evidence(steps[0].rva, getter, "disp")],
                              "the lookup and the getter follow the chain through this pointer"),
        "IfEventsNode.from": (lower, [session.evidence(lower_rva, getter, "disp")] + anchor,
                              f"the getter skips a node whose first slot is above the component's ({name}'s getter)"),
        "IfEventsNode.to": (upper, [session.evidence(upper_rva, getter, "disp")] + anchor,
                            f"the getter takes a node whose last slot is at or past the component's ({name}'s getter)"),
        "IfEventsNode.events": (events, [session.evidence(events_rva, getter, "disp")] + anchor,
                                "the getter returns this field of the node whose range holds the slot"),
        "IfType.events": (own, [session.evidence(own_rva, getter, "disp")] + anchor,
                          "the getter returns this field of the component when no node's range holds its slot"),
    }


def _slot_bounds(model, facts, slot):
    """(node, (first slot offset, rva), (last slot offset, rva)) from the getter's two compares of the component's slot
    with 4-byte fields of one node, read off the conditional jump after each; else None."""
    lower, upper = {}, {}
    for c in facts.cmps:
        for a, b, flipped in ((c.a, c.b, False), (c.b, c.a, True)):
            if a != slot or not isinstance(b, Deref) or b.size != 4 or b.index is not None \
                    or not isinstance(b.base, Deref) or b.base.size != 8:
                continue
            insn = model.insn(c.rva)
            jump = model.insn(insn.fallthrough) if insn is not None and insn.fallthrough is not None else None
            if jump is None or not jump.is_cond_jump:
                continue
            first = jump.mnem in LOWER_BOUND
            if jump.mnem not in LOWER_BOUND | UPPER_BOUND:
                continue
            (lower if first != flipped else upper).setdefault((b.base, b.off), c.rva)
    nodes = {node for node, _ in lower} & {node for node, _ in upper}
    if len(nodes) != 1:
        return None
    node = nodes.pop()
    firsts = [(off, rva) for (n, off), rva in lower.items() if n == node]
    lasts = [(off, rva) for (n, off), rva in upper.items() if n == node]
    if len(firsts) != 1 or len(lasts) != 1 or firsts[0][0] == lasts[0][0]:
        return None
    return node, firsts[0], lasts[0]


def _field_holder(value, ptr, offset):
    """The holder of a [holder + ptr]8 + offset address: a field of the component held in a shared_ptr, the holder
    being what the resolver call returned or the address it computes; else None."""
    if not isinstance(value, Add) or value.off != offset:
        return None
    component = value.base
    if not isinstance(component, Deref) or component.size != 8 or component.off != ptr or component.index is not None:
        return None
    holder = component.base
    if isinstance(holder, Ret) and holder.target is None:
        return None
    return holder


def _handler(model):
    """(fn, {op - 1300: case rva}, sorted case rvas) of the one function that subtracts 1300 and dispatches through a
    byte table and a case table, else None."""
    return _byte_table_handler(model, OP_RANGE, [op for op, _ in DRAG.values()])


def _byte_table_handler(model, op_range, ops):
    """(fn, {op - op_range: case rva}, sorted case rvas) of the one function that subtracts op_range and dispatches
    through a byte table and a case table with a case for each of ops, else None."""
    imm = (-op_range) & 0xffffffff
    candidates = {fn for _, fn in model.insns_with_disp(-op_range, "LEA") if fn is not None}
    candidates |= {fn for _, fn in model.insns_with_imm(imm, "ADD") if fn is not None}
    candidates |= {fn for _, fn in model.insns_with_imm(-op_range, "ADD") if fn is not None}
    found = []
    for fn in sorted(candidates):
        dispatch = _op_cases(model, fn)
        if dispatch is not None and all(op - op_range in dispatch[0] for op in ops):
            found.append((fn, *dispatch))
    return found[0] if len(found) == 1 else None


def _component_field(target, ptr):
    """(offset, holder) for a 4-byte [[holder + ptr]8 + offset]: a field of the component held in a shared_ptr, the
    holder being what the resolver call returned (Ret) or, where the resolver was summarised into the handler, the
    address it computes; else None."""
    if not isinstance(target, Deref) or target.size != 4 or target.index is not None:
        return None
    component = target.base
    if not isinstance(component, Deref) or component.size != 8 or component.off != ptr or component.index is not None:
        return None
    holder = component.base
    if isinstance(holder, Ret) and holder.target is None:
        return None
    return target.off, holder


def _pops_int(value, stack, size):
    """Whether a value is the int stack's top entry, [runner + [runner + size]4 * 4 + stack - 4]4."""
    popped = _int_stack(value)
    return popped is not None and popped[0] == stack - 4 and popped[1].off == size


def _current_components(session, fn, facts, holder, store_rva, ptr):
    """ScriptRunner.activeComponent and dotComponent from the slot the resolver returns for a cc_ op, runner + offset
    + operand * stride: read off the resolver's returns, where the handler passes the runner as its first argument,
    or off the address itself where the resolver was summarised into the handler."""
    if isinstance(holder, Ret):
        resolver = session.model.resolve(holder.target)
        calls = [c for c in facts.calls if c.target is not None and session.model.resolve(c.target) == resolver]
        if not calls or any(not c.args or c.args[0] != Arg(0) for c in calls):
            session.notes.append(f"components: the {OP_RANGE} handler doesn't pass its runner to the resolver "
                                 f"{resolver:#x}")
            return {}
        slots = {(slot, r.rva) for r in session.prov.facts(resolver).returns if (slot := _slot_sum(r.value)) is not None}
        where = resolver
    else:
        slots = {(slot, store_rva) for member in holder.members() if (slot := _slot_sum(member)) is not None}
        where = fn
    if len({slot for slot, _ in slots}) != 1:
        session.notes.append(f"components: the resolver returns {len(slots)} runner slots")
        return {}
    (offset, stride), rva = min(slots, key=lambda s: s[1])
    if stride < ptr + 8:
        session.notes.append(f"components: the resolver's slots are {stride:#x} apart, too close for a shared_ptr")
        return {}
    evidence = [session.evidence(rva, where)]
    return {
        "ScriptRunner.activeComponent": (offset, evidence, "the slot the resolver returns for operand 0 (cc_* ops)"),
        "ScriptRunner.dotComponent": (offset + stride, evidence,
                                      f"the slot the resolver returns for operand 1 (.cc_* ops), {stride:#x} on"),
    }


def _slot_sum(value):
    """(offset, stride) for Arg0 + offset + Arg(k) * stride with k > 0, else None."""
    terms = _terms(value)
    consts = [t.n for t in terms if isinstance(t, Const)]
    scaled = [t for t in terms if isinstance(t, Mul) and isinstance(t.base, Arg) and t.base.i > 0
              and isinstance(t.factor, Const) and t.factor.n > 0]
    if len(terms) != 3 or Arg(0) not in terms or len(consts) != 1 or len(scaled) != 1 or consts[0] <= 0:
        return None
    return consts[0], scaled[0].factor.n


def _terms(value):
    if isinstance(value, Add):
        return _terms(value.base) + [Const(value.off)]
    if isinstance(value, Bin) and value.op == "+":
        return _terms(value.a) + _terms(value.b)
    return [value]
