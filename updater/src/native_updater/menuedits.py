"""Where the hook DLL edits the minimenu (the right-click menu) the client builds, for menu swaps, recolours and rows of
Kraken's own (docs/developer/menu-edits.md). Everything hangs off the functions minimenu.py found: the rebuild, which
the client runs every cycle while the menu is closed, and the run, which a click on a row goes through.

  view rebuild(view, ?)   The rebuild's callee handed owner + Minimenu.view. It clears the view's rows and builds them
                          again from the entry lists, so the DLL edits the rows right after it returns and puts its own
                          rows back out right before it runs.
  node destroy(&children) What the rows clear calls on each node's children slot (node + container.minimenu.node.
                          children): it destroys a group's child nodes (calling itself for theirs), frees their vector
                          and deletes the vector object. The DLL guards it, so a child vector of its own is never freed.
  node reserve(vector, n) The tree builder's call that grows a node vector to hold n nodes: it divides the vector's
                          capacity span by the node size (a multiply by the size's magic and a shift) and tail-jumps to
                          the reallocation when n is larger. The DLL makes room for its rows with it.
  text assign(dst, src)   What the run's copy calls with (clicked + MinimenuEntry.target, entry + target) and the same
                          for the option: the assignment of the entry's fixed-capacity strings, which allocates with the
                          client's own allocator when the text doesn't fit. The DLL rewrites options and targets with it.

Output keys: functions.minimenuViewRebuild, functions.minimenuNodeDestroy, functions.minimenuNodeReserve,
functions.minimenuTextAssign.
"""
from .analysis.provenance import Add, Arg

RESERVE_MAX_INSNS = 20      # the reserve is a short check in front of the reallocation
MAGIC_MIN = 1 << 60         # a division by a small constant multiplies by a 64-bit magic at least this large


def menu_edit_points(session, p):
    model = session.model
    view = session.value("Minimenu.view")
    rows = session.value("MinimenuView.rows")
    children = session.value("container.minimenu.node.children")
    node_size = session.value("container.minimenu.node.size")
    rebuild = session.fn("@MinimenuRebuild")
    out = {}

    rebuild_calls = [c for c in session.prov.facts(rebuild).calls
                     if c.target is not None and c.args and c.args[0] == Add(Arg(0), view)]
    if len(rebuild_calls) != 1:
        session.notes.append(f"menuedits: the rebuild hands owner + view to {len(rebuild_calls)} calls")
        return out
    view_rebuild = model.resolve(rebuild_calls[0].target)
    out["functions.minimenuViewRebuild"] = (view_rebuild, [session.evidence(rebuild_calls[0].rva, rebuild, "rip")],
                                            "the rebuild's call with owner + Minimenu.view: clears and builds the rows")

    on_rows = [model.resolve(c.target) for c in session.prov.facts(view_rebuild).calls
               if c.target is not None and c.args and c.args[0] == Add(Arg(0), rows)]
    destroy = None
    clear = None
    for fn in on_rows:
        found = _children_destroy(model, fn, children)
        if found is not None:
            clear, destroy = fn, found
            break
    if destroy is None:
        session.notes.append("menuedits: no callee of the view rebuild destroys each row's children")
    else:
        out["functions.minimenuNodeDestroy"] = (destroy, [session.evidence(destroy, destroy, "rip")],
                                                "called by the rows clear with each node's children slot; calls itself "
                                                "for the child nodes")

    reserves = set()
    for fn in on_rows:
        if fn == clear:
            continue
        for callee in model.callees(fn):
            if _is_reserve(model, callee, node_size):
                reserves.add(callee)
    if len(reserves) == 1:
        reserve = reserves.pop()
        out["functions.minimenuNodeReserve"] = (reserve, [session.evidence(reserve, reserve, "rip")],
                                                "the tree builder's node vector reserve: divides the capacity span by "
                                                "the node size and reallocates when it is short")
    else:
        session.notes.append(f"menuedits: {len(reserves)} node vector reserves among the tree builder's callees")

    assign = _text_assign(session)
    if assign is not None:
        out["functions.minimenuTextAssign"] = assign
    return out


def _children_destroy(model, fn, children):
    """The function fn calls as `LEA RCX,[node + children]; CALL f` where f calls itself: the node destroy."""
    body = model.insns(fn)
    for i, insn in enumerate(body[:-1]):
        if insn.mnem != "LEA" or len(insn.ops) != 2 or insn.ops[0].kind != "reg" or insn.ops[0].reg != "RCX":
            continue
        src = insn.ops[1]
        if src.kind != "mem" or src.disp != children or src.index is not None:
            continue
        nxt = body[i + 1]
        if nxt.is_call and nxt.target is not None:
            target = model.resolve(nxt.target)
            if target in model.callees(target):
                return target
    return None


def _is_reserve(model, fn, node_size):
    """Whether fn is a short check dividing a vector's span by the node size and tail-jumping on."""
    body = model.insns(fn)
    if not body or len(body) > RESERVE_MAX_INSNS or not (body[-1].mnem == "JMP" or any(i.mnem == "JMP" for i in body)):
        return False
    for i, insn in enumerate(body):
        if insn.mnem == "MOV" and len(insn.ops) == 2 and insn.ops[1].kind == "imm" and insn.ops[1].imm >= MAGIC_MIN:
            magic = insn.ops[1].imm
            for nxt in body[i + 1:]:
                if nxt.mnem == "SAR" and len(nxt.ops) == 2 and nxt.ops[1].kind == "imm":
                    size = round((1 << 64) * (1 << nxt.ops[1].imm) / magic)
                    reads_capacity = any(j.mnem in ("MOV", "SUB") and len(j.ops) == 2 and j.ops[1].kind == "mem"
                                         and j.ops[1].disp == 0x10 and j.ops[1].base == "RCX" for j in body)
                    tail = any(j.mnem == "JMP" and j.target is not None and model.function_containing(j.target) != fn
                               for j in body)
                    return size == node_size and reads_capacity and tail
    return False


def _text_assign(session):
    """(fn, evidence, note) of the entry string assignment: the run's copy calls it with the clicked copy's target and
    the entry's, and again with the options."""
    model = session.model
    run = session.fn("@MinimenuRun")
    clicked = session.value("MinimenuView.clicked")
    target = session.value("MinimenuEntry.target")
    option = session.value("MinimenuEntry.option")
    for c in session.prov.facts(run).calls:
        if c.target is None or len(c.args) < 2 or c.args[0] != Arg(0):
            continue
        copy = model.resolve(c.target)
        found = {}
        for cc in session.prov.facts(copy).calls:
            if cc.target is None or len(cc.args) < 2:
                continue
            for field in (target, option):
                if cc.args[0] == Add(Arg(0), clicked + field) and cc.args[1] == Add(Arg(1), field):
                    found[field] = (model.resolve(cc.target), cc.rva)
        if len(found) == 2 and found[target][0] == found[option][0]:
            fn, rva = found[target]
            return (fn, [session.evidence(rva, copy, "rip")],
                    "the run's copy assigns the entry's target and option to the clicked copy with it")
    session.notes.append("menuedits: the run's copy assigns no target and option through one function")
    return None
