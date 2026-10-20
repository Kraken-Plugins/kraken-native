"""Hook targets that are not Lua events: the functions that add and remove players and ground items, the packet
decoders that apply actor updates, found from the struct fields they write (see docs/event-hooks-design.md §2.5).

  obj_pile_ops      ObjAdd / ObjDel / ObjCount: the zone-update handlers for ground items. ObjAdd is the one function
                    that allocates (operator new), stores GroundItem.id and GroundItem.count and reads Client.tickCount
                    for the despawn timers; its siblings (same callers) compare id and count, and either rewrite the
                    count (ObjCount) or unlink the entry after loading its ObjType (ObjDel).
  player_lifecycle  PlayerAdd: the caller of the Player constructor (an Actor constructor caller) that stores
                    Player.index into the new object. PlayerRemove: called from PlayerAdd's caller, reads Player.index
                    and Actor.worldId of the player it drops. PlayerInfoDecode: the packet handler's callee that
                    receives the same packet stack slot as the NPC info decoder and reaches PlayerAdd.
"""

SLOT_WINDOW = 10          # a call site loads the packet slot address within this many instructions of the call
REACH_DEPTH = 3           # how many calls deep PlayerAdd may sit under the player info decoder


def obj_pile_ops(session, p):
    model = session.model
    id_off = session.value("GroundItem.id")
    count_off = session.value("GroundItem.count")
    tick_off = session.value("Client.tickCount")
    new_fn = session.fn("@OperatorNew")
    obj_loader = session.fn("@ConfigLoader.10")

    def with_op(off, mnem):
        return {fn for _, fn in model.insns_with_disp(off, mnem) if fn is not None and _field_op(model, fn, off, mnem)}

    stores_id, stores_count = with_op(id_off, "MOV"), with_op(count_off, "MOV")
    cmps_id, cmps_count = with_op(id_off, "CMP"), with_op(count_off, "CMP")
    allocating = set(model.callers(new_fn))
    ticking = {fn for _, fn in model.insns_with_disp(tick_off) if fn is not None}

    adds = sorted(stores_id & stores_count & allocating & ticking)
    if len(adds) != 1:
        session.notes.append(f"obj_pile_ops: {len(adds)} functions store GroundItem.id and .count, allocate and read tickCount")
        return {}
    add = adds[0]
    siblings = set(model.callers(add))

    def sibling(fn):
        return fn != add and bool(siblings & set(model.callers(fn)))

    out = {"ObjAdd": (add, [session.evidence(_op_rva(model, add, count_off, "MOV"), add)],
                      "allocates, stores GroundItem.id and .count, reads Client.tickCount")}
    counts = sorted(fn for fn in (cmps_id & cmps_count & stores_count) - allocating if sibling(fn))
    if len(counts) == 1:
        out["ObjCount"] = (counts[0], [session.evidence(_op_rva(model, counts[0], count_off, "MOV"), counts[0])],
                           "sibling of ObjAdd comparing id and count, then storing the count")
    dels = sorted(fn for fn in (cmps_id & cmps_count) - stores_count - allocating
                  if sibling(fn) and obj_loader in model.callees(fn))
    if len(dels) == 1:
        out["ObjDel"] = (dels[0], [session.evidence(_op_rva(model, dels[0], count_off, "CMP"), dels[0])],
                         "sibling of ObjAdd comparing id and count, loading the ObjType, no count store")
    return out


def player_lifecycle(session, p):
    model = session.model
    actor_ctor = session.fn("@ActorCtor")
    index_off = session.value("Player.index")
    world_off = session.value("Actor.worldId")
    npc_info = session.fn("@NpcInfoDecode")

    adds = []
    for ctor in model.callers(actor_ctor):
        for caller in model.callers(ctor):
            rva = _op_rva(model, caller, index_off, "MOV", store=True)
            if rva is not None:
                adds.append((caller, ctor, rva))
    if len(adds) != 1:
        session.notes.append(f"player_lifecycle: {len(adds)} Actor-constructor callers store Player.index")
        return {}
    add, ctor, rva = adds[0]
    out = {"PlayerCtor": (ctor, [session.evidence(ctor, ctor, "rip")], "Actor constructor caller whose caller stores Player.index"),
           "PlayerAdd": (add, [session.evidence(rva, add)], "constructs a Player and stores Player.index")}

    removes = set()
    for adder in model.callers(add):
        for fn in model.callees(adder):
            if fn != add and _op_rva(model, fn, index_off, "MOV") is not None and _op_rva(model, fn, world_off, "MOV") is not None:
                removes.add(fn)
    if len(removes) == 1:
        fn = next(iter(removes))
        out["PlayerRemove"] = (fn, [session.evidence(_op_rva(model, fn, index_off, "MOV"), fn)],
                               "called beside PlayerAdd, reads Player.index and Actor.worldId")

    handlers = model.callers(npc_info)
    if len(handlers) == 1:
        handler = handlers[0]
        slots = _slots_before(model, handler, npc_info)
        infos = set()
        for insn in model.insns(handler):
            if insn.is_call and insn.target is not None:
                target = model.resolve(insn.target)
                if target != npc_info and _slot_passed(model, handler, insn.rva, slots) and _reaches(model, target, add, REACH_DEPTH):
                    infos.add(target)
        if len(infos) == 1:
            fn = next(iter(infos))
            out["PlayerInfoDecode"] = (fn, [session.evidence(fn, fn, "rip")],
                                       "packet handler callee taking the NPC info decoder's packet slot and reaching PlayerAdd")
    return out


def _field_op(model, fn, off, mnem, store=False):
    return _op_rva(model, fn, off, mnem, store) is not None


def _op_rva(model, fn, off, mnem, store=False):
    """The first instruction of fn with a 4-byte register-based memory operand at off (as ops[0] when store)."""
    for insn in model.insns(fn):
        if insn.mnem != mnem:
            continue
        ops = insn.ops[:1] if store else insn.ops
        for op in ops:
            if op.kind == "mem" and op.disp == off and op.base not in (None, "RSP", "RIP") and op.size in (0, 4):
                return insn.rva
    return None


def _slots_before(model, handler, callee):
    """Stack slots (RSP + n) whose address the handler loads just before calling callee."""
    slots = set()
    body = model.insns(handler)
    for i, insn in enumerate(body):
        if insn.is_call and insn.target is not None and model.resolve(insn.target) == callee:
            for prev in reversed(body[max(0, i - SLOT_WINDOW): i]):
                if prev.is_call:
                    break
                if _lea_slot(prev) is not None:
                    slots.add(_lea_slot(prev))
    return slots


def _slot_passed(model, handler, call_rva, slots):
    """Whether the call at call_rva is preceded (with no other call between) by LEA reg, [RSP + one of slots]."""
    body = model.insns(handler)
    idx = next((i for i, insn in enumerate(body) if insn.rva == call_rva), None)
    if idx is None:
        return False
    for prev in reversed(body[max(0, idx - SLOT_WINDOW): idx]):
        if prev.is_call:
            return False
        if _lea_slot(prev) in slots:
            return True
    return False


def _lea_slot(insn):
    if insn.mnem == "LEA" and len(insn.ops) == 2 and insn.ops[1].kind == "mem" and insn.ops[1].base == "RSP":
        return insn.ops[1].disp
    return None


def _reaches(model, fn, target, depth):
    seen, frontier = {fn}, [fn]
    for _ in range(depth):
        nxt = []
        for f in frontier:
            for c in model.callees(f):
                if c == target:
                    return True
                if c not in seen:
                    seen.add(c)
                    nxt.append(c)
        frontier = nxt
    return False
