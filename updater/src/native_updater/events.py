"""The client's native event raisers: where each `osrs.Events` id is fired, so a hook can catch it.

The client raises its Lua plugin events (ON_GAME_TICK, ON_NPC_LOAD, ...) through a family of small wrapper functions,
one per argument shape. Every wrapper has the same opening:

    MOV  RCX, [global.client]
    ADD  RCX, Client.pluginGate          ; the optional plugin manager slot
    CALL gateTest                        ; false when Lua plugins are off: nothing is raised
    TEST AL, AL / JZ ...
    MOV  RCX, [RCX]
    CALL runtimeGetter                   ; the Lua runtime (one getter for immediate raises, one for deferred ones)
    ... MOV EDX, <event id> ...          ; fixed id, or the wrapper's first argument passed through
    CALL/JMP fire<Args...>               ; the sol2 call, or the pending-flag setter for *_TRANSMIT events

Seed: the server-tick site, `INC dword ptr [client + Client.tickCount]; MOV ECX, ON_GAME_TICK; CALL dispatcher`, which
pins the no-payload dispatcher (`functions.dispatchEvent`). Its body gives the gate offset, the gate test and the
runtime getter; every function with the same opening is a raiser. A raiser's event id is either hard-coded in its body
(the `[RSP+x] = id` dword/qword pair of an inlined fire body, else `MOV EDX, id` before the fire call) or passed in ECX
by its callers, in which case the ids come from the `MOV ECX, id` before each call site. The argument count is how
many of RCX, RDX, R8, R9 the wrapper reads before testing the gate.

A hook on a raiser's entry sees every raise, gated or not, because the detour runs before the gate test.

Output keys (see `event_raisers`):
  events.<NAME>            [{fn, idArg | id, args}, ...] one hook site per raiser of that event
  functions.dispatchEvent  the no-payload raiser (id in ECX)
  Client.pluginGate        the slot the gate test reads
"""

ARG_REGS = ("RCX", "RDX", "R8", "R9")
GATE_WINDOW = 24           # a raiser tests the gate within this many instructions of its entry
SITE_WINDOW = 14           # a call site sets ECX within this many instructions before the call


def event_raisers(session, p):
    model = session.model
    tick_off = session.value("Client.tickCount")
    events = session.value("enums.events")
    by_id = {v: k for k, v in events.items() if isinstance(v, int) and v >= 0}
    tick_id = events.get("ON_GAME_TICK")
    if tick_id is None:
        return {}

    seed = _tick_site(model, tick_off, tick_id)
    if seed is None:
        session.notes.append("event_raisers: no INC [client+tickCount]; MOV ECX, ON_GAME_TICK; CALL site")
        return {}
    site, site_fn, dispatcher = seed
    anatomy = _anatomy(model, dispatcher)
    if anatomy is None:
        session.notes.append(f"event_raisers: dispatcher {dispatcher:#x} does not have the gate/runtime shape")
        return {}
    gate_off, gate_rva, gate_fn, runtime_core = anatomy

    out = {
        "functions.dispatchEvent": (dispatcher, [session.evidence(site, site_fn, "rip")],
                                    f"called with ON_GAME_TICK right after INC [client+tickCount] at {site:#x}"),
        "Client.pluginGate": (gate_off, [session.evidence(gate_rva, dispatcher, "imm")],
                              f"client + offset tested by {gate_fn:#x} before every raise"),
    }
    hooks = {}
    for fn in model.callers(gate_fn):
        raiser = _raiser(model, fn, gate_off, gate_fn, runtime_core, by_id)
        if raiser is None:
            continue
        ids, descriptor, note, rva = raiser
        for event_id in ids:
            name = by_id.get(event_id)
            if name is not None:
                hooks.setdefault(name, []).append((descriptor, session.evidence(rva, fn, "imm"), note))
    for name, sites in hooks.items():
        sites.sort(key=lambda s: s[0]["fn"])
        out[f"events.{name}"] = ([s[0] for s in sites], [s[1] for s in sites][:4], "; ".join(s[2] for s in sites))
    return out


def _tick_site(model, tick_off, tick_id):
    """(call rva, function, dispatcher) of `INC [reg+tickCount]; MOV ECX, tick_id; CALL x`, or None."""
    for rva, fn in model.insns_with_disp(tick_off, "INC"):
        if fn is None:
            continue
        body = model.insns(fn)
        idx = next((i for i, insn in enumerate(body) if insn.rva == rva), None)
        if idx is None:
            continue
        ecx_set = False
        for insn in body[idx + 1: idx + 6]:
            if insn.mnem == "MOV" and _is_reg(insn.ops[0], "RCX") and insn.ops[1].kind == "imm":
                ecx_set = insn.ops[1].imm == tick_id
            elif insn.is_call and insn.target is not None:
                if ecx_set:
                    return insn.rva, fn, model.resolve(insn.target)
                break
            elif _writes(insn, "RCX"):
                break
    return None


def _anatomy(model, dispatcher):
    """(gate offset, its ADD rva, gate test fn, runtime getter core) from the dispatcher's opening."""
    body = model.insns(dispatcher)
    gate = None
    calls = []
    for insn in body:
        if insn.mnem == "ADD" and _is_reg(insn.ops[0], "RCX") and insn.ops[1].kind == "imm" and gate is None:
            gate = (insn.ops[1].imm, insn.rva)
        elif (insn.is_call or insn.is_jump) and insn.target is not None:
            calls.append(model.resolve(insn.target))
    if gate is None or len(calls) < 3:
        return None
    core = _getter_core(model, calls[1])
    if core is None:
        return None
    return gate[0], gate[1], calls[0], core


def _getter_core(model, fn):
    """The runtime getters are `MOV EDX, n; JMP core`: siblings share the core and differ in n."""
    body = model.insns(fn)
    if len(body) == 2 and body[0].mnem == "MOV" and body[1].is_jump and body[1].target is not None:
        return body[1].target
    return None


def _raiser(model, fn, gate_off, gate_fn, runtime_core, by_id):
    """Classify a caller of the gate test. Returns (event ids, hook descriptor, note, evidence rva) or None."""
    body = model.insns(fn)
    gate_idx = next((i for i, insn in enumerate(body[:GATE_WINDOW])
                     if insn.is_call and insn.target is not None and model.resolve(insn.target) == gate_fn), None)
    if gate_idx is None:
        return None
    prologue = body[:gate_idx]
    if not any(insn.mnem == "ADD" and insn.ops and insn.ops[1].kind == "imm" and insn.ops[1].imm == gate_off for insn in prologue):
        return None
    getters = {model.resolve(insn.target) for insn in body if (insn.is_call or insn.is_jump) and insn.target is not None}
    getters = {g for g in getters if g != gate_fn and _getter_core(model, g) == runtime_core}
    if not getters:
        return None

    args = _arg_count(prologue)
    fixed = _fixed_ids(model, body, gate_fn, getters, by_id)
    if len(fixed) == 1:
        event_id = next(iter(fixed))
        rva = next(insn.rva for insn in body if insn.ops and insn.ops[-1].kind == "imm" and insn.ops[-1].imm == event_id)
        return [event_id], {"fn": fn, "id": event_id, "args": args}, f"raiser {fn:#x}, id fixed", rva
    if len(fixed) > 1 or not _reads_arg(prologue, "RCX"):
        return None
    ids = {}
    for site, caller in _sites(model, fn):
        event_id = _ecx_before(model, site, caller)
        if event_id in by_id:
            ids.setdefault(event_id, site)
    if not ids:
        return None
    return sorted(ids), {"fn": fn, "idArg": 0, "args": args}, f"raiser {fn:#x}, id in RCX", min(ids.values())


def _fixed_ids(model, body, gate_fn, getters, by_id):
    """Ids hard-coded in a raiser: the `[RSP+x] = id` dword/qword pair of an inlined fire body, else `MOV EDX, id`
    before a fire call or jump."""
    edx, stack = set(), set()
    pending = None
    for insn in body:
        if insn.mnem == "MOV" and len(insn.ops) == 2 and insn.ops[1].kind == "imm":
            dst, imm = insn.ops[0], insn.ops[1].imm
            if _is_reg(dst, "RDX") and imm in by_id:
                pending = imm
            elif dst.kind == "mem" and dst.base == "RSP" and imm in by_id:
                stack.add((dst.size, imm))
        elif (insn.is_call or insn.is_jump) and insn.target is not None:
            target = model.resolve(insn.target)
            if pending is not None and target != gate_fn and target not in getters:
                edx.add(pending)
            pending = None
        elif pending is not None and _writes(insn, "RDX"):
            pending = None
    paired = {imm for size, imm in stack if size == 4 and (8, imm) in stack}
    return paired or edx


def _arg_count(prologue):
    """1 + the highest argument register (RDX, R8, R9) the wrapper reads before overwriting it."""
    highest = 0
    for i, reg in enumerate(ARG_REGS):
        if i > highest and _reads_arg(prologue, reg):
            highest = i
    return highest + 1


def _reads_arg(prologue, reg):
    """Whether the wrapper reads the incoming value of reg: a read before any write of it."""
    for insn in prologue:
        if _reads(insn, reg):
            return True
        if _writes(insn, reg):
            return False
    return False


def _sites(model, fn):
    """(rva, calling function) of every call of fn, plus tail jumps into it."""
    sites = list(model.call_sites(fn))
    sites.extend((src, caller) for src, caller, _ in model.xrefs_to(fn, ("jump",)) if caller is not None and caller != fn)
    return sorted(set(sites))


def _ecx_before(model, site, caller):
    """The immediate a call site loads into ECX, or None if ECX comes from elsewhere."""
    body = model.insns(caller)
    idx = next((i for i, insn in enumerate(body) if insn.rva == site), None)
    if idx is None:
        return None
    for insn in reversed(body[max(0, idx - SITE_WINDOW): idx]):
        if insn.mnem == "MOV" and len(insn.ops) == 2 and _is_reg(insn.ops[0], "RCX") and insn.ops[1].kind == "imm":
            return insn.ops[1].imm
        if _writes(insn, "RCX") or insn.is_call:
            return None
    return None


def _is_reg(op, reg):
    return op.kind == "reg" and op.reg == reg


def _reads(insn, reg):
    """Whether the instruction reads reg: as a source operand, as the base or index of a memory operand, or as the
    destination of a read-modify-write."""
    for i, op in enumerate(insn.ops):
        if op.kind == "mem" and (op.base == reg or op.index == reg):
            return True
        if op.kind == "reg" and op.reg == reg and (i > 0 or insn.mnem not in ("MOV", "MOVSXD", "MOVZX", "LEA", "XOR")):
            return True
    return False


def _writes(insn, reg):
    return insn.mnem in ("MOV", "MOVSXD", "MOVZX", "LEA", "XOR", "ADD", "SUB", "POP") and insn.ops \
        and insn.ops[0].kind == "reg" and insn.ops[0].reg == reg
