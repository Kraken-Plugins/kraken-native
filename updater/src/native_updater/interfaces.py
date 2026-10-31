"""Where the client opens and closes interfaces, for WidgetLoaded and WidgetClosed (docs/developer/events.md).

The client keeps the groups opened inside components in a hash map on the Client (Client.subInterfaces: a component's
packed id to a node holding the group and the modal mode the server opened it with) and the top-level interface in an
int field of the Client. The packet handler's interface cases (open, close, move, resync), the close-modal path and
the logout reset all go through two functions. One opens a group in a component: it inserts the node, stores the
group and the mode into it, then runs the group's on-load scripts. The other closes one: it erases the node and, when
asked, unloads the group's components. The on-load runner is the one function raising ON_IF_LOAD (once per component
with an on-load script); the open function reaches it through a stub that moves the group into the second argument
and jumps to it. Both functions compare the top-level interface with -1 before laying it out again.

  sub_interfaces    ifOpenSub: takes the sub-interface map's address off its first argument, stores its third and
                    fourth arguments into the node the map's insert hands back, and runs on-load with its third.
                    ifCloseSub: the function called beside it that also takes the map's address off its first argument,
                    passes its third on (to the unload) and runs no on-load. The node's mode is where the fourth
                    argument goes, as an offset from the group's. The top-level interface is the int field of the
                    Client both compare with -1.

Output keys: functions.ifOpenSub, functions.ifCloseSub, Client.topLevelInterface, SubInterfaceNode.type.
"""
from .analysis.provenance import Add, Arg, Const, Deref, Out

MAP_TO_BUCKETS = 8          # the hash map object starts this far before its bucket pointer (strategies.hash_map)


def sub_interfaces(session, p):
    model = session.model
    map_off = session.value("Client.subInterfaces.buckets") - MAP_TO_BUCKETS
    group_off = session.value("SubInterfaceNode.group")
    sites = session.value("events.ON_IF_LOAD")
    map_addr = Add(Arg(0), map_off)

    runners = sorted({fn for site in sites for fn in model.callers(site["fn"])})
    if len(runners) != 1:
        session.notes.append(f"interfaces: {len(runners)} functions raise ON_IF_LOAD")
        return {}
    runner = runners[0]
    on_load = {runner} | {fn for _, fn, _ in model.xrefs_to(runner, ("jump",)) if fn is not None}
    referencing = {fn for _, fn in model.insns_with_disp(map_off) + model.insns_with_imm(map_off) if fn is not None}

    opens = []
    for fn in sorted({caller for entry in on_load for caller in model.callers(entry)} & referencing):
        facts = session.prov.facts(fn)
        if not _takes(facts, map_addr):
            continue
        runs = next((c for c in facts.calls if _runs(model, c, on_load) and Arg(2) in c.args), None)
        node = _node_stores(facts, map_addr)
        if runs is not None and node is not None:
            opens.append((fn, runs, node))
    if len(opens) != 1:
        session.notes.append(f"interfaces: {len(opens)} functions open a group in the sub-interface map and run "
                             f"its on-load")
        return {}
    open_fn, runs, (group_store, mode_store) = opens[0]
    out = {"functions.ifOpenSub": (open_fn, [session.evidence(runs.rva, open_fn, "rip")],
                                   "inserts into Client.subInterfaces, stores the group and the mode into the node "
                                   "and runs the group's on-load (the ON_IF_LOAD raiser)")}

    node_group = group_store[1]
    if node_group != group_off:
        session.notes.append(f"interfaces: the open function stores the group at node + {node_group:#x}, "
                             f"SubInterfaceNode.group is {group_off:#x}")
    else:
        out["SubInterfaceNode.type"] = (mode_store[1], [session.evidence(mode_store[0], open_fn)],
                                        "where the open function stores its fourth argument, next to the group")

    siblings = {model.resolve(c) for caller in model.callers(open_fn) for c in model.callees(caller)}
    closes = []
    for fn in sorted((siblings & referencing) - {open_fn}):
        facts = session.prov.facts(fn)
        if _takes(facts, map_addr) and not any(_runs(model, c, on_load) for c in facts.calls) \
                and any(Arg(2) in c.args[:2] for c in facts.calls if c.target is not None):
            closes.append(fn)
    if len(closes) != 1:
        session.notes.append(f"interfaces: {len(closes)} functions beside the open function take the sub-interface "
                             f"map and run no on-load")
        return out
    close_fn = closes[0]
    close_sites = model.call_sites(close_fn)
    out["functions.ifCloseSub"] = (close_fn, [session.evidence(close_sites[0][0], close_sites[0][1], "rip")]
                                   if close_sites else [],
                                   "called beside the open function, erases from Client.subInterfaces, runs no on-load")

    tops = _minus_one_fields(session.prov.facts(open_fn)) & _minus_one_fields(session.prov.facts(close_fn))
    if len(tops) != 1:
        session.notes.append(f"interfaces: the open and close functions compare {len(tops)} Client fields with -1")
        return out
    top, = tops
    cmp = next(c for c in session.prov.facts(close_fn).cmps if top in (_client_int(c.a), _client_int(c.b)))
    out["Client.topLevelInterface"] = (top, [session.evidence(cmp.rva, cmp.fn)],
                                       "the Client int the open and close functions compare with -1")
    return out


def _takes(facts, map_addr):
    """Whether the function computes the map's address off its first argument."""
    return any(a.value == map_addr for a in facts.addrs) or any(map_addr in c.args for c in facts.calls)


def _runs(model, call, on_load):
    return call.target is not None and model.resolve(call.target) in on_load


def _node_stores(facts, map_addr):
    """((rva, node offset) of the group, (rva, node offset) of the mode): the stores of the third and fourth arguments
    into the node an insert into the map hands back through an out argument; None unless both are there."""
    inserts = {c.rva for c in facts.calls if c.args and c.args[0] == map_addr}
    found = {}
    for store in sorted(facts.stores, key=lambda s: s.rva):
        if store.value not in (Arg(2), Arg(3)) or store.value in found:
            continue
        root, off = _flatten(store.target)
        if isinstance(root, Deref) and isinstance(root.base, Out) and root.base.rva in inserts:
            found[store.value] = (store.rva, off)
    if Arg(2) not in found or Arg(3) not in found:
        return None
    return found[Arg(2)], found[Arg(3)]


def _flatten(value):
    """(root, offset): a 4-byte store target [[root] + a + b ...] as its root pointer and total offset."""
    if not isinstance(value, Deref) or value.index is not None:
        return None, 0
    base, off = value.base, value.off
    while isinstance(base, Add):
        off += base.off
        base = base.base
    return base, off


def _minus_one_fields(facts):
    """The Client int fields (offsets off the first argument) the function compares with -1."""
    out = set()
    for c in facts.cmps:
        for a, b in ((c.a, c.b), (c.b, c.a)):
            if _client_int(a) is not None and isinstance(b, Const) and b.n & 0xffffffff == 0xffffffff:
                out.add(_client_int(a))
    return out


def _client_int(value):
    if isinstance(value, Deref) and value.base == Arg(0) and value.size == 4 and value.index is None:
        return value.off
    return None
