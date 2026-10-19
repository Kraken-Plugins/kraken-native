"""The program model: one SQLite file per build, built from the Ghidra export plus the PE itself.

Tables (all addresses are RVAs):
  meta(key, value)
  section(name, rva, vsize, raw_off, raw_size, perms)
  function(entry, name, end, size, is_thunk, thunk_target, pdata, body)
  insn(rva, fn, len, bytes, mnem, text, target, fallthrough, flows)
  operand(rva, idx, kind, base, idxreg, scale, disp, size, imm)     -- memory and immediate operands only
  string(rva, value)
  xref(src, dst, kind, fn)                                          -- kind: call, jump, read, write, lea

Ad hoc questions are plain SQL, e.g. who touches +0xCC70:
  SELECT i.rva, i.fn, i.text FROM operand o JOIN insn i USING (rva) WHERE o.kind = 'mem' AND o.disp = 0xCC70;
"""
import gzip
import sqlite3
from bisect import bisect_right
from functools import lru_cache
from pathlib import Path

from . import x86
from .pe import PE

SCHEMA_VERSION = "1"

_SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE section(name TEXT, rva INTEGER, vsize INTEGER, raw_off INTEGER, raw_size INTEGER, perms TEXT);
CREATE TABLE function(entry INTEGER PRIMARY KEY, name TEXT, end INTEGER, size INTEGER, is_thunk INTEGER,
                      thunk_target INTEGER, pdata INTEGER, body TEXT);
CREATE TABLE insn(rva INTEGER PRIMARY KEY, fn INTEGER, len INTEGER, bytes BLOB, mnem TEXT, text TEXT,
                  target INTEGER, fallthrough INTEGER, flows TEXT);
CREATE TABLE operand(rva INTEGER, idx INTEGER, kind TEXT, base TEXT, idxreg TEXT, scale INTEGER, disp INTEGER,
                     size INTEGER, imm INTEGER);
CREATE TABLE string(rva INTEGER PRIMARY KEY, value TEXT);
CREATE TABLE xref(src INTEGER, dst INTEGER, kind TEXT, fn INTEGER);
"""

_INDEXES = """
CREATE INDEX insn_fn ON insn(fn, rva);
CREATE INDEX insn_target ON insn(target);
CREATE INDEX operand_disp ON operand(disp) WHERE kind = 'mem';
CREATE INDEX operand_imm ON operand(imm) WHERE kind = 'imm';
CREATE INDEX operand_rva ON operand(rva);
CREATE INDEX string_value ON string(value);
CREATE INDEX xref_dst ON xref(dst, kind);
CREATE INDEX xref_src ON xref(src);
"""

# First operands these instructions only read; for everything else a memory destination is written.
_READS_FIRST = {"CMP", "TEST", "PUSH", "CALL", "JMP", "BT", "COMISS", "UCOMISS", "COMISD", "UCOMISD", "NOP",
                "PREFETCHT0", "PREFETCHT1", "PREFETCHT2", "PREFETCHNTA", "PREFETCHW"}


def build(export_dir, binary_path, db_path):
    """Import a Ghidra export directory (see ghidra/ExportProgramModel.java) into a new SQLite model."""
    export_dir, db_path = Path(export_dir), Path(db_path)
    pe = PE.load(binary_path)
    meta = dict(line.rstrip("\n").split("\t", 1) for line in open(export_dir / "meta.tsv", encoding="utf-8") if "\t" in line)
    image_base = int(meta["image_base"], 16)
    if image_base != pe.image_base:
        raise ValueError(f"export image base {image_base:#x} does not match the PE ({pe.image_base:#x})")

    tmp = db_path.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    db = sqlite3.connect(tmp)
    db.executescript("PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF;" + _SCHEMA)
    meta.update(schema=SCHEMA_VERSION, binary=Path(binary_path).name)
    db.executemany("INSERT INTO meta VALUES (?, ?)", meta.items())
    db.executemany("INSERT INTO section VALUES (?, ?, ?, ?, ?, ?)",
                   [(s.name, s.rva, s.vsize, s.raw_off, s.raw_size, s.perms) for s in pe.sections])

    pdata = {begin for begin, _ in pe.pdata()}
    with gzip.open(export_dir / "functions.tsv.gz", "rt", encoding="utf-8") as f:
        db.executemany("INSERT OR IGNORE INTO function VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                       (_function_row(line, pdata) for line in f))

    with gzip.open(export_dir / "insns.tsv.gz", "rt", encoding="utf-8") as f:
        while True:
            lines = f.readlines(1 << 24)
            if not lines:
                break
            operands, xrefs = [], []
            db.executemany("INSERT OR IGNORE INTO insn VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                           [_insn_row(line, image_base, operands, xrefs) for line in lines])
            db.executemany("INSERT INTO operand VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", operands)
            db.executemany("INSERT INTO xref VALUES (?, ?, ?, ?)", xrefs)

    strings = dict(pe.strings())
    for (dst,) in db.execute("SELECT DISTINCT dst FROM xref WHERE kind IN ('lea', 'read')"):
        if dst not in strings and not pe.is_code(dst):
            text = pe.cstring(dst)
            if text and len(text) >= 2:
                strings[dst] = text      # a reference into the middle of a longer string (suffix sharing)
    db.executemany("INSERT INTO string VALUES (?, ?)", strings.items())
    db.executescript(_INDEXES)
    db.commit()
    db.close()
    tmp.replace(db_path)


def _function_row(line, pdata):
    entry, name, thunk, target, body = line.rstrip("\n").split("\t")
    ranges = [tuple(int(x, 16) for x in r.split("-")) for r in body.split(",") if r]
    entry = int(entry, 16)
    return (entry, name, max((e for _, e in ranges), default=entry), sum(e - s for s, e in ranges),
            int(thunk), int(target, 16) if target else None, int(entry in pdata), body)


def _insn_row(line, image_base, operands, xrefs):
    rva, fn, length, raw, text, flows, fallthrough = line.rstrip("\n").split("\t")
    rva, length = int(rva, 16), int(length)
    fn = None if fn == "-" else int(fn, 16)
    insn = x86.parse(text, rva, length, image_base)
    for i, op in enumerate(insn.ops):
        if op.kind == "mem":
            operands.append((rva, i, "mem", op.base, op.index, op.scale, op.disp, op.size, None))
            if op.base == "RIP":
                kind = "lea" if insn.mnem == "LEA" else ("write" if i == 0 and insn.mnem not in _READS_FIRST else "read")
                xrefs.append((rva, op.disp, kind, fn))
        elif op.kind == "imm":
            operands.append((rva, i, "imm", None, None, None, None, None, op.imm))
    target = insn.target
    if target is not None:
        xrefs.append((rva, target, "call" if insn.is_call else "jump", fn))
    flow_list = [int(x, 16) for x in flows.split(",") if x]
    extra = ",".join(f"{x:x}" for x in flow_list if x != target)
    return (rva, fn, length, bytes.fromhex(raw), insn.mnem, text, target,
            int(fallthrough, 16) if fallthrough else None, extra or None)


class Function:
    __slots__ = ("entry", "name", "end", "size", "is_thunk", "thunk_target", "pdata")

    def __init__(self, entry, name, end, size, is_thunk, thunk_target, pdata):
        self.entry, self.name, self.end, self.size = entry, name, end, size
        self.is_thunk, self.thunk_target, self.pdata = bool(is_thunk), thunk_target, bool(pdata)

    def __repr__(self):
        return f"Function({self.entry:#x} {self.name})"


class ProgramModel:
    """Read-only queries over program.db; the PE supplies data bytes (vtables, constants)."""

    def __init__(self, db_path, binary_path):
        self.db = sqlite3.connect(f"file:{Path(db_path).as_posix()}?mode=ro", uri=True, check_same_thread=False)
        self.pe = PE.load(binary_path)
        self.meta = dict(self.db.execute("SELECT key, value FROM meta"))
        self.image_base = int(self.meta["image_base"], 16)
        self.sha256 = self.meta.get("sha256", "")
        self._entries = [row[0] for row in self.db.execute("SELECT entry FROM function ORDER BY entry")]

    @lru_cache(maxsize=None)
    def function(self, entry):
        row = self.db.execute("SELECT entry, name, end, size, is_thunk, thunk_target, pdata FROM function WHERE entry = ?",
                              (entry,)).fetchone()
        return Function(*row) if row else None

    def function_containing(self, rva):
        row = self.db.execute("SELECT fn FROM insn WHERE rva = ?", (rva,)).fetchone()
        if row and row[0] is not None:
            return row[0]
        i = bisect_right(self._entries, rva) - 1
        if i >= 0:
            f = self.function(self._entries[i])
            if f and rva < f.end:
                return f.entry
        return None

    def resolve(self, rva):
        """Follow thunks and single-JMP stubs to the real function."""
        for _ in range(8):
            f = self.function(rva)
            if f is None:
                return rva
            if f.is_thunk and f.thunk_target is not None:
                rva = f.thunk_target
                continue
            body = self.insns(rva)
            if len(body) == 1 and body[0].is_jump and body[0].target is not None:
                rva = body[0].target
                continue
            return rva
        return rva

    @lru_cache(maxsize=8192)
    def insns(self, fn):
        rows = self.db.execute("SELECT rva, len, text, fallthrough, flows, target FROM insn WHERE fn = ? ORDER BY rva", (fn,))
        out = []
        for rva, length, text, fallthrough, flows, target in rows:
            extra = tuple(int(x, 16) for x in flows.split(",")) if flows else ()
            out.append(x86.parse(text, rva, length, self.image_base, fallthrough,
                                 ((target,) if target is not None else ()) + extra))
        return tuple(out)

    def insn(self, rva):
        row = self.db.execute("SELECT rva, len, text, fallthrough, flows, target FROM insn WHERE rva = ?", (rva,)).fetchone()
        if not row:
            return None
        rva, length, text, fallthrough, flows, target = row
        extra = tuple(int(x, 16) for x in flows.split(",")) if flows else ()
        return x86.parse(text, rva, length, self.image_base, fallthrough, ((target,) if target is not None else ()) + extra)

    def insn_bytes(self, rva):
        row = self.db.execute("SELECT bytes FROM insn WHERE rva = ?", (rva,)).fetchone()
        return bytes(row[0]) if row else b""

    def callers(self, fn):
        """Functions that call fn directly or through a thunk/stub that resolves to it."""
        targets = {fn} | {src for src in self._thunks_to(fn)}
        out = set()
        for t in targets:
            out.update(r[0] for r in self.db.execute("SELECT fn FROM xref WHERE dst = ? AND kind = 'call' AND fn IS NOT NULL", (t,)))
        return sorted(out)

    def call_sites(self, fn):
        """(call rva, calling function) for every direct call of fn, thunks included."""
        targets = {fn} | set(self._thunks_to(fn))
        out = []
        for t in targets:
            out.extend(self.db.execute("SELECT src, fn FROM xref WHERE dst = ? AND kind = 'call'", (t,)).fetchall())
        return sorted(out)

    def callees(self, fn):
        return sorted({self.resolve(i.target) for i in self.insns(fn) if i.is_call and i.target is not None})

    @lru_cache(maxsize=None)
    def _thunks_to(self, fn):
        rows = self.db.execute("SELECT entry FROM function WHERE is_thunk = 1 AND thunk_target = ?", (fn,)).fetchall()
        stubs = self.db.execute("SELECT i.fn FROM insn i JOIN function f ON f.entry = i.fn WHERE i.target = ? AND i.mnem = 'JMP' "
                                "AND i.rva = f.entry AND f.size <= 8", (fn,)).fetchall()
        return tuple({r[0] for r in rows + stubs if r[0] is not None})

    def string_at(self, rva):
        row = self.db.execute("SELECT value FROM string WHERE rva = ?", (rva,)).fetchone()
        return row[0] if row else self.pe.cstring(rva)

    def strings_equal(self, value):
        return [r[0] for r in self.db.execute("SELECT rva FROM string WHERE value = ?", (value,))]

    def strings_like(self, pattern):
        return self.db.execute("SELECT rva, value FROM string WHERE value LIKE ?", (pattern,)).fetchall()

    def xrefs_to(self, rva, kinds=None):
        """(src insn rva, containing fn, kind) for references to rva."""
        rows = self.db.execute("SELECT src, fn, kind FROM xref WHERE dst = ?", (rva,)).fetchall()
        return [r for r in rows if kinds is None or r[2] in kinds]

    def string_refs(self, value):
        """(src insn rva, fn) for every instruction that references a string with exactly this text."""
        out = []
        for rva in self.strings_equal(value):
            out.extend((src, fn) for src, fn, _ in self.xrefs_to(rva, ("lea", "read")))
        return sorted(out)

    def insns_with_disp(self, disp, mnem=None):
        sql = "SELECT i.rva, i.fn FROM operand o JOIN insn i ON i.rva = o.rva WHERE o.kind = 'mem' AND o.disp = ?"
        args = [disp]
        if mnem:
            sql += " AND i.mnem = ?"
            args.append(mnem)
        return self.db.execute(sql, args).fetchall()

    def insns_with_imm(self, imm, mnem=None):
        sql = "SELECT i.rva, i.fn FROM operand o JOIN insn i ON i.rva = o.rva WHERE o.kind = 'imm' AND o.imm = ?"
        args = [imm]
        if mnem:
            sql += " AND i.mnem = ?"
            args.append(mnem)
        return self.db.execute(sql, args).fetchall()

    def functions(self):
        return list(self._entries)
