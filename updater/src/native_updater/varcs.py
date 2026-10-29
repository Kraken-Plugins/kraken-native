"""Where scripts set varcs, for VarClientIntChanged and VarClientStrChanged (docs/event-hooks-design.md §2.9).

The client keeps the varcs in a store the Client points at (Client.varcs): a vector of entries sorted by id, each a
variant value with its kind in a byte. Scripts are the only writers that set one varc: the interpreter's SET_VARC_INT
case (op 43) pops an int and calls the store's int setter with the store, the varc id from the script's int operands
and the int; the SET_VARC_STRING case (op 50) calls the string setter the same way with the popped string value. Each
setter finds the id's entry with the store's lower bound, inserts an entry when there is none, assigns the value and
marks the store for saving when the varc is a saved one. The only other writers work on the whole store: the logout
reset drops the varcs that aren't saved, and login loads the saved ones from the preferences file.

  varc_setters   functions.setVarcInt, functions.setVarcString: in the interpreter's case for each op, the one call
                 whose first argument is the store ([[global.client] + Client.varcs]) and whose callee inserts into
                 the store's entries (passes the address of Varcs.entries off its first argument to a call).

Output keys: functions.setVarcInt, functions.setVarcString.
"""
from .analysis.provenance import Add, Arg, Deref, Global
from .scripts import _case_facts, _op_cases

# The cs2 ops that set a varc, with the key of the setter each calls (the cache's script opcodes)
SETTERS = {
    "functions.setVarcInt": (43, "SET_VARC_INT"),
    "functions.setVarcString": (50, "SET_VARC_STRING"),
}


def varc_setters(session, p):
    model = session.model
    inner = session.value("functions.executeScriptInner")
    store = Deref(Deref(Global(session.value("global.client")), 0, 8), session.value("Client.varcs"), 8)
    entries = Add(Arg(0), session.value("Varcs.entries"))

    dispatch = _op_cases(model, inner)
    if dispatch is None:
        session.notes.append(f"varcs: no op dispatch through a byte table in the interpreter {inner:#x}")
        return {}
    cases, bounds = dispatch
    facts = session.prov.facts(inner)
    out = {}
    for key, (op, name) in SETTERS.items():
        if op not in cases:
            session.notes.append(f"varcs: the interpreter's dispatch has no op {op} ({name})")
            continue
        calls = [c for c in _case_facts(facts, cases[op], bounds)["calls"]
                 if c.target is not None and c.args and c.args[0] == store
                 and inserts(session.prov.facts(model.resolve(c.target)), entries)]
        setters = sorted({model.resolve(c.target) for c in calls})
        if len(setters) != 1:
            session.notes.append(f"varcs: the {name} case (op {op}) calls {len(setters)} functions that insert into "
                                 f"the varc store")
            continue
        out[key] = (setters[0], [session.evidence(calls[0].rva, inner, "rip")],
                    f"called by the {name} case (op {op}) with the varc store; inserts into Varcs.entries")
    if len({value for value, _, _ in out.values()}) < len(out):
        session.notes.append("varcs: the int and string cases call the same setter")
        return {}
    return out


def inserts(facts, entries):
    """Whether the function passes the address of the store's entries (off its first argument) to a call."""
    return any(entries in c.args for c in facts.calls)
