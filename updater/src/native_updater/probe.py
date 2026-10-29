"""Live probe (design §4.8): opt-in, operator-run, read-only.

Safety model: the process must be osclient.exe and the file's sha256 must match the mapping, reads go through a
PssCaptureSnapshot VA clone when available (falling back to bounded ReadProcessMemory), and nothing is ever written.
It prints PASS/FAIL/SKIP per check and exits 2 if any check fails.

Checks are named by the `live:` field of the spec, so a failing check points at the keys to look at. Checks that
need the cache decoders or in-process execution (varbit recompute, worldToScreen) report "skipped".

Untested against a live client at the time of writing: run it, read the overlay, then trust it.
"""
import ctypes
import ctypes.wintypes as wt
import hashlib
import json
import struct
import time
from pathlib import Path

PROCESS_VM_READ = 0x0010
PROCESS_QUERY_INFORMATION = 0x0400
TH32CS_SNAPPROCESS, TH32CS_SNAPMODULE = 0x2, 0x8
PSS_CAPTURE_VA_CLONE = 0x00000001
PSS_QUERY_VA_CLONE_INFORMATION = 1
MAX_READ = 1 << 20


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [("dwSize", wt.DWORD), ("cntUsage", wt.DWORD), ("th32ProcessID", wt.DWORD), ("th32DefaultHeapID", ctypes.c_void_p),
                ("th32ModuleID", wt.DWORD), ("cntThreads", wt.DWORD), ("th32ParentProcessID", wt.DWORD), ("pcPriClassBase", ctypes.c_long),
                ("dwFlags", wt.DWORD), ("szExeFile", ctypes.c_wchar * 260)]


class MODULEENTRY32W(ctypes.Structure):
    _fields_ = [("dwSize", wt.DWORD), ("th32ModuleID", wt.DWORD), ("th32ProcessID", wt.DWORD), ("GlblcntUsage", wt.DWORD),
                ("ProccntUsage", wt.DWORD), ("modBaseAddr", ctypes.c_void_p), ("modBaseSize", wt.DWORD), ("hModule", wt.HMODULE),
                ("szModule", ctypes.c_wchar * 256), ("szExePath", ctypes.c_wchar * 260)]


class Reader:
    """Bounded reads from a snapshot (or the live process)."""

    def __init__(self, mapping, display_name=None):
        self.m = mapping
        self.display_name = display_name
        self.k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self.k32.ReadProcessMemory.argtypes = [wt.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
        self.k32.OpenProcess.restype = wt.HANDLE
        self.k32.CreateToolhelp32Snapshot.restype = wt.HANDLE
        self.pid = self._find_pid()
        self.base, self.size, self.path = self._module()
        self._verify_identity()
        self.proc = self.k32.OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, False, self.pid)
        if not self.proc:
            raise OSError("OpenProcess failed; run as the same user as the client")
        self.handle, self.snapshot = self._clone()
        self.cache = {}

    def _find_pid(self):
        snap = self.k32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        e = PROCESSENTRY32W(dwSize=ctypes.sizeof(PROCESSENTRY32W))
        pids = []
        ok = self.k32.Process32FirstW(snap, ctypes.byref(e))
        while ok:
            if e.szExeFile.lower() == "osclient.exe":
                pids.append(e.th32ProcessID)
            ok = self.k32.Process32NextW(snap, ctypes.byref(e))
        self.k32.CloseHandle(snap)
        if len(pids) != 1:
            raise RuntimeError(f"expected exactly one osclient.exe, found {len(pids)}")
        return pids[0]

    def _module(self):
        snap = self.k32.CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, self.pid)
        e = MODULEENTRY32W(dwSize=ctypes.sizeof(MODULEENTRY32W))
        if not self.k32.Module32FirstW(snap, ctypes.byref(e)):
            raise OSError("Module32First failed")
        base, size, path = e.modBaseAddr, e.modBaseSize, e.szExePath
        self.k32.CloseHandle(snap)
        return base, size, path

    def _verify_identity(self):
        sha = hashlib.sha256(Path(self.path).read_bytes()).hexdigest()
        if sha != self.m["sha256"]:
            raise RuntimeError(f"running client sha256 {sha[:16]} != mapping {self.m['version']} ({self.m['sha256'][:16]})")

    def _clone(self):
        """PssCaptureSnapshot VA clone: reads come from a frozen copy. Falls back to the live process."""
        try:
            snapshot = ctypes.c_void_p()
            if self.k32.PssCaptureSnapshot(self.proc, PSS_CAPTURE_VA_CLONE, 0, ctypes.byref(snapshot)) != 0:
                return self.proc, None
            clone = ctypes.c_void_p()
            if self.k32.PssQuerySnapshot(snapshot, PSS_QUERY_VA_CLONE_INFORMATION, ctypes.byref(clone), ctypes.sizeof(clone)) != 0:
                return self.proc, None
            return clone.value, snapshot
        except AttributeError:
            return self.proc, None

    def close(self):
        if self.snapshot:
            self.k32.PssFreeSnapshot(self.proc, self.snapshot)
        self.k32.CloseHandle(self.proc)

    def read(self, addr, n):
        if not addr or addr < 0x10000 or addr > 0x7fffffffffff or n > MAX_READ:
            raise ValueError(f"refusing to read {n} bytes at {addr:#x}")
        buf = ctypes.create_string_buffer(n)
        got = ctypes.c_size_t()
        if not self.k32.ReadProcessMemory(self.handle, ctypes.c_void_p(addr), buf, n, ctypes.byref(got)) or got.value != n:
            raise OSError(f"read failed at {addr:#x}")
        return buf.raw

    def i16(self, a):
        return struct.unpack("<h", self.read(a, 2))[0]

    def i32(self, a):
        return struct.unpack("<i", self.read(a, 4))[0]

    def u32(self, a):
        return struct.unpack("<I", self.read(a, 4))[0]

    def i64(self, a):
        return struct.unpack("<q", self.read(a, 8))[0]

    def ptr(self, a):
        return struct.unpack("<Q", self.read(a, 8))[0]

    def u8(self, a):
        return self.read(a, 1)[0]

    def f64(self, a):
        return struct.unpack("<d", self.read(a, 8))[0]

    def rva(self, name):
        return self.base + int(self.m["globals"][name], 16)

    def off(self, struct_, field):
        return int(self.m["structs"][struct_][field], 16)

    def const(self, group, name):
        return int(self.m["containers"][group][name], 16)

    def string(self, addr):
        """eastl::string using the container contract."""
        size, flag_byte, mask = self.const("string", "size"), self.const("string", "heapFlagByte"), self.const("string", "heapFlagMask")
        raw = self.read(addr, size)
        if raw[flag_byte] & mask:
            p, n = struct.unpack_from("<QQ", raw, 0)
            return self.read(p, min(n, 256)).decode("latin-1") if 0 < n < 4096 else None
        n = self.const("string", "ssoCapacity") - raw[flag_byte]
        return raw[:n].decode("latin-1") if 0 <= n <= self.const("string", "ssoCapacity") else None

    def hash_lookup(self, map_addr, key):
        """eastl::hash_map<int, shared_ptr<T>>: returns T* or None; verifies the bucket invariant on the way."""
        buckets = self.ptr(map_addr + self.const("hash_map", "buckets"))
        count = self.i64(map_addr + self.const("hash_map", "bucketCount"))
        if not (0 < count < 1 << 20):
            raise ValueError(f"bucket count {count}")
        node = self.ptr(buckets + 8 * (key % count))
        steps = 0
        while node and steps < 64:
            if self.u32(node + self.const("hash_map", "node.key")) == key:
                return self.ptr(node + self.const("hash_map", "node.value"))
            node = self.ptr(node + self.const("hash_map", "node.next"))
            steps += 1
        return None


def level_for_xp(xp):
    points, out = 0, 1
    for lvl in range(1, 99):
        points += int(lvl + 300 * 2 ** (lvl / 7.0))
        if points // 4 > xp:
            return lvl
        out = lvl + 1
    return out


def run_checks(r, log):
    m = r.m
    results = {}

    def ok(name, cond, detail=""):
        results[name] = {"ok": bool(cond), "detail": detail}
        log(f"  {'PASS' if cond else 'FAIL'} {name}: {detail}")

    def skipped(name, why):
        results[name] = {"ok": None, "detail": f"skipped: {why}"}
        log(f"  SKIP {name}: {why}")

    client = r.ptr(r.rva("client"))
    ok("clientRoot", 0x10000 < client < 0x7fffffffffff, f"Client* = {client:#x}")
    state = r.i32(client + r.off("Client", "gameState"))
    logged_in = m["enums"].get("gameState", {}).get("LOGGED_IN")
    ok("gameState", state == logged_in, f"gameState={state} (LOGGED_IN={logged_in}); log in before probing")

    t0, c0 = r.i32(client + r.off("Client", "tickCount")), r.i32(r.rva("cycle"))
    time.sleep(1.3)
    t1, c1 = r.i32(client + r.off("Client", "tickCount")), r.i32(r.rva("cycle"))
    ok("tick", 1 <= t1 - t0 <= 3, f"tickCount {t0} -> {t1} over 1.3 s")
    ok("cycle", 40 <= c1 - c0 <= 90, f"cycle {c0} -> {c1} over 1.3 s (20 ms units)")
    if r.snapshot:
        results["tick"]["detail"] += " (snapshot clone: re-run without a clone to observe change)"

    lv, base, xp = (client + r.off("Client", k) for k in ("skillLevel", "skillBase", "skillXp"))
    bad = []
    for i in range(23):
        l, b, x = r.i32(lv + 4 * i), r.i32(base + 4 * i), r.i32(xp + 4 * i)
        if not (1 <= b <= 99 and 0 <= x <= 200_000_000 and level_for_xp(x) == b and 1 <= l <= 126):
            bad.append((i, l, b, x))
    ok("skills", not bad, f"23 skills consistent (level(xp)==base)" if not bad else f"inconsistent: {bad[:3]}")

    varps = r.ptr(r.rva("varps"))
    ok("varps", 0x10000 < varps < 0x7fffffffffff and r.read(varps, 4 * 64) is not None, f"varps array at {varps:#x} readable")
    skipped("varbits", "needs VarbitType definitions from the cache to recompute values")

    worlds = client + r.off("Client", "worlds.map")
    bcount = r.i64(worlds + r.const("hash_map", "bucketCount"))
    top = r.ptr(client + r.off("Client", "topLevelWorld"))
    plane = r.i32(top + r.off("World", "plane"))
    bx, bz = r.i32(top + r.off("World", "baseX")), r.i32(top + r.off("World", "baseZ"))
    w, h = r.i32(top + r.off("World", "width")), r.i32(top + r.off("World", "height"))
    ok("worlds", 0 < bcount < 4096 and 0 <= plane <= 3 and top > 0x10000, f"worlds buckets={bcount}, topLevelWorld={top:#x}, plane={plane}")
    ok("worldBase", bx % 8 == 0 and bz % 8 == 0 and 0 < bx < 16384 and 0 < bz < 16384 and 32 <= w <= 512 and 32 <= h <= 512,
       f"base=({bx},{bz}) size={w}x{h}")

    # Players and the local player.
    local = r.i32(client + r.off("Client", "localPlayerIndex"))
    pcount = r.i32(client + r.off("Client", "playerIndexCount"))
    ok("players", 0 <= pcount <= 2048 and 0 <= local < 2048, f"playerIndexCount={pcount}, localPlayerIndex={local}")
    player = r.hash_lookup(top + r.off("World", "players"), local) if 0 <= local < 2048 else None
    ok("localPlayer", player is not None, f"local player object {player and hex(player)}")
    actors = []
    if player:
        actors.append(("player", local, player))
        # A player's name isn't inline like an NPC's: Player.name points at a {display, normalised} name pair
        pair = r.ptr(player + r.off("Player", "name"))
        name = r.string(pair + r.off("PlayerName", "display")) if pair else None
        want = r.display_name
        ok("localPlayerName", name is not None and name.isprintable() and (want is None or name.lower() == want.lower()),
           f"name={name!r}" + (f" expected {want!r}" if want else " (pass --display-name to compare)"))
    # NPCs through the index list.
    idx_ptr = r.ptr(top + r.off("World", "npcIndices"))
    n = r.i32(top + r.off("World", "npcIndexCount"))
    npc_fail = []
    npcs = []
    if 0 <= n <= 1 << 16 and (n == 0 or idx_ptr):
        for k in range(min(n, 64)):
            nid = r.i32(idx_ptr + 4 * k)
            npc = r.hash_lookup(top + r.off("World", "npcs"), nid)
            if npc is None:
                npc_fail.append(nid)
                continue
            npcs.append((nid, npc))
            actors.append(("npc", nid, npc))
    ok("npcs", n >= 0 and not npc_fail, f"{n} NPC ids, {len(npcs)} resolved through World.npcs, missing {npc_fail[:4]}")
    ok("hashMap", not npc_fail and player is not None, "every looked-up key was found in its bucket chain")

    # Per-actor consistency.
    bad_coords, bad_inter, bad_anim, bad_world = [], [], [], []
    for kind, aid, a in actors:
        fx, fz = r.i32(a + r.off("Actor", "fineX")), r.i32(a + r.off("Actor", "fineZ"))
        px, pz = r.i32(a + r.off("Actor", "pathX")), r.i32(a + r.off("Actor", "pathZ"))
        if fx >> 7 != px or fz >> 7 != pz:
            bad_coords.append((kind, aid, fx, px, fz, pz))
        # interactingType: 0 none, 1 NPC index, 2 player index, 3 world entity id
        iv, it = r.u8(a + r.off("Actor", "interactingType")), r.i32(a + r.off("Actor", "interacting"))
        if iv not in (0, 1, 2, 3) or not (it == -1 or 0 <= it < 0x10000 + 2048):
            bad_inter.append((kind, aid, iv, it))
        an = r.i32(a + r.off("Actor", "anim"))
        st = r.ptr(a + r.off("Actor", "animSeqType"))
        if not (an == -1 or 0 <= an < 0x10000) or (an == -1) != (st == 0):
            bad_anim.append((kind, aid, an, st))
        if r.i32(a + r.off("Actor", "worldId")) not in range(0, 4096):
            bad_world.append((kind, aid))
    ok("fineVsPath", actors and not bad_coords, f"{len(actors)} actors: fine>>7 == path[0]" if not bad_coords else f"mismatch {bad_coords[:3]}")
    ok("interacting", actors and not bad_inter, f"interacting fields in range" if not bad_inter else f"{bad_inter[:3]}")
    ok("anim", actors and not bad_anim, "anim id and SeqType* consistent" if not bad_anim else f"{bad_anim[:3]}")
    ok("actorWorld", actors and not bad_world, "world ids plausible")
    hb_bad = []
    for kind, aid, a in actors:
        cnt = r.i64(a + r.off("Actor", "headbarCount"))
        head = a + r.off("Actor", "headbars")
        node, steps = r.ptr(head), 0
        while node != head and steps <= cnt + 1 and steps < 16:
            node = r.ptr(node)
            steps += 1
        if not (0 <= cnt <= 8) or node != head:
            hb_bad.append((kind, aid, cnt, steps))
    ok("headbars", actors and not hb_bad, "headbar lists terminate at their sentinel with the recorded count" if not hb_bad else f"{hb_bad[:3]}")

    # Hitmarks: each actor's vector of {type, value, end cycle} entries is whole entries, a handful at most, and an
    # entry's end cycle is never far past the client cycle (a hitmark shows for a few seconds).
    if "Hitmark" not in m["structs"]:
        skipped("hitmarks", "the mapping has no Hitmark keys")
    else:
        size, now, hm_bad, shown = r.off("Hitmark", "size"), r.i32(r.rva("cycle")), [], 0
        for kind, aid, a in actors:
            begin, end = r.ptr(a + r.off("Actor", "hitmarks")), r.ptr(a + r.off("Actor", "hitmarksEnd"))
            if begin == 0 and end == 0:
                continue
            if end < begin or (end - begin) % size or (end - begin) // size > 16:
                hm_bad.append((kind, aid, begin, end))
                continue
            for k in range((end - begin) // size):
                entry = begin + k * size
                ends = r.i32(entry + r.off("Hitmark", "endCycle"))
                if ends - now > 2000:
                    hm_bad.append((kind, aid, r.i32(entry + r.off("Hitmark", "type")), ends))
                shown += ends > now
        ok("hitmarks", actors and not hm_bad, f"hitmark vectors are whole entries; {shown} showing now" if not hm_bad
           else f"{hm_bad[:3]}")

    # Projectiles: the list ends at its sentinel; every projectile has a plausible id, endpoints and timing, and a
    # placed one sits inside the scene.
    head = client + r.off("Client", "projectiles")
    cycle = r.i32(r.rva("cycle"))
    pf = lambda name: r.off("Projectile", name)
    node, flying, pj_bad = r.ptr(head + r.const("list", "node.next")), 0, []
    while node and node != head and flying < 256:
        p = r.ptr(node + r.const("list", "node.value"))
        flying += 1
        spot, start, end = r.i32(p + pf("spotAnimId")), r.i32(p + pf("startCycle")), r.i32(p + pf("endCycle"))
        ends = [(r.i32(p + pf(f"{side}Level")), r.i32(p + pf(f"{side}X")), r.i32(p + pf(f"{side}Z")), r.i32(p + pf(f"{side}Actor")))
                for side in ("source", "target")]
        placed, hidden = r.u8(p + pf("started")), r.u8(p + pf("hidden"))
        fx, fz = r.f64(p + pf("fineX")), r.f64(p + pf("fineZ"))
        if not (0 <= spot < 0x10000 and start <= end and end >= cycle - 2 and placed in (0, 1) and hidden in (0, 1)
                and all(0 <= lv <= 3 and 0 <= x < 16384 and 0 <= z < 16384 and -2048 <= a <= 0x10000 for lv, x, z, a in ends)
                and (not placed or (0 <= fx < 512 * 128 and 0 <= fz < 512 * 128))):
            pj_bad.append((flying - 1, spot, start, end, cycle, ends, placed, round(fx), round(fz)))
        node = r.ptr(node + r.const("list", "node.next"))
    if node != head:
        ok("projectiles", False, f"the projectile list doesn't return to its sentinel after {flying} nodes")
    elif not flying:
        skipped("projectiles", "no projectiles in flight; stand near combat (ranged or magic) and re-run")
    else:
        ok("projectiles", not pj_bad, f"{flying} projectiles: ids, endpoints, cycles and positions plausible" if not pj_bad else f"{pj_bad[:2]}")

    # Keyed projectiles: every entry of the map has a key, a plausible id, endpoints and timing, and a placed one sits
    # inside the scene.
    kmap = client + r.off("Client", "keyedProjectiles")
    kf = lambda name: r.off("KeyedProjectile", name)
    buckets, nbuckets = r.ptr(kmap + r.const("hash_map", "buckets")), r.i64(kmap + r.const("hash_map", "bucketCount"))
    keyed, kp_bad = 0, []
    if not (0 < nbuckets < 0x10000):
        ok("keyedProjectiles", False, f"keyed projectile map has {nbuckets} buckets")
    else:
        for b in range(nbuckets):
            node, chain = r.ptr(buckets + 8 * b), 0
            while node and chain < 256:
                key, p = r.i32(node + r.const("hash_map", "node.key")), r.ptr(node + r.const("hash_map", "node.value"))
                keyed, chain = keyed + 1, chain + 1
                spot, start, end = r.i32(p + kf("spotAnimId")), r.i32(p + kf("startCycle")), r.i32(p + kf("endCycle"))
                ends = [(r.i32(p + kf(f"{side}Level")), r.i32(p + kf(f"{side}X")), r.i32(p + kf(f"{side}Z")),
                         r.i32(p + kf(f"{side}Actor"))) for side in ("source", "target")]
                placed = r.u8(p + kf("started"))
                fx, fz = struct.unpack("<f", r.read(p + kf("fineX"), 4))[0], struct.unpack("<f", r.read(p + kf("fineZ"), 4))[0]
                if not (0 <= key < 0x10000 and 0 <= spot < 0x10000 and start <= end and end >= cycle - 2 and placed in (0, 1)
                        and all(0 <= lv <= 3 and 0 <= x < 16384 and 0 <= z < 16384 and -2048 <= a <= 0x10000 for lv, x, z, a in ends)
                        and (not placed or (0 <= fx < 512 * 128 and 0 <= fz < 512 * 128))):
                    kp_bad.append((key, spot, start, end, cycle, ends, placed, round(fx), round(fz)))
                node = r.ptr(node + r.const("hash_map", "node.next"))
        if not keyed:
            skipped("keyedProjectiles", "no keyed projectiles in flight; re-run while one is in the air")
        else:
            ok("keyedProjectiles", not kp_bad, f"{keyed} keyed projectiles: keys, ids, endpoints, cycles and positions plausible"
               if not kp_bad else f"{kp_bad[:2]}")

    # Graphics objects: the top-level world's list ends at its sentinel; every object has a plausible id, sits on the
    # world's plane at the centre of a tile of its scene, and has a SeqType (null only between an animation ending and
    # the next frame's filter dropping the object).
    head = top + r.off("World", "graphicsObjects")
    gf = lambda name: r.off("GraphicsObject", name)
    node, playing, go_bad = r.ptr(head + r.const("list", "node.next")), 0, []
    while node and node != head and playing < 256:
        g = r.ptr(node + r.const("list", "node.value"))
        playing += 1
        spot, level, start = r.i32(g + gf("spotAnimId")), r.i32(g + gf("level")), r.i32(g + gf("startCycle"))
        fx, fz, height = r.i32(g + gf("fineX")), r.i32(g + gf("fineZ")), r.i32(g + gf("height"))
        seq, frame = r.ptr(g + gf("animSeqType")), r.i16(g + gf("animFrame"))
        if not (0 <= spot < 0x10000 and level == plane and fx % 128 == 64 and fz % 128 == 64 and 0 <= fx < w * 128
                and 0 <= fz < h * 128 and -0x4000 < height < 0x4000 and start <= cycle + 0x10000 and (seq == 0 or seq > 0x10000)
                and -1 <= frame < 0x1000):
            go_bad.append((playing - 1, spot, level, fx, fz, height, start, cycle, hex(seq), frame))
        node = r.ptr(node + r.const("list", "node.next"))
    if node != head:
        ok("graphicsObjects", False, f"the graphics object list doesn't return to its sentinel after {playing} nodes")
    elif not playing:
        skipped("graphicsObjects", "no graphics objects playing; stand near a cannon or a spell's splash and re-run")
    else:
        ok("graphicsObjects", not go_bad, f"{playing} graphics objects: ids, tiles, heights, cycles and animations plausible"
           if not go_bad else f"{go_bad[:2]}")

    # NPC types.
    nt_bad = []
    for nid, npc in npcs[:32]:
        t = r.ptr(npc + r.off("Npc", "type"))
        if not t:
            nt_bad.append((nid, "null type"))
            continue
        tid, size = r.i32(t + r.off("NpcType", "id")), r.i32(t + r.off("NpcType", "size"))
        name = r.string(t + r.off("NpcType", "name"))
        if not (0 <= tid < 0x10000 and 1 <= size <= 5 and name is not None):
            nt_bad.append((nid, tid, size, name))
    ok("npcType", npcs and not nt_bad, f"{min(len(npcs), 32)} NPC types: id<65536, size 1..5, name decodes" if not nt_bad else f"{nt_bad[:3]}")
    skipped("poseAnims", "slot order needs an in-game observation")

    # Scene.
    scene = r.ptr(top + r.off("World", "scene"))
    sx, sz = r.i32(scene + r.off("Scene", "sizeX")), r.i32(scene + r.off("Scene", "sizeZ"))
    tiles = r.ptr(scene + r.off("Scene", "tiles"))
    stride, poff = r.off("Scene", "tileEntryStride"), r.off("Scene", "tileEntryPtrOffset")
    tag_bad, obj_bad, seen = [], [], 0
    if 32 <= sx <= 512 and 32 <= sz <= 512 and tiles:
        for x in range(0, sx, 13):
            for z in range(0, sz, 13):
                tile = r.ptr(tiles + ((plane * sx + x) * sz + z) * stride + poff)
                if not tile:
                    continue
                seen += 1
                for slot in ("wall", "wallDecor", "groundDecor"):
                    loc = r.ptr(tile + r.off("Tile", slot))
                    if loc and not (0 <= (r.i64(loc + r.off("Loc", "tag")) >> r.off("Loc", "tagIdShift")) & 0xffffffff < 0x10000):
                        tag_bad.append((x, z, slot))
                cnt = r.i32(tile + r.off("Tile", "gameObjectCount"))
                arr = r.ptr(tile + r.off("Tile", "gameObjects"))
                for k in range(min(cnt, 8)):
                    obj = r.ptr(arr + k * r.off("Tile", "gameObjectEntryStride") + 8)
                    if obj and not (0 <= r.i32(obj + r.off("GameObject", "originX")) < sx and 0 <= r.i32(obj + r.off("GameObject", "originZ")) < sz):
                        obj_bad.append((x, z, k))
    ok("scene", seen > 0 and not tag_bad and not obj_bad, f"scene {sx}x{sz}, {seen} sampled tiles, loc ids < 65536, game-object origins inside the scene"
       if seen else f"scene {sx}x{sz} tiles={tiles:#x} unreadable")
    grid = r.ptr(top + r.off("World", "objPiles"))
    ok("groundItems", grid > 0x10000, f"obj pile grid at {grid:#x} (structure walk needs an item on the ground)")

    # Camera, viewport.
    ang = client + r.off("Client", "camera.angles")
    a0, a1 = r.i32(ang), r.i32(ang + 4)
    ok("camera", 0 <= a0 < 16384 and 0 <= a1 < 16384, f"angles=({a0},{a1}); rotate the camera and re-run to tell pitch from yaw")
    renderer = r.ptr(client + r.off("Client", "renderer"))
    cw, ch = r.i32(renderer + r.off("Renderer", "canvasWidth")), r.i32(renderer + r.off("Renderer", "canvasHeight"))
    ok("viewport", 100 <= cw <= 8192 and 100 <= ch <= 8192, f"canvas {cw}x{ch}")
    skipped("projection", "worldToScreen needs in-process execution")

    # Inventories (global hash table of id -> vectors).
    inv_b = r.ptr(r.rva("inventories.buckets"))
    inv_n = r.i64(r.rva("inventories.bucketCount"))
    inv_bad, inv_seen = [], 0
    if 0 < inv_n < 4096:
        for b in range(min(inv_n, 64)):
            node = r.ptr(inv_b + 8 * b)
            steps = 0
            while node and steps < 32:
                iid = r.i32(node + r.off("Inventory", "id"))
                ids0, ids1 = r.ptr(node + r.off("Inventory", "ids")), r.ptr(node + r.off("Inventory", "idsEnd"))
                c0_, c1_ = r.ptr(node + r.off("Inventory", "counts")), r.ptr(node + r.off("Inventory", "countsEnd"))
                if not (0 <= iid < 10000 and ids1 >= ids0 and (ids1 - ids0) == (c1_ - c0_) and (ids1 - ids0) // 4 <= 2000):
                    inv_bad.append((b, iid))
                inv_seen += 1
                node = r.ptr(node + r.off("Inventory", "next"))
                steps += 1
    ok("inventories", inv_seen > 0 and not inv_bad, f"{inv_seen} inventories: ids/counts vectors same length" if inv_seen else "no inventory nodes")

    # Interfaces.
    ib = r.ptr(client + r.off("Client", "interfaces.buckets"))
    ic = r.i32(client + r.off("Client", "interfaces.bucketCount"))
    groups = []
    if 0 < ic < 8192 and ib:
        for b in range(min(ic, 256)):
            node, steps = r.ptr(ib + 8 * b), 0
            while node and steps < 32:
                groups.append(r.i32(node + r.off("IfGroupNode", "key")))
                node = r.ptr(node + r.off("IfGroupNode", "next"))
                steps += 1
    ok("widgets", any(g in (149, 162, 548, 161, 164) for g in groups), f"{len(groups)} interface groups open; known root ids present: {sorted(set(groups) & {149, 162, 548, 161, 164})}")

    # Sub-interfaces: component -> the group opened in it. Every entry's group is open, and so is the group its
    # component is in; logged in, the inventory (149) is one of them. Each was opened with a modal mode (0, 1 or 3),
    # and the top-level interface is an open root group.
    if "SubInterfaceNode" not in m["structs"]:
        skipped("subInterfaces", "the mapping has no SubInterfaceNode keys")
    else:
        sb = r.ptr(client + r.off("Client", "subInterfaces.buckets"))
        sc = r.i32(client + r.off("Client", "subInterfaces.bucketCount"))
        has_mode = "type" in m["structs"]["SubInterfaceNode"]
        subs = []
        if 0 < sc < 8192 and sb:
            for b in range(min(sc, 256)):
                node, steps = r.ptr(sb + 8 * b), 0
                while node and steps < 32:
                    subs.append((r.i32(node + r.off("SubInterfaceNode", "key")), r.i32(node + r.off("SubInterfaceNode", "group")),
                                 r.i32(node + r.off("SubInterfaceNode", "type")) if has_mode else 0))
                    node = r.ptr(node + r.off("SubInterfaceNode", "next"))
                    steps += 1
        bad = [(c, g) for c, g, _ in subs if g not in groups or (c >> 16) not in groups]
        bad_modes = sorted({t for _, _, t in subs if t not in (0, 1, 3)})
        inventory = [f"{c >> 16}:{c & 0xffff}" for c, g, _ in subs if g == 149]
        top = r.i32(client + r.off("Client", "topLevelInterface")) if "topLevelInterface" in m["structs"]["Client"] else None
        top_ok = top is None or (top in groups and top in (161, 164, 165, 548))
        ok("subInterfaces", bool(subs) and not bad and bool(inventory) and not bad_modes and top_ok,
           f"{len(subs)} sub-interfaces, all between open groups; inventory opened in {inventory}; modes "
           f"{sorted({t for _, _, t in subs})}; top-level {top}" if not bad
           else f"entries outside the open groups: {[(f'{c >> 16}:{c & 0xffff}', g) for c, g, _ in bad][:5]}")

    # Chat: the newest lines, looked up by id below the next id. Logged in, the welcome message is there at least; every
    # line has a chat type, text and a cycle no later than now.
    if "ChatLine" not in m["structs"]:
        skipped("chat", "the mapping has no ChatLine keys")
    else:
        next_id, now = r.i32(r.rva("chatLines.nextId")), r.i32(r.rva("cycle"))
        lines_map = r.rva("chatLines.buckets") - r.const("hash_map", "buckets")
        lines, chat_bad = [], []
        for line_id in range(next_id - 1, max(next_id - 20, -1), -1):
            line = r.hash_lookup(lines_map, line_id)
            if line is None:
                continue
            kind, cyc = r.i32(line + r.off("ChatLine", "type")), r.i32(line + r.off("ChatLine", "cycle"))
            text = r.string(line + r.off("ChatLine", "text"))
            lines.append((line_id, kind, r.string(line + r.off("ChatLine", "name")), text))
            if not (0 <= kind < 256) or not text or not (0 <= cyc <= now):
                chat_bad.append((line_id, kind, cyc, text))
        ok("chat", lines and not chat_bad, f"next id {next_id}; newest lines {lines[:3]}" if not chat_bad
           else f"{chat_bad[:3]}")

    # The minimenu: the rows of what the mouse is over (or of the open menu), top first; the bottom row is "Cancel".
    if "Minimenu" not in m["structs"]:
        skipped("minimenu", "the mapping has no Minimenu keys")
        return results
    menu = r.ptr(client + r.off("Client", "minimenu"))
    begin, end = r.ptr(menu + r.off("Minimenu", "rows")), r.ptr(menu + r.off("Minimenu", "rows") + 8)
    node_size = r.const("minimenu", "node.size")
    rows = []
    if menu and begin and end >= begin and (end - begin) % node_size == 0 and (end - begin) // node_size <= 500:
        for k in range((end - begin) // node_size):
            node = begin + k * node_size
            lst = r.ptr(node + r.const("minimenu", "node.list"))
            if not lst:
                rows.append(("<submenu>", "", -1))
                continue
            entry = r.ptr(lst + r.const("minimenu", "list.entries")) + r.i64(node + r.const("minimenu", "node.index")) * r.off("MinimenuEntry", "size")
            rows.append((r.string(entry + r.off("MinimenuEntry", "option")), r.string(entry + r.off("MinimenuEntry", "target")),
                         r.u8(entry + r.off("MinimenuEntry", "subjectType"))))
    is_open = r.ptr(menu + r.off("Minimenu", "open")) != 0 if menu else False
    clicks = r.i32(menu + r.off("Minimenu", "clickCycles")) if menu else -1
    ok("minimenu", bool(rows) and rows[-1][0] == "Cancel",
       f"{len(rows)} rows (open={is_open}, {clicks} cycles since the last click): {rows[:4]}")
    return results


def main(mapping_path, display_name=None):
    mapping = json.loads(Path(mapping_path).read_text(encoding="utf-8"))
    log = print
    r = Reader(mapping, display_name)
    try:
        log(f"probing client {mapping['version']}: pid {r.pid}, module {r.base:#x} size {r.size:#x}, "
            f"snapshot={'yes' if r.snapshot else 'no (live reads)'}")
        checks = run_checks(r, log)
    finally:
        r.close()
    failed = [k for k, v in checks.items() if v["ok"] is False]
    log(f"{len(checks) - len(failed)}/{len(checks)} checks passed or skipped" + (f"; failed: {failed}" if failed else ""))
    return 2 if failed else 0
