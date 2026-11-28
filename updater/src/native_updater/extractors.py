"""Custom extractors: recipes that need more than a generic selector. Each returns
{field: (value, evidence[], note)}; the spec picks fields with `extractor: {name, field}`.

Every extractor is written against provenance facts (what is loaded from where), not against raw bytes,
so it survives register reallocation. Results are cached per run.
"""
import math
import struct

from .analysis.provenance import Add, Arg, Bin, Const, Deref, Global, Mul, Ret, Out, StackAddr, contains, walk
from .events import event_raisers
from .frames import render_frames
from .highlights import highlight_config_fields, highlight_script_ops
from .hooks import obj_pile_ops, player_lifecycle
from .minimenu import minimenu
from .menuedits import menu_edit_points
from .keyboard import component_listeners, render_view_wndproc
from .components import cs2_click_mask, cs2_components, cs2_set_text
from .worlds import cs2_world
from .playerops import player_ops
from .visibility import renderable_hidden
from .menuops import menu_ops, walk_destination
from .dialogs import dialog_packets
from .clicks import mouse_click_v2
from .scripts import script_runner
from .interfaces import sub_interfaces
from .chat import chat_history
from .hitmarks import actor_hitmarks
from .overhead import overhead_countdown
from .spotanims import actor_spot_anims
from .varcs import varc_setters
from .session import Blocked

_cache = {}


def run(session, name, params):
    key = (id(session), name, tuple(sorted((k, str(v)) for k, v in params.items() if k not in ("field", "cls"))))
    if key not in _cache:
        _cache[key] = EXTRACTORS[name](session, params)
    return _cache[key]


def _ev(session, rva, fn=None, slot="disp"):
    return [session.evidence(rva, fn, slot)]


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


SLOPE_SCALE = 2 * math.pi / 256    # the step turns a projectile's slope into radians with this constant (0.02454369)


def projectiles(session, p):
    """Client.projectiles and the Projectile layout, from the code that keeps the list moving.

      walker(list, client)  some function is handed client + Client.projectiles. For each node's projectile
                            P = [node + list.node.value] the walker drops P once global.cycle passes one field (the end
                            cycle), and calls the step on P once global.cycle reaches the other (the start cycle). It
                            hands one more field of P to the renderer: the yaw.
      step(P, out, ...)     compares its cycle argument with the start cycle; see projectile_step_fields.
      endpoint(out, ...)    the step's callee that calls WorldBounds; see projectile_endpoint_roles.
      ctor(P, params)       the SpotAnim loader's caller that clears the step's started flag passes it [P + spotAnimId].
    """
    model = session.model
    lst = session.value("Client.projectiles")
    proj = Deref(Deref(Arg(0), session.value("container.list.node.next"), 8), session.value("container.list.node.value"), 8)
    cycle = Deref(Global(session.value("global.cycle")), 0, 4)
    bounds = session.fn("@WorldBounds")
    loader = session.fn("@ConfigLoader.13")
    # Immediates first: the walker's caller adds the list offset to the client in a small thunk.
    hits = [fn for _, fn in model.insns_with_imm(lst)] + [fn for _, fn in model.insns_with_disp(lst)]
    for fn in dict.fromkeys(f for f in hits if f is not None):
        for c in session.prov.facts(fn).calls:
            if c.target is None or not c.args or not any(isinstance(m, Add) and m.off == lst for m in c.args[0].members()):
                continue
            out = _projectile_layout(session, c.target, proj, cycle, bounds, loader)
            if out:
                return out
    return {}


def _projectile_layout(session, walker, proj, cycle, bounds, loader):
    model = session.model
    wf = session.prov.facts(walker)
    cycles, step, drawn = projectile_walker_fields(wf, proj, cycle)
    if step is None:
        return {}
    fn = step.target
    sf = session.prov.facts(fn)
    ends = sorted({c.target for c in sf.calls if c.target is not None and len(c.args) >= 5 and bounds in model.callees(c.target)})
    if len(ends) != 1:
        return {}
    roles = projectile_endpoint_roles(session.prov.facts(ends[0]))
    if roles is None:
        return {}
    scale = {l.value.base.rva for l in sf.loads if isinstance(l.value.base, Global) and l.value.off == 0 and l.value.size == 8
             and _f64_near(model, l.value.base.rva, SLOPE_SCALE)}
    fields = projectile_step_fields(sf, cycles, ends[0], roles, scale)
    if "Projectile.startCycle" not in fields:
        return {}
    notes = {
        "Projectile.startCycle": "compared with the cycle by the walker and with the step's cycle argument",
        "Projectile.endCycle": "the walker drops the projectile once the cycle passes it",
        "Projectile.started": "byte the step compares with 0 and sets to 1",
        "Projectile.endHeight": "subtracted from the target endpoint's height",
        "Projectile.startHeight": "subtracted from the source endpoint's height",
        "Projectile.slope": "multiplied by 2pi/256 for the initial climb",
        "Projectile.startDistance": "how far along the path from the source the projectile starts",
        "Projectile.fineX": "position double, returned truncated in out[0]",
        "Projectile.fineY": "height double, returned truncated in out[1]",
        "Projectile.fineZ": "position double, returned truncated in out[2]",
        "Projectile.pitch": "the angle the step stores that the walker doesn't draw with",
    }
    out = {}
    for name, (value, rva) in fields.items():
        where = walker if name == "Projectile.endCycle" else fn
        out[name] = (value, _ev(session, rva, where), notes.get(name, "argument of the endpoint lookup"))
    out["ProjectileListUpdate"] = (walker, _ev(session, walker, walker, "rip"), "walks Client.projectiles against the cycle")
    out["ProjectileUpdate"] = (fn, _ev(session, step.rva, walker, "rip"), "called with each projectile whose start cycle came")
    out["ProjectileEndpoint"] = (ends[0], _ev(session, ends[0], ends[0], "rip"), "the step's callee that calls WorldBounds")
    angles = {v: rva for k, (v, rva) in fields.items() if k in ("Projectile.yaw", "Projectile.pitch")}
    yaw = sorted(set(angles) & drawn)
    if len(yaw) == 1 and len(angles) == 2:
        rva = next(l.rva for l in wf.loads if _is_field(l.value, proj, 4) and l.value.off == yaw[0])
        pitch = next(off for off in angles if off != yaw[0])
        out["Projectile.yaw"] = (yaw[0], _ev(session, rva, walker), "the angle the walker hands to the renderer")
        out["Projectile.pitch"] = (pitch, _ev(session, angles[pitch], fn), notes["Projectile.pitch"])
    else:
        out.pop("Projectile.yaw", None)
        out.pop("Projectile.pitch", None)
    started = fields.get("Projectile.started")
    ctor = _spot_anim_ctor(session, loader, started[0]) if started is not None else None
    if ctor is not None:
        caller, spot, rva = ctor
        out["ProjectileCtor"] = (caller, _ev(session, caller, caller, "rip"), "SpotAnim loader caller clearing the started flag")
        out["Projectile.spotAnimId"] = (spot, _ev(session, rva, caller), "id passed to the SpotAnim loader")
    return out


def _spot_anim_ctor(session, loader, started):
    """(ctor, spot anim id offset, rva) for the SpotAnim loader caller that clears the started byte [this + started] and
    passes the loader a 4-byte field of this (the id); None if no caller does."""
    model = session.model
    for caller in model.callers(loader):
        cf = session.prov.facts(caller)
        if not any(_is_field(s.target, Arg(0), 1) and s.target.off == started and s.value == Const(0) for s in cf.stores):
            continue
        for c in cf.calls:
            if c.target is not None and model.resolve(c.target) == loader and len(c.args) > 1 and _is_field(c.args[1], Arg(0), 4):
                return caller, c.args[1].off, c.rva
    return None


def _f64_near(model, rva, want):
    try:
        return abs(struct.unpack("<d", model.pe.read(rva, 8))[0] - want) < 1e-6
    except (AttributeError, struct.error, TypeError, ValueError):
        return False


def projectile_walker_fields(facts, proj, cycle):
    """What the list walker does with each projectile `proj`: the two fields it compares with the cycle global
    ({offset: rva}), the call that passes the projectile on (the step), and the other 4-byte fields it reads (the angle
    it draws with). (cycles, None, drawn) when it isn't the walker."""
    cycles = {}
    for c in facts.cmps:
        for a, b in ((c.a, c.b), (c.b, c.a)):
            if a == cycle and _is_field(b, proj, 4):
                cycles.setdefault(b.off, c.rva)
    step = next((c for c in facts.calls if c.target is not None and c.args and proj in c.args[0].members()), None)
    drawn = {l.value.off for l in facts.loads if _is_field(l.value, proj, 4)} - set(cycles)
    return cycles, (step if len(cycles) == 2 else None), drawn


def projectile_endpoint_roles(facts):
    """Which argument of the endpoint lookup endpoint(out, ...) is which: x and z are the arguments it rebases into
    out[0] and out[2] as (coordinate - base) * 128 + 64, the actor reference is the one it decrements into an NPC index,
    and the level is the remaining one, passed on to the height lookup. {"X", "Z", "Actor", "Level"} -> argument index,
    or None."""
    roles = {}
    for s in facts.stores:
        if _is_field(s.target, Arg(0), 4) and s.target.off in (0, 8) and contains(s.value, Const(0x80)):
            args = {a.i for a in walk(s.value) if isinstance(a, Arg) and 1 <= a.i <= 4}
            if len(args) == 1:
                roles.setdefault("X" if s.target.off == 0 else "Z", args.pop())
    for c in facts.calls:
        for a in c.args:
            if isinstance(a, Add) and isinstance(a.base, Arg) and 1 <= a.base.i <= 4 and a.off == -1:
                roles.setdefault("Actor", a.base.i)
    rest = {1, 2, 3, 4} - set(roles.values())
    if len(roles) != 3 or len(rest) != 1:
        return None
    level = rest.pop()
    if not any(Arg(level) in c.args for c in facts.calls):
        return None
    roles["Level"] = level
    return roles


def projectile_step_fields(facts, cycles, endpoint, roles, scale):
    """The fields the per-cycle step(P, out, ...) reads and writes, as {key: (offset, rva)}.

      start cycle   the walker's cycle field the step compares with one of its arguments; the other is the end cycle
      started       a byte compared with 0 and set to 1. The target endpoint is looked up before that test (every
                    cycle), the source endpoint after it (only on the first): each lookup passes the endpoint's actor,
                    x, z and level fields (`roles` says which argument is which)
      heights       after each lookup, the next field loaded is subtracted from the height it found: the end height
                    (target) and the start height (source)
      slope         the field loaded into a float register right before a load of a constant in `scale` (2pi/256)
      startDistance the other field loaded into a float register
      position      the step returns its position truncated to ints in out[0], out[1], out[2]: each comes from the
                    last load, before that store, of a double the step also writes (fineX, fineY = height, fineZ)
      angles        the two 4-byte fields the step stores; yaw and pitch are told apart by the walker
    """
    this = Arg(0)
    out = {}
    start = next(((a.off, c.rva) for c in facts.cmps for a, b in ((c.a, c.b), (c.b, c.a))
                  if _is_field(a, this, 4) and a.off in cycles and isinstance(b, Arg) and b.i > 0), None)
    if start is None:
        return out
    out["Projectile.startCycle"] = start
    end = next(off for off in cycles if off != start[0])
    out["Projectile.endCycle"] = (end, cycles[end])
    claimed = set(cycles)

    flags = {s.target.off for s in facts.stores if _is_field(s.target, this, 1) and s.value == Const(1)}
    tests = sorted((c.rva, a.off) for c in facts.cmps for a, b in ((c.a, c.b), (c.b, c.a))
                   if _is_field(a, this, 1) and a.off in flags and b == Const(0))
    if not tests:
        return out
    test_rva, started = tests[0]
    out["Projectile.started"] = (started, test_rva)

    lookups = sorted((c for c in facts.calls if c.target == endpoint), key=lambda c: c.rva)
    before = [c for c in lookups if c.rva < test_rva]
    after = [c for c in lookups if c.rva > test_rva]
    if len(before) != 1 or len(after) != 1:
        return out
    for side, call in (("target", before[0]), ("source", after[0])):
        for role, i in roles.items():
            a = call.args[i] if i < len(call.args) else None
            if not _is_field(a, this, 4):
                return out
            out[f"Projectile.{side}{role}"] = (a.off, call.rva)
            claimed.add(a.off)

    loads = sorted((l for l in facts.loads if _is_field(l.value, this, 4)), key=lambda l: l.rva)
    for name, call in (("endHeight", before[0]), ("startHeight", after[0])):
        h = next((l for l in loads if l.rva > call.rva and l.value.off not in claimed), None)
        if h is not None:
            out[f"Projectile.{name}"] = (h.value.off, h.rva)
            claimed.add(h.value.off)

    floats = [l for l in loads if l.dest and l.dest.startswith("XMM") and l.value.off not in claimed]
    consts = sorted(l.rva for l in facts.loads if isinstance(l.value.base, Global) and l.value.base.rva in scale)
    slope = [l for l in floats if consts and l.rva < consts[0]]
    if slope:
        out["Projectile.slope"] = (slope[-1].value.off, slope[-1].rva)
        claimed.add(slope[-1].value.off)
        distance = {l.value.off for l in floats} - claimed
        if len(distance) == 1:
            off = distance.pop()
            out["Projectile.startDistance"] = (off, next(l.rva for l in floats if l.value.off == off))
            claimed.add(off)

    doubles = {s.target.off for s in facts.stores if _is_field(s.target, this, 8) and not isinstance(s.value, Const)}
    dloads = sorted((l for l in facts.loads if _is_field(l.value, this, 8) and l.value.off in doubles), key=lambda l: l.rva)
    for k, name in ((0, "fineX"), (4, "fineY"), (8, "fineZ")):
        st = [s for s in facts.stores if _is_field(s.target, Arg(1), 4) and s.target.off == k and not isinstance(s.value, Const)]
        prev = [l for l in dloads if st and l.rva < st[-1].rva]
        if prev:
            out[f"Projectile.{name}"] = (prev[-1].value.off, st[-1].rva)

    angles = {}
    for s in sorted(facts.stores, key=lambda s: s.rva):
        if _is_field(s.target, this, 4) and s.target.off not in claimed:
            angles.setdefault(s.target.off, s.rva)
    if len(angles) == 2:
        out["Projectile.yaw"], out["Projectile.pitch"] = sorted(angles.items())
    return out


def keyed_projectiles(session, p):
    """Client.keyedProjectiles and the KeyedProjectile layout: the projectiles the server sends with a key, so it can
    retarget one in flight. The client keeps them in an eastl::hash_map from the key to a jag::shared_ptr, and moves
    each along a curve from its source to its target.

      walk(client)       for each node's value P of the map at client + M, compares global.cycle with two fields of P
                         (the start and end cycles), erases through client + M, steps P with step(P, out, ...) and
                         hands [P + yaw] on to the draw
      step(P, out, ...)  calls init(P, ...) while the byte [P + started] is 0; looks the target up with
                         endpoint(P, out, [P + targetActor], coord, ...), coord being a stack copy of the 12 bytes at
                         P + targetCoord; takes [P + startCycle] from its cycle argument and from [P + endCycle] (how
                         far along the path it is); stores yaw and pitch; returns its position truncated in out[0..2]
      init(P, ...)       looks the source up the same way, from P + sourceCoord and [P + sourceActor], then sets started
      endpoint(...)      hands coord.x and coord.z to WorldBounds; the coord's remaining slot is the level
      offsets            right after each lookup, its caller loads three consecutive 4-byte fields into float
                         registers: the endpoint's {x, height, z} offset (the height is subtracted, so larger is higher)
      ctor               the SpotAnim loader caller that clears the started flag passes it [P + spotAnimId]
    """
    model, prov = session.model, session.prov
    cycle_rva = session.value("global.cycle")
    cycle = Deref(Global(cycle_rva), 0, 4)
    layout = (session.value("container.hash_map.buckets"), session.value("container.hash_map.bucketCount"),
              session.value("container.hash_map.node.value"))
    bounds, loader = session.fn("@WorldBounds"), session.fn("@ConfigLoader.13")
    matches = []
    for fn in sorted({fn for _, fn in model.insns_with_disp(cycle_rva) if fn is not None}):
        walk = keyed_projectile_walk_fields(prov.facts(fn), cycle, *layout)
        out = _keyed_projectile_layout(session, fn, walk, bounds, loader) if walk else None
        if out:
            matches.append(out)
    return matches[0] if len(matches) == 1 else {}


def keyed_projectile_walk_fields(facts, cycle, buckets, count, value):
    """What the per-frame walk does with the keyed projectile map: {"map": (offset, rva), "cycles": {offset: rva},
    "step": the call stepping each projectile, "drawn": the other 4-byte fields it reads}, or None when the function
    isn't the walk. The map is the client field the walk erases through and whose bucket array and count it reads; a
    projectile is a node's value, two of whose fields are compared with the cycle."""
    loaded = {l.value.off for l in facts.loads if _is_field(l.value, Arg(0), 8)}
    maps = {(c.args[0].off, c.rva) for c in facts.calls if c.args and isinstance(c.args[0], Add) and c.args[0].base == Arg(0)
            and c.args[0].off + buckets in loaded and c.args[0].off + count in loaded}
    if len({off for off, _ in maps}) != 1:
        return None
    obj, cycles = None, {}
    for c in sorted(facts.cmps, key=lambda c: c.rva):
        for a, b in ((c.a, c.b), (c.b, c.a)):
            if a == cycle and isinstance(b, Deref) and b.index is None and b.size in (0, 4) and isinstance(b.base, Deref) \
                    and b.base.off == value and b.base.index is None and b.base.size in (0, 8) and obj in (None, b.base):
                obj = b.base
                cycles.setdefault(b.off, c.rva)
    if len(cycles) != 2:
        return None
    step = next((c for c in sorted(facts.calls, key=lambda c: c.rva) if c.target is not None and c.args and c.args[0] == obj), None)
    if step is None:
        return None
    drawn = {l.value.off for l in facts.loads if _is_field(l.value, obj, 4)} - set(cycles)
    return {"map": min(maps), "cycles": cycles, "step": step, "drawn": drawn}


def _keyed_projectile_layout(session, walker, walk, bounds, loader):
    model, prov = session.model, session.prov
    fn = walk["step"].target
    sf = prov.facts(fn)
    fields = keyed_projectile_step_fields(sf, walk["cycles"])
    if "KeyedProjectile.started" not in fields:
        return None
    started = fields["KeyedProjectile.started"][0]
    init = next((c.target for c in sf.calls if c.target is not None and c.args and c.args[0] == Arg(0) and any(
        _is_field(s.target, Arg(0), 1) and s.target.off == started and s.value == Const(1) for s in prov.facts(c.target).stores)), None)
    if init is None:
        return None
    inf = prov.facts(init)
    ends = sorted({c.target for c in sf.calls if c.target is not None and bounds in model.callees(c.target)}
                  & {c.target for c in inf.calls if c.target is not None})
    if len(ends) != 1:
        return None
    target, source = keyed_projectile_lookup(sf, ends[0]), keyed_projectile_lookup(inf, ends[0])
    if target is None or source is None or target["coordArg"] != source["coordArg"]:
        return None
    roles = keyed_projectile_coord_roles(prov.facts(ends[0]), bounds, target["coordArg"])
    if roles is None:
        return None
    notes = {
        "KeyedProjectile.startCycle": "taken from the step's cycle argument: how long the projectile has been flying",
        "KeyedProjectile.endCycle": "the start cycle is taken from it: how long the flight lasts",
        "KeyedProjectile.started": "byte the step compares with 0 before calling the init, which sets it to 1",
        "KeyedProjectile.fineX": "position float, returned truncated in out[0]",
        "KeyedProjectile.fineY": "height float, returned truncated in out[1]",
        "KeyedProjectile.fineZ": "position float, returned truncated in out[2]",
        "KeyedProjectile.yaw": "the angle the walk hands on to the draw",
        "KeyedProjectile.pitch": "the angle the step stores that the walk doesn't draw with",
    }
    fields = {k: (*v, fn) for k, v in fields.items() if k != "angles"} | {"angles": fields.get("angles", {})}
    for side, found, where, height in (("source", source, init, "startHeight"), ("target", target, fn, "endHeight")):
        lookup = f"the {side} lookup ({'the init' if side == 'source' else 'the step'})"
        for role, k in roles.items():
            fields[f"KeyedProjectile.{side}{role}"] = (found["coord"] + k, found["rva"], where)
            notes[f"KeyedProjectile.{side}{role}"] = f"{role.lower()} slot of the coordinate {lookup} copies"
        fields[f"KeyedProjectile.{side}Actor"] = (found["actor"], found["rva"], where)
        notes[f"KeyedProjectile.{side}Actor"] = f"the actor reference {lookup} passes"
        for name, k in ((f"{side}OffsetX", 0), (height, 4), (f"{side}OffsetZ", 8)):
            fields[f"KeyedProjectile.{name}"] = (found["offset"] + k, found["offsetRva"], where)
            notes[f"KeyedProjectile.{name}"] = f"slot {k // 4} of the x, height, z offset loaded after {lookup}"
    angles = fields.pop("angles")
    yaw = sorted(set(angles) & walk["drawn"])
    if len(yaw) == 1 and len(angles) == 2:
        pitch = next(off for off in angles if off != yaw[0])
        fields["KeyedProjectile.yaw"] = (yaw[0], angles[yaw[0]], fn)
        fields["KeyedProjectile.pitch"] = (pitch, angles[pitch], fn)
    out = {name: (value, _ev(session, rva, where), notes[name]) for name, (value, rva, where) in fields.items()}
    off, rva = walk["map"]
    out["Client.keyedProjectiles"] = (off, _ev(session, rva, walker), "the map the walk erases through, whose buckets it reads")
    out["KeyedProjectileWalk"] = (walker, _ev(session, walker, walker, "rip"), "walks Client.keyedProjectiles against the cycle")
    out["KeyedProjectileStep"] = (fn, _ev(session, walk["step"].rva, walker, "rip"), "called with each projectile whose start cycle came")
    out["KeyedProjectileInit"] = (init, _ev(session, init, init, "rip"), "called by the step while the started flag is clear")
    out["KeyedProjectileEndpoint"] = (ends[0], _ev(session, ends[0], ends[0], "rip"), "the step's and the init's callee that calls WorldBounds")
    ctor = _spot_anim_ctor(session, loader, started)
    if ctor is not None:
        caller, spot, at = ctor
        out["KeyedProjectileCtor"] = (caller, _ev(session, caller, caller, "rip"), "SpotAnim loader caller clearing the started flag")
        out["KeyedProjectile.spotAnimId"] = (spot, _ev(session, at, caller), "id passed to the SpotAnim loader")
    return out


def keyed_projectile_step_fields(facts, cycles):
    """The fields the keyed projectile step(P, out, ...) reads and writes, as {key: (offset, rva)} plus "angles":
    {offset: rva} for the two 4-byte fields it stores apart from its position (yaw and pitch, told apart by the walk).

      start cycle   the walk's cycle field the step subtracts from one of its arguments; the end cycle is the other,
                    from which the step subtracts the start
      started       a byte compared with 0
      position      the step returns its position truncated to ints in out[0], out[1], out[2]: each comes from the last
                    load, before that store, of a 4-byte field the step also writes (fineX, fineY = height, fineZ)
    """
    this, out = Arg(0), {}
    for a in facts.addrs:
        v = a.value
        if isinstance(v, Bin) and v.op == "-" and _is_field(v.b, this, 4) and v.b.off in cycles:
            if isinstance(v.a, Arg) and v.a.i > 0:
                out.setdefault("KeyedProjectile.startCycle", (v.b.off, a.rva))
            elif _is_field(v.a, this, 4) and v.a.off in cycles and v.a.off != v.b.off:
                out.setdefault("KeyedProjectile.endCycle", (v.a.off, a.rva))
    if set(out) != {"KeyedProjectile.startCycle", "KeyedProjectile.endCycle"} \
            or out["KeyedProjectile.startCycle"][0] == out["KeyedProjectile.endCycle"][0]:
        return {}
    tests = sorted((c.rva, a.off) for c in facts.cmps for a, b in ((c.a, c.b), (c.b, c.a))
                   if _is_field(a, this, 1) and a.size == 1 and b == Const(0))
    if tests:
        out["KeyedProjectile.started"] = (tests[0][1], tests[0][0])
    written = {s.target.off: s.rva for s in sorted(facts.stores, key=lambda s: s.rva, reverse=True)
               if _is_field(s.target, this, 4) and not isinstance(s.value, Const)}
    loads = sorted((l for l in facts.loads if _is_field(l.value, this, 4) and l.value.off in written), key=lambda l: l.rva)
    position = set()
    for k, name in ((0, "fineX"), (4, "fineY"), (8, "fineZ")):
        st = [s for s in facts.stores if _is_field(s.target, Arg(1), 4) and s.target.off == k]
        prev = [l for l in loads if st and l.rva < max(s.rva for s in st)]
        if prev:
            out[f"KeyedProjectile.{name}"] = (prev[-1].value.off, max(s.rva for s in st))
            position.add(prev[-1].value.off)
    out["angles"] = {off: rva for off, rva in written.items() if off not in position and off not in cycles}
    return out


def keyed_projectile_lookup(facts, endpoint):
    """One endpoint lookup endpoint(this, out, actor, &coord, ...) in a function of this: {"coord": the 12-byte field
    copied to the stack coord, "coordArg": the argument it is passed as, "actor": the 4-byte field passed as the actor,
    "offset": the first of the three consecutive 4-byte fields loaded into float registers next, "rva", "offsetRva"},
    or None."""
    this = Arg(0)
    for c in sorted(facts.calls, key=lambda c: c.rva):
        state = facts.states.get(c.rva)
        if c.target != endpoint or state is None:
            continue
        coord = actor = None
        for i, a in enumerate(c.args):
            first = state.stack.get(a.off) if isinstance(a, StackAddr) else None
            if _is_field(first, this, 8) and state.stack.get(a.off + 8) == Deref(this, first.off + 8, 4):
                coord = (first.off, i)
            elif _is_field(a, this, 4) and a.size == 4:
                actor = a.off
        if coord is None or actor is None:
            continue
        xmm = []
        for l in sorted(facts.loads, key=lambda l: l.rva):
            if l.rva > c.rva and l.dest and l.dest.startswith("XMM") and _is_field(l.value, this, 4) and l.value.off not in xmm:
                xmm.append(l.value.off)
                if len(xmm) == 3:
                    break
        if len(xmm) != 3 or sorted(xmm) != [min(xmm), min(xmm) + 4, min(xmm) + 8]:
            continue
        rva = next(l.rva for l in facts.loads if l.rva > c.rva and _is_field(l.value, this, 4) and l.value.off == min(xmm))
        return {"coord": coord[0], "coordArg": coord[1], "actor": actor, "offset": min(xmm), "rva": c.rva, "offsetRva": rva}
    return None


def keyed_projectile_coord_roles(facts, bounds, coord_arg):
    """Which slot of the coordinate the endpoint lookup gets (argument coord_arg) is which: x and z are the slots it
    hands to WorldBounds as its second and third arguments, the level the one other slot it reads.
    {"Level", "X", "Z"} -> offset in the coordinate, or None."""
    coord = Arg(coord_arg)
    for c in facts.calls:
        if c.target != bounds or len(c.args) < 3 or not (_is_field(c.args[1], coord, 4) and _is_field(c.args[2], coord, 4)):
            continue
        x, z = c.args[1].off, c.args[2].off
        rest = {l.value.off for l in facts.loads if _is_field(l.value, coord, 4)} - {x, z}
        if len(rest) == 1 and x != z:
            return {"Level": rest.pop(), "X": x, "Z": z}
    return None


def graphics_objects(session, p):
    """World.graphicsObjects and where a graphics object (the client's MapSpotAnim: a spot anim the server plays on a
    tile) keeps its level, position, height, start cycle and animation, from the code that plays them each frame.

      update(ctx, sp)    the predicate a world's list is filtered with every frame: ctx holds the client and the world W,
                         sp is a node's shared_ptr and P = [sp + shared_ptr.ptr]. It drops P when [P + level] isn't
                         [W + World.plane] or when the 8-byte [P + animSeqType] is 0 (its animation ended), waits until
                         global.cycle reaches [P + startCycle], then steps P's animation (the step tests animSeqType
                         too) and hands [W + World.scene], level, fineX, fineZ and height to the scene's draw call.
      filter(list, ctx)  calls the update with each node of the list. Its caller passes W + World.graphicsObjects, with
                         W in the stack context where the update reads it.
    """
    model = session.model
    cycle_rva = session.value("global.cycle")
    cycle = Deref(Global(cycle_rva), 0, 4)
    plane, scene = session.value("World.plane"), session.value("World.scene")
    obj = Deref(Arg(1), session.value("container.shared_ptr.ptr"), 8)
    notes = {
        "GraphicsObject.level": "compared with the world's plane; on another level the object is dropped",
        "GraphicsObject.animSeqType": "8-byte field tested against 0 before the object is stepped and after",
        "GraphicsObject.startCycle": "compared with the cycle before the object is stepped and drawn",
        "GraphicsObject.fineX": "drawn at: the argument after the level",
        "GraphicsObject.fineZ": "drawn at: the argument after fineX",
        "GraphicsObject.height": "drawn at: the argument after fineZ",
    }
    matches = []
    for fn in sorted({fn for _, fn in model.insns_with_disp(cycle_rva) if fn is not None}):
        fields = graphics_object_update_fields(session.prov.facts(fn), obj, cycle, plane, scene, session.prov.facts)
        found = _graphics_object_list(session, fn, fields.pop("world")) if fields else None
        if found is not None:
            matches.append((fn, fields, found))
    if len(matches) != 1:
        return {}
    fn, fields, (off, rva, walker) = matches[0]
    out = {name: (value, _ev(session, at, fn), notes[name]) for name, (value, at) in fields.items()}
    out["World.graphicsObjects"] = (off, _ev(session, rva, walker), "the list the update filters, at an offset of its world")
    out["GraphicsObjectUpdate"] = (fn, _ev(session, fn, fn, "rip"), "drops, steps and draws each graphics object")
    return out


def graphics_object_update_fields(facts, obj, cycle, plane, scene, facts_of):
    """What the per-frame update does with the graphics object `obj`: {key: (offset, rva)}, plus "world", the offset in
    its first argument where it finds the world; None when the function isn't the update (see graphics_objects).
    `facts_of(fn)` gives the facts of a callee (the animation step)."""
    out, world = {}, None
    for c in sorted(facts.cmps, key=lambda c: c.rva):
        for a, b in ((c.a, c.b), (c.b, c.a)):
            if "GraphicsObject.level" not in out and _is_field(a, obj, 4) and isinstance(b, Deref) and b.off == plane \
                    and b.index is None and b.size in (0, 4) and _is_field(b.base, Arg(0), 8):
                out["GraphicsObject.level"] = (a.off, c.rva)
                world = b.base
            elif "GraphicsObject.startCycle" not in out and a == cycle and _is_field(b, obj, 4):
                out["GraphicsObject.startCycle"] = (b.off, c.rva)
            elif "GraphicsObject.animSeqType" not in out and _is_field(a, obj, 8) and b == Const(0):
                out["GraphicsObject.animSeqType"] = (a.off, c.rva)
    if world is None or len(out) != 3:
        return None
    level, finished = out["GraphicsObject.level"][0], out["GraphicsObject.animSeqType"][0]
    stepped = any(c.target is not None and c.args and c.args[0] == obj and any(
        _is_field(x, Arg(0), 8) and x.off == finished and y == Const(0)
        for k in facts_of(c.target).cmps for x, y in ((k.a, k.b), (k.b, k.a))) for c in facts.calls)
    if not stepped:
        return None
    for c in facts.calls:
        if len(c.args) < 5 or c.args[0] != Deref(world, scene, 8) or c.args[1] != Deref(obj, level, 4):
            continue
        rest = c.args[2:5]
        if all(_is_field(a, obj, 4) for a in rest) and len({a.off for a in rest} | {level}) == 4:
            for name, a in zip(("fineX", "fineZ", "height"), rest):
                out[f"GraphicsObject.{name}"] = (a.off, c.rva)
            out["world"] = world.off
            return out
    return None


def _graphics_object_list(session, update, slot):
    """(offset, rva, walker) of the world's list the update filters: the update's caller (the filter) hands it one of
    its own arguments as the context; the filter's caller passes it (world + offset) and a stack context holding that
    world at `slot`, where the update reads it. None if no caller does."""
    model, prov = session.model, session.prov
    for filt in model.callers(update):
        ctx = next((c.args[0] for c in prov.facts(filt).calls if c.target == update and c.args), None)
        if not isinstance(ctx, Arg):
            continue
        for walker in model.callers(filt):
            wf = prov.facts(walker)
            for c in wf.calls:
                if c.target != filt or len(c.args) <= ctx.i:
                    continue
                lst, box, state = c.args[0], c.args[ctx.i], wf.states.get(c.rva)
                if isinstance(lst, Add) and isinstance(box, StackAddr) and state is not None \
                        and state.stack.get(box.off + slot) == lst.base:
                    return lst.off, c.rva, walker
    return None


GRAPHICS_OBJECT_VTABLE_SCAN = 8     # vtables sit back to back, so only the first few slots are the object's own
ANIM_STATE_SIZE = 0x20              # the animation block: SeqType pointer, sequence id, frame, frame cycle, loops


def graphics_object_ctor(session, p):
    """The graphics object constructor ctor(P, world, id, level, x, z, height, delay, cycle) and the model getter it
    installs.

      ctor      a SpotAnim loader caller that stores the sum of two arguments (cycle + delay, the start cycle), stores
                an argument it also passes to the loader (the spot anim id), and hands P + animState to a SeqType loader
                caller (the animation setter) with the spot anim's sequence
      model(P)  among the first slots of the vtable the ctor stores at [P], the one passing [P + spotAnimId] to the
                SpotAnim loader; it hands the spot anim the 16-bit [P + animFrame] inside the animation block (the
                frame to build the model at)
    """
    model = session.model
    loader = session.fn("@ConfigLoader.13")
    setters = set(model.callers(session.fn("@ConfigLoader.12")))
    notes = {
        "GraphicsObject.startCycle": "stored from the sum of two arguments (the cycle plus the delay)",
        "GraphicsObject.spotAnimId": "the argument stored here is passed to the SpotAnim loader",
        "GraphicsObject.animState": "animation block handed to the sequence setter",
    }
    matches = [(fn, fields) for fn in model.callers(loader)
               if (fields := graphics_object_ctor_fields(session.prov.facts(fn), loader, setters)) is not None]
    if len(matches) != 1:
        return {}
    fn, fields = matches[0]
    vtable = fields.pop("vtable", None)
    out = {name: (off, _ev(session, rva, fn), notes[name]) for name, (off, rva) in fields.items()}
    out["GraphicsObjectCtor"] = (fn, _ev(session, fn, fn, "rip"), "SpotAnim loader caller storing cycle + delay")
    anim = fields.get("GraphicsObject.animState", (None,))[0]
    for slot in range(GRAPHICS_OBJECT_VTABLE_SCAN if vtable else 0):
        target = model.pe.pointer_rva(vtable[0] + 8 * slot)
        if target is None:
            break
        getter = model.resolve(target)
        frame = graphics_object_model_frame(session.prov.facts(getter), loader, fields["GraphicsObject.spotAnimId"][0], anim)
        if frame is not None:
            out["GraphicsObjectModel"] = (getter, _ev(session, vtable[1], fn), f"slot {slot} of the vtable the ctor installs")
            out["GraphicsObject.animFrame"] = (frame[0], _ev(session, frame[1], getter), "16-bit animation frame the model is built at")
            break
    return out


def graphics_object_ctor_fields(facts, loader, setters):
    """From a SpotAnim loader caller: {key: (offset, rva)} for the start cycle (a store of the sum of two arguments),
    the spot anim id (an argument stored and passed to the loader) and the animation block (this + N handed to one of
    `setters`), plus "vtable": (rva, store rva) for a global stored at [this]. None unless it is the graphics object
    constructor, which has both of the first two."""
    this, out = Arg(0), {}
    for s in sorted(facts.stores, key=lambda s: s.rva):
        v = s.value
        if _is_field(s.target, this, 4) and isinstance(v, Bin) and v.op == "+" \
                and all(isinstance(x, Arg) and x.i > 0 for x in (v.a, v.b)):
            out.setdefault("GraphicsObject.startCycle", (s.target.off, s.rva))
        elif _is_field(s.target, this, 8) and s.target.off == 0 and isinstance(v, Global):
            out.setdefault("vtable", (v.rva, s.rva))
    stored = {s.value: s for s in facts.stores if _is_field(s.target, this, 4) and isinstance(s.value, Arg) and s.value.i > 0}
    for c in sorted(facts.calls, key=lambda c: c.rva):
        if c.target == loader and len(c.args) > 1 and c.args[1] in stored:
            out.setdefault("GraphicsObject.spotAnimId", (stored[c.args[1]].target.off, c.rva))
        elif c.target in setters and c.args and isinstance(c.args[0], Add) and c.args[0].base == this:
            out.setdefault("GraphicsObject.animState", (c.args[0].off, c.rva))
    if "GraphicsObject.startCycle" not in out or "GraphicsObject.spotAnimId" not in out:
        return None
    return out


def graphics_object_model_frame(facts, loader, spot, anim):
    """(offset, rva) of the 16-bit field of `this` the model getter hands on with the spot anim it loads by
    [this + spot], inside the animation block at `anim` when that is known; None if it isn't the getter."""
    this = Arg(0)
    if not any(c.target == loader and len(c.args) > 1 and c.args[1] == Deref(this, spot, 4) for c in facts.calls):
        return None
    for c in sorted(facts.calls, key=lambda c: c.rva):
        if c.target == loader:
            continue
        for a in c.args:
            if _is_field(a, this, 2) and a.size == 2 and (anim is None or anim < a.off < anim + ANIM_STATE_SIZE):
                return a.off, c.rva
    return None


def cycle_function(session, p):
    """The client cycle (RuneLite's client tick): the one function that increments global.cycle. A hook target."""
    cycle = session.value("global.cycle")
    sites = [(rva, fn) for rva, fn in session.model.insns_with_disp(cycle, "INC") if fn is not None]
    fns = {fn for _, fn in sites}
    if len(fns) != 1:
        return {}
    rva, fn = sites[0]
    return {"CycleFunction": (fn, _ev(session, rva, fn, "rip"), "the function incrementing global.cycle")}


def game_state_setter(session, p):
    """The game state setter: the function storing its second argument into [client + Client.gameState]. A hook target
    that sees every state change, which the Lua ON_GAME_STATE_CHANGED event does not."""
    off = session.value("Client.gameState")
    fns = {fn for _, fn in session.model.insns_with_disp(off, "MOV") if fn is not None}
    for fn in sorted(fns):
        for s in session.prov.facts(fn).stores:
            if _is_field(s.target, Arg(0), 4) and s.target.off == off and s.value == Arg(1):
                return {"GameStateSetter": (fn, _ev(session, s.rva, fn), "stores arg1 to [arg0 + Client.gameState]")}
    return {}


EXTRACTORS = {
    "event_raisers": event_raisers, "cycle_function": cycle_function, "game_state_setter": game_state_setter,
    "obj_pile_ops": obj_pile_ops, "player_lifecycle": player_lifecycle,
    "projectiles": projectiles, "minimenu": minimenu, "menu_ops": menu_ops, "walk_destination": walk_destination,
    "menu_edit_points": menu_edit_points, "render_view_wndproc": render_view_wndproc,
    "component_listeners": component_listeners, "cs2_components": cs2_components, "cs2_set_text": cs2_set_text,
    "cs2_click_mask": cs2_click_mask, "cs2_world": cs2_world, "player_ops": player_ops,
    "renderable_hidden": renderable_hidden,
    "dialog_packets": dialog_packets, "mouse_click_v2": mouse_click_v2,
    "script_runner": script_runner, "sub_interfaces": sub_interfaces, "chat_history": chat_history,
    "actor_hitmarks": actor_hitmarks, "varc_setters": varc_setters, "overhead_countdown": overhead_countdown,
    "actor_spot_anims": actor_spot_anims,
    "keyed_projectiles": keyed_projectiles,
    "graphics_objects": graphics_objects, "graphics_object_ctor": graphics_object_ctor,
    "loc_tag_kind": loc_tag_kind, "collision_record": collision_record, "renderer_layout": renderer_layout,
    "config_loader_core": config_loader_core, "config_archive": config_archive, "packet_layout": packet_layout,
    "player_name_pair": player_name_pair, "set_sequence": set_sequence, "actor_ctor": actor_ctor,
    "scene_grid": scene_grid, "tile_game_objects": tile_game_objects, "obj_pile_grid": obj_pile_grid,
    "operator_new": operator_new, "alloc_sizes": alloc_sizes, "string_sso": string_sso, "shared_layouts": shared_layouts,
    "highlight_script_ops": highlight_script_ops, "highlight_config_fields": highlight_config_fields,
    "render_frames": render_frames,
}
