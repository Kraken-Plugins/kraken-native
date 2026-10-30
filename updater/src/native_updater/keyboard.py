"""Where the keyboard reaches the client, for key remapping in the hook DLL, and which components listen for keys
(docs/developer/key-interception.md).

The client draws into a child window of the class "JagRenderView". Its window procedure hands every message to the
client's input device, which turns WM_KEYDOWN, WM_KEYUP and WM_CHAR into key pressed, released and typed calls for the
game thread; the game keeps no key state of its own beyond those calls. So rewriting or dropping the key messages in
that window procedure changes what the game sees.

  render_view_wndproc   functions.renderViewWndProc: the window procedure the function registering "JagRenderView"
                        stores into the WNDCLASSEXA it hands RegisterClassExA (lpfnWndProc, 8 bytes after cbSize,
                        which is 0x50). A plain WNDPROC: (HWND, UINT, WPARAM, LPARAM) in RCX, RDX, R8, R9.
  component_listeners   IfType.onKey and the IfListener layout: the cs2 runtime sets a component's hooks in the
                        handler of ops 1400-1499 (cc_setonclick ... cc_setonkey), which subtracts 1400 from the op and
                        jumps through a table of cases, each pointing RBX at one of the component's listener slots
                        (LEA RBX,[component + slot]) before a shared tail resizes the slot's argument vector and fills
                        it. Case 19 (cc_setonkey, op 1419) gives the key listener. A slot holds its argument count at
                        IfListener.count (what the resize compares the new count with) and its arguments, VarValues,
                        at IfListener.args (what the tail indexes after the resize); a slot with no arguments has no
                        listener.

Output keys: functions.renderViewWndProc, IfType.onKey, IfListener.count, IfListener.args.
"""

CLASS_NAME = "JagRenderView"
WNDCLASSEX_SIZE = 0x50          # sizeof(WNDCLASSEXA), stored into its cbSize
WNDPROC_OFFSET = 8              # lpfnWndProc's offset in WNDCLASSEXA
HOOK_RANGE = 1400               # the cs2 ops that set a component's listeners
ON_KEY = 1419                   # cc_setonkey
ARGS_WINDOW = 48                # instructions after the resize searched for the argument array load


def render_view_wndproc(session, p):
    model = session.model
    found = set()
    for src, fn in model.string_refs(CLASS_NAME):
        if fn is None:
            continue
        body = model.insns(fn)
        sizes = {i.ops[0].disp for i in body if i.mnem == "MOV" and len(i.ops) == 2 and i.ops[0].kind == "mem"
                 and i.ops[0].base == "RSP" and i.ops[1].kind == "imm" and i.ops[1].imm == WNDCLASSEX_SIZE}
        loaded = {}
        for i in body:
            if i.mnem == "LEA" and len(i.ops) == 2 and i.ops[0].kind == "reg" and i.ops[1].kind == "mem" \
                    and i.ops[1].base == "RIP" and model.function(i.ops[1].disp) is not None:
                loaded[i.ops[0].reg] = (i.ops[1].disp, i.rva)
            elif i.mnem == "MOV" and len(i.ops) == 2 and i.ops[0].kind == "mem" and i.ops[0].base == "RSP" \
                    and i.ops[1].kind == "reg" and i.ops[1].reg in loaded \
                    and i.ops[0].disp - WNDPROC_OFFSET in sizes:
                found.add((loaded[i.ops[1].reg][0], loaded[i.ops[1].reg][1], fn))
    procs = {proc for proc, _, _ in found}
    if len(procs) != 1:
        session.notes.append(f"keyboard: {len(procs)} window procedures registered with {CLASS_NAME!r}")
        return {}
    proc, rva, fn = min(found, key=lambda f: f[1])
    return {"functions.renderViewWndProc": (proc, [session.evidence(rva, fn, "rip")],
                                            f"lpfnWndProc of the {CLASS_NAME!r} window class")}


def component_listeners(session, p):
    model = session.model
    handler = _hook_handler(model)
    if handler is None:
        session.notes.append(f"keyboard: no cs2 handler subtracts {HOOK_RANGE} and jumps through a case table")
        return {}
    fn, cases = handler
    index = ON_KEY - HOOK_RANGE
    if index >= len(cases):
        session.notes.append(f"keyboard: the {HOOK_RANGE} handler {fn:#x} has {len(cases)} cases, no op {ON_KEY}")
        return {}
    first = model.insn(cases[index])
    if first is None or first.mnem != "LEA" or len(first.ops) != 2 or first.ops[0].kind != "reg" \
            or first.ops[1].kind != "mem" or first.ops[1].index is not None:
        session.notes.append(f"keyboard: op {ON_KEY}'s case doesn't start by pointing at a listener slot")
        return {}
    slot_reg = first.ops[0].reg
    out = {"IfType.onKey": (first.ops[1].disp, [session.evidence(first.rva, fn)],
                            f"cc_setonkey's case (op {ON_KEY}) points its listener slot at the component plus this")}
    tail = _tail(model, fn, first)
    if tail is None:
        session.notes.append("keyboard: the listener cases' shared tail resizes no slot")
        return out
    resize, call_rva, after = tail
    count = next((i.ops[1].disp for i in model.insns(resize) if i.mnem in ("MOV", "CMP") and len(i.ops) == 2
                  and i.ops[1].kind == "mem" and i.ops[1].base == "RCX" and i.ops[1].index is None), None)
    args = next((i.ops[1].disp for i in after if i.mnem == "MOV" and len(i.ops) == 2 and i.ops[1].kind == "mem"
                 and i.ops[1].base == slot_reg and i.ops[1].index is None), None)
    if count is not None:
        out["IfListener.count"] = (count, [session.evidence(call_rva, fn, "rip")],
                                   "the field the slot resize compares the new argument count with")
    if args is not None and args != count:
        out["IfListener.args"] = (args, [session.evidence(call_rva, fn, "rip")],
                                  "the VarValue array the tail fills after resizing the slot")
    return out


def _hook_handler(model):
    """(fn, [case rva]) of the cs2 handler of ops 1400-1499: the `LEA EAX,[R - 1400]` or `ADD EAX,-1400`, a compare
    with the case count and a jump through a table of image-relative case addresses."""
    imm = (-HOOK_RANGE) & 0xffffffff
    candidates = {fn for _, fn in model.insns_with_imm(imm) if fn is not None}
    candidates |= {fn for _, fn in model.insns_with_disp(-HOOK_RANGE, "LEA") if fn is not None}
    found = []
    for fn in sorted(candidates):
        body = model.insns(fn)
        for k, insn in enumerate(body):
            subtracts = (insn.mnem == "LEA" and len(insn.ops) == 2 and insn.ops[1].kind == "mem"
                         and insn.ops[1].disp == -HOOK_RANGE) or \
                        (insn.mnem == "ADD" and len(insn.ops) == 2 and insn.ops[1].kind == "imm"
                         and insn.ops[1].imm in (imm, -HOOK_RANGE))
            if not subtracts:
                continue
            bound = next((i.ops[1].imm for i in body[k + 1: k + 4] if i.mnem == "CMP" and len(i.ops) == 2
                          and i.ops[1].kind == "imm"), None)
            table = next((i.ops[1].disp for i in body[k + 1: k + 12] if i.mnem == "MOV" and len(i.ops) == 2
                          and i.ops[1].kind == "mem" and i.ops[1].scale == 4 and i.ops[1].disp > 0), None)
            if bound is None or table is None or not 0 < bound < 100:
                continue
            raw = model.pe.read(table, 4 * (bound + 1))
            if raw is None:
                continue
            cases = [int.from_bytes(raw[4 * c: 4 * c + 4], "little") for c in range(bound + 1)]
            if all(model.function_containing(c) == fn for c in cases):
                found.append((fn, cases))
    return found[0] if len(found) == 1 else None


def _tail(model, fn, first):
    """(resize fn, call rva, the instructions after it) of the listener cases' shared tail: the case jumps to it, and
    its first call made with the slot in RCX resizes the slot."""
    jump = model.insn(first.fallthrough) if first.fallthrough is not None else None
    if jump is None or jump.mnem != "JMP" or jump.target is None or model.function_containing(jump.target) != fn:
        return None
    slot_reg = first.ops[0].reg
    body = model.insns(fn)
    start = next((k for k, i in enumerate(body) if i.rva == jump.target), None)
    if start is None:
        return None
    for at in range(start, len(body)):
        insn = body[at]
        if not insn.is_call or insn.target is None:
            continue
        sets_rcx = any(i.mnem == "MOV" and len(i.ops) == 2 and i.ops[0].kind == "reg" and i.ops[0].reg == "RCX"
                       and i.ops[1].kind == "reg" and i.ops[1].reg == slot_reg for i in body[max(start, at - 6): at])
        if sets_rcx:
            return model.resolve(insn.target), insn.rva, body[at + 1: at + ARGS_WINDOW]
    return None
