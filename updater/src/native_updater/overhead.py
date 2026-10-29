"""The overhead text an actor shows and the client's count of actor steps, for OverheadTextChanged
(docs/event-hooks-design.md §2.11).

The NPC update's say block, the player update's say and public chat blocks, and Lua's npcSay and playerSay write the
text into an eastl string on the actor and set its timer (Actor.overheadTimer: 100 cycles for an NPC, 150 for a player,
Lua's duration). Once a client cycle, while logged in, the client steps its actors: one function walks the players and
the NPCs and counts each timer above 0 down by one, emptying the text when it reaches 0, and the step function calling
it then increments a counter on the Client. A timer only goes down, one per step of that counter, until a write sets it
again, which is how the API tells the same line said again.

  overhead_countdown   Actor.overheadText: in a function storing [P + Actor.overheadTimer] - 1 back into the timer, the
                       field P + X it assigns an empty range to (a call taking P + X and the same address twice).
                       Client.actorSteps: in a function functions.clientCycle calls, the field of its first argument
                       the INC right after its call of that countdown increments.

Output keys: Actor.overheadText, Client.actorSteps.
"""
from .analysis.provenance import Add, Arg, Deref


def overhead_countdown(session, p):
    model = session.model
    timer = session.value("Actor.overheadTimer")
    countdowns = {}
    for fn in sorted({fn for _, fn in model.insns_with_disp(timer) if fn is not None}):
        text = countdown_text(session.prov.facts(fn), timer)
        if text is not None:
            countdowns[fn] = text
    texts = {off for off, _ in countdowns.values()}
    if len(texts) != 1:
        session.notes.append(f"overhead: {len(countdowns)} functions count Actor.overheadTimer down, emptying "
                             f"{len(texts)} different fields")
        return {}
    fn, (text, rva) = next(iter(countdowns.items()))
    out = {"Actor.overheadText": (text, [session.evidence(rva, fn)],
                                  f"the string {len(countdowns)} functions empty when they count the timer down to 0")}

    cycle_callees = set(model.callees(session.value("functions.clientCycle")))
    counters = {}
    for countdown in countdowns:
        for caller in model.callers(countdown):
            if caller not in cycle_callees:
                continue
            found = step_counter(model, session.prov.facts(caller), countdown)
            if found is not None:
                counters[found[0]] = (found[1], caller)
    if len(counters) != 1:
        session.notes.append(f"overhead: the client cycle's callees increment {len(counters)} fields right after "
                             f"counting the overhead timers down")
        return out
    off, (rva, caller) = next(iter(counters.items()))
    out["Client.actorSteps"] = (off, [session.evidence(rva, caller)],
                                "the Client field the client cycle's actor step increments right after counting the "
                                "overhead timers down")
    return out


def countdown_text(facts, timer):
    """(text offset, rva) when the function stores [P + timer] - 1 back into [P + timer] and assigns an empty range to
    P + X (the text it clears once the timer runs out); None otherwise."""
    bases = {s.target.base for s in facts.stores
             if isinstance(s.target, Deref) and s.target.off == timer and s.target.index is None
             and s.target.size == 4 and s.value == Add(s.target, -1)}
    found = {(c.args[0].off, c.rva) for c in facts.calls
             if len(c.args) > 2 and isinstance(c.args[0], Add) and c.args[0].base in bases and c.args[1] == c.args[2]}
    if len({off for off, _ in found}) != 1:
        return None
    return min(found, key=lambda f: f[1])


def step_counter(model, facts, countdown):
    """(field offset, rva) of the INC of [Arg0 + X] that directly follows the function's call of `countdown` with its
    first argument; None if there is none."""
    for call in facts.calls:
        if call.target is None or model.resolve(call.target) != countdown or not call.args or call.args[0] != Arg(0):
            continue
        insn = model.insn(call.rva)
        after = call.rva + insn.length if insn is not None else None
        for store in facts.stores:
            t = store.target
            if store.rva == after and isinstance(t, Deref) and t.base == Arg(0) and t.index is None and t.size == 4 \
                    and model.insn(store.rva).mnem == "INC":
                return t.off, store.rva
    return None
