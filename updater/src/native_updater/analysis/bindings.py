"""sol2 binding tables: Lua API name -> native handler (or data-member offset), plus enum tables.

Registration shapes seen in the client (docs/developer/updater.md, "Lua Bindings"):
  (a) ClientOp:    LEA name ... LEA handler ... CALL register          name first
  (b) usertypes:   LEA handler -> slot; LEA name -> slot; CALL set      handler first
  (c) factories:   LEA name ... CALL factory (body holds LEA handler)   no handler in the table itself
  (d) properties:  MOV dword [slot], offset; LEA name; CALL set         data member: the offset is the payload
  (e) getters:     LEA name; CALL setter<lambda>                        sol::property(lambda): see _property_getter
Instead of modelling call sequences, the parser lists the events in a registration function (N = name string,
H = code address, M = member offset immediate) and pairs each N with an adjacent H/M by maximum matching.
Pairs that straddle a register call (a CALL whose RCX is not a stack temporary) are penalised, which settles
runs where both directions are possible. Names left without a partner are resolved through the per-lambda
factory function called next to them, then through the per-property setter (shape e).

Enum tables (`osrs.Events` and friends) are static arrays of {const char* name, size_t len, int64 value}
records in .rdata, found by scanning for runs of such records.
"""
import re
import struct
from dataclasses import dataclass
from typing import Optional

NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
FACTORY_MAX_INSNS = 400
FACTORY_MAX_CALLSITES = 2
MEMBER_MAX = 0x10000


@dataclass
class Binding:
    table: str
    name: str
    handler: Optional[int] = None        # function RVA for function bindings
    member_offset: Optional[int] = None  # field offset for data-member (property) bindings
    kind: str = "unpaired"               # direct | member | factory | unpaired | ambiguous
    name_insn: int = 0                   # RVA of the LEA that references the name string
    register_fn: int = 0                 # the registration function
    register_call: Optional[int] = None  # target of the first register call after the pair (groups by signature)
    candidates: tuple = ()

    @property
    def key(self):
        return f"{self.table}.{self.name}"


class BindingTableParser:
    def __init__(self, model):
        self.model = model

    def registration_functions(self, anchor_strings):
        """Functions referencing every anchor string (normally exactly one function)."""
        sets = []
        for s in anchor_strings:
            fns = {fn for _, fn in self.model.string_refs(s) if fn is not None}
            if fns:
                sets.append(fns)
        if not sets:
            return []
        common = set.intersection(*sets)
        return sorted(common) if common else sorted(set.union(*sets))

    def parse(self, fn, table, namespace_strings=()):
        model = self.model
        body = model.insns(fn)
        events = []                       # [rva, kind, payload]
        counts = {}
        cuts = []                         # RVAs of register calls
        for k, insn in enumerate(body):
            if insn.is_call:
                if self._is_register_call(body, k):
                    cuts.append(insn.rva)
                continue
            if insn.mnem == "MOV" and len(insn.ops) == 2 and insn.ops[0].kind == "mem" and insn.ops[0].size == 4 \
                    and insn.ops[0].base in ("RSP", "RBP") and insn.ops[1].kind == "imm" and 0 <= insn.ops[1].imm < MEMBER_MAX:
                if self._slot_address_taken(body, k):
                    events.append([insn.rva, "M", insn.ops[1].imm])
                continue
            if insn.mnem != "LEA" or len(insn.ops) != 2 or insn.ops[1].base != "RIP":
                continue
            rva = insn.ops[1].disp
            if model.pe.is_code(rva):
                target = model.resolve(rva)
                if model.function(target) is not None:
                    events.append([insn.rva, "H", target])
                continue
            text = model.string_at(rva)
            if text and NAME_RE.match(text):
                if events and events[-1][1] == "N" and events[-1][2] == text:
                    continue              # strlen loop + ctor reference the same string twice
                events.append([insn.rva, "N", text])
                counts[text] = counts.get(text, 0) + 1
        drop = set(namespace_strings) | {t for t, c in counts.items() if c >= 3}
        for ns in namespace_strings:
            drop.update(ns.split("."))
        events = [e for e in events if not (e[1] == "N" and e[2] in drop)]
        if not events:
            return []

        pairs = self._match(events, cuts)
        used = set()
        out = []
        for k, e in enumerate(events):
            if e[1] != "N":
                continue
            b = Binding(table, e[2], name_insn=e[0], register_fn=fn, register_call=self._call_after(body, e[0]))
            j = pairs.get(k)
            if j is not None:
                partner = events[j]
                if partner[1] == "H":
                    b.handler, b.kind = partner[2], "direct"
                    used.add(partner[2])
                else:
                    b.member_offset, b.kind = partner[2], "member"
            out.append(b)

        # Factory follow-through: the name's register window holds a call to a one-off factory that LEAs the handler.
        for k, b in enumerate(out):
            if b.kind != "unpaired":
                continue
            hi = out[k + 1].name_insn if k + 1 < len(out) else 1 << 62
            found = []
            for insn in body:
                if b.name_insn < insn.rva < hi and insn.is_call and insn.target is not None:
                    h = self._factory_handler(model.resolve(insn.target))
                    if h is not None and h not in used and h not in found:
                        found.append(h)
                    if insn.rva in cuts:
                        break
            if len(found) == 1:
                b.handler, b.kind = found[0], "factory"
                used.add(found[0])
            elif found:
                b.kind, b.candidates = "ambiguous", tuple(found)

        # Property follow-through: a getter lambda reached through the setter template called in the name's window.
        for k, b in enumerate(out):
            if b.kind != "unpaired":
                continue
            hi = out[k + 1].name_insn if k + 1 < len(out) else 1 << 62
            found = []
            for insn in body:
                if b.name_insn < insn.rva < hi and insn.is_call and insn.target is not None:
                    g = self._property_getter(model.resolve(insn.target))
                    if g is not None and g not in found:
                        found.append(g)
            if len(found) == 1:
                b.handler, b.kind = found[0], "property"
        return out

    @staticmethod
    def _match(events, cuts):
        """Maximum matching of N events with adjacent H/M events on the event path (DP), penalising pairs that
        straddle a register call. Returns {index of N: index of partner}."""
        n = len(events)
        cuts = sorted(cuts)

        def straddles(i, j):
            lo, hi = sorted((events[i][0], events[j][0]))
            import bisect
            return bisect.bisect_left(cuts, lo) != bisect.bisect_left(cuts, hi)

        def pairable(i, j):
            return {events[i][1], events[j][1]} in ({"N", "H"}, {"N", "M"})

        best = [(0, ())] * (n + 1)        # best[i] = (score, pairs) for the suffix starting at i
        for i in range(n - 1, -1, -1):
            score, chosen = best[i + 1]
            if i + 1 < n and pairable(i, i + 1):
                s2, c2 = best[i + 2]
                s2 += 1000 - (10 if straddles(i, i + 1) else 0)
                if s2 > score:
                    score, chosen = s2, ((i, i + 1),) + c2
            best[i] = (score, chosen)
        out = {}
        for i, j in best[0][1]:
            if events[i][1] == "N":
                out[i] = j
            else:
                out[j] = i
        return out

    @staticmethod
    def _is_register_call(body, k):
        """A call whose RCX is not a stack temporary: the table/this pointer of a sol2 register or set call.
        Helper calls (string ctor/dtor, factories) take a stack address in RCX."""
        for j in range(k - 1, max(-1, k - 12), -1):
            i = body[j]
            if i.is_call or i.is_jump or i.is_ret:
                return True
            if i.ops and i.ops[0].kind == "reg" and i.ops[0].reg == "RCX" and i.mnem in ("MOV", "LEA"):
                src = i.ops[1]
                return not (i.mnem == "LEA" and src.kind == "mem" and src.base in ("RSP", "RBP"))
        return True

    @staticmethod
    def _slot_address_taken(body, k):
        slot = (body[k].ops[0].base, body[k].ops[0].disp)
        for j in range(k + 1, min(len(body), k + 7)):
            i = body[j]
            if i.mnem == "LEA" and len(i.ops) == 2 and i.ops[1].kind == "mem" and (i.ops[1].base, i.ops[1].disp) == slot:
                return True
        return False

    @staticmethod
    def _call_after(body, rva):
        for i in body:
            if i.rva > rva and i.is_call:
                return i.target
        return None

    def _factory_handler(self, fn):
        """The single function a one-off factory stores into its sol2 function object, if any."""
        if len(self.model.call_sites(fn)) > FACTORY_MAX_CALLSITES:
            return None
        body = self.model.insns(fn)
        if not body or len(body) > FACTORY_MAX_INSNS:
            return None
        found = set()
        for insn in body:
            if insn.mnem == "LEA" and len(insn.ops) == 2 and insn.ops[1].base == "RIP" and self.model.pe.is_code(insn.ops[1].disp):
                t = self.model.resolve(insn.ops[1].disp)
                if self.model.function(t) is not None:
                    found.add(t)
        return found.pop() if len(found) == 1 else None

    def _property_getter(self, fn, depth=0):
        """The lambda of a sol::property(lambda) registration. The registration calls a setter template made for
        that property alone (sometimes through a one-off wrapper). Among the code addresses the setter LEAs, the
        sol2 helpers every setter shares are LEA'd from many functions; the property's own index functions are LEA'd
        from this setter only, the getter's first and then, for a writable property, the setter's. Each (a JMP stub
        to its binding) checks `self` and calls the lambda, which nothing else calls. Returns the getter lambda, or
        None."""
        model = self.model
        if depth > 1 or len(model.call_sites(fn)) > FACTORY_MAX_CALLSITES:
            return None
        body = model.insns(fn)
        if not body or len(body) > FACTORY_MAX_INSNS:
            return None
        for insn in body:
            if insn.mnem == "LEA" and len(insn.ops) == 2 and insn.ops[1].base == "RIP" and model.pe.is_code(insn.ops[1].disp):
                addr = insn.ops[1].disp
                if {f for _, f, _ in model.xrefs_to(addr, ("lea",))} == {fn}:
                    lam = self._sole_callee(model.resolve(addr))
                    if lam is not None:
                        return lam
        found = []
        for insn in body:
            if insn.is_call and insn.target is not None:
                lam = self._property_getter(model.resolve(insn.target), depth + 1)
                if lam is not None and lam not in found:
                    found.append(lam)
        return found[0] if len(found) == 1 else None

    def _sole_callee(self, fn):
        """The one function fn calls that has no other caller, or None."""
        body = self.model.insns(fn)
        if not body or len(body) > FACTORY_MAX_INSNS:
            return None
        own = {self.model.resolve(i.target) for i in body if i.is_call and i.target is not None
               and len(self.model.call_sites(self.model.resolve(i.target))) == 1}
        return own.pop() if len(own) == 1 else None


class BindingTable:
    """All parsed bindings keyed by 'Table.name'. The first occurrence of a duplicated name wins."""

    def __init__(self):
        self.bindings = {}
        self.by_table = {}

    def add(self, b: Binding):
        self.bindings.setdefault(b.key, b)
        self.by_table.setdefault(b.table, []).append(b)

    def get(self, key):
        return self.bindings.get(key)

    def handler(self, key):
        b = self.bindings.get(key)
        return b.handler if b else None

    def handlers(self, table):
        return [b.handler for b in self.by_table.get(table, ()) if b.handler is not None]

    def __contains__(self, key):
        return key in self.bindings


@dataclass
class EnumTable:
    rva: int
    entries: dict           # name -> value


def parse_enum_tables(model, min_entries=3):
    """Find enum registration arrays in read-only data: 24-byte records {int32 value (padded to 8), char* name,
    size_t len}, value first. The last record is followed by unrelated data (often the name strings themselves),
    and the client agrees: it fires event 11 (ON_VAR_TRANSMIT in this layout) from the varp packet handler, and
    getProfileType returns 0..5 in the order the ProfileType records list them. Reading the records name-first
    instead shifts every value by one record."""
    pe = model.pe
    tables = []
    for sec in pe.sections:
        if sec.name not in (".rdata", "_RDATA") or sec.raw_size == 0:
            continue
        blob = pe.data[sec.raw_off:sec.raw_off + sec.raw_size]
        n = len(blob) // 8
        qwords = struct.unpack_from(f"<{n}Q", blob, 0)
        k = 0
        while k + 2 < n:
            run = []
            j = k
            while j + 2 < n:
                value, ptr, length = _int32(qwords[j]), qwords[j + 1], qwords[j + 2]
                if value is None or not (1 <= length <= 64 and pe.image_base <= ptr < pe.image_base + pe.size_of_image):
                    break
                text = pe.cstring(ptr - pe.image_base, limit=65)
                if text is None or len(text) != length:
                    break
                run.append((text, value))
                j += 3
            if len(run) >= min_entries:
                tables.append(EnumTable(sec.rva + k * 8, dict(run)))
                k = j
            else:
                k += 1
    return tables


def _int32(qword):
    """The signed 32-bit enum value held in an 8-byte slot (upper half zero padding or sign extension), else None."""
    high, low = qword >> 32, qword & 0xFFFFFFFF
    if high not in (0, 0xFFFFFFFF):
        return None
    return low - (1 << 32) if low & 0x80000000 else low


def select_enum_table(tables, name_pattern):
    """The table most of whose names match the pattern."""
    rx = re.compile(name_pattern)
    best, score = None, 0
    for t in tables:
        hits = sum(1 for n in t.entries if rx.search(n))
        if hits > score:
            best, score = t, hits
    return best if score >= 2 else None
