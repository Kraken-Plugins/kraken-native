"""The minimenu (the right-click menu): where the client keeps the menu it shows, the entry it last ran and the layout
of an entry. The Lua API has no minimenu reads, so the anchors are the op event raisers (events.ON_NPC_OP and its
siblings), which the client calls with the entry being run, and the ON_MINIMENU_REBUILD raise.

  dispatch(?, entry)    The one function calling the raisers of ON_NPC_OP, ON_LOC_OP and ON_TILE_OP. It switches on
                        the entry's subject kind byte through a jump table (case i raises the op event of kind i + bias),
                        hands every raiser the option text (an eastl string), compares the op index with -1 (an entry
                        aimed by a selected item or spell) and passes, per kind: the index (NPC, player, component; the
                        last argument of ON_NPC_OP), the type id (the second of ON_LOC_OP) and the coord (the second of
                        ON_TILE_OP: level, x, z).
  run(view, path, ...)  dispatch's caller. Looks the path up in the view's rows (nodes {list, index, children}), takes
                        the entry at [list + entries] + index * stride, copies it into the view (copy(view, entry, ...))
                        and dispatches it. The copy stores [view + clicked + n] = [entry + n] and zeroes the counter the
                        client cycle increments: the cycles since the last op ran.
  rebuild(owner)        The function raising ON_MINIMENU_REBUILD. Unless the menu is open (its first callee tests
                        [owner + open] != 0) it rebuilds the view at owner + view, whose callee builds view + rows (the
                        rows run reads).
  Client.minimenu       The owner: rebuild's caller passes it [client + offset]; the getter the op code calls with the
                        client loads the same field.
  target                The function referencing "(level" (it trims combat levels off targets) reads the entries the
                        same way run does and searches the target text, an eastl string in the entry.

Output keys (see `minimenu`): Client.minimenu, Minimenu.*, MinimenuEntry.*, container.minimenu.*,
enums.minimenuSubject.
"""
from .analysis.provenance import Add, Arg, Const, Deref, Global, Mul
from .events import _ecx_before, _sites

OP_EVENTS = {
    "ON_TILE_OP": "TILE", "ON_NPC_OP": "NPC", "ON_LOC_OP": "LOC",
    "ON_OBJ_OP": "OBJ", "ON_PLAYER_OP": "PLAYER", "ON_COMPONENT_OP": "COMPONENT",
}
CASE_STEPS = 48            # how far a jump-table case is followed to its raiser call
STRIDE_WINDOW = 16         # the SAR of a size division follows its magic constant within this many instructions
LEVEL_STRING = "(level"


def minimenu(session, p):
    model = session.model
    events = session.value("enums.events")
    flag_byte = session.value("container.string.heapFlagByte")
    for name in ("ON_NPC_OP", "ON_LOC_OP", "ON_TILE_OP"):
        session.value(f"events.{name}")                 # blocks until the raisers are mapped
    session.value("functions.dispatchEvent")
    session.value("global.client")
    raisers = {}                                        # raiser fn -> (fixed id or None)
    for name in OP_EVENTS:
        sites = session.values.get(f"events.{name}") or []
        for site in sites:
            raisers[site["fn"]] = site.get("id")
    by_event = {name: {s["fn"] for s in session.values.get(f"events.{name}") or []} for name in OP_EVENTS}
    needed = ("ON_NPC_OP", "ON_LOC_OP", "ON_TILE_OP")
    if not all(by_event[name] for name in needed):
        session.notes.append("minimenu: the op event raisers are not mapped")
        return {}

    dispatches = set.intersection(*({c for fn in by_event[name] for c in model.callers(fn)} for name in needed))
    if len(dispatches) != 1:
        session.notes.append(f"minimenu: {len(dispatches)} functions call the NPC, loc and tile op raisers")
        return {}
    dispatch = next(iter(dispatches))
    df = session.prov.facts(dispatch)
    out = {}

    entry = Arg(1)
    calls = {}
    for c in df.calls:
        if c.target is None:
            continue
        target = model.resolve(c.target)
        if target in raisers:
            event_id = raisers[target] if raisers[target] is not None else _const(c.args[0] if c.args else None)
            name = next((n for n, v in events.items() if v == event_id), None)
            if name in OP_EVENTS:
                calls.setdefault(name, c)

    def field(name, arg):
        c = calls.get(name)
        if c is None or len(c.args) <= arg:
            return None
        v = c.args[arg]
        return (v.off, c.rva) if isinstance(v, Add) and v.base == entry else None

    for key, name, arg, note in (
            ("MinimenuEntry.subjectIndex", "ON_NPC_OP", 3, "last argument of the NPC op raise (also the player and component raises)"),
            ("MinimenuEntry.typeId", "ON_LOC_OP", 2, "second argument of the loc op raise"),
            ("MinimenuEntry.level", "ON_TILE_OP", 2, "the coord the tile op raise gets: level, then x and z")):
        got = field(name, arg)
        if got is not None:
            out[key] = (got[0], [session.evidence(got[1], dispatch, "rip")], note)
    if "MinimenuEntry.level" in out:
        level, ev, _ = out["MinimenuEntry.level"]
        loc = field("ON_LOC_OP", 3)
        if loc is None or loc[0] == level:
            out["MinimenuEntry.x"] = (level + 4, ev, "second int of the coord the tile and loc op raises get")
            out["MinimenuEntry.z"] = (level + 8, ev, "third int of the coord the tile and loc op raises get")
        else:
            del out["MinimenuEntry.level"]
            session.notes.append(f"minimenu: tile op coord {level:#x} and loc op coord {loc[0]:#x} differ")

    ops = {(cm.a.off, cm.rva) for cm in df.cmps if _field(cm.a, entry, 4) and cm.b == Const(-1)}
    if len({off for off, _ in ops}) == 1:
        off, rva = min(ops, key=lambda x: x[1])
        out["MinimenuEntry.opIndex"] = (off, [session.evidence(rva, dispatch)],
                                        "compared with -1 (an entry aimed by a selected item or spell) before the raises")
        special = _special_ops(session, off)
        if special is not None:
            out["enums.minimenuOp"] = special

    strings = {}
    for a in df.addrs:
        v = a.value
        if isinstance(v, Add) and v.base == entry and any(_field(l.value, entry, 1) and l.value.off == v.off + flag_byte
                                                            for l in df.loads):
            strings.setdefault(v.off, a.rva)
    if len(strings) == 1:
        off, rva = next(iter(strings.items()))
        out["MinimenuEntry.option"] = (off, [session.evidence(rva, dispatch)], "the eastl string every op raise gets as its text")

    kinds = _subject_kinds(session, dispatch, df, entry, raisers, events)
    if kinds is not None:
        off, rva, table = kinds
        out["MinimenuEntry.subjectType"] = (off, [session.evidence(rva, dispatch)], "byte the op dispatch switches on")
        out["enums.minimenuSubject"] = (table, [session.evidence(rva, dispatch)],
                                        "jump-table case of each op raise: the subject kind value of that event")

    runs = model.callers(dispatch)
    if len(runs) == 1:
        out.update(_run_layout(session, runs[0], dispatch))
    else:
        session.notes.append(f"minimenu: the op dispatch has {len(runs)} callers")

    stride = out.get("MinimenuEntry.size", (None,))[0]
    entries = out.get("container.minimenu.list.entries", (None,))[0]
    if stride is not None and entries is not None:
        target = _target(session, stride, entries, flag_byte)
        if target is not None:
            out["MinimenuEntry.target"] = target

    out["MinimenuOpDispatch"] = (dispatch, [session.evidence(dispatch, dispatch, "rip")],
                                 "calls the NPC, loc and tile op raisers with the entry being run")
    rebuild = _rebuild(session, events)
    if rebuild is not None:
        out.update(_owner_layout(session, rebuild, out))
    return out


def _special_ops(session, op_off):
    """({TARGET: -1, EXAMINE: n}, evidence, note): the op index values the client tests entries for. -1 marks an entry
    aimed by a selected item or spell; the one other constant code compares the op index with (the menu filters and
    the shift-click test) is the Examine op every NPC, loc and obj gets."""
    model = session.model
    seen = {}
    for rva, fn in model.insns_with_disp(op_off, "CMP"):
        insn = model.insn(rva)
        if fn is None or insn is None or len(insn.ops) != 2 or insn.ops[1].kind != "imm":
            continue
        for cm in session.prov.facts(fn).cmps:
            if cm.rva == rva and isinstance(cm.a, Deref) and cm.a.off == op_off and cm.a.size == 4 \
                    and isinstance(cm.a.base, Arg) and isinstance(cm.b, Const) and cm.b.n != -1:
                seen.setdefault(cm.b.n, (rva, fn))
    if len(seen) != 1:
        session.notes.append(f"minimenu: op index compared with {sorted(hex(v) for v in seen)}, expected one constant")
        return None
    value, (rva, fn) = next(iter(seen.items()))
    return ({"TARGET": -1, "EXAMINE": value}, [session.evidence(rva, fn, "imm")],
            "-1 is the targeted entry the op dispatch tests; the other constant entries are compared with is Examine")


def _subject_kinds(session, dispatch, df, entry, raisers, events):
    """(subject byte offset, its load rva, {kind name: value}) from the dispatch's jump table, or None."""
    model = session.model
    for l in df.loads:
        v = l.value
        if not (isinstance(v, Deref) and isinstance(v.base, Global) and v.base.rva == 0 and v.size == 4 and v.scale == 4):
            continue
        index = v.index
        bias = 0
        if isinstance(index, Add):
            index, bias = index.base, index.off
        if not _field(index, entry, 1):
            continue
        bound = next((cm.b.n for cm in df.cmps if cm.a == v.index and isinstance(cm.b, Const)), None)
        if bound is None or not 0 < bound < 64:
            continue
        try:
            raw = model.pe.read(v.off, 4 * (bound + 1))
        except (AttributeError, TypeError, ValueError):
            return None
        if raw is None or len(raw) < 4 * (bound + 1):
            return None
        table = {}
        for case in range(bound + 1):
            start = int.from_bytes(raw[4 * case: 4 * case + 4], "little")
            name = _case_event(model, dispatch, start, raisers, events)
            kind = OP_EVENTS.get(name)
            if kind is not None and kind not in table:
                table[kind] = case - bias
        if {"NPC", "LOC", "TILE"} <= set(table):
            return index.off, l.rva, dict(sorted(table.items(), key=lambda kv: kv[1]))
    return None


def _case_event(model, dispatch, start, raisers, events):
    """The op event a jump-table case raises: follows the case's straight-line path (unconditional jumps inside the
    dispatch included) to the first raiser call, taking the event id from the raiser or the ECX it was handed."""
    pc, ecx = start, None
    for _ in range(CASE_STEPS):
        insn = model.insn(pc)
        if insn is None or insn.is_ret:
            return None
        if insn.mnem == "MOV" and len(insn.ops) == 2 and insn.ops[0].kind == "reg" and insn.ops[0].reg == "RCX" \
                and insn.ops[1].kind == "imm":
            ecx = insn.ops[1].imm
        if insn.is_call and insn.target is not None:
            target = model.resolve(insn.target)
            if target in raisers:
                event_id = raisers[target] if raisers[target] is not None else ecx
                return next((n for n, v in events.items() if v == event_id), None)
        if insn.is_jump:
            if insn.target is None or model.function_containing(insn.target) != dispatch:
                return None
            pc = insn.target
            continue
        if insn.fallthrough is None:
            return None
        pc = insn.fallthrough
    return None


def _run_layout(session, run, dispatch):
    """The rows, node and entry layout from the function that runs an entry, and the clicked copy from its copy."""
    model = session.model
    rf = session.prov.facts(run)
    out = {}
    to_dispatch = next((c for c in rf.calls if c.target is not None and model.resolve(c.target) == dispatch
                        and len(c.args) > 1), None)
    if to_dispatch is None:
        return out
    shape = None
    for member in to_dispatch.args[1].members():
        shape = _entry_shape(member)
        if shape is not None:
            break
    if shape is None:
        session.notes.append("minimenu: the entry the run passes on is not [list + entries] + index * stride")
        return out
    stride, entries, index_off, list_off = shape
    ev = [session.evidence(to_dispatch.rva, run, "rip")]
    out["MinimenuEntry.size"] = (stride, ev, "the entry index is scaled by this before the dispatch")
    out["container.minimenu.list.entries"] = (entries, ev, "entry array pointer in the list a node refers to")
    out["container.minimenu.node.index"] = (index_off, ev, "the node's entry index")
    out["container.minimenu.node.list"] = (list_off, ev, "the node's entry list (null for a submenu group)")
    children = {l.value.off for l in rf.loads if isinstance(l.value, Deref) and l.value.size == 8
                and l.value.off not in (index_off, list_off) and _is_ret(l.value.base)}
    if len(children) == 1:
        out["container.minimenu.node.children"] = (children.pop(), ev, "vector of child nodes a group descends into")
    rows = {c.args[0].off for c in rf.calls if c.args and isinstance(c.args[0], Add) and c.args[0].base == Arg(0)
            and c.target is not None and _returns_node(rf, c.target)}
    if len(rows) == 1:
        out["MinimenuView.rows"] = (rows.pop(), ev, "the view's rows, which the path lookup searches")
    stride_nodes = _node_stride(model, run)
    if stride_nodes is not None:
        out["container.minimenu.node.size"] = (stride_nodes, ev, "node size: rows and children vectors divide by it")

    copies = [c for c in rf.calls if c.target is not None and c.rva < to_dispatch.rva and len(c.args) > 1
              and c.args[0] == Arg(0) and c.args[1] == to_dispatch.args[1]]
    for c in copies:
        cf = session.prov.facts(model.resolve(c.target))
        deltas = {}
        for s in cf.stores:
            if _field(s.target, Arg(0), s.target.size) and isinstance(s.value, Deref) and s.value.base == Arg(1) \
                    and s.value.index is None:
                deltas.setdefault(s.target.off - s.value.off, []).append(s.rva)
        if not deltas:
            continue
        delta, rvas = max(deltas.items(), key=lambda kv: len(kv[1]))
        if len(rvas) < 8:
            continue
        out["MinimenuView.clicked"] = (delta, [session.evidence(rvas[0], c.target)],
                                       f"the run copies the entry here before dispatching it ({len(rvas)} field copies)")
        zeros = sorted((s.target.off, s.rva) for s in cf.stores if _field(s.target, Arg(0), 4) and s.value == Const(0)
                       and s.target.off >= delta + stride)
        if len(zeros) == 1:
            out["MinimenuView.clickCycles"] = (zeros[0][0], [session.evidence(zeros[0][1], c.target)],
                                               "zeroed by the copy; the client cycle increments it")
        out["MinimenuRun"] = (run, [session.evidence(run, run, "rip")], "runs the entry at a path: copies it, then dispatches it")
        break
    return out


def _entry_shape(v):
    """(stride, entries offset, node index offset, node list offset) of `[node + i]*stride + [[node + l] + e]`."""
    if not (hasattr(v, "a") and hasattr(v, "b")):
        return None
    for mul, base in ((v.a, v.b), (v.b, v.a)):
        if isinstance(mul, Mul) and isinstance(mul.factor, Const) and isinstance(mul.base, Deref) and mul.base.size == 8 \
                and isinstance(base, Deref) and base.size == 8 and isinstance(base.base, Deref) and base.base.size == 8:
            node = mul.base.base
            if base.base.base == node:
                return mul.factor.n, base.off, mul.base.off, base.base.off
    return None


def _is_ret(v):
    return type(v).__name__ == "Ret"


def _returns_node(facts, target):
    """Whether the run dereferences what the call returns (the path lookup hands back a node)."""
    return any(isinstance(l.value, Deref) and _is_ret(l.value.base) and l.value.base.target == target for l in facts.loads)


def _node_stride(model, run):
    """The node size from the run's `(end - begin) * magic >> n` division (0x2aaaaaaaaaaaaaab >> 2 divides by 24)."""
    body = model.insns(run)
    for i, insn in enumerate(body):
        if insn.mnem != "MOV" or len(insn.ops) != 2 or insn.ops[1].kind != "imm" or insn.ops[1].imm < 1 << 60:
            continue
        magic = insn.ops[1].imm
        for nxt in body[i + 1: i + STRIDE_WINDOW]:
            if nxt.mnem == "SAR" and len(nxt.ops) == 2 and nxt.ops[1].kind == "imm":
                return round((1 << 64) * (1 << nxt.ops[1].imm) / magic)
    return None


def _target(session, stride, entries, flag_byte):
    """(offset, evidence, note) of the target text: the entry string the "(level" function hands to its search."""
    model = session.model
    for _, fn in model.string_refs(LEVEL_STRING):
        if fn is None:
            continue
        facts = session.prov.facts(fn)
        offs = {}
        for c in facts.calls:
            for a in c.args:
                if isinstance(a, Add) and _is_entry(a.base, stride, entries):
                    offs.setdefault(a.off, c.rva)
        for off, rva in offs.items():
            if any(isinstance(l.value, Deref) and l.value.size == 1 and l.value.off == off + flag_byte
                   and _is_entry(l.value.base, stride, entries) for l in facts.loads):
                return off, [session.evidence(rva, fn)], 'the entry string the "(level" trim searches'
    return None


def _is_entry(v, stride, entries):
    """Whether v is `index * stride + [list + entries]`."""
    if not (hasattr(v, "a") and hasattr(v, "b")):
        return False
    for mul, base in ((v.a, v.b), (v.b, v.a)):
        if isinstance(mul, Mul) and mul.factor == Const(stride) and isinstance(base, Deref) and base.off == entries:
            return True
    return False


def _rebuild(session, events):
    """The function raising ON_MINIMENU_REBUILD through the no-payload raiser, or None."""
    model = session.model
    rebuild_id = events.get("ON_MINIMENU_REBUILD")
    dispatcher = session.values.get("functions.dispatchEvent")
    if rebuild_id is None or dispatcher is None:
        return None
    fns = {caller for site, caller in _sites(model, dispatcher) if _ecx_before(model, site, caller) == rebuild_id}
    if len(fns) != 1:
        session.notes.append(f"minimenu: {len(fns)} functions raise ON_MINIMENU_REBUILD")
        return None
    return next(iter(fns))


def _owner_layout(session, rebuild, found):
    """Client.minimenu and the owner's fields, from the rebuild and its caller."""
    model = session.model
    bf = session.prov.facts(rebuild)
    out = {}
    rows_in_view = found.get("MinimenuView.rows", (None,))[0]
    view = None
    for c in bf.calls:
        if c.target is None or not c.args:
            continue
        target = model.resolve(c.target)
        a0 = c.args[0]
        if a0 == Arg(0) and "Minimenu.open" not in out:
            tf = session.prov.facts(target)
            tests = [cm for cm in tf.cmps if _field(cm.a, Arg(0), 8) and cm.b == Const(0)]
            if len(tests) == 1 and len(tf.cmps) == 1:
                out["Minimenu.open"] = (tests[0].a.off, [session.evidence(tests[0].rva, target)],
                                        "the rebuild's first test: non-null while a menu is open, so it doesn't rebuild")
        elif isinstance(a0, Add) and a0.base == Arg(0) and rows_in_view is not None and view is None:
            tf = session.prov.facts(target)
            if any(c2.args and c2.args[0] == Add(Arg(0), rows_in_view) for c2 in tf.calls):
                view = (a0.off, c.rva)
    if view is not None:
        off, rva = view
        ev = [session.evidence(rva, rebuild)]
        out["Minimenu.view"] = (off, ev, "the menu view the rebuild rebuilds, whose rows the run reads")
        for key, sub in (("Minimenu.rows", "MinimenuView.rows"), ("Minimenu.clicked", "MinimenuView.clicked"),
                         ("Minimenu.clickCycles", "MinimenuView.clickCycles")):
            if sub in found:
                out[key] = (off + found[sub][0], ev + found[sub][1], f"Minimenu.view + {sub}")
    client = session.values.get("global.client")
    owners = set()
    for site, caller in model.call_sites(rebuild):
        for c in session.prov.facts(caller).calls:
            if c.rva == site and c.args and isinstance(c.args[0], Deref) and c.args[0].size == 8                     and c.args[0].base == Arg(0) and c.args[0].index is None:
                owners.add((c.args[0].off, site, caller))
    offs = {o for o, _, _ in owners}
    users = [found[k][0] for k in ("MinimenuOpDispatch", "MinimenuRun") if k in found]
    if len(offs) == 1 and client is not None and _client_getter(session, client, next(iter(offs)), users):
        off, site, caller = min(owners, key=lambda o: o[1])
        out["Client.minimenu"] = (off, [session.evidence(site, caller, "rip")],
                                  "the rebuild's caller hands it [client + offset]; the op code's getter loads it too")
    return out


def _client_getter(session, client, off, users):
    """Whether one of the op functions calls a getter with [global.client] that (itself or through its callee) loads
    [Arg0 + off]: the op code reaching the minimenu."""
    model = session.model
    for fn in users:
        for c in session.prov.facts(fn).calls:
            if c.target is None or not c.args or c.args[0] != Deref(Global(client), 0, 8):
                continue
            getter = model.resolve(c.target)
            for g in [getter] + list(model.callees(getter)):
                if any(_field(l.value, Arg(0), 8) and l.value.off == off for l in session.prov.facts(g).loads):
                    return True
    return False


def _field(v, base, size):
    return isinstance(v, Deref) and v.base == base and v.index is None and (size == 0 or v.size == size)


def _const(v):
    return v.n if isinstance(v, Const) else None
