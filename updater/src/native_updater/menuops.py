"""Running a menu action without the menu: the functions behind the minimenu's rows, and the packet helpers a mouse
click goes out through.

Each row of the minimenu carries a callback the client runs when the row is clicked (after the op event is raised):
a small struct of captured arguments plus a body function, built by the menu builder of the row's subject kind. The
builders are the callers of the subject setters (the functions storing the subject kind into MinimenuEntry.subjectType)
and add rows through adder helpers that store the body's address next to the captures. The bodies the API calls take
(void* unused, const captures*):

  NPC     {i32 index, i32 op, i32 flag}                      looks the NPC up by index, sends OPNPC<op>
  LOC     {i32 level, i32 x, i32 z, i32 locId, i32 op, i32 flag}        sends OPLOC<op> for the loc at the world tile
  OBJ     {i32 level, i32 x, i32 z, i32 objId, i32 count, i32 op, i32 flag}   sends OPOBJ<op>
  PLAYER  {i32 index, i32 op}                                looks the player up by index, sends OPPLAYER<op>

Every body reaches a sender that switches on the op and builds one packet per case through the same helpers:
create(packet*, opcode, connection + outCipher), the writers, send(connection, packet*) and release(buffer*), the
connection being the Client's. The per-cycle network step (the caller of the server packet handler, itself the caller
of functions.npcInfoDecode) builds the mouse click packet with those helpers: create, three short writes
((delta << 1) | right button, x, y), send; its opcode is enums.clientProt.EVENT_MOUSE_CLICK.

Output keys (see `menu_ops`): functions.menuNpcOp, functions.menuLocOp, functions.menuObjOp, functions.menuPlayerOp,
functions.packetCreate, functions.packetWriteShort, functions.packetSend, functions.packetRelease, Client.connection,
Connection.outCipher, enums.clientProt.
"""
from collections import Counter

from .analysis.provenance import Add, Arg, Const, Deref, Global, StackAddr

MIN_OPS = 4                 # a sender builds at least this many packets (one per op)
MOUSE_CLICK_WRITES = 3      # the mouse click packet is three shorts
MOUSE_CLICK_SIZE = 6

# Subject kind -> (output key, the capture fields the body hands its sender, as Deref offsets off Arg(1) in order)
KINDS = {
    "NPC": ("functions.menuNpcOp", (4, 8)),
    "LOC": ("functions.menuLocOp", (0xc, 0x10, 0x14)),
    "OBJ": ("functions.menuObjOp", (0xc, 0x10, 0x14, 0x18)),
    "PLAYER": ("functions.menuPlayerOp", (0, 4)),
}


def menu_ops(session, p):
    model = session.model
    subject_off = session.value("MinimenuEntry.subjectType")
    subjects = session.value("enums.minimenuSubject")
    npc_lookup = session.value("functions.npcLookup")
    player_lookup = session.value("functions.playerLookup")
    npc_decode = session.value("functions.npcInfoDecode")
    client = session.value("global.client")

    builders = _builders(session, subject_off, subjects)
    if "NPC" not in builders:
        return {}
    bodies = {kind: _bodies(model, fn) for kind, fn in builders.items()}
    shared = {b for b in bodies["NPC"] if sum(b in bs for bs in bodies.values()) > 1}

    out = {}
    senders = {}
    for kind, (key, fields) in KINDS.items():
        if kind not in bodies:
            continue
        lookup = {"NPC": npc_lookup, "PLAYER": player_lookup}.get(kind)
        found = []
        for body, (lea, adder) in bodies[kind].items():
            if body in shared:
                continue
            sender = _sender(session, body, lookup)
            if sender is not None and _hands_captures(session, body, sender, fields):
                found.append((body, sender, lea, adder))
        if len(found) != 1:
            session.notes.append(f"menu ops: {len(found)} {kind} row bodies look like the op sender")
            continue
        body, sender, lea, adder = found[0]
        out[key] = (body, [session.evidence(lea, adder, "rip")],
                    f"the {kind} menu builder's row body that reaches the op sender {sender:#x}")
        senders[kind] = sender
    if "NPC" not in senders:
        return out

    helpers = _packet_helpers(session, senders["NPC"], client)
    if helpers is None:
        return out
    create, send, release, conn_off, cipher_off, evidence = helpers
    for kind, sender in senders.items():
        if kind != "NPC" and create not in {c.target for c in session.prov.facts(sender).calls}:
            session.notes.append(f"menu ops: the {kind} op sender {sender:#x} does not create packets with {create:#x}")
    out["functions.packetCreate"] = (create, [evidence["create"]], "called with (packet*, opcode, connection + outCipher) once per op")
    out["functions.packetSend"] = (send, [evidence["send"]], "called with (connection, packet*) after the writes")
    out["functions.packetRelease"] = (release, [evidence["release"]], "called with the packet's buffer (packet + 8) after the send")
    out["Client.connection"] = (conn_off, [evidence["create"]], "the pointer the op senders load from the client for the packet helpers")
    out["Connection.outCipher"] = (cipher_off, [evidence["create"]], "the offset the op senders add to the connection for packetCreate's third argument")

    click = _mouse_click(session, npc_decode, create, send, client)
    if click is not None:
        opcode, writer, rva, cycle = click
        out["functions.packetWriteShort"] = (writer, [session.evidence(rva, cycle, "rip")],
                                             "called three times on the mouse click packet's buffer")
        out["enums.clientProt"] = ({"EVENT_MOUSE_CLICK": opcode}, [session.evidence(rva, cycle, "imm")],
                                   "the opcode of the six-byte packet the network step builds from three shorts")
    return out


def _builders(session, subject_off, subjects):
    """{kind name: builder fn}: the unique caller of the function storing the kind into [entry + subjectType]."""
    model = session.model
    setters = {}
    for rva, fn in model.insns_with_disp(subject_off, "MOV"):
        insn = model.insn(rva)
        if insn is None or fn is None or len(insn.ops) != 2:
            continue
        dst, src = insn.ops
        if dst.kind == "mem" and dst.size == 1 and src.kind == "imm":
            setters.setdefault(src.imm, set()).add(fn)
    out = {}
    for kind in KINDS:
        value = subjects.get(kind)
        fns = setters.get(value, set())
        if len(fns) != 1:
            session.notes.append(f"menu ops: {len(fns)} functions store subject kind {kind} ({value})")
            continue
        callers = model.callers(next(iter(fns)))
        if len(callers) != 1:
            session.notes.append(f"menu ops: the {kind} subject setter has {len(callers)} callers")
            continue
        out[kind] = callers[0]
    return out


def _bodies(model, builder):
    """{body fn: (lea rva, adder fn)}: every function whose address the builder or one of its callees takes."""
    out = {}
    for fn in [builder] + list(model.callees(builder)):
        for insn in model.insns(fn):
            if insn.mnem != "LEA" or len(insn.ops) != 2:
                continue
            op = insn.ops[1]
            if op.kind == "mem" and op.base == "RIP" and op.index is None and model.function(op.disp) is not None:
                out.setdefault(op.disp, (insn.rva, fn))
    return out


def _targets(model, body):
    """The body and the functions it calls or tail-jumps to."""
    out = [body] + list(model.callees(body))
    insns = model.insns(body)
    if insns and insns[-1].is_jump and insns[-1].target is not None:
        out.append(model.resolve(insns[-1].target))
    return out


def _sender(session, body, lookup):
    """The function reached from a row body that builds a packet per op (MIN_OPS creates with constant opcodes), or
    None. With a lookup, the body or one of its callees must call it (the NPC and player bodies look the actor up)."""
    model = session.model
    reached = _targets(model, body)
    if lookup is not None:
        if not any(lookup == fn or lookup in model.callees(fn) for fn in reached):
            return None
    for fn in reached:
        if _packet_creates(session, fn):
            return fn
    return None


def _packet_creates(session, fn):
    """The calls in fn that look like create(packet*, opcode, connection + outCipher), if there are MIN_OPS of them
    with distinct opcodes to one function; else []."""
    by_target = {}
    for c in session.prov.facts(fn).calls:
        if c.target is None or len(c.args) < 3:
            continue
        if isinstance(c.args[0], StackAddr) and isinstance(c.args[1], Const) and isinstance(c.args[2], Add) \
                and isinstance(c.args[2].base, Deref):
            by_target.setdefault(session.model.resolve(c.target), []).append(c)
    for calls in by_target.values():
        if len(calls) >= MIN_OPS and len({c.args[1].n for c in calls}) >= MIN_OPS:
            return calls
    return []


def _hands_captures(session, body, sender, fields):
    """Whether the body passes its captures (Arg 1) on as expected: the fields at the given offsets, in order, among
    the arguments of its call to the sender (or the sender is the body and takes the captures itself)."""
    if sender == body:
        return True
    model = session.model
    for c in session.prov.facts(body).calls:
        if c.target is not None and model.resolve(c.target) == sender:
            offs = [a.off for a in c.args if isinstance(a, Deref) and a.base == Arg(1) and a.index is None]
            return offs == list(fields)
    insns = model.insns(body)
    if insns and insns[-1].is_jump:            # a tail call: the arguments were set up before the jump
        offs = sorted(l.value.off for l in session.prov.facts(body).loads
                      if isinstance(l.value, Deref) and l.value.base == Arg(1) and l.value.index is None)
        return offs == sorted(fields)
    return False


def _packet_helpers(session, sender, client):
    """(create, send, release, Client.connection, Connection.outCipher, evidence) from an op sender, or None."""
    model = session.model
    creates = _packet_creates(session, sender)
    if not creates:
        return None
    create = model.resolve(creates[0].target)
    conn = creates[0].args[2].base
    cipher_off = creates[0].args[2].off
    if not isinstance(conn, Deref) or conn.index is not None or not _is_client(conn.base, client):
        session.notes.append(f"menu ops: packetCreate's third argument is {conn} + {cipher_off:#x}, not a client field")
        return None
    conn_off = conn.off
    facts = session.prov.facts(sender)
    sends = [c for c in facts.calls if c.target is not None and len(c.args) > 1 and c.args[0] == conn
             and isinstance(c.args[1], StackAddr) and model.resolve(c.target) != create]
    if len({model.resolve(c.target) for c in sends}) != 1:
        session.notes.append(f"menu ops: {len(sends)} calls in {sender:#x} take (connection, packet*)")
        return None
    send = model.resolve(sends[0].target)
    packet = sends[0].args[1]
    releases = [c for c in facts.calls if c.target is not None and c.args and c.args[0] == StackAddr(packet.off + 8)
                and model.resolve(c.target) not in (create, send) and c.rva > sends[0].rva]
    if len({model.resolve(c.target) for c in releases}) != 1:
        session.notes.append(f"menu ops: {len(releases)} calls in {sender:#x} take the sent packet's buffer")
        return None
    release = model.resolve(releases[0].target)
    evidence = {"create": session.evidence(creates[0].rva, sender, "rip"),
                "send": session.evidence(sends[0].rva, sender, "rip"),
                "release": session.evidence(releases[0].rva, sender, "rip")}
    return create, send, release, conn_off, cipher_off, evidence


def _is_client(v, client):
    """Whether v is the Client*: loaded from its global, or the first argument of a Client method."""
    return v == Deref(Global(client), 0, 8) or v == Arg(0)


def _mouse_click(session, npc_decode, create, send, client):
    """(opcode, writeShort fn, create rva, cycle fn) of the mouse click packet, or None: in the network step, the
    packet created with a constant opcode, written with three calls of one function on its buffer, and sent, whose
    size in the packet size table is six."""
    model = session.model
    handlers = model.callers(npc_decode)
    if len(handlers) != 1:
        session.notes.append(f"menu ops: {len(handlers)} functions call npcInfoDecode")
        return None
    cycles = model.callers(handlers[0])
    if len(cycles) != 1:
        session.notes.append(f"menu ops: the server packet handler has {len(cycles)} callers")
        return None
    cycle = cycles[0]
    sizes = _size_table(session, create)
    calls = sorted(session.prov.facts(cycle).calls, key=lambda c: c.rva)
    found = []
    for i, c in enumerate(calls):
        if c.target is None or model.resolve(c.target) != create or len(c.args) < 2 \
                or not isinstance(c.args[0], StackAddr) or not isinstance(c.args[1], Const):
            continue
        packet = c.args[0]
        writes = []
        for nxt in calls[i + 1:]:
            if nxt.target is None:
                break
            target = model.resolve(nxt.target)
            if target == send:
                break
            if nxt.args and nxt.args[0] == StackAddr(packet.off + 8) and target != create:
                writes.append(target)
            else:
                break
        else:
            continue
        if len(writes) == MOUSE_CLICK_WRITES and len(set(writes)) == 1 and sizes.get(c.args[1].n) == MOUSE_CLICK_SIZE:
            found.append((c.args[1].n, writes[0], c.rva))
    if len(found) != 1:
        session.notes.append(f"menu ops: {len(found)} six-byte packets of three shorts in the network step {cycle:#x}")
        return None
    opcode, writer, rva = found[0]
    return opcode, writer, rva, cycle


def _size_table(session, create):
    """{opcode: size} from the table packetCreate indexes with the opcode (a 4-byte load scaled by 4 off a global)."""
    model = session.model
    for l in session.prov.facts(create).loads:
        v = l.value
        if isinstance(v, Deref) and isinstance(v.base, Global) and v.size == 4 and v.scale == 4 and v.index is not None:
            table = v.base.rva + v.off
            break
    else:
        return {}
    out = {}
    for opcode in range(256):
        try:
            raw = model.pe.read(table + 4 * opcode, 4)
        except (AttributeError, TypeError, ValueError):
            break
        if raw is None or len(raw) < 4:
            break
        out[opcode] = int.from_bytes(raw, "little", signed=True)
    return out
