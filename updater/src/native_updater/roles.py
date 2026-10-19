"""Roles name the pointer a load is based on, so recipes talk about "the Actor" instead of registers.

  client                 the Client object: *(global.client)
  arg0 .. arg5           function arguments
  sp_result              the object inside a jag::shared_ptr returned by a call: Deref(Ret|Out, 8)
  {sp_result: "@F"}      same, restricted to calls of fingerprint F
  {ret: "@F"}            the raw return value of a call of F
  {ret_indirect: "@F"}   the return value of an indirect call (through a vtable or a pointer) made inside F
  node_value             the value slot of a hash-map node: Deref(any, 0x10)
  {field: {of: ROLE, key: K}}   Deref(ROLE, value(K)); or {field: {of: ROLE, offset: N}}
  (the YAML key is `offset`, never `off`: YAML 1.1 reads a bare `off` as the boolean false)
  global                 a pointer held in a global: Deref(Global(X), 0)
  any
"""
from .analysis.provenance import Arg, Deref, Global, Many, Out, Ret


def match(session, role, value):
    if isinstance(value, Many):
        return any(match(session, role, m) for m in value.values)
    if role in (None, "any"):
        return True
    if isinstance(role, str):
        if role == "client":
            return value == Deref(Global(session.value("global.client")), 0, 8)
        if role.startswith("arg"):
            return isinstance(value, Arg) and value.i == int(role[3:])
        if role == "sp_result":
            return _sp_result(value, None)
        if role == "node_value":
            # The value slot of a hash-map node: [node + 0x10] where the node itself came from a bucket walk,
            # i.e. the base is a pointer load (not an object field at +0x10 such as World.scene).
            return isinstance(value, Deref) and value.off == 0x10 and value.index is None and value.size in (0, 8) \
                and all(isinstance(m, Deref) and m.size in (0, 8) for m in value.base.members())
        if role == "global":
            return isinstance(value, Deref) and isinstance(value.base, Global) and value.off == 0
        if role == "hash_node":
            # An element of a pointer array indexed with scale 8: a bucket slot / chained node of a hash table.
            return isinstance(value, Deref) and value.index is not None and value.scale == 8 and value.size in (0, 8)
        raise ValueError(f"unknown role {role!r}")
    if "sp_result" in role:
        return _sp_result(value, session.fn(role["sp_result"]))
    if "ret" in role:
        target = session.fn(role["ret"])
        return isinstance(value, Ret) and value.target == target
    if "ret_indirect" in role:
        target = session.fn(role["ret_indirect"])
        return isinstance(value, Ret) and value.target is None and session.model.function_containing(value.rva) == target
    if "addr" in role:
        from .analysis.provenance import Add
        return isinstance(value, Add) and match(session, role["addr"], value.base)
    if "field" in role:
        f = role["field"]
        if not isinstance(value, Deref) or value.index is not None:
            return False
        off = session.value(f["key"]) if "key" in f else f.get("offset")
        if off is not None and value.off != off:
            return False
        return match(session, f.get("of", "any"), value.base)
    raise ValueError(f"unknown role {role!r}")


def _sp_result(value, target):
    if not (isinstance(value, Deref) and value.off == 8 and value.index is None and value.size in (0, 8)):
        return False
    bases = value.base.values if isinstance(value.base, Many) else (value.base,)
    for b in bases:
        if isinstance(b, (Ret, Out)) and (target is None or b.target == target):
            return True
    return False
