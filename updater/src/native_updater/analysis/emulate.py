"""Concrete execution of a short stretch of one function: what does it compute for chosen inputs?

Some recipes need the arithmetic a block applies to its inputs, which the symbolic provenance drops (NEG, AND, OR,
byte-register writes and clamps through CMOV). `Emulator.run` executes the instructions from one RVA up to another
with concrete register values; the caller decides what each memory load returns (`load`) and what a call does
(`on_call`), and reads back the registers and the stores. Recipes run a block several times with different inputs
and fit what it stores against the inputs, so they don't depend on the exact instruction choice.

Covered: MOV and its extending forms, LEA, ADD, SUB, AND, OR, XOR, NEG, NOT, INC, DEC, the shifts by an immediate,
IMUL, CDQE, CMP, TEST, CMOVcc, SETcc, Jcc and JMP inside the stretch, CALL through `on_call`, NOP. Anything else
stops the run (`stopped` names it), as do a high-byte register (AH, BH, CH, DH), a branch out of the stretch, a
condition on a flag the last instruction left undefined (the carry and overflow after a shift, every flag after IMUL)
and a read of memory the run stored only part of.
"""
import re
from dataclasses import dataclass, field

MASK64 = (1 << 64) - 1
VOLATILE = ("RAX", "RCX", "RDX", "R8", "R9", "R10", "R11")
MAX_STEPS = 2000
_HIGH_BYTE = re.compile(r"\b[ABCD]H\b")


def mask(size):
    return (1 << (8 * size)) - 1


def signed(value, size):
    value &= mask(size)
    top = 1 << (8 * size - 1)
    return value - (1 << (8 * size)) if value & top else value


@dataclass
class Store:
    rva: int
    addr: int
    size: int
    value: int
    op: object


class Stop(Exception):
    """Ends a run: the instruction can't be executed faithfully."""


@dataclass
class Flags:
    """The flags conditions read; None for one the last instruction left undefined."""
    zf: object = False
    sf: object = False
    cf: object = False
    of: object = False


CONDITIONS = {
    "Z": lambda f: f.zf, "E": lambda f: f.zf, "NZ": lambda f: not f.zf, "NE": lambda f: not f.zf,
    "S": lambda f: f.sf, "NS": lambda f: not f.sf, "O": lambda f: f.of, "NO": lambda f: not f.of,
    "L": lambda f: f.sf != f.of, "NGE": lambda f: f.sf != f.of, "GE": lambda f: f.sf == f.of,
    "NL": lambda f: f.sf == f.of, "LE": lambda f: f.zf or f.sf != f.of, "NG": lambda f: f.zf or f.sf != f.of,
    "G": lambda f: not f.zf and f.sf == f.of, "NLE": lambda f: not f.zf and f.sf == f.of,
    "B": lambda f: f.cf, "C": lambda f: f.cf, "NAE": lambda f: f.cf, "AE": lambda f: not f.cf,
    "NC": lambda f: not f.cf, "NB": lambda f: not f.cf, "BE": lambda f: f.cf or f.zf, "NA": lambda f: f.cf or f.zf,
    "A": lambda f: not f.cf and not f.zf, "NBE": lambda f: not f.cf and not f.zf,
}
# The flags each condition reads.
USES = {
    "Z": ("zf",), "E": ("zf",), "NZ": ("zf",), "NE": ("zf",), "S": ("sf",), "NS": ("sf",), "O": ("of",),
    "NO": ("of",), "L": ("sf", "of"), "NGE": ("sf", "of"), "GE": ("sf", "of"), "NL": ("sf", "of"),
    "LE": ("zf", "sf", "of"), "NG": ("zf", "sf", "of"), "G": ("zf", "sf", "of"), "NLE": ("zf", "sf", "of"),
    "B": ("cf",), "C": ("cf",), "NAE": ("cf",), "AE": ("cf",), "NC": ("cf",), "NB": ("cf",), "BE": ("cf", "zf"),
    "NA": ("cf", "zf"), "A": ("cf", "zf"), "NBE": ("cf", "zf"),
}


@dataclass
class Emulator:
    """regs: {64-bit register name: value}; a register never set reads as `default`. load(addr, size, op) returns
    the value a memory read sees (stores made during the run are read back first). on_call(insn, emulator) runs a
    call and returns False to stop."""
    regs: dict
    load: object
    on_call: object = None
    default: int = 0
    stores: list = field(default_factory=list)
    flags: Flags = field(default_factory=Flags)
    stopped: str = None
    memory: dict = field(default_factory=dict)

    def run(self, insns, start, stop):
        """Execute from the instruction at `start` until the one at `stop` (not executed). True if `stop` was
        reached."""
        order = {i.rva: n for n, i in enumerate(insns)}
        if start not in order or stop not in order:
            self.stopped = "the stretch isn't in the function"
            return False
        lo, hi = start, stop
        n = order[start]
        for _ in range(MAX_STEPS):
            insn = insns[n]
            if insn.rva == stop:
                return True
            if _HIGH_BYTE.search(insn.text):
                self.stopped = f"high-byte register at {insn.rva:#x}"
                return False
            try:
                nxt = self.step(insn)
            except Stop as e:
                self.stopped = f"{e} at {insn.rva:#x}"
                return False
            if nxt is False:
                return False
            if nxt is None:
                n += 1
                if n >= len(insns):
                    self.stopped = "ran off the function"
                    return False
            else:
                if not lo <= nxt <= hi or nxt not in order:
                    self.stopped = f"branch out of the stretch at {insn.rva:#x}"
                    return False
                n = order[nxt]
        self.stopped = "too many steps"
        return False

    def holds(self, condition):
        """Whether a condition holds on the flags; stops the run if it reads an undefined flag."""
        if any(getattr(self.flags, f) is None for f in USES[condition]):
            raise Stop(f"condition {condition} on an undefined flag")
        return CONDITIONS[condition](self.flags)

    def reg(self, name, size=8):
        return self.regs.get(name, self.default) & mask(size)

    def set_reg(self, name, size, value):
        value &= mask(size)
        if size >= 4:
            self.regs[name] = value                     # 32-bit writes clear the upper half
        else:
            self.regs[name] = (self.reg(name) & ~mask(size) & MASK64) | value

    def address(self, op):
        addr = op.disp
        if op.base and op.base != "RIP":
            addr += self.reg(op.base)
        if op.index:
            addr += self.reg(op.index) * op.scale
        return addr & MASK64

    def read(self, op, size):
        if op.kind == "imm":
            return op.imm & mask(size)
        if op.kind == "reg":
            return self.reg(op.reg, size)
        addr = self.address(op)
        known = [self.memory.get(addr + i) for i in range(size)]
        if all(b is not None for b in known):
            return sum(b << (8 * i) for i, b in enumerate(known))
        if any(b is not None for b in known):
            raise Stop("a read of memory stored only in part")
        return self.load(addr, size, op) & mask(size)

    def write(self, op, size, value, insn):
        if op.kind == "reg":
            self.set_reg(op.reg, size, value)
            return
        addr = self.address(op)
        value &= mask(size)
        for i in range(size):
            self.memory[addr + i] = (value >> (8 * i)) & 0xff
        self.stores.append(Store(insn.rva, addr, size, value, op))

    def size_of(self, op, other=None):
        if op.size:
            return op.size
        if other is not None and other.size:
            return other.size
        return 8

    def _arith(self, kind, a, b, size):
        m = mask(size)
        top = 1 << (8 * size - 1)
        if kind == "ADD":
            res = (a + b) & m
            self.flags = Flags(res == 0, bool(res & top), a + b > m, bool(~(a ^ b) & (a ^ res) & top))
        elif kind == "SUB":
            res = (a - b) & m
            self.flags = Flags(res == 0, bool(res & top), a < b, bool((a ^ b) & (a ^ res) & top))
        else:
            res = {"AND": a & b, "OR": a | b, "XOR": a ^ b}[kind] & m
            self.flags = Flags(res == 0, bool(res & top), False, False)
        return res

    def step(self, insn):
        """Run one instruction: None to fall through, an RVA to branch to, False to stop."""
        m, ops = insn.mnem, insn.ops
        if m in ("NOP", "ENDBR64"):
            return None
        if m in ("MOV", "MOVABS") and len(ops) == 2:
            size = self.size_of(ops[0], ops[1])
            self.write(ops[0], size, self.read(ops[1], size), insn)
            return None
        if m in ("MOVZX", "MOVSX", "MOVSXD") and len(ops) == 2:
            dst, src = ops
            src_size = self.size_of(src) if src.kind != "reg" else src.size
            value = self.read(src, src_size)
            if m != "MOVZX":
                value = signed(value, src_size)
            self.write(dst, dst.size, value, insn)
            return None
        if m == "LEA" and len(ops) == 2:
            self.write(ops[0], ops[0].size, self.address(ops[1]), insn)
            return None
        if m in ("ADD", "SUB", "AND", "OR", "XOR", "CMP", "TEST") and len(ops) == 2:
            dst, src = ops
            size = self.size_of(dst, src)
            a, b = self.read(dst, size), self.read(src, size)
            kind = {"CMP": "SUB", "TEST": "AND"}.get(m, m)
            res = self._arith(kind, a, b, size)
            if m not in ("CMP", "TEST"):
                self.write(dst, size, res, insn)
            return None
        if m in ("INC", "DEC", "NEG", "NOT") and len(ops) == 1:
            size = self.size_of(ops[0])
            a = self.read(ops[0], size)
            cf = self.flags.cf
            if m == "NOT":
                self.write(ops[0], size, ~a, insn)
                return None
            if m == "NEG":
                res = self._arith("SUB", 0, a, size)
            else:
                res = self._arith("ADD" if m == "INC" else "SUB", a, 1, size)
                self.flags.cf = cf
            self.write(ops[0], size, res, insn)
            return None
        if m in ("SHL", "SAL", "SHR", "SAR") and len(ops) == 2 and ops[1].kind == "imm":
            size = self.size_of(ops[0])
            a = self.read(ops[0], size)
            count = ops[1].imm & (0x3f if size == 8 else 0x1f)
            if m in ("SHL", "SAL"):
                res = a << count
            elif m == "SHR":
                res = a >> count
            else:
                res = signed(a, size) >> count
            res &= mask(size)
            if count:
                self.flags = Flags(res == 0, bool(res & (1 << (8 * size - 1))), None, None)
            self.write(ops[0], size, res, insn)
            return None
        if m == "IMUL" and len(ops) in (2, 3) and ops[0].kind == "reg":
            size = ops[0].size
            a = self.read(ops[0] if len(ops) == 2 else ops[1], size)
            b = self.read(ops[-1], size)
            self.write(ops[0], size, signed(a, size) * signed(b, size), insn)
            self.flags = Flags(None, None, None, None)
            return None
        if m == "CDQE":
            self.set_reg("RAX", 8, signed(self.reg("RAX", 4), 4))
            return None
        if m.startswith("CMOV") and len(ops) == 2 and m[4:] in CONDITIONS:
            if self.holds(m[4:]):
                size = self.size_of(ops[0], ops[1])
                self.write(ops[0], size, self.read(ops[1], size), insn)
            elif ops[0].kind == "reg" and ops[0].size == 4:
                self.set_reg(ops[0].reg, 4, self.reg(ops[0].reg, 4))
            return None
        if m.startswith("SET") and len(ops) == 1 and m[3:] in CONDITIONS:
            self.write(ops[0], 1, 1 if self.holds(m[3:]) else 0, insn)
            return None
        if m == "JMP" and insn.target is not None:
            return insn.target
        if m.startswith("J") and m[1:] in CONDITIONS and insn.target is not None:
            return insn.target if self.holds(m[1:]) else None
        if m == "CALL":
            if self.on_call is None or not self.on_call(insn, self):
                self.stopped = self.stopped or f"call at {insn.rva:#x}"
                return False
            return None
        self.stopped = f"{m} at {insn.rva:#x}"
        return False
