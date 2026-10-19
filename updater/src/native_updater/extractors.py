"""Custom extractors: recipes that need more than a generic selector. Each returns
{field: (value, evidence[], note)}; the spec picks fields with `extractor: {name, field}`.

Every extractor is written against provenance facts (what is loaded from where), not against raw bytes,
so it survives register reallocation. Results are cached per run.
"""
from .analysis.provenance import Add, Arg, Bin, Const, Deref, Global, Mul, Ret, Out, contains, walk
from .session import Blocked

_cache = {}


def run(session, name, params):
    key = (id(session), name, tuple(sorted((k, str(v)) for k, v in params.items() if k not in ("field", "cls"))))
    if key not in _cache:
        _cache[key] = EXTRACTORS[name](session, params)
    return _cache[key]


def _ev(session, rva, fn=None, slot="disp"):
    return [session.evidence(rva, fn, slot)]


# ---- config loaders --------------------------------------------------------------------------------------------
REQUIRED_GROUPS = {6, 9, 10, 12, 13, 14}      # loc, npc, obj, seq, spotanim, varbit: the cache protocol constants


def config_loader_core(session, p):
    """The function called as loader(cache, &key, group, id). Candidates are call targets preceded by
    `MOV R8D, imm`; the winner passes a global (the config cache) as its first argument at most call sites and
    sees every required cache group id as its third argument."""
    model = session.model
    rows = model.db.execute(
        "SELECT i.rva, i.fn FROM insn i JOIN operand o ON o.rva = i.rva AND o.kind = 'imm' "
        "WHERE i.mnem = 'MOV' AND i.text LIKE 'MOV R8D,0x%' AND o.imm < 256").fetchall()
    candidates = {}
    for rva, fn in rows:
        nxt = model.db.execute("SELECT target FROM insn WHERE fn = ? AND rva > ? AND rva <= ? AND mnem = 'CALL' AND target IS NOT NULL "
                               "ORDER BY rva LIMIT 1", (fn, rva, rva + 32)).fetchone()
        if nxt:
            candidates.setdefault(model.resolve(nxt[0]), set()).add(fn)
    best = None
    for target, callers in candidates.items():
        if len(callers) < len(REQUIRED_GROUPS):
            continue
        groups, global_arg0, sites = {}, 0, 0
        for src, fn in model.call_sites(target):
            for c in session.prov.facts(fn).calls:
                if c.rva != src:
                    continue
                sites += 1
                a0 = c.args[0] if c.args else None
                if isinstance(a0, Global) or (isinstance(a0, Deref) and isinstance(a0.base, Global)):
                    global_arg0 += 1
                if len(c.args) > 2 and isinstance(c.args[2], Const):
                    groups.setdefault(c.args[2].n, (src, fn))
        if sites and global_arg0 / sites >= 0.25 and REQUIRED_GROUPS <= set(groups):
            if best is None or len(groups) > len(best[1]):
                best = (target, groups, global_arg0, sites)
    if best is None:
        return {}
    core, groups, global_arg0, sites = best
    out = {"ConfigLoaderCore": (core, _ev(session, core, core, "rip"), f"{len(groups)} group ids; cache global in arg0 at {global_arg0}/{sites} sites")}
    for group, (rva, fn) in groups.items():
        out[f"ConfigLoader.{group}"] = (fn, _ev(session, rva, fn, "imm"), f"calls the loader core with group {group}")
    return out


def _indexed_record(facts, index, owner_ok):
    """An array element address `index * stride + [owner + table]` computed in a function:
    (address value, stride, table offset, rva) for the first whose owner passes owner_ok, or None."""
    for a in facts.addrs:
        v = a.value
        if not (isinstance(v, Bin) and v.op == "+"):
            continue
        for mul, table in ((v.a, v.b), (v.b, v.a)):
            if isinstance(mul, Mul) and mul.base == index and isinstance(mul.factor, Const) \
                    and isinstance(table, Deref) and table.index is None and table.size in (0, 8) and owner_ok(table.base):
                return v, mul.factor.n, table.off, a.rva
    return None


def config_archive(session, p):
    """The config archive as the loader core walks it: core(archive, out, group, file).

      groups[group]  = [archive + Js5Archive.groups] + group * Js5Group.size, after group < [archive + groupCount];
                       a group whose dword at Js5Group.version is -1 is absent
      files[file]    = [group + Js5Group.files] + file * Js5File.size, after file < [group + Js5Group.fileCount];
                       a file whose start (Js5File.start) is -1 is absent, one whose buffer pointer is 0 not loaded yet
      out->ptr->pos  = file.start (the core rewinds the shared buffer it hands out: Packet.pos)
    """
    fn = session.fn("@ConfigLoaderCore")
    facts = session.prov.facts(fn)
    archive, outp, group, file = Arg(0), Arg(1), Arg(2), Arg(3)
    out = {}
    for c in facts.cmps:
        for a, b in ((c.a, c.b), (c.b, c.a)):
            if a == group and _is_field(b, archive, 8):
                out.setdefault("Js5Archive.groupCount", (b.off, _ev(session, c.rva, fn), "compared with the group id"))
    grec = _indexed_record(facts, group, lambda base: base == archive)
    if grec is None:
        return out
    grp, gstride, gtable, grva = grec
    out["Js5Archive.groups"] = (gtable, _ev(session, grva, fn), "group records, indexed by the group id")
    out["Js5Group.size"] = (gstride, _ev(session, grva, fn, "imm"), "group id * this")
    frec = _indexed_record(facts, file, lambda base: base == grp)
    for c in facts.cmps:
        for a, b in ((c.a, c.b), (c.b, c.a)):
            if _is_field(a, grp, 4) and b == Const(-1):
                out.setdefault("Js5Group.version", (a.off, _ev(session, c.rva, fn), "-1 marks an absent group"))
            if a == file and _is_field(b, grp, 8):
                out.setdefault("Js5Group.fileCount", (b.off, _ev(session, c.rva, fn), "compared with the file id"))
    if frec is None:
        return out
    rec, fstride, ftable, frva = frec
    out["Js5Group.files"] = (ftable, _ev(session, frva, fn), "file records, indexed by the file id")
    out["Js5File.size"] = (fstride, _ev(session, frva, fn, "imm"), "file id * this")
    for c in facts.cmps:
        for a, b in ((c.a, c.b), (c.b, c.a)):
            if _is_field(a, rec, 8) and b == Const(-1):
                out.setdefault("Js5File.start", (a.off, _ev(session, c.rva, fn), "-1 marks an absent file"))
            if _is_field(a, rec, 8) and b == Const(0):
                out.setdefault("Js5File.buffer", (a.off, _ev(session, c.rva, fn), "0 until the group is loaded"))
    start = out.get("Js5File.start")
    if start is not None:
        for s in facts.stores:
            t = s.target
            if s.value == Deref(rec, start[0], 8) and isinstance(t, Deref) and t.index is None \
                    and isinstance(t.base, Deref) and t.base.base == outp:
                out["Packet.pos"] = (t.off, _ev(session, s.rva, fn), "the returned buffer is rewound to the file's start")
    return out


def packet_layout(session, p):
    """The js5 group loader splits a group by its footer: it reads the chunk count from the packet's last byte,
    data[length - 1], and seeks to length - 1 - chunks * files * 4. That one store fixes the packet's position,
    length and data fields."""
    fn = session.fn("@ConfigGroupLoader")
    facts = session.prov.facts(fn)
    for s in facts.stores:
        t = s.target
        if not isinstance(t, Deref) or t.index is not None or t.size not in (0, 8):
            continue
        pkt, pos = (t.base.base, t.base.off + t.off) if isinstance(t.base, Add) else (t.base, t.off)
        subs = list(walk(s.value))
        lengths = {d.off for d in subs if isinstance(d, Deref) and d.base == pkt and d.index is None and d.size == 8 and d.off != pos}
        for d in subs:
            if isinstance(d, Deref) and d.size == 1 and d.index is not None and _is_field(d.base, pkt, 8):
                length = next((n for n in lengths if contains(d.index, Deref(pkt, n, 8))), None)
                if length is not None:
                    ev = _ev(session, s.rva, fn)
                    return {"Packet.pos": (pos, ev, "seek past the group's chunk table"),
                            "Packet.length": (length, ev, "the last byte is data[length - 1]"),
                            "Packet.data": (d.base.off, ev, "byte array the footer is read from")}
    return {}


def player_name_pair(session, p):
    """Player.name points at a pair of eastl::strings, {display name, normalised name}. playerName pushes the display
    string, so its offset is how far the pushed string is from the pair pointer (0 when the pointer itself is pushed);
    the name hash friend and ignore lists use reads the normalised one."""
    out = {}
    fn = session.fn("ClientOp.playerName")
    name = session.value("Player.name")
    for c in session.prov.facts(fn).calls:
        if c.target is not None or len(c.args) < 2:
            continue
        for m in c.args[1].members():
            if isinstance(m, Deref) and m.off == name and m.index is None and m.size in (0, 8):
                out.setdefault("PlayerName.display", (0, _ev(session, c.rva, fn), "the pair pointer is pushed as the display string"))
            elif isinstance(m, Add) and isinstance(m.base, Deref) and m.base.off == name:
                out.setdefault("PlayerName.display", (m.off, _ev(session, c.rva, fn), "pair + this is pushed as the display string"))
    return out


# ---- setSequence ---------------------------------------------------------------------------------------------------
def set_sequence(session, p):
    """Among callers of the SeqType loader, the one comparing its int argument with an Actor field and reading
    the SeqType priority: setSequence(actor, seq, delay). Yields the AnimState layout."""
    loader = session.fn("@ConfigLoader.12")
    for fn in session.model.callers(loader):
        facts = session.prov.facts(fn)
        anim = [c for c in facts.cmps if (c.a == Arg(1) and _is_field(c.b, Arg(0), 4)) or (c.b == Arg(1) and _is_field(c.a, Arg(0), 4))]
        if not anim:
            continue
        anim_off = (anim[0].b if c_is_arg1(anim[0].a) else anim[0].a).off
        seq_ptr = [l for l in facts.loads if _is_field(l.value, Arg(0), 8) and any(
            isinstance(d, Deref) and d.base == l.value and d.off in range(0x80, 0x200) for d, _ in facts.derefs())]
        if not seq_ptr:
            continue
        out = {
            "SetSequence": (fn, _ev(session, fn, fn, "rip"), "caller of SeqType loader comparing arg1 with [actor+anim]"),
            "Actor.anim": (anim_off, _ev(session, anim[0].rva, fn), "CMP arg1,[actor+X]"),
            "Actor.animSeqType": (seq_ptr[0].value.off, _ev(session, seq_ptr[0].rva, fn), "SeqType* read next to the id"),
        }
        delay = [s for s in facts.stores if _is_field(s.target, Arg(0), 4) and s.value == Arg(2)]
        if delay:
            out["Actor.animDelay"] = (delay[0].target.off, _ev(session, delay[0].rva, fn), "store of arg2 (delay)")
        loop = [s for s in facts.stores if _is_field(s.target, Arg(0), 2) and s.value == Const(0)]
        if loop:
            out["Actor.animLoop"] = (loop[0].target.off, _ev(session, loop[0].rva, fn), "16-bit reset on restart")
        state = [c for c in facts.calls if c.args and isinstance(c.args[0], Add) and c.args[0].base == Arg(0)]
        if state:
            out["Actor.animState"] = (state[0].args[0].off, _ev(session, state[0].rva, fn), "AnimState block passed by address")
        seq_field_cmps = [(d.off, r) for d, r in facts.derefs() if isinstance(d.base, Deref) and d.base == seq_ptr[0].value]
        for off, fact in seq_field_cmps:
            if any(c.a == Const(1) or c.b == Const(1) for c in facts.cmps if c.rva == fact.rva):
                out["SeqType.restartMode"] = (off, _ev(session, fact.rva, fn), "compared with 1 and 2")
            elif any(isinstance(c.a, Deref) and isinstance(c.b, Deref) for c in facts.cmps if c.rva == fact.rva):
                out["SeqType.priority"] = (off, _ev(session, fact.rva, fn), "old vs new SeqType field compared")
        copy = [s for s in facts.stores if _is_field(s.target, Arg(0), 4) and _is_field(s.value, Arg(0), 4) and s.value.off != s.target.off]
        if copy:
            out["Actor.orientationCandidate"] = (copy[0].target.off, _ev(session, copy[0].rva, fn), f"[actor+{copy[0].target.off:#x}] = [actor+{copy[0].value.off:#x}] when an animation starts")
            out["Actor.pathLength"] = (copy[0].value.off, _ev(session, copy[0].rva, fn), "the path length, copied when an animation starts")
        return out
    return {}


def c_is_arg1(v):
    return v == Arg(1)


def _is_field(v, base, size):
    return isinstance(v, Deref) and v.base == base and v.index is None and (size == 0 or v.size in (0, size))


# ---- Actor constructor ---------------------------------------------------------------------------------------------
def actor_ctor(session, p):
    """The constructor storing a run of -1 qwords (pose animations), a dword 200 (default height) and 100
    (overhead text timer). Candidates are narrowed with SQL first, then checked with provenance."""
    model = session.model
    rows = model.db.execute("SELECT fn, count(*) c FROM insn WHERE mnem = 'MOV' AND text LIKE 'MOV qword ptr [R%],-0x1' "
                            "GROUP BY fn HAVING c >= 7 ORDER BY c DESC LIMIT 40").fetchall()
    for fn, _ in rows:
        facts = session.prov.facts(fn)
        this = Arg(0)
        neg = sorted(s.target.off for s in facts.stores if _is_field(s.target, this, 8) and s.value == Const(-1))
        run = _longest_run(neg, 8)
        height = [s for s in facts.stores if _is_field(s.target, this, 4) and s.value == Const(0xC8)]
        timer = [s for s in facts.stores if _is_field(s.target, this, 0) and s.value == Const(0x64)]
        if len(run) < 7 or not height or not timer:
            continue
        pose_start = run[0]
        extra = [s for s in facts.stores if _is_field(s.target, this, 4) and s.target.off == run[-1] + 8 and s.value == Const(-1)]
        count = len(run) * 2 + len(extra)
        out = {
            "ActorCtor": (fn, _ev(session, fn, fn, "rip"), f"{len(run)} qword -1 stores from +{pose_start:#x}, height 200, timer 100"),
            "Actor.poseAnims": (pose_start, _ev(session, _store_rva(facts, pose_start), fn), f"{count} ints initialised to -1"),
            "Actor.poseAnimCount": (count, [], "derived from the -1 run"),
            "Actor.defaultHeight": (height[0].target.off, _ev(session, height[0].rva, fn), "dword 200"),
            "Actor.overheadTimer": (timer[0].target.off, _ev(session, timer[0].rva, fn), "100"),
        }
        speed = [s for s in facts.stores if _is_field(s.target, this, 0) and s.value == Const(0x20) and s.target.off > pose_start]
        if speed:
            out["Actor.turnSpeed"] = (speed[0].target.off, _ev(session, speed[0].rva, fn), "32")
        # Self-referencing sentinel stores initialise empty intrusive lists: [this+X] = this+X.
        heads = sorted({s.target.base.off for s in facts.stores if isinstance(s.target, Deref) and isinstance(s.target.base, Add)
                        and s.target.base.base == this and s.target.off == 0 and s.value == s.target.base})
        if heads:
            out["Actor.listHeads"] = (heads, _ev(session, fn, fn, "rip"), "self-pointing list sentinels initialised by the ctor")
        return out
    return {}


def _store_rva(facts, off, base_add=False):
    for s in facts.stores:
        if base_add:
            if isinstance(s.target.base, Add) and s.target.base.off == off:
                return s.rva
        elif isinstance(s.target, Deref) and s.target.off == off and s.target.base == Arg(0):
            return s.rva
    return facts.fn


def _longest_run(values, step):
    best, cur = [], []
    for v in values:
        if cur and v == cur[-1] + step:
            cur.append(v)
        else:
            cur = [v]
        if len(cur) > len(best):
            best = list(cur)
    return best


# ---- scene grid ------------------------------------------------------------------------------------------------------
def scene_grid(session, p):
    """Tile lookup(scene, out, plane, x, z, locType): index = (plane*sizeX + x)*sizeZ + z, entries of
    tileEntryStride bytes with Tile* at tileEntryPtrOffset; a second table serves plane == -1 (bridges).
    Layer slot offsets are paired with the result codes 0x100|layer stored right after each slot is tested."""
    fn = session.fn("@TileLookup")
    facts = session.prov.facts(fn)
    scene = Arg(0)
    out = {}
    # Sizes: the two scene fields used as IMUL factors. The one multiplied with the plane (arg2) is sizeX.
    size_x = size_z = None
    for a in facts.addrs:
        v = a.value
        if not isinstance(v, Mul):
            continue
        field = v.factor if _is_field(v.factor, scene, 4) else (v.base if _is_field(v.base, scene, 4) else None)
        other = v.base if field is v.factor else v.factor
        if field is None:
            continue
        if other == Arg(2):
            size_x = (field.off, a.rva)
        elif size_z is None or other == Arg(3):
            size_z = (field.off, a.rva)
    if size_x is None or size_z is None:
        return out
    out["Scene.sizeX"] = (size_x[0], _ev(session, size_x[1], fn), "multiplied with the plane")
    out["Scene.sizeZ"] = (size_z[0], _ev(session, size_z[1], fn), "multiplied with the row index")
    # Entry load: [index*stride + table + ptrOffset] where table is a scene field.
    entry = None
    for d, fact in facts.derefs():
        if d.index is None or d.scale != 1:
            continue
        sides = (d.base, d.index)
        table_side = next((s for s in sides if any(_is_field(m, scene, 8) for m in s.members())), None)
        mul_side = next((s for s in sides if isinstance(s, Mul) and isinstance(s.factor, Const)), None)
        if table_side is not None and mul_side is not None:
            entry = d
            out["Scene.tileEntryPtrOffset"] = (d.off, _ev(session, fact.rva, fn), "Tile* inside each entry")
            out["Scene.tileEntryStride"] = (mul_side.factor.n, _ev(session, fact.rva, fn), "index scale")
            tables = sorted({m.off for m in table_side.members() if _is_field(m, scene, 8)})
            break
    if entry is None:
        return out
    # Which table is the main one: the load that follows the address computation using sizeX.
    table_loads = sorted((l for l in facts.loads if _is_field(l.value, scene, 8) and l.value.off in tables), key=lambda l: l.rva)
    main_rva = max((a.rva for a in facts.addrs if contains(a.value, Deref(scene, size_x[0], 4))), default=0)
    main = min(table_loads, key=lambda l: abs(l.rva - main_rva)) if table_loads else None
    if main is not None:
        out["Scene.tiles"] = (main.value.off, _ev(session, main.rva, fn), "entry table for plane >= 0")
        for l in table_loads:
            if l.value.off != main.value.off:
                out["Scene.bridgeTiles"] = (l.value.off, _ev(session, l.rva, fn), "entry table for plane == -1")
    # Layer slots: pointer loads based on the entry's Tile*, paired with the following result-code store.
    codes = sorted((s for s in facts.stores if isinstance(s.value, Const) and 0x100 <= s.value.n <= 0x1ff), key=lambda s: s.rva)
    names = {0: "Tile.wall", 1: "Tile.wallDecor", 2: "Tile.gameObjectsSlot", 3: "Tile.groundDecor"}
    for l in sorted(facts.loads, key=lambda l: l.rva):
        v = l.value
        if v.index is None and v.size in (0, 8) and v.base == entry and v.off >= 0x20:
            nxt = next((s for s in codes if s.rva > l.rva), None)
            if nxt:
                layer = nxt.value.n & 0xFF
                out[names.get(layer, f"Tile.layer{layer}")] = (v.off, _ev(session, l.rva, fn), f"result code {nxt.value.n:#x}")
    # Tag decoder: the function called most often with a field of a slot object; its shift gives the id.
    tag_calls = [c for c in facts.calls if c.target is not None and c.args and isinstance(c.args[0], Deref) and c.args[0].index is None]
    counts = {}
    for c in tag_calls:
        counts[c.target] = counts.get(c.target, 0) + 1
    if counts:
        decoder = max(counts, key=counts.get)
        calls = [c for c in tag_calls if c.target == decoder]
        out["LocTagToId"] = (decoder, _ev(session, decoder, decoder, "rip"), f"called {counts[decoder]}x with a loc tag")
        slot_tags = [c for c in calls if isinstance(c.args[0].base, Deref) and c.args[0].base.base == entry]
        if slot_tags:
            out["Loc.tag"] = (slot_tags[0].args[0].off, _ev(session, slot_tags[0].rva, fn), "argument of the tag decoder")
        dec_facts = session.prov.facts(decoder)
        shift = [i for i in session.model.insns(decoder) if i.mnem in ("SHR", "SAR") and len(i.ops) == 2 and i.ops[1].kind == "imm"]
        if shift:
            out["Loc.tagIdShift"] = (shift[0].ops[1].imm, _ev(session, shift[0].rva, decoder, "imm"), "tag >> n")
        else:
            for c in dec_facts.calls:
                consts = [a.n for a in c.args[1:] if isinstance(a, Const) and 8 <= a.n <= 40]
                if consts:
                    out["Loc.tagIdShift"] = (consts[0], _ev(session, c.rva, decoder, "imm"), "shift passed to the bit-extract helper")
                    break
        # Game object: the decoder argument read from +8 of another call's result ({ctrl, GameObject*}).
        for c in calls:
            b = c.args[0].base
            if isinstance(b, Deref) and b.off == 8 and isinstance(b.base, (Ret, Out)) and b.base.target is not None:
                out["GameObject.tag"] = (c.args[0].off, _ev(session, c.rva, fn), "tag of the game object returned by the tile object lookup")
                out["TileGameObjectLookup"] = (b.base.target, _ev(session, b.base.target, None, "rip"), "returns {ctrl, GameObject*}")
                break
    return out


def _deref_rva(facts, d):
    for x, fact in facts.derefs():
        if x == d:
            return fact.rva
    return facts.fn


def tile_game_objects(session, p):
    """Tile game-object lookup(scene, plane, x, z): re-does the tile lookup, then walks the tile's game-object
    array {ctrl, GameObject*} comparing each object's origin with x and z."""
    fn = session.fn("@TileGameObjectLookup")
    facts = session.prov.facts(fn)
    out = {}
    tile = None
    for d, _ in facts.derefs():       # the Tile* entry load: [index*stride + table + ptrOffset]
        if d.index is not None and d.scale == 1 and any(isinstance(s, Mul) for s in (d.base, d.index)):
            tile = d
            break
    if tile is None:
        return out
    tile_fields = [(d, f) for d, f in facts.derefs() if d.base == tile and d.index is None]
    counts = [(d, f) for d, f in tile_fields if d.size == 4 and any(contains(c.a, d) or contains(c.b, d) for c in facts.cmps)]
    if counts:
        out["Tile.gameObjectCount"] = (counts[0][0].off, _ev(session, counts[0][1].rva, fn), "loop bound compared each iteration")
    # The entry array is either indexed ([[tile+X] + i*stride + 8]) or walked by pointer (ADD reg, stride).
    for d, f in tile_fields:
        if d.size not in (0, 8):
            continue
        indexed = [(x, g) for x, g in facts.derefs() if x.index is not None and x.base == d]
        walked = [a for a in facts.addrs if isinstance(a.value, Add) and a.value.base == d and 8 <= a.value.off <= 64]
        if indexed:
            out["Tile.gameObjects"] = (d.off, _ev(session, f.rva, fn), "array indexed by the loop counter")
            out["Tile.gameObjectEntryStride"] = (indexed[0][0].scale, _ev(session, indexed[0][1].rva, fn, "imm"), "index scale")
            break
        if walked and any(x.base == d and x.off == 8 for x, _ in facts.derefs()):
            out["Tile.gameObjects"] = (d.off, _ev(session, f.rva, fn), "array walked by pointer; object pointer at +8 of each entry")
            out["Tile.gameObjectEntryStride"] = (walked[0].value.off, _ev(session, walked[0].rva, fn, "imm"), "pointer increment per entry")
            break
    # Origins: dword fields of the object (loaded through +8 of an array entry) compared with the x/z arguments.
    origins = {}
    for c in facts.cmps:
        for side, other in ((c.a, c.b), (c.b, c.a)):
            if other in (Arg(2), Arg(3)):
                for d in walk(side):
                    if isinstance(d, Deref) and d.size == 4 and isinstance(d.base, Deref) and d.base.off == 8 and d.base.index is None:
                        origins.setdefault(other.i, (d.off, c.rva))
    if 2 in origins:
        out["GameObject.originX"] = (origins[2][0], _ev(session, origins[2][1], fn), "compared with the x argument")
    if 3 in origins:
        out["GameObject.originZ"] = (origins[3][0], _ev(session, origins[3][1], fn), "compared with the z argument")
    return out


# ---- ground item piles -------------------------------------------------------------------------------------------------
def obj_pile_grid(session, p):
    """Nested stride-0x18 containers: *(w+grid+0x10+plane*0x18) -> +0x10+x*0x18 -> z*0x18 = list head."""
    lookup = session.fn("@ObjPileLookup")
    for call in session.prov.facts(lookup).calls:
        if call.target is None:
            continue
        facts = session.prov.facts(call.target)
        rets = [r.value for r in facts.returns if isinstance(r.value, Deref) and r.value.index is not None]
        if not rets:
            continue
        chain = rets[0]
        levels = []
        node = chain
        while isinstance(node, Deref) and node.index is not None:
            scale = node.scale * (node.index.factor.n if isinstance(node.index, Mul) and isinstance(node.index.factor, Const) else 1)
            levels.append((node.off, scale))
            node = node.base
        if not isinstance(node, Deref) or len(levels) < 2:
            continue
        fn = call.target
        return {
            "ObjPileTile": (fn, _ev(session, fn, fn, "rip"), f"{len(levels)} nested indexed loads"),
            "World.objPiles": (node.off, _ev(session, facts.loads[0].rva, fn), "grid root in World"),
            "World.objPiles.stride": (levels[0][1], [], "per-plane / per-column stride"),
            "World.objPiles.dataOffset": (levels[-1][0], [], "offset of the array inside each level"),
        }
    return {}


# ---- allocation sizes ------------------------------------------------------------------------------------------------
def operator_new(session, p):
    """operator new: the call target most often preceded by MOV ECX, <size> for the known object sizes."""
    rows = session.model.db.execute(
        "SELECT i2.target, count(*) FROM insn i JOIN operand o ON o.rva = i.rva AND o.kind = 'imm' AND o.idx = 1 "
        "JOIN insn i2 ON i2.fn = i.fn AND i2.rva > i.rva AND i2.rva <= i.rva + 12 AND i2.mnem = 'CALL' AND i2.target IS NOT NULL "
        "WHERE i.mnem = 'MOV' AND i.text LIKE 'MOV ECX,0x%' AND o.imm > 0x100 GROUP BY i2.target ORDER BY count(*) DESC LIMIT 1").fetchone()
    if not rows:
        return {}
    fn = session.model.resolve(rows[0])
    return {"OperatorNew": (fn, _ev(session, fn, fn, "rip"), f"{rows[1]} sized allocations")}


def alloc_sizes(session, p):
    """Sizes passed to operator new inside a function (or the function's callers): struct sizes."""
    new = session.fn("@OperatorNew")
    fn = session.fn(p["in"])
    sizes = []
    for call in session.prov.facts(fn).calls:
        if call.target == new and call.args and isinstance(call.args[0], Const):
            sizes.append((call.args[0].n, call.rva))
    if not sizes:
        return {}
    return {p.get("field", "size"): (sizes[0][0], _ev(session, sizes[0][1], fn, "imm"), "operator new size")}


# ---- strings ---------------------------------------------------------------------------------------------------------
def string_sso(session, p):
    """eastl::string layout from the SSO idiom in a handler: a fresh string sets byte 23 to 23 (remaining
    capacity); the top bit of that byte marks the heap form (tested with SHR AL,7)."""
    fn = session.fn(p["in"])
    body = session.model.insns(fn)
    init = next((i for i in body if i.mnem == "MOV" and len(i.ops) == 2 and i.ops[0].kind == "mem" and i.ops[0].size == 1
                 and i.ops[1].kind == "imm" and i.ops[1].imm == 0x17), None)
    if init is None:
        return {}
    shr = next((i for i in body if i.mnem == "SHR" and len(i.ops) == 2 and i.ops[1].kind == "imm" and i.ops[1].imm == 7), None)
    out = {"container.string.ssoCapacity": (0x17, _ev(session, init.rva, fn, "imm"), "MOV byte [str+23], 23"),
           "container.string.size": (24, _ev(session, init.rva, fn, "imm"), "ssoCapacity + 1"),
           "container.string.heapFlagByte": (23, _ev(session, init.rva, fn, "imm"), "same byte")}
    if shr is not None:
        out["container.string.heapFlagMask"] = (0x80, _ev(session, shr.rva, fn, "imm"), "SHR AL,7 tests the top bit")
    return out


def shared_layouts(session, p):
    """Container contracts observed in the lookup functions: jag::shared_ptr {ctrl, ptr}, the hash_map header
    (buckets, bucketCount relative to the map), and the eastl::list node value slot in the headbar walk."""
    from .strategies import recognize_hash_maps
    out = {}
    lookup = session.fn("@NpcLookup")
    facts = session.prov.facts(lookup)
    maps = [m for m in recognize_hash_maps(facts, session.model) if isinstance(m.base, Deref)]
    if maps:
        m = maps[0]
        out["container.hash_map.buckets"] = (8, _ev(session, m.rva, m.fn), f"buckets at map+8 (map = buckets-8 by contract), count at +{m.count - m.buckets + 8:#x}")
        out["container.hash_map.bucketCount"] = (m.count - m.buckets + 8, _ev(session, m.rva, m.fn), "relative to the map")
    # shared_ptr: the object pointer read at +8 of a lookup result; the refcount helper gets ctrl+8.
    handler = session.fn("ClientOp.npcCoord")
    hf = session.prov.facts(handler)
    for l in hf.loads:
        if isinstance(l.value.base, (Ret, Out)) and l.value.off == 8 and l.value.size == 8:
            out["container.shared_ptr.ptr"] = (8, _ev(session, l.rva, handler), "object pointer of a lookup result")
            break
    for c in hf.calls:
        if c.args and isinstance(c.args[0], Add) and isinstance(c.args[0].base, Deref) and isinstance(c.args[0].base.base, (Ret, Out)) \
                and c.args[0].base.off == 0 and c.args[0].off in (8, 0xC):
            out.setdefault("container.shared_ptr.ctrl.useCount", (c.args[0].off, _ev(session, c.rva, handler), "ctrl+8 is the use count"))
            break
    # eastl::list node: isHealthbarRendered walks [node + X] -> element
    try:
        hb = session.fn("osrs.Entity.isHealthbarRendered")
        for d, fact in session.prov.facts(hb).derefs():
            if isinstance(d.base, Deref) and d.base.size == 8 and d.off >= 0x10 and d.size == 8 and isinstance(d.base.base, Deref):
                out["container.list.node.value"] = (d.off, _ev(session, fact.rva, hb), "element pointer inside a list node")
                break
    except Blocked:
        pass
    return out


def loc_tag_kind(session, p):
    """The loc tag kind: a decoder the tile game-object lookup calls, which returns tag >> n and is compared with 2
    (a loc). Its shift n is the one passed to the bit-extract helper, next to the tag id decoder's shift."""
    fn = session.fn("@TileGameObjectLookup")
    for call in session.prov.facts(fn).calls:
        if call.target is None:
            continue
        for cmp in session.prov.facts(call.target).cmps:
            for side, other in ((cmp.a, cmp.b), (cmp.b, cmp.a)):
                if not (isinstance(side, Ret) and side.target is not None and other == Const(2)):
                    continue
                for c in session.prov.facts(side.target).calls:
                    shifts = [a.n for a in c.args[1:] if isinstance(a, Const) and 8 <= a.n <= 40]
                    if shifts:
                        return {"Loc.tagKindShift": (shifts[0], _ev(session, c.rva, side.target, "imm"),
                                                     f"kind = tag >> n, compared with 2 in {call.target:#x}")}
    return {}


def _const_product(v):
    """Product of the constant factors in a chain of Mul nodes: ((x - o) * 3) * 8 gives 24."""
    total = 1
    while isinstance(v, Mul):
        if isinstance(v.factor, Const):
            total *= v.factor.n
            v = v.base
        elif isinstance(v.base, Const):
            total *= v.base.n
            v = v.factor
        else:
            break
    return total


def collision_record_fields(facts):
    """What a walk or line-of-sight core reads from its CollisionMapRecord (arg0): the origin subtracted from the x and z
    coordinates (args 1 and 2), the sizes the rebased coordinates (args 3 and 4) are compared with, and the flag columns
    indexed as [flags + (x - originX) * columnStride + columnData] + (z - originZ) * 4."""
    rec = Arg(0)
    origin, bound = {}, {}
    for v, fact in facts.values():
        for sub in walk(v):
            if isinstance(sub, Bin) and sub.op == "-" and sub.a in (Arg(1), Arg(2)) and isinstance(sub.b, Deref) \
                    and sub.b.base == rec and sub.b.index is None and sub.b.size in (0, 4):
                origin.setdefault(sub.a.i, (sub.b.off, fact.rva))
    for c in facts.cmps:
        for side, other in ((c.a, c.b), (c.b, c.a)):
            if isinstance(side, Deref) and side.base == rec and side.index is None and side.size in (0, 4):
                for i in (3, 4):
                    if contains(other, Arg(i)):
                        bound.setdefault(i, (side.off, c.rva))
    out = {}
    if 1 in origin:
        out["CollisionMapRecord.originX"] = origin[1]
    if 2 in origin:
        out["CollisionMapRecord.originZ"] = origin[2]
    if 3 in bound:
        out["CollisionMapRecord.sizeX"] = bound[3]
    if 4 in bound:
        out["CollisionMapRecord.sizeZ"] = bound[4]
    for d, fact in facts.derefs():
        if d.index is None:
            continue
        # The column pointer is a load of [flags + x * stride + columnData]; flags can sit on either side of the sum.
        for flat, term, scale in ((d.base, d.index, d.scale), (d.index, d.base, 1)):
            if isinstance(flat, Deref) and flat.base == rec and flat.index is None and flat.size in (0, 8) \
                    and contains(term, Arg(1)):
                out.setdefault("CollisionMapRecord.flags", (flat.off, fact.rva))
                out.setdefault("CollisionMapRecord.columnData", (d.off, fact.rva))
                out.setdefault("CollisionMapRecord.columnStride", (_const_product(term) * scale, fact.rva))
    return out


def collision_record(session, p):
    """Fields of CollisionMapRecord read by the walk core (CollisionWalkCore, the callee of the lineOfWalk handler)."""
    fn = session.fn("@CollisionWalkCore")
    return {name: (value, _ev(session, rva, fn), "read from the record by the walk core")
            for name, (value, rva) in collision_record_fields(session.prov.facts(fn)).items()}


def renderer_layout(session, p):
    """The renderer keeps its viewport (the projection) at +V and a holder object at +H whose vtable slot returns the
    viewport. H is the offset WorldToScreen passes to ProjectionCore; the renderer constructor passes both [renderer + H]
    (to the holder helper) and [renderer + V] (to the viewport initialiser), so V is the other argument of that call."""
    wts = session.fn("@WorldToScreen")
    proj = session.fn("@ProjectionCore")
    hold = next((c for c in session.prov.facts(wts).calls
                 if c.target == proj and len(c.args) > 1 and isinstance(c.args[1], Add)), None)
    if hold is None:
        return {}
    holder = hold.args[1].off
    for fn in sorted({f for _, f in session.model.insns_with_disp(holder) if f is not None}):
        for c in session.prov.facts(fn).calls:
            if len(c.args) < 2:
                continue
            a, b = c.args[0], c.args[1]
            if isinstance(a, Add) and a.off == holder and isinstance(b, Add) and b.base == a.base and b.off != holder:
                ev = _ev(session, c.rva, fn)
                return {"Renderer.holder": (holder, ev, "holder offset WorldToScreen passes to ProjectionCore"),
                        "Renderer.viewport": (b.off, ev, "viewport beside the holder in the renderer constructor"),
                        "RendererCtor": (fn, _ev(session, fn, fn, "rip"), "renderer constructor")}
    return {}


EXTRACTORS = {
    "loc_tag_kind": loc_tag_kind, "collision_record": collision_record, "renderer_layout": renderer_layout,
    "config_loader_core": config_loader_core, "config_archive": config_archive, "packet_layout": packet_layout,
    "player_name_pair": player_name_pair, "set_sequence": set_sequence, "actor_ctor": actor_ctor,
    "scene_grid": scene_grid, "tile_game_objects": tile_game_objects, "obj_pile_grid": obj_pile_grid,
    "operator_new": operator_new, "alloc_sizes": alloc_sizes, "string_sso": string_sso, "shared_layouts": shared_layouts,
}
