"""Which entities the scene draws, for hiding them from the hook DLL.

Everything the scene draws as an entity (players, NPCs, projectiles, keyed projectiles, graphics objects) is a
renderable. Each constructor starts by calling the entity base constructor with the object, which starts by calling
the renderable base constructor, and that one clears one byte: the renderable's hidden flag. The scene adds every
entity it draws through one function, and its draw skips an entity whose flag is set. The Lua API sets the flag on
actors (osrs.Entity.render3D) and on the projectile list (ClientOp.renderProjectiles); no Lua function reaches keyed
projectiles or graphics objects, which carry the same flag.

  renderable_hidden   GraphicsObject.hidden, KeyedProjectile.hidden, one per run with the constructor's fingerprint as
                      `ctor`: down the chain of first calls the constructor makes with the object (the constructor,
                      the entity base, the renderable base, which makes none), the one byte the last stores 0 to.

Output keys: GraphicsObject.hidden, KeyedProjectile.hidden.
"""
from .analysis.provenance import Arg, Const, Deref

CTORS = {                          # the fingerprint of each constructor, with the key its chain gives
    "@GraphicsObjectCtor": "GraphicsObject.hidden",
    "@KeyedProjectileCtor": "KeyedProjectile.hidden",
}
MAX_CHAIN = 4                      # base constructors followed below a constructor


def renderable_hidden(session, p):
    key = CTORS.get(p.get("ctor"))
    if key is None:
        session.notes.append(f"visibility: no key comes from the constructor {p.get('ctor')!r}")
        return {}
    found = _cleared_byte(session, session.fn(p["ctor"]))
    return {key: found} if found is not None else {}


def _cleared_byte(session, ctor):
    """(offset, evidence, note) of the one byte the renderable base clears, reached from `ctor` through the first
    call each constructor makes with the object; None if the chain doesn't end in a constructor clearing one byte."""
    chain = [ctor]
    for _ in range(MAX_CHAIN):
        base = _first_base(session, chain[-1])
        if base is None:
            break
        chain.append(base)
    if len(chain) < 2 or _first_base(session, chain[-1]) is not None:
        session.notes.append(f"visibility: the constructor {ctor:#x} reaches no base constructor making no such call")
        return None
    root = chain[-1]
    stores = [s for s in session.prov.facts(root).stores
              if s.value == Const(0) and isinstance(s.target, Deref) and s.target.base == Arg(0)
              and s.target.size == 1 and s.target.index is None]
    if len(stores) != 1:
        session.notes.append(f"visibility: the renderable base {root:#x} clears {len(stores)} bytes")
        return None
    store = stores[0]
    path = " -> ".join(f"{fn:#x}" for fn in chain)
    return store.target.off, [session.evidence(store.rva, root)], \
        f"the one byte the renderable base clears ({path})"


def _first_base(session, fn):
    """The function fn calls first with its first argument as the callee's, resolved; None if there is none."""
    calls = sorted((c for c in session.prov.facts(fn).calls
                    if c.target is not None and c.args and c.args[0] == Arg(0)), key=lambda c: c.rva)
    return session.model.resolve(calls[0].target) if calls else None
