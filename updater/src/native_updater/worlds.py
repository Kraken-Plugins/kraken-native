"""The world the local player is on, read from the Client by the cs2 runtime's world ops.

The cs2 runtime's handler of ops 3300-3399 subtracts 3300 from the op and jumps through a table of image-relative case
addresses. Its case of op 3318 (map_world) loads the Client's current world id, a 4-byte field read through
[global.client], and jumps to the int push the other int ops share. Its case of op 3324 (worldflags) loads the same
way from Client.worldFlags. The worldflags case is checked first: its one client load must be Client.worldFlags, so a
reshuffled table can't give another field. Then map_world's case must hold exactly one client load, which is the world.

  cs2_world   Client.currentWorld: the offset of the one 4-byte client load in map_world's case.

Output keys: Client.currentWorld.
"""
from .analysis.provenance import Deref, Global
from .scripts import _case_facts, _table_cases

OP_RANGE = 3300                  # the cs2 ops this handler runs
MAP_WORLD = 3318                 # pushes the client's current world id
WORLD_FLAGS = 3324               # pushes Client.worldFlags, checked against the handler's table first


def cs2_world(session, p):
    model = session.model
    client = Deref(Global(session.value("global.client")), 0, 8)
    flags = session.value("Client.worldFlags")
    handler = _table_cases(model, OP_RANGE)
    if handler is None:
        session.notes.append(f"worlds: no cs2 handler subtracts {OP_RANGE} and jumps through a case table")
        return {}
    fn, cases = handler
    if any(op - OP_RANGE >= len(cases) for op in (MAP_WORLD, WORLD_FLAGS)):
        session.notes.append(f"worlds: the {OP_RANGE} handler {fn:#x} has {len(cases)} cases, no op {MAP_WORLD} or "
                             f"{WORLD_FLAGS}")
        return {}
    facts = session.prov.facts(fn)
    bounds = sorted(set(cases))
    flag_fields = _client_fields(_case_facts(facts, cases[WORLD_FLAGS - OP_RANGE], bounds)["loads"], client)
    if [offset for offset, _ in flag_fields] != [flags]:
        session.notes.append(f"worlds: worldflags' case (op {WORLD_FLAGS}) loads {len(flag_fields)} client fields, "
                             f"not Client.worldFlags alone")
        return {}
    world_fields = _client_fields(_case_facts(facts, cases[MAP_WORLD - OP_RANGE], bounds)["loads"], client)
    if len(world_fields) != 1:
        session.notes.append(f"worlds: map_world's case (op {MAP_WORLD}) loads {len(world_fields)} client fields")
        return {}
    (offset, rva), = world_fields
    return {"Client.currentWorld": (offset, [session.evidence(rva, fn)],
                                    f"map_world's case (op {MAP_WORLD}) loads the client's world id from this field; "
                                    f"worldflags' case (op {WORLD_FLAGS}) loads Client.worldFlags the same way")}


def _client_fields(loads, client):
    """(offset, rva) of each 4-byte load of [client + offset] among loads."""
    return [(load.value.off, load.rva) for load in loads if _is_client_field(load.value, client)]


def _is_client_field(value, client):
    return isinstance(value, Deref) and value.base == client and value.size == 4 and value.index is None
