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
  TILE    {i32 level, i32 x, i32 z}                          the Walk here row: sends the walk packet, sets the target

Every op body reaches a sender that switches on the op and builds one packet per case through the same helpers:
create(packet*, opcode, connection + outCipher), the writers, send(connection, packet*) and release(buffer*), the
connection being the Client's. The per-cycle network step (the caller of the server packet handler, itself the caller
of functions.npcInfoDecode) builds the mouse click packet with those helpers: create, three short writes
((delta << 1) | right button, x, y), send; its opcode is enums.clientProt.EVENT_MOUSE_CLICK. The other writers (byte,
int, string) are the functions the packet builders call on a packet's buffer, told apart by how far each advances the
buffer's position.

A selected item or spell ("Use X ->") is kept in the minimenu owner; the targeted row bodies of every kind read it
through one short getter (the function returning [Client + Client.minimenu] + constant). The world subjects' targeted
bodies take the op body's subject fields ({index} or {level, x, z, id}). The component rows come from several
builders; their bodies the API calls take {i32 component, i32 slot, ...}: the component op ({component, slot, op,
subOp, objId, u8 flag} handed on to a sender), the targeted op ({component, slot, objId}, the 16-byte packet), the
select ("Use": {component, slot, objId}, no packet, stores the selection) and the pause button ({component, slot},
the 6-byte packet). The old-style button bodies take {component} (the slot is -1 in the body) and send the 4-byte
button packet: the plain button checks the op is enabled first and stores nothing after the send, the toggle stores
1 - value into the var the component names, the set stores the component's own value; the close body is the stub
that tail-calls the function sending the size-0 close packet. The op row builder also lists a component's sub-option
names (the rows a sub-menu opens under an op): the count it compares the op index with and the array of vectors of
strings it indexes by the op, next to IfType.opCount and IfType.opNames.

Output keys (see `menu_ops`): functions.menuNpcOp, functions.menuLocOp, functions.menuObjOp, functions.menuPlayerOp,
functions.menuWalk, functions.menu{Npc,Loc,Obj,Player}TargetOp, functions.menuComponentOp,
functions.menuComponentTargetOp, functions.menuComponentSelect, functions.menuComponentPause,
functions.menuComponentButton, functions.menuComponentToggle, functions.menuComponentSet,
functions.menuComponentClose, IfType.subOpCount, IfType.subOpNames, functions.packetCreate,
functions.packetWrite{Byte,Short,Int,String}, functions.packetSend, functions.packetRelease, Client.connection,
Connection.outCipher, enums.clientProt. `walk_destination` adds Client.destination, Destination.x and Destination.z.
"""
from collections import Counter

from .analysis.provenance import Add, Arg, Bin, Const, Deref, Global, StackAddr, contains

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
# Subject kind -> the output key of its targeted row body (a selected item or spell used on the subject)
TARGET_KINDS = {
    "NPC": "functions.menuNpcTargetOp",
    "LOC": "functions.menuLocTargetOp",
    "OBJ": "functions.menuObjTargetOp",
    "PLAYER": "functions.menuPlayerTargetOp",
}
COMPONENT = "COMPONENT"
COMPONENT_OP_FIELDS = (0, 4, 8, 0xc, 0x10, 0x14)   # {component, slot, op, subOp, objId, u8 flag} handed to the sender
TARGET_PACKET_SIZE = 16     # the component targeted op packet (two components with slots and items)
PAUSE_PACKET_SIZE = 6       # the pause button packet (a component and its slot)
BUTTON_PACKET_SIZE = 4      # the old-style button packet (the component's packed id)
CLOSE_PACKET_SIZE = 0       # the close button's packet
CLOSE_STUB_MAX_INSNS = 3    # the close body loads the client and tail-calls the function sending the packet
WALK_FIELDS = (0, 4, 8)     # {level, x, z}, compared with the last walk target
GETTER_MAX_INSNS = 8        # the selection getter is a few instructions around a call
STRING_WRITER_MIN_USES = 5  # the string writer is called (buffer, chars, length, 0) at least this often
WRITER_NOTES = {"Byte": "advances the buffer's position by one", "Int": "advances the buffer's position by four",
                "String": "takes (buffer, chars, length, 0)"}


def menu_ops(session, p):
    model = session.model
    subject_off = session.value("MinimenuEntry.subjectType")
    subjects = session.value("enums.minimenuSubject")
    npc_lookup = session.value("functions.npcLookup")
    player_lookup = session.value("functions.playerLookup")
    npc_decode = session.value("functions.npcInfoDecode")
    client = session.value("global.client")
    minimenu = session.value("Client.minimenu")

    builders = _builders(session, subject_off, subjects)
    if "NPC" not in builders:
        return {}
    bodies = {kind: _kind_bodies(model, fns) for kind, fns in builders.items()}
    shared = {b for b in bodies["NPC"] if sum(b in bs for bs in bodies.values()) > 1}

    out = {}
    senders = {}
    op_bodies = {}
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
        op_bodies[kind] = body
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
        for kind, (fn, rva, caller, n) in _writers(session, create, send, release, writer).items():
            out[f"functions.packetWrite{kind}"] = (fn, [session.evidence(rva, caller, "rip")],
                                                   f"the buffer writer that {WRITER_NOTES[kind]}, called on {n} packets")

    walk = _walk_body(session, create)
    if walk is not None:
        body, rva, fn = walk
        out["functions.menuWalk"] = (body, [session.evidence(rva, fn, "disp")],
                                     "the row body comparing its {level, x, z} captures with the last walk target before "
                                     "building the walk packet")

    sizes = _size_table(session, create)
    getter = _selection_getter(session, bodies, shared, minimenu)
    if getter is None:
        return out
    for kind, key in TARGET_KINDS.items():
        if kind not in bodies or kind not in op_bodies:
            continue
        found = [(body, lea, adder) for body, (lea, adder) in bodies[kind].items()
                 if body not in shared and body != op_bodies[kind] and _calls(model, body, getter)
                 and _create_calls(session, body, create)]
        if len(found) != 1:
            session.notes.append(f"menu ops: {len(found)} {kind.lower()} row bodies read the selection and build a packet")
            continue
        body, lea, adder = found[0]
        out[key] = (body, [session.evidence(lea, adder, "rip")],
                    f"the {kind} menu builder's row body that reads the selection through {getter:#x} and builds a packet")
    if COMPONENT in bodies:
        out.update(_component_bodies(session, bodies[COMPONENT], shared, getter, create, sizes))
        op_body = out.get("functions.menuComponentOp")
        if op_body is not None:
            out.update(_sub_ops(session, builders[COMPONENT], op_body[0]))
    return out


def _builders(session, subject_off, subjects):
    """{kind name: [builder fns]}: the callers of the function storing the kind into [entry + subjectType]. A world
    subject has one builder; the component rows come from several."""
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
    for kind in list(KINDS) + [COMPONENT]:
        value = subjects.get(kind)
        fns = setters.get(value, set())
        if len(fns) != 1:
            session.notes.append(f"menu ops: {len(fns)} functions store subject kind {kind} ({value})")
            continue
        callers = model.callers(next(iter(fns)))
        if kind == COMPONENT:
            if callers:
                out[kind] = callers
            continue
        if len(callers) != 1:
            session.notes.append(f"menu ops: the {kind} subject setter has {len(callers)} callers")
            continue
        out[kind] = callers
    return out


def _kind_bodies(model, builders):
    """{body fn: (lea rva, adder fn)} over every builder of one subject kind."""
    out = {}
    for builder in builders:
        for body, where in _bodies(model, builder).items():
            out.setdefault(body, where)
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


def _calls(model, body, fn):
    """Whether the body, one of its callees or its tail-call target calls fn."""
    return any(fn in model.callees(f) for f in _targets(model, body))


def _create_calls(session, body, create):
    """The packetCreate calls in the body, its callees and its tail-call target."""
    model = session.model
    out = []
    for fn in _targets(model, body):
        out.extend(c for c in session.prov.facts(fn).calls if c.target is not None and model.resolve(c.target) == create)
    return out


def _buffer_calls(session, fn, create, send):
    """The calls fn makes on a packet's buffer (packet + 8) between creating the packet and sending it, for every
    packet it creates on its stack."""
    model = session.model
    calls = sorted(session.prov.facts(fn).calls, key=lambda c: c.rva)
    out = []
    for i, c in enumerate(calls):
        if c.target is None or model.resolve(c.target) != create or not c.args or not isinstance(c.args[0], StackAddr):
            continue
        buffer = StackAddr(c.args[0].off + 8)
        for nxt in calls[i + 1:]:
            target = model.resolve(nxt.target) if nxt.target is not None else None
            if target in (create, send):
                break
            if nxt.args and nxt.args[0] == buffer and target is not None:
                out.append(nxt)
    return out


def _position_field(model, write_short):
    """The offset of the position field the short writer advances by two ([RCX + offset] += 2), or None."""
    for insn in model.insns(write_short):
        if insn.mnem == "ADD" and len(insn.ops) == 2 and insn.ops[0].kind == "mem" and insn.ops[0].base == "RCX" \
                and insn.ops[1].kind == "imm" and insn.ops[1].imm == 2:
            return insn.ops[0].disp
    return None


def _position_step(model, fn, pos):
    """How far fn advances a buffer's position field, or None if it never touches it directly."""
    for insn in model.insns(fn):
        if insn.ops and insn.ops[0].kind == "mem" and insn.ops[0].base == "RCX" and insn.ops[0].disp == pos:
            if insn.mnem == "INC":
                return 1
            if insn.mnem == "ADD" and len(insn.ops) == 2 and insn.ops[1].kind == "imm":
                return insn.ops[1].imm
    return None


def _writers(session, create, send, release, write_short):
    """{"Byte" | "Int" | "String": (fn, call rva, caller, uses)}: the other functions the packet builders call on a
    packet's buffer. The byte and int writers advance the position field the short writer advances by two, by one and
    four; the string writer is the one called (buffer, chars, length, 0) STRING_WRITER_MIN_USES times at least."""
    model = session.model
    pos = _position_field(model, write_short)
    if pos is None:
        session.notes.append(f"menu ops: the short writer {write_short:#x} does not advance a position field by two")
        return {}
    uses = {}
    zeros = Counter()
    for caller in model.callers(create):
        for c in _buffer_calls(session, caller, create, send):
            target = model.resolve(c.target)
            if target in (create, send, release, write_short):
                continue
            uses.setdefault(target, []).append((c.rva, caller))
            if len(c.args) > 3 and c.args[3] == Const(0):
                zeros[target] += 1
    by_kind = {}
    for fn, sites in uses.items():
        step = _position_step(model, fn, pos)
        kind = {1: "Byte", 4: "Int"}.get(step)
        if kind is None and step is None and zeros[fn] >= STRING_WRITER_MIN_USES:
            kind = "String"
        if kind is not None:
            by_kind.setdefault(kind, []).append((fn, sites))
    out = {}
    for kind in ("Byte", "Int", "String"):
        found = by_kind.get(kind, [])
        if len(found) != 1:
            session.notes.append(f"menu ops: {len(found)} buffer writers look like the {kind.lower()} writer")
            continue
        fn, sites = found[0]
        rva, caller = sites[0]
        out[kind] = (fn, rva, caller, len(sites))
    return out


def _walk_body(session, create):
    """(row address, cmp rva, fn) of the Walk here row body: the packet-building function that compares the three
    i32s of its captures (Arg 1) with three consecutive globals, the last tile walked to, before building anything."""
    model = session.model
    found = []
    for fn in model.callers(create):
        globals_ = {}
        first = None
        for c in session.prov.facts(fn).cmps:
            a, b = c.a, c.b
            if isinstance(a, Deref) and a.base == Arg(1) and a.index is None and a.size == 4 \
                    and isinstance(b, Deref) and isinstance(b.base, Global) and b.index is None:
                globals_.setdefault(a.off, b.base.rva + b.off)
                first = c.rva if first is None else min(first, c.rva)
        if all(off in globals_ for off in WALK_FIELDS) \
                and all(globals_[off] == globals_[0] + off for off in WALK_FIELDS):
            row = _row_address(model, fn)
            if row is not None:
                found.append((row, first, fn))
    if len(found) != 1:
        session.notes.append(f"menu ops: {len(found)} row bodies compare their captures with the last walk target")
        return None
    return found[0]


def walk_destination(session, p):
    """Client.destination, Destination.x, Destination.z: where the client keeps the walk destination (the tile the
    flag marks). After sending the walk packet, the Walk here row body calls a client method with (client, captures)
    that turns the captured world tile into a scene tile and hands it, through a call or a tail jump, to the
    destination setter (client, world, sceneX, sceneZ). The setter loads one pointer from the client and stores its
    third argument at one offset of it and its fourth at another. The client clears the destination by setting it to
    (0, 0), as it does when the local player stands on it."""
    model = session.model
    client = session.value("global.client")
    body = _jump_target(model, session.value("functions.menuWalk"))
    found = []
    for call in session.prov.facts(body).calls:
        if call.target is None or len(call.args) < 2 or not _is_client(call.args[0], client) or call.args[1] != Arg(1):
            continue
        candidates = [call.target] + [c.target for c in session.prov.facts(call.target).calls
                                      if c.target is not None and c.args and c.args[0] == Arg(0)]
        for setter in candidates:
            stores = _destination_stores(session, setter)
            if stores is not None:
                found.append((setter, stores))
    if len({stores[:3] for _, stores in found}) != 1:
        session.notes.append(f"walk destination: {len(found)} setters store a scene tile through a client pointer")
        return {}
    setter, (pointer, x, z, rva) = found[0]
    ev = [session.evidence(rva, setter, "disp")]
    return {
        "Client.destination": (pointer, ev, f"the pointer the destination setter {setter:#x} loads from the client"),
        "Destination.x": (x, ev, "where the setter stores its scene x (its third argument)"),
        "Destination.z": (z, ev, "where the setter stores its scene z (its fourth argument)"),
    }


def _destination_stores(session, fn):
    """(pointer offset, x offset, z offset, rva of the x store) if fn stores Arg 2 and Arg 3 as i32s through one
    pointer loaded from [Arg 0 + pointer offset], else None."""
    stores = {}
    for s in session.prov.facts(fn).stores:
        t = s.target
        if s.value in (Arg(2), Arg(3)) and isinstance(t, Deref) and t.size == 4 and t.index is None \
                and isinstance(t.base, Deref) and t.base.base == Arg(0) and t.base.size == 8 and t.base.index is None:
            stores.setdefault(s.value, (t.base.off, t.off, s.rva))
    if Arg(2) not in stores or Arg(3) not in stores or stores[Arg(2)][0] != stores[Arg(3)][0]:
        return None
    (pointer, x, rva), (_, z, _) = stores[Arg(2)], stores[Arg(3)]
    return pointer, x, z, rva


def _jump_target(model, fn):
    """The function a one-instruction jump stub goes to, or fn itself."""
    body = model.insns(fn)
    if len(body) == 1 and body[0].is_jump and body[0].target is not None:
        return body[0].target
    return fn


def _row_address(model, fn):
    """The address a row holds for its body: fn itself if a builder takes its address, else the one-instruction jump
    stub to fn whose address is taken; None if neither."""
    if model.xrefs_to(fn, ("lea",)):
        return fn
    for _, stub, _ in model.xrefs_to(fn, ("jump",)):
        if stub is not None and len(model.insns(stub)) == 1 and model.xrefs_to(stub, ("lea",)):
            return stub
    return None


def _selection_getter(session, bodies, shared, minimenu):
    """The function the targeted row bodies call for the selected item or spell: at most GETTER_MAX_INSNS
    instructions around a call of the function loading [Client + Client.minimenu], called from the bodies of at least
    two subject kinds."""
    model = session.model
    kinds_by_fn = {}
    for kind, kind_bodies in bodies.items():
        for body in kind_bodies:
            if body in shared:
                continue
            for fn in _targets(model, body):
                for g in model.callees(fn):
                    if _reads_minimenu(model, g, minimenu):
                        kinds_by_fn.setdefault(g, set()).add(kind)
    found = [g for g, kinds in kinds_by_fn.items() if len(kinds) >= 2]
    if len(found) != 1:
        session.notes.append(f"menu ops: {len(found)} functions look like the selection getter")
        return None
    return found[0]


def _reads_minimenu(model, g, minimenu):
    """Whether g is a short function calling a function that loads [reg + Client.minimenu]."""
    insns = model.insns(g)
    if not insns or len(insns) > GETTER_MAX_INSNS:
        return False
    for insn in insns:
        if insn.is_call and insn.target is not None:
            body = model.insns(model.resolve(insn.target))
            if 0 < len(body) <= 3 and any(op.kind == "mem" and op.disp == minimenu for i in body for op in i.ops):
                return True
    return False


def _component_bodies(session, bodies, shared, getter, create, sizes):
    """The component rows' bodies the API calls, by what each does: the op body hands {component, slot, op, subOp,
    objId, flag} to a sender; the targeted body reads the selection and builds the TARGET_PACKET_SIZE packet; the
    select body reads the selection and builds no packet; the pause body builds the PAUSE_PACKET_SIZE packet."""
    model = session.model
    notes = {"functions.menuComponentOp": "hands {component, slot, op, subOp, objId, flag} to a packet sender",
             "functions.menuComponentTargetOp": f"reads the selection and builds a {TARGET_PACKET_SIZE}-byte packet",
             "functions.menuComponentSelect": "reads the selection and builds no packet",
             "functions.menuComponentPause": f"builds a {PAUSE_PACKET_SIZE}-byte packet",
             "functions.menuComponentButton": f"writes its {{component}} capture into a {BUTTON_PACKET_SIZE}-byte "
                                              "packet and stores nothing after the send",
             "functions.menuComponentToggle": f"writes its {{component}} capture into a {BUTTON_PACKET_SIZE}-byte "
                                              "packet, then stores 1 - value into a var",
             "functions.menuComponentSet": f"writes its {{component}} capture into a {BUTTON_PACKET_SIZE}-byte "
                                           "packet, then stores the component's value into a var",
             "functions.menuComponentClose": f"tail-calls the function building the {CLOSE_PACKET_SIZE}-byte packet"}
    found = {key: [] for key in notes}
    for body, (lea, adder) in bodies.items():
        if body in shared:
            continue
        creates = _create_calls(session, body, create)
        opcodes = {c.args[1].n for c in creates if len(c.args) > 1 and isinstance(c.args[1], Const)}
        packet_sizes = {sizes.get(op) for op in opcodes}
        reads_selection = _calls(model, body, getter)
        if any(fn != body and _create_calls(session, fn, create) and _hands_captures(session, body, fn, COMPONENT_OP_FIELDS)
               for fn in _targets(model, body)):
            found["functions.menuComponentOp"].append((body, lea, adder))
        elif reads_selection and not creates:
            found["functions.menuComponentSelect"].append((body, lea, adder))
        elif reads_selection and packet_sizes == {TARGET_PACKET_SIZE}:
            found["functions.menuComponentTargetOp"].append((body, lea, adder))
        elif not reads_selection and packet_sizes == {PAUSE_PACKET_SIZE}:
            found["functions.menuComponentPause"].append((body, lea, adder))
        elif not reads_selection and packet_sizes == {CLOSE_PACKET_SIZE} and _is_stub(model, body):
            found["functions.menuComponentClose"].append((body, lea, adder))
        elif not reads_selection and _button_packet(session, body, create, sizes):
            found[_button_key(session, body)].append((body, lea, adder))
    out = {}
    for key, rows in found.items():
        if len(rows) != 1:
            session.notes.append(f"menu ops: {len(rows)} component row bodies: each {notes[key]}")
            continue
        body, lea, adder = rows[0]
        out[key] = (body, [session.evidence(lea, adder, "rip")], f"the component row body that {notes[key]}")
    return out



def _is_stub(model, body):
    """Whether the body is a few instructions ending in a tail jump (the close body: load the client, jump)."""
    insns = model.insns(body)
    return 0 < len(insns) <= CLOSE_STUB_MAX_INSNS and insns[-1].is_jump and insns[-1].target is not None


def _button_packet(session, body, create, sizes):
    """Whether the body itself creates one packet, of BUTTON_PACKET_SIZE bytes, and the first call on its buffer
    writes the body's first capture ([Arg 1], the component). The callees' packets (the plain button's op check sends
    one of its own) don't count."""
    model = session.model
    calls = sorted(session.prov.facts(body).calls, key=lambda c: c.rva)
    creates = [c for c in calls if c.target is not None and model.resolve(c.target) == create and len(c.args) > 1
               and isinstance(c.args[0], StackAddr) and isinstance(c.args[1], Const)]
    if len(creates) != 1 or sizes.get(creates[0].args[1].n) != BUTTON_PACKET_SIZE:
        return False
    buffer = StackAddr(creates[0].args[0].off + 8)
    writes = [c for c in calls if c.target is not None and c.rva > creates[0].rva and len(c.args) > 1
              and c.args[0] == buffer and model.resolve(c.target) != create]
    return bool(writes) and writes[0].args[1] == Deref(Arg(1), 0, 4)


def _button_key(session, body):
    """Which old-style button body this is, by the var it stores after the send: none for the plain button, 1 - value
    for the toggle, a field of the component (a dereference) for the set."""
    stores = [st for st in session.prov.facts(body).stores if _is_var_store(st.target)]
    if not stores:
        return "functions.menuComponentButton"
    value = stores[0].value
    if isinstance(value, Bin) and value.op == "-" and value.a == Const(1):
        return "functions.menuComponentToggle"
    if isinstance(value, Deref):
        return "functions.menuComponentSet"
    return "functions.menuComponentButton"


def _is_var_store(target):
    """Whether a store's target is an element of an array held in a global: [[global] + index * 4], the element's
    address computed in a register (an address-sized dereference) or in the store itself."""
    if not isinstance(target, Deref) or target.size != 4:
        return False
    element = target
    if element.index is None and isinstance(element.base, Deref) and element.base.size == 0:
        element = element.base
    return element.index is not None and isinstance(element.base, Deref) and isinstance(element.base.base, Global)


def _sub_ops(session, builders, op_body):
    """IfType.subOpCount and IfType.subOpNames from the builder of the component op rows (the one taking the op
    body's address): it compares its op argument with IfType.opCount and with the sub-option count, both fields of the
    same component pointer, and indexes with the op the array held as far after that count as opNames is after
    opCount."""
    model = session.model
    try:
        op_count = session.value("IfType.opCount")
        op_names = session.value("IfType.opNames")
    except Exception:
        session.notes.append("menu ops: IfType.opCount and IfType.opNames are needed for the sub-option tables")
        return {}
    builder = next((b for b in builders if op_body in _bodies(model, b)), None)
    if builder is None:
        session.notes.append("menu ops: no component builder takes the op body's address")
        return {}
    facts = session.prov.facts(builder)
    fields = []
    for c in facts.cmps:
        for a, b in ((c.a, c.b), (c.b, c.a)):
            if isinstance(a, Deref) and a.size == 4 and a.index is None and isinstance(a.base, Deref) \
                    and isinstance(b, Arg):
                fields.append((a.base, b, a.off, c.rva))
    bases = {(base, arg) for base, arg, off, _ in fields if off == op_count}
    if len(bases) != 1:
        session.notes.append(f"menu ops: {len(bases)} component pointers are compared with an argument at opCount")
        return {}
    base, arg = next(iter(bases))
    delta = op_names - op_count
    found = {}
    for b, a, off, rva in fields:
        if (b, a) != (base, arg) or off == op_count or off in found:
            continue
        table = Deref(base, off + delta, 8)
        loads = [l for l in facts.loads if contains(l.value, table) and contains(l.value, arg) and l.value != table]
        if loads:
            found[off] = (rva, loads[0].rva)
    if len(found) != 1:
        session.notes.append(f"menu ops: {len(found)} other fields of the component are compared with the op argument "
                             "and followed by a table indexed by it")
        return {}
    count, (rva, load_rva) = next(iter(found.items()))
    return {"IfType.subOpCount": (count, [session.evidence(rva, builder, "disp")],
                                  "the other component field the op row builder compares its op argument with, "
                                  "followed by a table it indexes with the op"),
            "IfType.subOpNames": (count + delta, [session.evidence(load_rva, builder, "disp")],
                                  "the array of sub-option name vectors, as far after its count as opNames is after "
                                  "opCount, indexed by the op")}
