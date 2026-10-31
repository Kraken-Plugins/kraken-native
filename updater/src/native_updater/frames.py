"""The render queue between the game thread and the render thread (spec/90-draw.yaml, "Frame sync" in
docs/developer/in-game-drawing.md).

The GL device records each frame's commands on the game thread into a frame object it holds at [device + R], then
endFrame(device, ...) queues the frame and clears [device + R]. A render thread pops the frame into [device + X], runs
its commands and presents it with present(device + C). Without a render thread, appUpdate presents the frame itself
right after endFrame, after checking [device + R].

The DLL hooks endFrame to draw the overlay for the frame being queued, while the game thread waits, and matches the
frame the render thread presents ([device + X], device = the present's argument - C) to the overlay drawn for it.
"""
from .analysis.provenance import Add, Arg, Const, Deref, Ret


def render_frames(session, p):
    """functions.renderEndFrame and the GlDevice offsets, from appUpdate's own present and the render loop's.

      appUpdate    present(device + C), where device is a pointer appUpdate loads; before it, it compares
                   [device + R] with 0. endFrame is the call before that present which takes the device and whose
                   body stores 0 to [arg0 + R].
      render loop  the other caller of present, present(arg0 + C). It stores a call's result (the popped frame) to
                   [arg0 + X] and clears it again; X is not R.
    """
    model, prov = session.model, session.prov
    present = session.fn("@Present")
    update = session.fn("@AppUpdate")
    uf = prov.facts(update)

    direct = [(c, m) for c in uf.calls if c.target == present and c.args for m in c.args[0].members()
              if isinstance(m, Add) and isinstance(m.base, Deref)]
    if len(direct) != 1:
        session.notes.append(f"render frames: appUpdate presents {len(direct)} times through a loaded device")
        return {}
    present_call, context_arg = direct[0]
    device, context = context_arg.base, context_arg.off

    recording = {c.a.off for c in uf.cmps if isinstance(c.a, Deref) and c.a.size == 8 and c.b == Const(0)
                 and device in c.a.base.members()}
    ends = {}
    for c in uf.calls:
        if c.target is None or c.rva >= present_call.rva or not c.args or device not in c.args[0].members():
            continue
        cleared = recording & _cleared(prov.facts(c.target))
        if cleared:
            ends[c.target] = (c, cleared)
    if len(ends) != 1:
        session.notes.append(f"render frames: {len(ends)} calls before appUpdate's present clear the recorded frame")
        return {}
    end_fn, (end_call, cleared) = next(iter(ends.items()))
    if len(cleared) != 1:
        return {}
    recorded = next(iter(cleared))

    rendering, loop = set(), None
    for fn in model.callers(present):
        if fn == update:
            continue
        lf = prov.facts(fn)
        if not any(c.target == present and c.args and Add(Arg(0), context) in c.args[0].members() for c in lf.calls):
            continue
        popped = {s.target.off for s in lf.stores if _field(s.target) and isinstance(s.value, Ret)}
        found = (popped & _cleared(lf)) - {recorded}
        if found:
            rendering |= found
            loop = fn
    if len(rendering) != 1:
        session.notes.append(f"render frames: {len(rendering)} candidate offsets for the frame being rendered")
        return {}

    present_ev = [session.evidence(present_call.rva, update)]
    return {
        "RenderEndFrame": (end_fn, [session.evidence(end_call.rva, update)],
                           "appUpdate's call before its own present whose body clears the recorded frame"),
        "GlDevice.context": (context, present_ev, "appUpdate and the render loop present device + this"),
        "GlDevice.recordingFrame": (recorded, present_ev, "compared with 0 before appUpdate's present; endFrame "
                                                          "clears it"),
        "GlDevice.renderingFrame": (next(iter(rendering)), [session.evidence(loop, loop, "rip")],
                                    "the render loop stores the popped frame here and clears it"),
    }


def _field(target):
    """Whether a store target is a pointer-sized field of the first argument: [arg0 + offset]8."""
    return isinstance(target, Deref) and target.base == Arg(0) and target.size == 8 and target.index is None


def _cleared(facts):
    """The offsets of the pointer-sized fields of its first argument a function stores 0 to."""
    return {s.target.off for s in facts.stores if _field(s.target) and s.value == Const(0)}
