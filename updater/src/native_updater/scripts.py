"""The client's script runtime (cs2): where scripts start and end, so the hook DLL can tell the API about each one.

Every script runs on one static runner. A root script comes from a component's listener as a request (the listener's
argument list, the source and target components, the op and sub-op, the mouse position, the key, the op's base name)
or from a trigger. The request entry resolves the script (the first argument is its id), resets the stacks, copies
the remaining arguments into the runner's locals, replacing the event placeholders (0x80000001 for the mouse x and so
on) with the request's fields and the string "event_opbase" with its base name, then calls the interpreter loop with
the runner and the script. The loop fetches each op and jumps through a byte table of case indices and a table of
case addresses. Op 40 (gosub) looks the callee up by the id in the script's int operands, pushes a frame holding the
caller's script and locals, pops the callee's arguments off the stacks into its locals and swaps them in; op 21
(return) at depth 0 ends the run, else pops the frame, clears its script slot and swaps the caller's locals back. The
lookup is called from the gosub case and the request entry only, and the locals swap from the gosub and return cases
only, so hooks on the four functions see every root run, gosub and return.

The runner holds an int stack and a stack of VarValues (a variant of undefined, int, string, coord, ... and long, its
kind in a byte), each with its size, and the frame count. The loop reads the stacks, the sizes and the frame count off
its first argument; a loaded script stores its id where the lookup puts it.

Output keys (see `script_runner`): functions.executeScript, functions.executeScriptInner, functions.scriptLookup,
functions.scriptSwapLocals, global.scriptRunner, ScriptRunner.{intStack, intStackSize, objStack, objStackSize,
frameCount}, ScriptFrame.script, ClientScript.id, VarValue.{type, size}, HookReq.{argCount, args, source, target, op,
subOp, mouseX, mouseY, keyCode, keyChar, opbase}, enums.varValueType.
"""
from .analysis.provenance import Add, Arg, Const, Deref, Global, Out, walk

OPBASE = "event_opbase"
OP_RETURN = 21
OP_GOSUB = 40
DISPATCH_WINDOW = 8         # instructions from the bound check to the jump through the case table
# The placeholders a listener's arguments may hold -> (the request field the entry loads instead, the component field
# it reads through that pointer, if any)
PLACEHOLDERS = {
    0x80000001: ("HookReq.mouseX", None),
    0x80000002: ("HookReq.mouseY", None),
    0x80000003: ("HookReq.source", "IfType.packedId"),
    0x80000004: ("HookReq.op", None),
    0x80000005: ("HookReq.source", "IfType.subId"),
    0x80000006: ("HookReq.target", "IfType.packedId"),
    0x80000007: ("HookReq.target", "IfType.subId"),
    0x80000008: ("HookReq.keyCode", None),
    0x80000009: ("HookReq.keyChar", None),
    0x8000000a: ("HookReq.subOp", None),
}


def script_runner(session, p):
    model = session.model
    refs = sorted({fn for _, fn in model.string_refs(OPBASE) if fn is not None})
    if len(refs) != 1:
        session.notes.append(f"scripts: {len(refs)} functions reference {OPBASE!r}")
        return {}
    run = refs[0]
    facts = session.prov.facts(run)
    out, arg_stride = _request(session, run, facts)

    inner_calls = [c for c in facts.calls if c.target is not None and len(c.args) > 2
                   and c.args[0] == Arg(0) and c.args[2] == Arg(2)]
    if len({model.resolve(c.target) for c in inner_calls}) != 1:
        session.notes.append(f"scripts: the request entry {run:#x} passes its runner and op limit to "
                             f"{len(inner_calls)} calls")
        return out
    inner = model.resolve(inner_calls[0].target)
    entry_sites = model.call_sites(run)
    if entry_sites:
        out["functions.executeScript"] = (run, [session.evidence(entry_sites[0][0], entry_sites[0][1], "rip")],
                                          f"the one function referencing {OPBASE!r}: runs a listener's request")
    out["functions.executeScriptInner"] = (inner, [session.evidence(inner_calls[0].rva, run, "rip")],
                                           "called by the request entry with its runner and op limit")

    runner = _runner_global(session, [run, inner])
    if runner is not None:
        out["global.scriptRunner"] = runner
    type_off = out["VarValue.type"][0] if "VarValue.type" in out else None
    out.update(_interpreter(session, run, inner, type_off))
    size = out.get("VarValue.size")
    if size is not None and arg_stride is not None and size[0] != arg_stride:
        session.notes.append(f"scripts: the VarValue stack's stride is {size[0]:#x}, the request's arguments' {arg_stride:#x}")
    return out


def _request(session, run, facts):
    """The request's fields, from the loads that replace each placeholder, the argument loop and the argument kinds,
    and the stride of the argument array (the loop's kind compares read the second argument, so their offset less the
    first's), or None."""
    out = {}
    cmps = sorted(facts.cmps, key=lambda c: c.rva)
    loads = sorted(facts.loads, key=lambda l: l.rva)
    marks = [(c.rva, c.b.n) for c in cmps if isinstance(c.b, Const) and c.b.n in PLACEHOLDERS]
    if not marks:
        session.notes.append(f"scripts: the request entry {run:#x} compares no argument with a placeholder")
        return out, None
    fields = {}
    for k, (rva, const) in enumerate(marks):
        end = marks[k + 1][0] if k + 1 < len(marks) else None
        key, through = PLACEHOLDERS[const]
        found = None
        for load in loads:
            if load.rva <= rva or (end is not None and load.rva >= end):
                continue
            field = _request_field(load.value)
            if field is not None:
                found = (load, field)
                break
        if found is None:
            session.notes.append(f"scripts: no request field loaded for placeholder {const:#x}")
            continue
        load, (off, inner_off) = found
        if (through is None) != (inner_off is None):
            session.notes.append(f"scripts: placeholder {const:#x} loads {load.value!r}")
            continue
        if through is not None:
            expected = session.values.get(through)
            if expected is not None and expected != inner_off:
                session.notes.append(f"scripts: placeholder {const:#x} reads the component's {inner_off:#x}, "
                                     f"{through} is {expected:#x}")
            pointer = next((l for l in loads if rva < l.rva <= load.rva and l.value == Deref(Arg(1), off, 8)), load)
            load = pointer
        if key in fields and fields[key][0] != off:
            session.notes.append(f"scripts: placeholders disagree on {key}: {fields[key][0]:#x} and {off:#x}")
            continue
        fields.setdefault(key, (off, [session.evidence(load.rva, run, "disp")],
                                f"loaded in place of the placeholder {const:#x}"))
    out.update(fields)

    opbase = min((rva for rva, fn in session.model.string_refs(OPBASE) if fn == run), default=None)
    name = next((a for a in sorted(facts.addrs, key=lambda a: a.rva) if opbase is not None and a.rva > opbase
                 and isinstance(a.value, Add) and a.value.base == Arg(1)), None)
    if name is None:
        session.notes.append(f"scripts: the request entry takes no request field's address after {OPBASE!r}")
    else:
        out["HookReq.opbase"] = (name.value.off, [session.evidence(name.rva, run, "disp")],
                                 f"the string the entry copies in place of {OPBASE!r}")

    last_mark = marks[-1][0]
    count = next((c for c in cmps if c.rva > last_mark and any(_request_int(side) for side in (c.a, c.b))), None)
    if count is not None:
        side = next(side for side in (count.a, count.b) if _request_int(side))
        out["HookReq.argCount"] = (side.off, [session.evidence(count.rva, run, "disp")],
                                   "the bound of the loop copying the arguments into the locals")

    tags = [c for c in cmps if isinstance(c.b, Const) and _tag(c.a) is not None]
    if not tags:
        session.notes.append("scripts: no compare of an argument's kind")
        return out, None
    type_off = min(c.a.off for c in tags)
    first = next(c for c in tags if c.a.off == type_off)
    array = next((l for l in loads if l.value == first.a.base), None)
    out["HookReq.args"] = (first.a.base.off, [session.evidence(array.rva if array else first.rva, run, "disp")],
                           "the argument array whose first entry's kind the entry checks")
    out["VarValue.type"] = (type_off, [session.evidence(first.rva, run, "disp")],
                            "the kind byte of an argument (a VarValue)")
    loop = [c for c in tags if c.a.off > type_off]
    out.update(_kinds(session, run, loop, marks, loads))
    strides = {c.a.off - type_off for c in loop}
    return out, (strides.pop() if len(strides) == 1 else None)


def _request_int(value):
    """True for a 4-byte load of [request + offset]."""
    return isinstance(value, Deref) and value.base == Arg(1) and value.size == 4 and value.index is None


def _request_field(value):
    """(offset, component offset or None) for a 4-byte load of [request + offset] or [[request + offset] + field]."""
    if not isinstance(value, Deref) or value.size != 4 or value.index is not None:
        return None
    if value.base == Arg(1):
        return value.off, None
    base = value.base
    if isinstance(base, Deref) and base.base == Arg(1) and base.size == 8 and base.index is None:
        return base.off, value.off
    return None


def _tag(value):
    """The VarValue a one-byte kind load reads, or None: [[request + args] + n] in the argument loop."""
    if isinstance(value, Deref) and value.size == 1 and value.index is None and isinstance(value.base, Deref) \
            and value.base.base == Arg(1) and value.base.size == 8:
        return value
    return None


def _kinds(session, run, loop_tags, marks, loads):
    """enums.varValueType: the kind the int branch checks before the placeholders, the kind the string branch checks
    before comparing with "event_opbase", and the kind whose branch copies eight bytes of the value."""
    opbase = [rva for rva, _ in session.model.string_refs(OPBASE)]
    if not loop_tags or not opbase:
        session.notes.append("scripts: no argument kind compares in the argument loop")
        return {}
    before_marks = [c for c in loop_tags if c.rva < marks[0][0]]
    before_opbase = [c for c in loop_tags if c.rva < opbase[0]]
    if not before_marks or not before_opbase:
        session.notes.append("scripts: the int or string branch of the argument loop has no kind compare")
        return {}
    kinds = {"INT": before_marks[-1], "STRING": before_opbase[-1]}
    if kinds["INT"].b == kinds["STRING"].b:
        session.notes.append("scripts: the int and string branches check the same kind")
        return {}
    for c in loop_tags:
        if c in kinds.values():
            continue
        end = min((t.rva for t in loop_tags if t.rva > c.rva), default=None)
        branch = [l for l in loads if l.rva > c.rva and (end is None or l.rva < end)]
        if any(isinstance(l.value, Deref) and l.value.base == c.a.base and l.value.size == 8 for l in branch):
            kinds["LONG"] = c
            break
    return {"enums.varValueType": ({name: c.b.n for name, c in kinds.items()},
                                   [session.evidence(kinds["INT"].rva, run, "imm")],
                                   "the argument kinds the request entry's branches check")}


def _runner_global(session, functions):
    """The static runner every call of the request entry or the interpreter passes as its first argument."""
    found = {}
    for fn in functions:
        for src, caller in session.model.call_sites(fn):
            if caller is None:
                continue
            facts = session.prov.facts(caller)
            call = next((c for c in facts.calls if c.rva == src), None)
            if call is None or not call.args or not isinstance(call.args[0], Global):
                continue
            lea = max((a for a in facts.addrs if a.rva < src and a.value == call.args[0]), key=lambda a: a.rva,
                      default=None)
            found.setdefault(call.args[0].rva, lea.rva if lea else src)
    if len(found) != 1:
        session.notes.append(f"scripts: the script entries are called with {len(found)} different runner globals")
        return None
    (rva, lea), = found.items()
    return rva, [session.evidence(lea, None, "rip")], "the runner the request entry and the interpreter are called with"


def _interpreter(session, run, inner, type_off):
    """The runner's stacks and frames, from the gosub and return cases of the interpreter loop."""
    model = session.model
    dispatch = _op_cases(model, inner)
    if dispatch is None:
        session.notes.append(f"scripts: no op dispatch through a byte table in the interpreter {inner:#x}")
        return {}
    cases, bounds = dispatch
    if OP_RETURN not in cases or OP_GOSUB not in cases:
        session.notes.append(f"scripts: the interpreter's dispatch has {len(cases)} cases, not ops {OP_RETURN} and {OP_GOSUB}")
        return {}
    facts = session.prov.facts(inner)
    gosub = _case_facts(facts, cases[OP_GOSUB], bounds)
    ret = _case_facts(facts, cases[OP_RETURN], bounds)
    out = {}

    count = _entry_load(gosub, cases[OP_GOSUB])
    if count is None or _entry_load(ret, cases[OP_RETURN]) != count:
        session.notes.append("scripts: the gosub and return cases don't both start by loading the frame count")
        return out
    count_load = next(l for l in gosub["loads"] if l.rva == cases[OP_GOSUB])
    out["ScriptRunner.frameCount"] = (count.off, [session.evidence(count_load.rva, inner, "disp")],
                                      "loaded first by the gosub and the return case")

    ptr = session.values.get("container.shared_ptr.ptr")
    lookup = None
    for c in gosub["calls"]:
        operands = _operand_array(c.args[0] if c.args else None)
        if c.target is not None and operands is not None:
            lookup = c
            if ptr is not None and operands[0] != ptr:
                session.notes.append(f"scripts: the gosub reads the script at {operands[0]:#x} of its slot, "
                                     f"container.shared_ptr.ptr is {ptr:#x}")
            break
    if lookup is None:
        session.notes.append("scripts: the gosub case calls nothing with an int operand of the script")
        return out
    lookup_fn = model.resolve(lookup.target)
    if run not in model.callers(lookup_fn):
        session.notes.append(f"scripts: the request entry doesn't call the gosub's lookup {lookup_fn:#x}")
    out["functions.scriptLookup"] = (lookup_fn, [session.evidence(lookup.rva, inner, "rip")],
                                     "called by the gosub case with the callee's id")

    swaps = [c for c in gosub["calls"] if _swaps_locals(c, count)]
    back = [c for c in ret["calls"] if _swaps_locals(c, count)]
    if len(swaps) != 1 or len(back) != 1 or model.resolve(swaps[0].target) != model.resolve(back[0].target):
        session.notes.append(f"scripts: {len(swaps)} locals swaps in the gosub case, {len(back)} in the return case")
    else:
        out["functions.scriptSwapLocals"] = (model.resolve(swaps[0].target),
                                             [session.evidence(swaps[0].rva, inner, "rip")],
                                             "called by the gosub and the return case with the frame and the runner's locals")

    stack = next((l for l in gosub["loads"] if _int_stack(l.value) is not None), None)
    if stack is None:
        session.notes.append("scripts: the gosub case pops no int argument")
    else:
        base, size = _int_stack(stack.value)
        size_load = next((l for l in gosub["loads"] if l.value == size), stack)
        out["ScriptRunner.intStack"] = (base, [session.evidence(stack.rva, inner, "disp")],
                                        "the int stack the gosub pops the callee's int arguments from")
        out["ScriptRunner.intStackSize"] = (size.off, [session.evidence(size_load.rva, inner, "disp")],
                                            "the int stack's size, less the callee's int argument count")
        out.update(_obj_stack(session, inner, gosub, count, size, type_off))

    frame = _frame_script(ret, count)
    if frame is None:
        session.notes.append("scripts: the return case doesn't clear the popped frame's script")
    else:
        off, rva = frame
        out["ScriptFrame.script"] = (off, [session.evidence(rva, inner, "disp")],
                                     "the shared_ptr<ClientScript> the return case clears in the popped frame")
    out.update(_script_id(session, lookup_fn))
    return out


def _op_cases(model, fn):
    """({op: case rva}, sorted case rvas) of fn's opcode dispatch: a bound check on the op, a byte load from
    [image + op + table] and a dword load from [image + index * 4 + cases], added to the image base and jumped to."""
    insns = model.insns(fn)
    image_regs = {i.ops[0].reg for i in insns if i.mnem == "LEA" and len(i.ops) == 2 and i.ops[1].kind == "mem"
                  and i.ops[1].base == "RIP" and i.ops[1].disp == 0}
    for k, insn in enumerate(insns):
        if not (insn.is_jump and insn.ops and insn.ops[0].kind == "reg"):
            continue
        window = insns[max(0, k - DISPATCH_WINDOW):k]
        index = next((i.ops[1] for i in window if i.mnem == "MOVZX" and len(i.ops) == 2 and i.ops[1].kind == "mem"
                      and i.ops[1].size == 1 and i.ops[1].base in image_regs and i.ops[1].scale == 1), None)
        table = next((i.ops[1] for i in window if i.mnem == "MOV" and len(i.ops) == 2 and i.ops[1].kind == "mem"
                      and i.ops[1].size == 4 and i.ops[1].base in image_regs and i.ops[1].scale == 4), None)
        bound = next((i.ops[1].imm for i in reversed(window) if i.mnem == "CMP" and len(i.ops) == 2
                      and i.ops[0].kind == "reg" and i.ops[0].reg == (index.index if index else None)
                      and i.ops[1].kind == "imm"), None)
        if index is None or table is None or bound is None:
            continue
        raw = model.pe.read(index.disp, bound + 1)
        if raw is None or len(raw) < bound + 1:
            return None
        cases = {}
        for op, slot in enumerate(raw):
            entry = model.pe.read(table.disp + 4 * slot, 4)
            if entry is None or len(entry) < 4:
                return None
            cases[op] = int.from_bytes(entry, "little")
        return cases, sorted(set(cases.values()))
    return None


def _case_facts(facts, entry, bounds):
    """The loads, stores and calls between a case's entry and the next case."""
    end = next((b for b in bounds if b > entry), None)
    inside = (lambda rva: entry <= rva < end) if end is not None else (lambda rva: rva >= entry)
    return {"loads": [l for l in facts.loads if inside(l.rva)],
            "stores": [s for s in facts.stores if inside(s.rva)],
            "calls": [c for c in facts.calls if inside(c.rva)]}


def _entry_load(case, entry):
    """[runner + off]4, the case's first instruction's load, or None."""
    load = next((l for l in case["loads"] if l.rva == entry), None)
    if load is None or not isinstance(load.value, Deref) or load.value.base != Arg(0) or load.value.size != 4:
        return None
    return load.value


def _operand_array(value):
    """(slot offset, operand array offset) for [[[script slot + ptr]8 + operands]8 + pc * 4]4, else None."""
    if not isinstance(value, Deref) or value.size != 4 or value.scale != 4:
        return None
    array = value.base
    if not isinstance(array, Deref) or array.size != 8 or not isinstance(array.base, Deref):
        return None
    script = array.base
    if script.base != Arg(1) or script.size != 8:
        return None
    return script.off, array.off


def _swaps_locals(call, count):
    """A call of (frame, runner + a, runner + b, runner + c), the frame's address computed from the frame count."""
    if call.target is None or len(call.args) < 4:
        return False
    if not all(isinstance(a, Add) and a.base == Arg(0) for a in call.args[1:4]) or isinstance(call.args[0], Deref):
        return False
    return any(sub == count for sub in walk(call.args[0]))


def _int_stack(value):
    """(stack offset, size Deref) for [runner + ([runner + size]4 - n) * 4 + stack]4, else None."""
    if not isinstance(value, Deref) or value.base != Arg(0) or value.size != 4 or value.scale != 4 or value.index is None:
        return None
    size = next((sub for sub in walk(value.index) if isinstance(sub, Deref) and sub.base == Arg(0) and sub.size == 4), None)
    return (value.off, size) if size is not None else None


def _obj_stack(session, inner, case, count, int_size, type_off):
    """The VarValue stack: the gosub's loop reads each argument's kind byte at [runner + (size - n) * 40 + stack]
    + kind, computed as LEA a,[k + i*4]; ADD a,i; LEA b,[runner + a*8] (so the stack is at 8k and an entry is 40
    bytes). The size is the other runner field in that address."""
    model = session.model
    out = {}
    rvas = {l.rva for l in case["loads"]} | {c.rva for c in case["calls"]}
    lo, hi = min(rvas), max(rvas)
    insns = [i for i in model.insns(inner) if lo <= i.rva <= hi]
    for k, insn in enumerate(insns):
        if insn.mnem != "LEA" or len(insn.ops) != 2 or insn.ops[1].kind != "mem":
            continue
        m = insn.ops[1]
        if m.base is not None or m.index is None or m.scale != 4:
            continue
        a, i = insn.ops[0].reg, m.index
        rest = insns[k + 1:k + 4]
        add = next((x for x in rest if x.mnem == "ADD" and len(x.ops) == 2 and x.ops[0].kind == "reg"
                    and x.ops[0].reg == a and x.ops[1].kind == "reg" and x.ops[1].reg == i), None)
        scaled = next((x for x in rest if x.mnem == "LEA" and len(x.ops) == 2 and x.ops[1].kind == "mem"
                       and x.ops[1].index == a and x.ops[1].scale == 8 and x.ops[1].base is not None), None)
        if add is None or scaled is None:
            continue
        out["ScriptRunner.objStack"] = (m.disp * 8, [session.evidence(insn.rva, inner, "disp")],
                                        "the VarValue stack the gosub pops the callee's object arguments from")
        out["VarValue.size"] = (5 * 8, [session.evidence(insn.rva, inner, "disp")],
                                "the stride of the VarValue stack (index * 4 + index, times 8)")
        break
    else:
        session.notes.append("scripts: no VarValue stack address in the gosub case")
        return out
    for load in case["loads"]:
        v = load.value
        if isinstance(v, Deref) and v.size == 1:
            sizes = [sub for sub in walk(v) if isinstance(sub, Deref) and sub.base == Arg(0) and sub.size == 4
                     and sub not in (int_size, count)]
            if sizes:
                size_load = next((l for l in case["loads"] if l.value == sizes[0]), load)
                out["ScriptRunner.objStackSize"] = (sizes[0].off, [session.evidence(size_load.rva, inner, "disp")],
                                                    "the VarValue stack's size, less the callee's object argument count")
                if type_off is not None and type_off != v.off:
                    session.notes.append(f"scripts: the gosub reads an argument's kind at {v.off:#x}, "
                                         f"the request entry at {type_off:#x}")
                break
    return out


def _frame_script(case, count):
    """(slot offset, store rva) of the two zero stores that clear the popped frame's shared_ptr, else None."""
    cleared = sorted((s.target.off, s.rva) for s in case["stores"]
                     if s.value == Const(0) and isinstance(s.target, Deref) and s.target.size == 8
                     and s.target.index is None and any(sub == count for sub in walk(s.target.base)))
    for (a, rva), (b, _) in zip(cleared, cleared[1:]):
        if b == a + 8:
            return a, rva
    return None


def _script_id(session, lookup):
    """ClientScript.id: the lookup stores its argument, the id, into the script it loads."""
    for s in session.prov.facts(lookup).stores:
        t = s.target
        if s.value == Arg(0) and isinstance(t, Deref) and t.size == 8 and isinstance(t.base, Deref) \
                and isinstance(t.base.base, (Out, Add)):
            return {"ClientScript.id": (t.off, [session.evidence(s.rva, lookup, "disp")],
                                        "the lookup stores the id it was called with into the script it loaded")}
    session.notes.append(f"scripts: the lookup {lookup:#x} stores its id nowhere")
    return {}
