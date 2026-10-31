"""The options the server sets for right-clicking other players ("Trade with", "Follow", "Attack", ...): a table of
eight strings in the minimenu owner, and a byte per option saying it is listed below Walk here.

The player row builder is the one caller of the row adder, the function taking functions.menuPlayerOp's address. It
loops over the eight options and hands the table (the owner, its fifth argument, plus an offset) and the option's
index to three accessors: whether the option is set (its string isn't empty), the option's string and the option's
priority byte. The string accessor returns table + index * stride, the stride being an eastl string's size; the
priority accessor checks the index against the option count, then loads the byte at table + index + offset. An option
whose byte is set gets the low priority in the menu ("Attack" gets its own instead). The server's set-option packet
stores the byte as the inverse of the flag it sends, and an option text of "null" as an empty string.

The owner is checked through another reader of the table: the case of cs2 op 6907 (which pushes an option's text)
hands the string accessor [[global.client] + Client.minimenu] plus the same offset.

  player_ops   Minimenu.playerOps: the table's offset in the owner (the base both accessors take in the builder);
               Minimenu.playerOpPriorities: the offset of the eight priority bytes (the table's offset plus the
               priority accessor's displacement).

Output keys: Minimenu.playerOps, Minimenu.playerOpPriorities.
"""
from .analysis.provenance import Add, Arg, Const, Deref, Global, Mul

OPTIONS = 8                      # the player options the server can set, op 1 to op 8


def player_ops(session, p):
    model = session.model
    body = session.value("functions.menuPlayerOp")
    client = Deref(Global(session.value("global.client")), 0, 8)
    minimenu = session.value("Client.minimenu")
    stride = session.value("container.string.size")

    adders = sorted({fn for _, fn, _ in model.xrefs_to(body, ("lea",)) if fn is not None})
    builders = sorted({c for adder in adders for c in model.callers(adder)})
    if len(builders) != 1:
        session.notes.append(f"player ops: the functions taking the player row body's address have {len(builders)} "
                             f"callers, not one builder")
        return {}
    builder = builders[0]

    tables = {}                  # table value -> {"name": (fn, call rva), "priority": (fn, disp, count, load rva)}
    for call in session.prov.facts(builder).calls:
        if call.target is None or not call.args or not isinstance(call.args[0], Add):
            continue
        target = model.resolve(call.target)
        found = tables.setdefault(call.args[0], {})
        if _string_stride(session, target) == stride:
            found.setdefault("name", (target, call.rva))
        priority = _priority_byte(session, target)
        if priority is not None:
            found.setdefault("priority", (target, *priority))
    both = [(table, found) for table, found in tables.items() if "name" in found and "priority" in found]
    if len(both) != 1:
        session.notes.append(f"player ops: the player row builder {builder:#x} hands {len(both)} tables to a string "
                             f"accessor and a priority accessor")
        return {}
    table, found = both[0]
    accessor, name_call = found["name"]
    priority_fn, disp, count, priority_load = found["priority"]
    if count != OPTIONS:
        session.notes.append(f"player ops: the priority accessor {priority_fn:#x} checks the index against {count}, "
                             f"not {OPTIONS}")
        return {}

    owner = Add(Deref(client, minimenu, 8), table.off)
    readers = [fn for fn in model.callers(accessor) if fn != builder
               and any(c.target is not None and model.resolve(c.target) == accessor and c.args and c.args[0] == owner
                       for c in session.prov.facts(fn).calls)]
    if not readers:
        session.notes.append(f"player ops: no function hands the string accessor {accessor:#x} "
                             f"[client + Client.minimenu] + {table.off:#x}")
        return {}

    lea = next((i.rva for i in model.insns(builder) for op in i.ops
                if op.kind == "mem" and op.base not in (None, "RIP") and op.disp == table.off), name_call)
    return {
        "Minimenu.playerOps": (table.off, [session.evidence(lea, builder)],
                               f"the table the player row builder hands the string accessor {accessor:#x} and the "
                               f"priority accessor {priority_fn:#x}; {readers[0]:#x} reads it through Client.minimenu"),
        "Minimenu.playerOpPriorities": (table.off + disp, [session.evidence(priority_load, priority_fn)],
                                        f"the priority accessor {priority_fn:#x} loads the byte at table + index + "
                                        f"{disp:#x} after checking the index against {count}"),
    }


def _string_stride(session, fn):
    """The stride of fn's returned address Arg0 + Arg1 * stride (the address of the option's string), or None."""
    for r in session.prov.facts(fn).returns:
        v = r.value
        if not (isinstance(v, Deref) and v.size == 0 and v.off == 0 and v.base == Arg(0) and v.index is not None):
            continue
        if v.index == Arg(1):
            return v.scale
        if isinstance(v.index, Mul) and v.index.base == Arg(1) and isinstance(v.index.factor, Const):
            return v.index.factor.n * v.scale
    return None


def _priority_byte(session, fn):
    """(displacement, count, load rva) of fn returning the byte [Arg0 + Arg1 + displacement] after comparing Arg1
    with count, or None."""
    facts = session.prov.facts(fn)
    counts = [c.b.n for c in facts.cmps if c.a == Arg(1) and isinstance(c.b, Const)]
    for r in facts.returns:
        v = r.value
        if (isinstance(v, Deref) and v.size == 1 and v.scale == 1 and v.index is not None
                and {v.base, v.index} == {Arg(0), Arg(1)} and len(counts) == 1):
            load = next((l.rva for l in facts.loads if l.value == v), r.rva)
            return v.off, counts[0], load
    return None
