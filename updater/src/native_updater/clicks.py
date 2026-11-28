"""The second mouse click packet: the seven bytes the network step sends right after EVENT_MOUSE_CLICK.

A click reaches the client as two queued input events, and each fills its own pending click in the input listener:
one is what the six-byte EVENT_MOUSE_CLICK is built from (menuops.py), the other carries a button code and a flag
with the position and the event's time. The network step sends both packets in the same cycle, the six-byte one first,
each with the milliseconds since its own previous click. The second packet's bytes are stored inline into the buffer
after its create, and their order and byte transforms change between builds: 243.1 writes x as a little-endian short,
y with 128 added to its low byte, the button byte plus 128, then the delay with 128 added to its low byte; 241.3 writes
y and x as little-endian shorts with 128 added to their low bytes, the delay, then the negated button byte.

The recipe takes the network step's first packet created after the six-byte click: a constant opcode, seven bytes in
the packet size table, gated by a 4-byte client field compared with -1 (the pending click's x, -1 when there is none).
The stretch from the gate's first read to the send is executed (analysis/emulate.py) for random clicks: the pending
x, y and time, the previous click's time (every other client qword), the button and flag (every other client read)
and a canvas larger than any click (reads through any other pointer). Each stored byte is then fitted to one source
byte and one transform, the same in every run. A last run with a click beyond the canvas checks that the gated field
is x: it must come back clamped by a canvas field before the one that clamps the field after it (the width before the
height), or the build is refused rather than mapped with x and y swapped.

  X_LOW, X_HIGH          the click's canvas x, low and high byte
  Y_LOW, Y_HIGH          the click's canvas y
  BUTTONS                (flag & 1) | (button << 1), from the two client bytes the event stored
  DELAY_LOW, DELAY_HIGH  (milliseconds since the previous click, at most 32767) << 1

  transforms (enums.mouseClickV2Transform): 0 as is, 1 plus 128, 2 negated, 3 subtracted from 128, modulo 256

Output keys (see `mouse_click_v2`): enums.clientProt.EVENT_MOUSE_CLICK_V2 (the opcode), enums.mouseClickV2 ({member:
the byte's index in the packet}), enums.mouseClickV2Transform ({member: its transform}).
"""
import random
from itertools import permutations

from .analysis.emulate import VOLATILE, Emulator
from .analysis.provenance import Const, Deref, StackAddr
from .menuops import _is_client, _size_table, network_step

SIZE = 7
RUNS = 16
SEED = 0x6b726b6e
CLIENT = 0x7f0000000000          # where the run puts the Client
CLIENT_SPAN = 0x10000000         # reads inside this range of CLIENT are client fields
CANVAS = 0x3fffffff              # every 4-byte read through another pointer: a canvas larger than any click
UNSET = 0x40000000               # every register the run doesn't set (page aligned, so field offsets survive)
PAGE = 0xfff
CLAMP_BASE = 0x100               # in the clamp run, a 4-byte read through another pointer: this plus its page offset
CLAMP_CLICK = 0x7000             # the clamp run's click, beyond every such canvas field
MAX_COORD = 0x7ff0
MAX_DELAY = 0x7ffe
NONE = (-1, 0xffffffff)
FIELDS = ("X_LOW", "X_HIGH", "Y_LOW", "Y_HIGH", "BUTTONS", "DELAY_LOW", "DELAY_HIGH")
TRANSFORMS = (lambda v: v, lambda v: v + 128, lambda v: -v, lambda v: 128 - v)


def mouse_click_v2(session, p):
    model = session.model
    create = session.value("functions.packetCreate")
    send = session.value("functions.packetSend")
    client = session.value("global.client")
    v1 = session.value("enums.clientProt").get("EVENT_MOUSE_CLICK")
    cycle = network_step(session, session.value("functions.npcInfoDecode"))
    if cycle is None or v1 is None:
        return {}
    calls = sorted(session.prov.facts(cycle).calls, key=lambda c: c.rva)
    creates = [c for c in calls if c.target is not None and model.resolve(c.target) == create and len(c.args) > 1]
    first = next((i for i, c in enumerate(creates) if c.args[1] == Const(v1)), None)
    if first is None or first + 1 >= len(creates):
        session.notes.append(f"mouse click v2: no packet created after the six-byte click in {cycle:#x}")
        return {}
    v1_send = _next_call(model, calls, creates[first].rva, send)
    packet = creates[first + 1]
    end = _next_call(model, calls, packet.rva, send)
    if v1_send is None or end is None or not v1_send.rva < packet.rva < end.rva:
        session.notes.append(f"mouse click v2: the packets after the six-byte click aren't each sent ({cycle:#x})")
        return {}
    if not isinstance(packet.args[0], StackAddr) or not isinstance(packet.args[1], Const):
        session.notes.append(f"mouse click v2: the packet at {packet.rva:#x} has no constant opcode")
        return {}
    opcode = packet.args[1].n
    size = _size_table(session, create).get(opcode)
    if size != SIZE:
        session.notes.append(f"mouse click v2: the packet after the six-byte click (opcode {opcode}) is {size} bytes")
        return {}
    gate = _gate(session, cycle, client, v1_send.rva, packet.rva)
    if gate is None:
        return {}
    pending, start, base = gate
    layout = _layout(session, cycle, start, base, pending, packet.rva, end.rva, create)
    if layout is None:
        return {}
    positions, transforms, sources = layout
    evidence = [session.evidence(start, cycle, "disp")]
    where = f"stored after the create at {packet.rva:#x}, gated by client + {pending:#x} != -1"
    return {
        "enums.clientProt.EVENT_MOUSE_CLICK_V2": (opcode, [session.evidence(packet.rva, cycle, "imm")],
                                                  f"the seven-byte packet the network step creates after the "
                                                  f"six-byte click ({v1}), {where}"),
        "enums.mouseClickV2": (positions, evidence, f"each source byte's index in the packet, {where}; {sources}"),
        "enums.mouseClickV2Transform": (transforms, evidence, f"each source byte's transform, {where}"),
    }


def _next_call(model, calls, after, target):
    return next((c for c in calls if c.rva > after and c.target is not None and model.resolve(c.target) == target), None)


def _gate(session, cycle, client, lo, hi):
    """(pending x offset, rva of the first read of it, the register holding the client there) for the 4-byte client
    field compared with -1 between the six-byte click's send and the packet's create, or None with a note."""
    offsets = {c.a.off for c in session.prov.facts(cycle).cmps
               if lo < c.rva < hi and isinstance(c.b, Const) and c.b.n in NONE and isinstance(c.a, Deref)
               and c.a.size == 4 and c.a.index is None and _is_client(c.a.base, client)}
    if len(offsets) != 1:
        session.notes.append(f"mouse click v2: {len(offsets)} client fields compared with -1 before the packet")
        return None
    pending = offsets.pop()
    for insn in session.model.insns(cycle):
        if lo < insn.rva < hi:
            for op in insn.ops:
                if (op.kind == "mem" and op.disp == pending and op.size == 4 and op.index is None
                        and op.base not in (None, "RIP", "RSP")):
                    return pending, insn.rva, op.base
    session.notes.append(f"mouse click v2: no read of client + {pending:#x} before the packet")
    return None


def _layout(session, cycle, start, base, pending, create_rva, send_rva, create):
    """({member: index}, {member: transform}, a note naming the button and flag fields) fitted over the runs, or None
    with a note."""
    insns = session.model.insns(cycle)
    rng = random.Random(SEED)
    runs = []
    for _ in range(RUNS):
        click = {"X": rng.randint(1, MAX_COORD), "Y": rng.randint(1, MAX_COORD), "T": rng.randint(1 << 32, 1 << 40),
                 "D": rng.randint(1, MAX_DELAY)}
        run = _run(session, insns, start, send_rva, base, pending, create_rva, create, rng, click, False)
        if run is None:
            return None
        runs.append((click, *run))
    fit = _fit(session, runs)
    if fit is None or not _clamped_in_order(session, insns, start, send_rva, base, pending, create_rva, create, rng,
                                            fit[0], fit[1]):
        return None
    return fit


def _run(session, insns, start, send_rva, base, pending, create_rva, create, rng, click, clamp):
    """({client offset: value} of the button and flag candidates, the seven stored bytes) for one click, or None
    with a note. With clamp, every 4-byte read through another pointer returns 0x100 plus its offset in its page, so
    a coordinate larger than that comes back as the canvas field that clamped it."""
    model = session.model
    reads = {}
    others = {}

    def load(addr, size, op):
        if CLIENT <= addr < CLIENT + CLIENT_SPAN:
            off = addr - CLIENT
            if off == pending:
                return click["X"]
            if off == pending + 4:
                return click["Y"]
            if off == pending + 8 and size == 8:
                return click["T"]
            if size == 8:
                return click["T"] - click["D"]
            return reads.setdefault(off, rng.randint(0, 0xff))
        if size == 8:
            return others.setdefault(addr, rng.randint(1 << 20, 1 << 32) << 12)
        return CLAMP_BASE + (addr & PAGE) if clamp else CANVAS

    def on_call(insn, emu):
        if insn.target is None or model.resolve(insn.target) != create:
            emu.stopped = f"a call other than the packet's create at {insn.rva:#x}"
            return False
        for r in VOLATILE:
            emu.regs[r] = rng.randint(1 << 20, 1 << 32) << 12
        return True

    emu = Emulator({base: CLIENT}, load, on_call, default=UNSET)
    if not emu.run(insns, start, send_rva):
        session.notes.append(f"mouse click v2: the run from {start:#x} stopped: {emu.stopped}")
        return None
    stored = {}
    for s in emu.stores:
        if s.rva <= create_rva or s.op.base in ("RSP", "RBP") or CLIENT <= s.addr < CLIENT + CLIENT_SPAN:
            continue
        for i in range(s.size):
            stored[s.addr + i] = (s.value >> (8 * i)) & 0xff
    if len(stored) != SIZE or max(stored) - min(stored) != SIZE - 1:
        session.notes.append(f"mouse click v2: the run stored {len(stored)} packet bytes, not {SIZE} in a row")
        return None
    lowest = min(stored)
    return reads, [stored[lowest + i] for i in range(SIZE)]


def _clamped_in_order(session, insns, start, send_rva, base, pending, create_rva, create, rng, positions, transforms):
    """Whether the field the gate reads is x: with a click beyond the canvas, it must come back clamped by a canvas
    field before the one that clamps the field after it (the canvas width before its height)."""
    click = {"X": CLAMP_CLICK, "Y": CLAMP_CLICK, "T": rng.randint(1 << 32, 1 << 40), "D": rng.randint(1, MAX_DELAY)}
    run = _run(session, insns, start, send_rva, base, pending, create_rva, create, rng, click, True)
    if run is None:
        return False
    packet = run[1]

    def value(low, high):
        return _undo(transforms[low], packet[positions[low]]) | _undo(transforms[high], packet[positions[high]]) << 8

    x, y = value("X_LOW", "X_HIGH") - CLAMP_BASE, value("Y_LOW", "Y_HIGH") - CLAMP_BASE
    if not (0 <= x <= PAGE and 0 <= y <= PAGE and x < y):
        session.notes.append(f"mouse click v2: a click beyond the canvas came back as {x + CLAMP_BASE:#x}, "
                             f"{y + CLAMP_BASE:#x}, not clamped by the width and then the height")
        return False
    return True


def _undo(transform, byte):
    """The source byte a transform stored as byte."""
    return {0: byte, 1: byte - 128, 2: -byte, 3: 128 - byte}[transform] & 0xff


def _source(click, member):
    """A source byte's value for a click, before its transform (the low byte is taken by the fit)."""
    delay = click["D"] << 1
    return {"X_LOW": click["X"], "X_HIGH": click["X"] >> 8, "Y_LOW": click["Y"], "Y_HIGH": click["Y"] >> 8,
            "DELAY_LOW": delay, "DELAY_HIGH": delay >> 8}[member]


def _fit(session, runs):
    common = sorted(set.intersection(*(set(reads) for _, reads, _ in runs)))
    matches = {}

    def fits(values):
        for index in range(SIZE):
            for t, transform in enumerate(TRANSFORMS):
                if all(transform(v & 0xff) & 0xff == packet[index] for v, (_, _, packet) in zip(values, runs)):
                    yield index, t

    for member in FIELDS:
        if member == "BUTTONS":
            continue
        values = [_source(click, member) for click, _, _ in runs]
        for index, t in fits(values):
            matches.setdefault(index, set()).add((member, t, None))
    for pair in permutations(common, 2):
        flag, button = pair
        values = [(reads[flag] & 1) | (reads[button] << 1) for _, reads, _ in runs]
        for index, t in fits(values):
            matches.setdefault(index, set()).add(("BUTTONS", t, pair))
    found = {}
    for index in range(SIZE):
        options = matches.get(index, set())
        if len(options) != 1:
            session.notes.append(f"mouse click v2: packet byte {index} fits {len(options)} source bytes")
            return None
        found[index] = options.pop()
    members = [m for m, _, _ in found.values()]
    if sorted(members) != sorted(FIELDS):
        session.notes.append(f"mouse click v2: the bytes fit {sorted(members)}, not each source byte once")
        return None
    positions = {m: index for index, (m, _, _) in found.items()}
    transforms = {m: t for m, t, _ in found.values()}
    flag, button = next(pair for m, _, pair in found.values() if m == "BUTTONS")
    return positions, transforms, f"the button from client + {button:#x}, the flag from client + {flag:#x}"
