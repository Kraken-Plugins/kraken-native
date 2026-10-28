"""The packets that answer the server's dialogs: the count, name, string and item dialogs the cs2 runtime resumes.

The client's scripting runtime (cs2) sends them from four of its ops: resume_countdialog (op 3104, an int),
resume_namedialog (3105, a string), resume_stringdialog (3106, a string) and resume_objdialog (3115, an item id). The
runtime handles each hundred ops in one function that subtracts the range's first op from the op and jumps through a
table of case addresses; the handler of the 3100 range holds these four cases, each building its packet with the
packet helpers (create, a writer, send, release) on the runtime's stack: the count dialog's writes the popped int with
the int writer, the item dialog's the popped int with the short writer, the string dialogs' the string's length plus
one with the byte writer and then the characters and a terminator inline.

Output keys (see `dialog_packets`): enums.clientProt.RESUME_P_COUNTDIALOG, enums.clientProt.RESUME_P_NAMEDIALOG,
enums.clientProt.RESUME_P_STRINGDIALOG, enums.clientProt.RESUME_P_OBJDIALOG.
"""
from .analysis.provenance import Const, StackAddr

CS2_RANGE = 3100
# cs2 op -> (the packet's name, the writer its case calls on the buffer)
DIALOG_OPS = {
    3104: ("RESUME_P_COUNTDIALOG", "functions.packetWriteInt"),
    3105: ("RESUME_P_NAMEDIALOG", "functions.packetWriteByte"),
    3106: ("RESUME_P_STRINGDIALOG", "functions.packetWriteByte"),
    3115: ("RESUME_P_OBJDIALOG", "functions.packetWriteShort"),
}
DISPATCH_WINDOW = 12        # instructions between the range subtraction and the jump through the table
MAX_CASE_INSNS = 400        # a case is walked this far at most


def dialog_packets(session, p):
    model = session.model
    create = session.value("functions.packetCreate")
    send = session.value("functions.packetSend")
    release = session.value("functions.packetRelease")
    writers = {key: session.value(key) for _, key in DIALOG_OPS.values()}

    handler = _range_handler(session, CS2_RANGE)
    if handler is None:
        return {}
    fn, cases = handler
    calls = sorted(session.prov.facts(fn).calls, key=lambda c: c.rva)
    out = {}
    for op, (name, writer_key) in DIALOG_OPS.items():
        index = op - CS2_RANGE
        if index >= len(cases):
            session.notes.append(f"dialogs: the {CS2_RANGE} range handler {fn:#x} has {len(cases)} cases, no op {op}")
            continue
        reached = _reach(model, fn, cases[index], set(cases))
        creates = [c for c in calls if c.rva in reached and c.target is not None and model.resolve(c.target) == create
                   and len(c.args) > 1 and isinstance(c.args[0], StackAddr) and isinstance(c.args[1], Const)]
        if len(creates) != 1:
            session.notes.append(f"dialogs: op {op} creates {len(creates)} packets")
            continue
        packet = creates[0]
        buffer = StackAddr(packet.args[0].off + 8)
        writes = []
        for c in calls:
            if c.rva <= packet.rva or c.rva not in reached or c.target is None:
                continue
            target = model.resolve(c.target)
            if target in (send, create):
                break
            if c.args and c.args[0] == buffer and target != release:
                writes.append(target)
        if writes != [writers[writer_key]]:
            session.notes.append(f"dialogs: op {op} writes its packet with {[hex(w) for w in writes]}, "
                                 f"not {writer_key} alone")
            continue
        out[f"enums.clientProt.{name}"] = (packet.args[1].n, [session.evidence(packet.rva, fn, "imm")],
                                            f"the opcode cs2 op {op} creates its packet with, written with {writer_key}")
    return out


def _range_handler(session, first):
    """(fn, [case rva]) of the cs2 handler of the ops from `first`: the function that adds -first to its op, compares
    the result with the case count and jumps through a table of image-relative case addresses."""
    model = session.model
    found = []
    for rva, fn in model.insns_with_imm((-first) & 0xffffffff, "ADD"):
        if fn is None:
            continue
        insns = model.insns(fn)
        start = next((i for i, insn in enumerate(insns) if insn.rva == rva), None)
        if start is None:
            continue
        count = table = None
        for insn in insns[start + 1:start + 1 + DISPATCH_WINDOW]:
            if insn.mnem == "CMP" and len(insn.ops) == 2 and insn.ops[1].kind == "imm":
                count = insn.ops[1].imm + 1
            elif insn.mnem == "MOV" and len(insn.ops) == 2 and insn.ops[1].kind == "mem" \
                    and insn.ops[1].index is not None and insn.ops[1].scale == 4:
                table = insn.ops[1].disp
            elif insn.is_jump and insn.ops and insn.ops[0].kind == "reg" and count and table is not None:
                found.append((fn, table, count))
                break
    if len(found) != 1:
        session.notes.append(f"dialogs: {len(found)} functions dispatch cs2 ops from {first} through a table")
        return None
    fn, table, count = found[0]
    cases = []
    for i in range(count):
        raw = model.pe.read(table + 4 * i, 4)
        if raw is None or len(raw) < 4:
            session.notes.append(f"dialogs: the case table of {fn:#x} at {table:#x} is unreadable")
            return None
        cases.append(int.from_bytes(raw, "little"))
    return fn, cases


def _reach(model, fn, entry, stops):
    """The rvas reachable from entry inside fn by falling through and branching, never entering another case."""
    insns = {insn.rva: insn for insn in model.insns(fn)}
    seen = set()
    todo = [entry]
    while todo and len(seen) < MAX_CASE_INSNS:
        rva = todo.pop()
        if rva in seen or rva not in insns or (rva in stops and rva != entry):
            continue
        seen.add(rva)
        insn = insns[rva]
        if insn.is_ret:
            continue
        if insn.is_call:
            following = [insn.fallthrough]
        elif insn.is_jump or insn.is_cond_jump:
            following = list(insn.flows) or [insn.target]
            if insn.is_cond_jump:
                following.append(insn.fallthrough)
        else:
            following = [insn.fallthrough]
        todo.extend(n for n in following if n is not None)
    return seen
