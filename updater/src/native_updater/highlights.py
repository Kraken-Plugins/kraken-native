"""The client's highlight system beyond its Lua handlers: the functions the Lua API doesn't bind, and the layout of the
style struct its configure function reads.

  script ops    The clientscript ops 7000..7044 (HIGHLIGHT_*) run through one dispatcher, a jump table on
                `opcode - base`. Five ops per category, in enums.highlightCategory order: configure (calls the channel
                store with the category in arg1), on, off, active, clear (calls the channel clear with the category in
                arg1). Every on/off/clear passes the manager, client + Client.highlightManager, as arg0. LOC and LOCTYPE
                have no Lua binding, so their on/off functions (and the clear) come from here. The NPC, NPC type, obj
                type, player and tile cases must call the functions the Lua handlers call, and the obj cases the Lua
                handlers themselves; when any disagrees nothing is emitted.
  config struct The sol2 registration of osrs.ClientOp.HighlightConfig stores each field's member offset in a stack
                int right next to the LEA of its name, before the call that registers it.

Output fields (see `highlight_script_ops`, `highlight_config_fields`): HighlightScriptOps, HighlightLocOn,
HighlightLocOff, HighlightLocTypeOn, HighlightLocTypeOff, HighlightChannelClear; HighlightConfig.<field>.
"""
import re

from . import roles
from .x86 import VOLATILE
from .analysis.provenance import Add, Const, Deref, Global

OPS_PER_CATEGORY = 5
OP_CONFIGURE, OP_ON, OP_OFF, OP_CLEAR = 0, 1, 2, 4
SCRIPT_OP_BASE = 7000      # the clientscript opcode of the first case (NPC configure)

# Category -> (on, off) the Lua handlers' fingerprints name. OBJ's cases call the Lua handlers instead (bindings).
LUA_CORES = {
    "NPC": ("@HighlightNpcOn", "@HighlightNpcOff"),
    "NPCTYPE": ("@HighlightNpcTypeOn", "@HighlightNpcTypeOff"),
    "OBJTYPE": ("@HighlightObjTypeOn", "@HighlightObjTypeOff"),
    "PLAYER": ("@HighlightPlayerOn", "@HighlightPlayerOff"),
    "TILE": ("@HighlightTileOn", "@HighlightTileOff"),
}
LUA_HANDLERS = {"OBJ": ("ClientOp.highlightObjOn", "ClientOp.highlightObjOff")}
SCRIPT_ONLY = {"LOC": ("HighlightLocOn", "HighlightLocOff"), "LOCTYPE": ("HighlightLocTypeOn", "HighlightLocTypeOff")}

CONFIG_TYPE = "osrs.ClientOp.HighlightConfig"
# Lua field name -> mapping key field (the colour's Lua name is longer than the struct field needs)
CONFIG_FIELDS = {"highlightColour": "colour"}
FIELD_WINDOW = 8           # a field's name LEA and its offset store sit within this many instructions of each other
IDENTIFIER = re.compile(r"^[a-z][A-Za-z0-9]*$")


def highlight_script_ops(session, p):
    model = session.model
    manager = session.value("Client.highlightManager")
    categories = session.value("enums.highlightCategory")
    store = session.fn("@HighlightConfigStore")
    cores = {cat: (session.fn(on), session.fn(off)) for cat, (on, off) in LUA_CORES.items()}
    handlers = {cat: (session.fn(on), session.fn(off)) for cat, (on, off) in LUA_HANDLERS.items()}
    dispatch = _dispatcher(session, cores["NPC"][0], store)
    if dispatch is None:
        return {}
    facts = session.prov.facts(dispatch)
    table = _jump_table(session, dispatch, facts)
    if table is None:
        return {}
    base, starts = table
    if base != SCRIPT_OP_BASE:
        session.notes.append(f"highlight script ops: the dispatch's first case is op {base}, expected {SCRIPT_OP_BASE}")
    cases = _calls_by_case(facts, starts)

    def manager_calls(case):
        return [c for c in cases.get(case, ()) if c.args and roles.match(session, {"addr": "client"}, c.args[0])
                and c.args[0].off == manager and c.target is not None]

    def targets(case):
        return {model.resolve(c.target) for c in cases.get(case, ()) if c.target is not None}

    problems = []
    clear, clear_ev = {}, None
    for name, cat in sorted(categories.items(), key=lambda kv: kv[1]):
        if name == "NUM_CATEGORIES" or cat < 0:
            continue
        first = cat * OPS_PER_CATEGORY
        if first + OP_CLEAR >= len(starts):
            continue
        configure = [c for c in manager_calls(first + OP_CONFIGURE) if model.resolve(c.target) == store]
        if not any(len(c.args) > 1 and c.args[1] == Const(cat) for c in configure):
            problems.append(f"{name}: configure case does not call the channel store with category {cat}")
        for c in manager_calls(first + OP_CLEAR):
            if len(c.args) > 1 and c.args[1] == Const(cat):
                clear.setdefault(model.resolve(c.target), []).append(cat)
                clear_ev = clear_ev or session.evidence(c.rva, dispatch)
        if name in cores:
            for op, want in ((OP_ON, cores[name][0]), (OP_OFF, cores[name][1])):
                if want not in targets(first + op):
                    problems.append(f"{name}: case {base + first + op} does not call {want:#x}")
        if name in handlers:
            for op, want in ((OP_ON, handlers[name][0]), (OP_OFF, handlers[name][1])):
                if want not in targets(first + op):
                    problems.append(f"{name}: case {base + first + op} does not call the Lua handler {want:#x}")
    if problems:
        session.notes.append("highlight script ops: " + "; ".join(problems))
        return {}

    out = {"HighlightScriptOps": (dispatch, [session.evidence(dispatch, dispatch, "rip")],
                                  "calls the NPC on function and the channel store; jump table on opcode - "
                                  f"{base}")}
    for name, (on_key, off_key) in SCRIPT_ONLY.items():
        cat = categories.get(name)
        if cat is None:
            continue
        for op, key in ((OP_ON, on_key), (OP_OFF, off_key)):
            calls = manager_calls(cat * OPS_PER_CATEGORY + op)
            found = {model.resolve(c.target) for c in calls}
            if len(found) != 1:
                session.notes.append(f"highlight script ops: case {base + cat * OPS_PER_CATEGORY + op} ({key}) "
                                     f"calls {len(found)} functions with the manager")
                continue
            call = calls[0]
            out[key] = (found.pop(), [session.evidence(call.rva, dispatch, "rip")],
                        f"script op {base + cat * OPS_PER_CATEGORY + op} calls it with the manager")
    if len(clear) == 1:
        fn, cats = next(iter(clear.items()))
        if len(cats) >= len([n for n in categories if n != "NUM_CATEGORIES"]) - 1:
            out["HighlightChannelClear"] = (fn, [clear_ev], f"the clear case of {len(cats)} categories calls it with "
                                                            "(manager, category, channel)")
    else:
        session.notes.append(f"highlight script ops: the clear cases call {len(clear)} different functions")
    return out


def _dispatcher(session, npc_on, store):
    """The one function calling both the NPC on function and the channel store, other than the Lua handlers."""
    model = session.model
    both = set(model.callers(npc_on)) & set(model.callers(store))
    if len(both) != 1:
        session.notes.append(f"highlight script ops: {len(both)} functions call the NPC on function and the store")
        return None
    return both.pop()


def _jump_table(session, dispatch, facts):
    """(first opcode, [case start rva per index]) of the dispatch's jump table on its opcode argument, or None.

    The table holds image-relative case addresses: a 4-byte load [image base + (arg1 - base) * 4 + table], bounded by
    a compare of the same index."""
    model = session.model
    for l in facts.loads:
        v = l.value
        if not (isinstance(v, Deref) and isinstance(v.base, Global) and v.base.rva == 0 and v.size == 4
                and v.scale == 4 and isinstance(v.index, Add)):
            continue
        bias = v.index.off
        if bias >= 1 << 31:
            bias -= 1 << 32
        if bias >= 0:
            continue
        bound = next((c.b.n for c in facts.cmps if c.a == v.index and isinstance(c.b, Const)), None)
        if bound is None or not 0 < bound < 512:
            continue
        try:
            raw = model.pe.read(v.off, 4 * (bound + 1))
        except (AttributeError, TypeError, ValueError):
            return None
        if raw is None or len(raw) < 4 * (bound + 1):
            return None
        starts = [int.from_bytes(raw[4 * i: 4 * i + 4], "little") for i in range(bound + 1)]
        if not all(model.function_containing(s) == dispatch for s in starts):
            session.notes.append("highlight script ops: a jump-table entry points outside the dispatch")
            return None
        return -bias, starts
    session.notes.append("highlight script ops: no jump table on the opcode in the dispatch")
    return None


def _calls_by_case(facts, starts):
    """{case index: [Call]}: each call belongs to the case whose code it follows, cases being laid out in order. A case
    that shares its start with another (the default) owns no calls."""
    distinct = sorted({s for s in starts if starts.count(s) == 1})
    index_of = {s: i for i, s in enumerate(starts) if starts.count(s) == 1}
    out = {}
    for call in sorted(facts.calls, key=lambda c: c.rva):
        owner = None
        for s in distinct:
            if s <= call.rva:
                owner = s
            else:
                break
        if owner is not None:
            out.setdefault(index_of[owner], []).append(call)
    return out


def highlight_config_fields(session, p):
    """HighlightConfig.<field>: the member offset each field of the usertype is registered with."""
    model = session.model
    refs = {fn for _, fn in model.string_refs(CONFIG_TYPE) if fn is not None}
    if len(refs) != 1:
        session.notes.append(f"highlight config: {len(refs)} functions reference {CONFIG_TYPE!r}")
        return {}
    register = refs.pop()
    out = {}
    pending_name = pending_value = None
    zero = set()
    insns = model.insns(register)
    for k, insn in enumerate(insns):
        if insn.mnem == "XOR" and len(insn.ops) == 2 and insn.ops[0].kind == "reg" and insn.ops[1].kind == "reg" \
                and insn.ops[0].reg == insn.ops[1].reg:
            zero.add(insn.ops[0].reg)
        elif insn.ops and insn.ops[0].kind == "reg" and insn.mnem not in ("CMP", "TEST", "PUSH"):
            zero.discard(insn.ops[0].reg)
        if insn.mnem == "MOV" and len(insn.ops) == 2 and insn.ops[0].kind == "mem" and insn.ops[0].size == 4 \
                and insn.ops[0].base in ("RBP", "RSP") and insn.ops[0].index is None:
            src = insn.ops[1]
            if src.kind == "imm":
                pending_value = (src.imm, insn.rva, k)
            elif src.kind == "reg" and src.reg in zero:
                pending_value = (0, insn.rva, k)
        if insn.mnem == "LEA" and len(insn.ops) == 2 and insn.ops[1].kind == "mem" and insn.ops[1].base == "RIP":
            text = model.string_at(insn.ops[1].disp)
            if text and IDENTIFIER.match(text):
                pending_name = (text, k)
        if insn.is_call:
            zero.difference_update(VOLATILE)
            if pending_name and pending_value and abs(pending_name[1] - pending_value[2]) <= FIELD_WINDOW:
                name = CONFIG_FIELDS.get(pending_name[0], pending_name[0])
                value, rva, _ = pending_value
                if 0 <= value < 0x100:
                    out[f"HighlightConfig.{name}"] = (value, [session.evidence(rva, register, "imm")],
                                                      f"registered as {CONFIG_TYPE}.{pending_name[0]}")
            pending_name = pending_value = None
    offsets = [v[0] for v in out.values()]
    if len(set(offsets)) != len(offsets):
        session.notes.append(f"highlight config: fields share offsets {sorted(offsets)}")
        return {}
    return out

