"""The client's chat history, for ChatMessage (docs/developer/events.md).

Every chat line goes through one function, (type, name, text, sender), the three strings as eastl strings. It finds
the type's history buffer (a ring of 100 lines, in a global hash map keyed by type), reuses the buffer's oldest line
or makes a new one, fills the line in, files it under its id in a second global hash map (id to line), then raises
ON_CHAT_TRANSMIT through its dedicated deferred wrapper, whose only caller it is. Filling a line in takes a fresh id
from a global counter (the counter's old value is the line's id; a list node the line points at holds it), stores the
client cycle and the type into the line, and copies the three strings into it, each behind an `&src != &field` check.

  chat_history   functions.chatAddLine: the one caller of the ON_CHAT_TRANSMIT wrappers with a fixed id.
                 ChatLine.{type, cycle, name, text, sender}: in the function under it (at most three calls deep) that
                 stores its second argument and global.cycle into the struct its first argument points at and compares
                 its third and fourth arguments with the addresses of fields of it (the name and the text). The sender
                 is the third field copied with the same string-assign call.
                 global.chatLines.nextId: the global int one of that function's callees increments.
                 global.chatLines.{buckets, bucketCount}: the id-to-line map. The add function calls two hash map
                 inserts on global maps (a load of [[B] + index*8] and of [B + 8]); the one keyed by the type (its
                 first argument) is the buffer map, the other the line map.

Output keys: functions.chatAddLine (internal), ChatLine.{type, cycle, name, text, sender}, global.chatLines.{buckets,
bucketCount, nextId}.
"""
from .analysis.provenance import Add, Arg, Deref, Global

FILL_DEPTH = 3              # calls from the add function down to the function filling a line in
BUCKETS_TO_COUNT = 8        # a global hash map's bucket count follows its bucket pointer


def chat_history(session, p):
    model = session.model
    raisers = sorted({site["fn"] for site in session.value("events.ON_CHAT_TRANSMIT") if "id" in site})
    adders = sorted({fn for raiser in raisers for fn in model.callers(raiser)})
    if len(adders) != 1:
        session.notes.append(f"chat: {len(adders)} functions call the ON_CHAT_TRANSMIT wrappers {raisers}")
        return {}
    add = adders[0]
    raises = [rva for raiser in raisers for rva, fn in model.call_sites(raiser) if fn == add]
    out = {"functions.chatAddLine": (add, [session.evidence(raises[0], add, "rip")] if raises else [],
                                     "the one caller of the dedicated ON_CHAT_TRANSMIT wrapper")}

    cycle = session.value("global.cycle")
    fills = [(fn, fields) for fn in _below(model, add, FILL_DEPTH)
             if (fields := line_fields(session.prov.facts(fn), cycle)) is not None]
    if len(fills) != 1:
        session.notes.append(f"chat: {len(fills)} functions under {add:#x} fill a chat line in")
        return out
    fill, fields = fills[0]
    for name, (off, rva) in fields.items():
        out[f"ChatLine.{name}"] = (off, [session.evidence(rva, fill)], FIELD_NOTES[name])

    counters = {g for callee in model.callees(fill) for g in increments(session.prov.facts(model.resolve(callee)))}
    if len(counters) == 1:
        counter, = counters
        out["global.chatLines.nextId"] = (counter[0], [session.evidence(counter[1], counter[2], "rip")],
                                          "the global int the line-filling function's callee increments: the next "
                                          "line's id")
    else:
        session.notes.append(f"chat: the line-filling function's callees increment {len(counters)} globals")

    lines, types = {}, {}
    for call in session.prov.facts(add).calls:
        if call.target is None or len(call.args) < 5:
            continue
        found = global_hash_map(session.prov.facts(model.resolve(call.target)))
        if found is not None:
            buckets, count, rva, fn = found
            (types if call.args[4] == Arg(0) else lines).setdefault((buckets, count), (rva, fn))
    if len(lines) != 1 or len(types) != 1 or lines.keys() == types.keys():
        session.notes.append(f"chat: the add function inserts into {len(types)} maps keyed by the type and "
                             f"{len(lines)} others")
        return out
    ((buckets, count), (rva, fn)), = lines.items()
    out["global.chatLines.buckets"] = (buckets, [session.evidence(rva, fn, "rip")],
                                       "the bucket pointer of the map the add function files the line under by id")
    out["global.chatLines.bucketCount"] = (count, [session.evidence(rva, fn, "rip")],
                                           "the bucket count beside it")
    return out


FIELD_NOTES = {
    "type": "where the line-filling function stores its type argument",
    "cycle": "where it stores global.cycle",
    "name": "the field it compares its name argument's address with before copying",
    "text": "the field it compares its text argument's address with before copying",
    "sender": "the third field it copies a string into with the same call",
}


def line_fields(facts, cycle):
    """{field: (offset, rva)} for a function filling a chat line in, or None if it doesn't look like one: it stores its
    second argument and the client cycle into its first argument's struct, checks its third and fourth arguments
    against the addresses of two fields before copying them, and copies a third string with the same call."""
    out = {}
    for store in facts.stores:
        t = store.target
        if not (isinstance(t, Deref) and t.base == Arg(0) and t.index is None and t.size == 4):
            continue
        if store.value == Arg(1):
            out.setdefault("type", (t.off, store.rva))
        elif store.value == Deref(Global(cycle), 0, 4):
            out.setdefault("cycle", (t.off, store.rva))
    for cmp in facts.cmps:
        for a, b in ((cmp.a, cmp.b), (cmp.b, cmp.a)):
            if isinstance(b, Add) and b.base == Arg(0):
                if a == Arg(2):
                    out.setdefault("name", (b.off, cmp.rva))
                elif a == Arg(3):
                    out.setdefault("text", (b.off, cmp.rva))
    if len(out) != 4:
        return None
    copies = [c for c in facts.calls if c.target is not None and c.args and _field(c.args[0]) is not None]
    assign = {c.target for c in copies if _field(c.args[0]) == out["name"][0]}
    others = [c for c in copies if c.target in assign and _field(c.args[0]) not in (out["name"][0], out["text"][0])]
    if len(assign) != 1 or len({_field(c.args[0]) for c in others}) != 1:
        return None
    out["sender"] = (_field(others[0].args[0]), others[0].rva)
    return out


def _field(value):
    return value.off if isinstance(value, Add) and value.base == Arg(0) else None


def increments(facts):
    """(global, rva, fn) for each global int the function loads, adds one to and stores back."""
    out = set()
    for store in facts.stores:
        t = store.target
        if isinstance(t, Deref) and isinstance(t.base, Global) and t.off == 0 and t.size == 4 \
                and store.value == Add(Deref(t.base, 0, 4), 1):
            out.add((t.base.rva, store.rva, store.fn))
    return out


def global_hash_map(facts):
    """(buckets, bucket count, rva, fn) for the global hash map a function looks a key up in: a bucket pointer B it
    indexes by 8 (a load of [[B] + index*8]) and a bucket count at B + 8 it loads too; None unless there is exactly
    one."""
    loaded = {d.base.rva for d, _ in facts.derefs()
              if isinstance(d.base, Global) and d.off == 0 and d.index is None and d.size in (0, 8)}
    found = set()
    for d, fact in facts.derefs():
        if d.index is not None and d.scale == 8 and isinstance(d.base, Deref) and isinstance(d.base.base, Global) \
                and d.base.off == 0 and d.base.index is None:
            buckets = d.base.base.rva
            if buckets + BUCKETS_TO_COUNT in loaded:
                found.add((buckets, buckets + BUCKETS_TO_COUNT))
    if len(found) != 1:
        return None
    (buckets, count), = found
    rva, fn = next((f.rva, f.fn) for d, f in facts.derefs() if isinstance(d.base, Global) and d.base.rva == buckets)
    return buckets, count, rva, fn


def _below(model, fn, depth):
    """Every function reached from fn by at most depth direct calls, fn excluded."""
    seen, frontier = set(), {fn}
    for _ in range(depth):
        frontier = {model.resolve(c) for f in frontier for c in model.callees(f)} - seen - {fn}
        seen |= frontier
    return sorted(seen)
