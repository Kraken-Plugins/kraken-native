"""Forward abstract interpretation over one function: what does each register and stack slot hold?

The values form a small symbolic language. Extractors ask structural questions about them ("which loads of
4 bytes have the Actor pointer as their base?") instead of matching raw bytes, so recipes survive register
reallocation and instruction reordering between builds.

  Const(n)                  integer constant
  Global(rva)               address of a global (LEA reg,[rip+X])
  Arg(i)                    i-th integer argument at entry: RCX, RDX, R8, R9, then [RSP+0x28], [RSP+0x30], ...
  StackAddr(off)            address of a stack slot (off relative to RSP at entry)
  Ret(rva, target)          value returned by the call at rva
  Out(rva, i)               an out-parameter written by the call at rva through argument i (a stack address)
  Deref(base, off, size, index, scale)   load from [base + off + index*scale]
  Add(base, off)            base + off
  Mul(base, factor)         base * factor (factor is a value, usually Const)
  Many(values)              one of several values (merge at a control-flow join, capped at 4)
  Unknown()

Facts collected per function (all with the instruction RVA): loads, stores, calls (with arguments), compares,
returns and address computations. Small leaf callees (getters that return one field) are summarised and
substituted into the caller, so `CALL actor_interacting` shows up as a load of [actor + 0x3A8].
"""
from dataclasses import dataclass, field
from typing import Optional, Tuple

from .. import x86

ARG_REGS = x86.ARG_REGS
VOLATILE = set(x86.VOLATILE) | {"RAX"}
MANY_CAP = 4
LEAF_MAX_INSNS = 14
OUT_PARAM_SPAN = 0x40


@dataclass(frozen=True)
class Value:
    def members(self):
        return (self,)


@dataclass(frozen=True)
class Const(Value):
    n: int

    def __repr__(self):
        return hex(self.n) if abs(self.n) >= 10 else str(self.n)


@dataclass(frozen=True)
class Global(Value):
    rva: int

    def __repr__(self):
        return f"Global({self.rva:#x})"


@dataclass(frozen=True)
class Arg(Value):
    i: int

    def __repr__(self):
        return f"Arg{self.i}"


@dataclass(frozen=True)
class StackAddr(Value):
    off: int

    def __repr__(self):
        return f"Stack({self.off:#x})"


@dataclass(frozen=True)
class Ret(Value):
    rva: int
    target: Optional[int]

    def __repr__(self):
        return f"Ret({self.target:#x})" if self.target is not None else f"Ret(@{self.rva:#x})"


@dataclass(frozen=True)
class Out(Value):
    rva: int
    i: int
    target: Optional[int] = None

    def __repr__(self):
        return f"Out({self.target:#x}, arg{self.i})" if self.target is not None else f"Out(@{self.rva:#x}, arg{self.i})"


@dataclass(frozen=True)
class Deref(Value):
    base: Value
    off: int = 0
    size: int = 0
    index: Optional[Value] = None
    scale: int = 1

    def __repr__(self):
        s = f"[{self.base!r}"
        if self.index is not None:
            s += f" + {self.index!r}*{self.scale}"
        if self.off:
            s += f" {'+' if self.off >= 0 else '-'} {abs(self.off):#x}"
        return s + f"]{self.size or ''}"


@dataclass(frozen=True)
class Add(Value):
    base: Value
    off: int

    def __repr__(self):
        return f"({self.base!r} {'+' if self.off >= 0 else '-'} {abs(self.off):#x})"


@dataclass(frozen=True)
class Mul(Value):
    base: Value
    factor: Value

    def __repr__(self):
        return f"({self.base!r} * {self.factor!r})"


@dataclass(frozen=True)
class Bin(Value):
    """a <op> b for operations we do not fold (register/register or register/memory ADD and SUB)."""
    op: str
    a: Value
    b: Value

    def __repr__(self):
        return f"({self.a!r} {self.op} {self.b!r})"


@dataclass(frozen=True)
class Many(Value):
    values: Tuple[Value, ...]

    def members(self):
        return self.values

    def __repr__(self):
        return "{" + " | ".join(repr(v) for v in self.values) + "}"


@dataclass(frozen=True)
class Unknown(Value):
    def __repr__(self):
        return "?"


UNKNOWN = Unknown()


def is_unknown(v):
    return v is None or isinstance(v, Unknown)


def many(values):
    """Flatten and deduplicate; collapse to Unknown beyond the cap. Unknown members are dropped: at a join
    where one path knows the value and another does not, the known candidates are what extractors want."""
    flat = []
    for v in values:
        for m in (v.members() if v is not None else (UNKNOWN,)):
            if is_unknown(m):
                continue
            if m not in flat:
                flat.append(m)
    if not flat:
        return UNKNOWN
    if len(flat) == 1:
        return flat[0]
    if len(flat) > MANY_CAP:
        return UNKNOWN
    return Many(tuple(flat))


def add(v, off):
    if off == 0 or is_unknown(v):
        return v
    if isinstance(v, Const):
        return Const(v.n + off)
    if isinstance(v, Add):
        return add(v.base, v.off + off)
    if isinstance(v, StackAddr):
        return StackAddr(v.off + off)
    if isinstance(v, Many):
        return many(add(m, off) for m in v.values)
    return Add(v, off)


def deref(base, off, size=0, index=None, scale=1):
    """Build a Deref, folding an Add base into the offset: [(p + a) + b] is [p + a + b]."""
    if isinstance(base, Add):
        base, off = base.base, off + base.off
    if isinstance(base, Many) and all(isinstance(m, Add) for m in base.values):
        return many(deref(m, off, size, index, scale) for m in base.values)
    return Deref(base, off, size, index, scale)


def subst(v, args):
    """Replace Arg(i) by args[i] (used when inlining a leaf callee's summary)."""
    if isinstance(v, Arg):
        return args[v.i] if v.i < len(args) and args[v.i] is not None else UNKNOWN
    if isinstance(v, Deref):
        base = subst(v.base, args)
        index = subst(v.index, args) if v.index is not None else None
        return UNKNOWN if is_unknown(base) else deref(base, v.off, v.size, index, v.scale)
    if isinstance(v, Add):
        return add(subst(v.base, args), v.off)
    if isinstance(v, Mul):
        return Mul(subst(v.base, args), subst(v.factor, args))
    if isinstance(v, Bin):
        return Bin(v.op, subst(v.a, args), subst(v.b, args))
    if isinstance(v, Many):
        return many(subst(m, args) for m in v.values)
    return v


def walk(v):
    """Every sub-value of v, including v."""
    yield v
    if isinstance(v, Deref):
        yield from walk(v.base)
        if v.index is not None:
            yield from walk(v.index)
    elif isinstance(v, (Add, Mul)):
        yield from walk(v.base)
        if isinstance(v, Mul):
            yield from walk(v.factor)
    elif isinstance(v, Bin):
        yield from walk(v.a)
        yield from walk(v.b)
    elif isinstance(v, Many):
        for m in v.values:
            yield from walk(m)


def contains(container, needle):
    """True when needle (or any Many member of it) occurs inside container."""
    targets = set(needle.members())
    return any(sub in targets for sub in walk(container))


@dataclass(frozen=True)
class Load:
    rva: int
    value: Deref
    dest: Optional[str]          # destination register, None when the load feeds a CMP/TEST/arith operand
    fn: int                      # function the instruction belongs to (differs from the analysed fn when inlined)


@dataclass(frozen=True)
class Store:
    rva: int
    target: Deref                # where (as a Deref describing the slot); value is what was written
    value: Value
    fn: int


@dataclass(frozen=True)
class Call:
    rva: int
    target: Optional[int]        # resolved direct target, None for indirect calls
    via: Optional[Value]         # the value called through for indirect calls, e.g. Deref([this], 0x28)
    args: Tuple[Value, ...]
    fn: int


@dataclass(frozen=True)
class Cmp:
    rva: int
    a: Value
    b: Value
    fn: int


@dataclass(frozen=True)
class Return:
    rva: int
    value: Value
    fn: int


@dataclass(frozen=True)
class Addr:
    rva: int
    value: Value                 # an Add / Mul / Deref-with-index computed by LEA or ADD
    dest: str
    fn: int


@dataclass
class Facts:
    fn: int
    rvas: set = field(default_factory=set)
    loads: list = field(default_factory=list)
    stores: list = field(default_factory=list)
    calls: list = field(default_factory=list)
    cmps: list = field(default_factory=list)
    returns: list = field(default_factory=list)
    addrs: list = field(default_factory=list)
    divs: list = field(default_factory=list)        # Cmp records whose `a` is a DIV/IDIV/MUL operand
    states: dict = field(default_factory=dict)      # rva -> state before the instruction (for debugging)

    def values(self):
        """Every value mentioned by any fact, with the fact it came from."""
        for l in self.loads:
            yield l.value, l
        for s in self.stores:
            yield s.value, s
            yield s.target, s
        for c in self.calls:
            for a in c.args:
                yield a, c
            if c.via is not None:
                yield c.via, c
        for c in self.cmps:
            yield c.a, c
            yield c.b, c
        for r in self.returns:
            yield r.value, r
        for a in self.addrs:
            yield a.value, a
        for d in self.divs:
            yield d.a, d

    def derefs(self):
        """Every Deref appearing anywhere (loads, bases of other loads, stored values...), deduplicated by rva+value."""
        seen = set()
        for v, fact in self.values():
            for sub in walk(v):
                if isinstance(sub, Deref) and (fact.rva, sub) not in seen:
                    seen.add((fact.rva, sub))
                    yield sub, fact


class State:
    __slots__ = ("regs", "stack", "sp", "dirty")

    def __init__(self, regs=None, stack=None, sp=0, dirty=None):
        self.regs = dict(regs or {})
        self.stack = dict(stack or {})
        self.sp = sp
        self.dirty = set(dirty or ())

    def copy(self):
        return State(self.regs, self.stack, self.sp, self.dirty)

    @staticmethod
    def entry():
        s = State()
        for i, r in enumerate(ARG_REGS):
            s.regs[r] = Arg(i)
        return s

    def merge(self, other):
        out = State(sp=self.sp)
        for k in set(self.regs) | set(other.regs):
            v = many((self.regs.get(k, UNKNOWN), other.regs.get(k, UNKNOWN)))
            if not is_unknown(v):
                out.regs[k] = v
        for k in set(self.stack) | set(other.stack):
            v = many((self.stack.get(k, UNKNOWN), other.stack.get(k, UNKNOWN)))
            if not is_unknown(v):
                out.stack[k] = v
        out.dirty = self.dirty | other.dirty
        return out

    def reg(self, name):
        return self.regs.get(name, UNKNOWN)

    def slot(self, off):
        """Value of the entry-relative stack slot; untouched incoming-argument slots read as Arg(4+)."""
        if off in self.stack:
            return self.stack[off]
        if off >= 0x28 and off % 8 == 0:
            return Arg(4 + (off - 0x28) // 8)
        return UNKNOWN


class Provenance:
    """Runs the interpreter over functions of a ProgramModel and caches results."""

    def __init__(self, model):
        self.model = model
        self._facts = {}
        self._summaries = {}

    def facts(self, fn, inline=True) -> Facts:
        key = (fn, inline)
        if key not in self._facts:
            self._facts[key] = self._analyse(fn, inline)
        return self._facts[key]

    def summary(self, fn):
        """Return value of a small leaf function in terms of its arguments, or None if it is not a simple getter."""
        if fn not in self._summaries:
            self._summaries[fn] = None          # guard against recursion
            self._summaries[fn] = self._summarise(fn)
        return self._summaries[fn]

    def _summarise(self, fn):
        f = self.model.function(fn)
        if f is None or f.size > LEAF_MAX_INSNS * 8:
            return None
        body = self.model.insns(fn)
        if not body or len(body) > LEAF_MAX_INSNS or any(i.is_call for i in body):
            return None
        facts = self._analyse(fn, inline=False)
        rets = [r.value for r in facts.returns]
        if not rets:
            return None
        v = many(rets)
        if is_unknown(v) or not any(isinstance(s, Arg) for s in walk(v)):
            return None
        return facts, v

    def _analyse(self, fn, inline) -> Facts:
        model = self.model
        body = model.insns(fn)
        facts = Facts(fn)
        if not body:
            return facts
        rvas = [i.rva for i in body]
        index = {rva: k for k, rva in enumerate(rvas)}
        preds = {rva: [] for rva in rvas}
        for i in body:
            targets = set(i.flows)
            if i.fallthrough is not None and not i.is_jump:
                targets.add(i.fallthrough)
            for t in targets:
                if t in preds:
                    preds[t].append(i.rva)
        out_states = {}
        prev_sp = 0
        facts.rvas = set(rvas)
        for k, insn in enumerate(body):
            incoming = [out_states[p] for p in preds[insn.rva] if p in out_states]
            if k == 0:
                state = State.entry()
            elif incoming:
                state = incoming[0].copy()
                for s in incoming[1:]:
                    state = state.merge(s)
            else:
                state = State(sp=prev_sp)                 # reached only by back edges we have not seen
            facts.states[insn.rva] = state
            state = state.copy()
            self._step(insn, state, facts, fn, inline)
            out_states[insn.rva] = state
            prev_sp = state.sp
        return facts

    def _mem(self, op, state):
        """(base value, entry-relative stack offset or None, index value) for a memory operand."""
        if op.base == "RIP":
            return Global(op.disp), None, None
        if op.base == "RSP":
            base = StackAddr(state.sp)
        else:
            base = state.reg(op.base) if op.base else None
        index = state.reg(op.index) if op.index else None
        if base is None and index is None:
            return Const(op.disp), None, None
        if base is None:
            return None, None, index
        for m in base.members():
            if isinstance(m, StackAddr) and index is None:
                return base, m.off + op.disp, None
        return base, None, index

    def _deref(self, op, state, size=None):
        base, slot, index = self._mem(op, state)
        if slot is not None:
            return state.slot(slot), slot
        if (base is None or is_unknown(base)) and index is not None and not is_unknown(index) and op.scale == 1:
            base, index = index, UNKNOWN        # [unknown + known*1]: keep the known part as the base
        if isinstance(base, Const) and index is not None and not is_unknown(index) and op.scale == 1:
            return Deref(index, op.disp + base.n, size or op.size), None     # [0 + ptr*1]: the index is the pointer
        if base is None or is_unknown(base):
            return UNKNOWN, None
        if isinstance(base, Global) and index is None:
            return Deref(base, 0, size or op.size), None
        return deref(base, op.disp, size or op.size, index, op.scale if index is not None else 1), None

    # Registers invalidated by a call. The client is built with whole-program optimisation and its code reads
    # "volatile" registers after calls whenever the callee happens to preserve them; a compiler only emits such
    # a read when the value really survives, so the sound choice is to keep everything except the return registers.
    CALL_CLOBBERS = ("RAX", "XMM0")

    def _addr(self, op, state):
        base, slot, index = self._mem(op, state)
        if slot is not None:
            return StackAddr(slot)
        if base is None and index is not None:
            return Mul(index, op.scale) if not is_unknown(index) else UNKNOWN
        if base is None or is_unknown(base):
            return UNKNOWN
        if isinstance(base, Global):
            return base
        if index is not None:
            if is_unknown(index):
                return UNKNOWN
            if index == base and op.disp == 0:
                return Mul(base, Const(op.scale + 1))
            return Deref(base, op.disp, 0, index, op.scale)       # address-only; marked by size 0
        return add(base, op.disp)

    def _value(self, op, state, size=None):
        """Value of a source operand."""
        if op.kind == "imm":
            return Const(op.imm)
        if op.kind == "reg":
            return state.reg(op.reg)
        v, _ = self._deref(op, state, size)
        return v

    def _write(self, op, value, state, facts, insn, fn):
        """Write to a destination operand (register or memory)."""
        if op.kind == "reg":
            if is_unknown(value):
                state.regs.pop(op.reg, None)
            else:
                state.regs[op.reg] = value
            return
        base, slot, index = self._mem(op, state)
        if slot is not None:
            if is_unknown(value):
                state.stack.pop(slot, None)
            else:
                state.stack[slot] = value
            state.dirty.add(slot)
            return
        if base is None or is_unknown(base):
            return
        target = Deref(base, 0 if isinstance(base, Global) else op.disp, op.size or x86.reg_size(getattr(op, "reg", "") or ""),
                       index, op.scale if index is not None else 1)
        facts.stores.append(Store(insn.rva, target, value, fn))

    def _note_load(self, op, value, dest, state, facts, insn, fn):
        if isinstance(value, Deref) and op.kind == "mem":
            facts.loads.append(Load(insn.rva, value, dest, fn))

    def _step(self, insn, state, facts, fn, inline):
        m = insn.mnem
        ops = insn.ops
        if m in ("NOP", "INT3", "HLT", "PAUSE", "LFENCE", "MFENCE", "SFENCE", "ENDBR64"):
            return
        if m == "PUSH":
            state.sp -= 8
            v = self._value(ops[0], state) if ops else UNKNOWN
            if not is_unknown(v):
                state.stack[state.sp] = v
            return
        if m == "POP":
            v = state.stack.get(state.sp, UNKNOWN)
            state.sp += 8
            if ops and ops[0].kind == "reg":
                self._write(ops[0], v, state, facts, insn, fn)
            return
        if m == "RET":
            facts.returns.append(Return(insn.rva, state.reg("RAX"), fn))
            return
        if m == "CALL":
            self._call(insn, state, facts, fn, inline)
            return
        if m == "JMP" and insn.target is not None and insn.target not in facts.rvas and self.model.function(insn.target) is not None:
            self._call(insn, state, facts, fn, inline)           # tail call: the callee's result is ours
            facts.returns.append(Return(insn.rva, state.reg("RAX"), fn))
            return
        if m.startswith("J") or m in ("LOOP",):
            return
        if m in ("CMP", "TEST", "COMISS", "UCOMISS", "COMISD", "UCOMISD") and len(ops) == 2:
            a = self._value(ops[0], state)
            b = self._value(ops[1], state)
            self._note_load(ops[0], a, None, state, facts, insn, fn)
            self._note_load(ops[1], b, None, state, facts, insn, fn)
            facts.cmps.append(Cmp(insn.rva, a, b, fn))
            return
        if m in ("MOV", "MOVSXD", "MOVZX", "MOVSX", "MOVUPS", "MOVAPS", "MOVDQU", "MOVDQA", "MOVSD", "MOVSS", "MOVD", "MOVQ",
                 "MOVABS", "MOVBE", "CVTDQ2PS", "CVTTSD2SI", "CVTSI2SD", "CVTSS2SD", "CVTSD2SS", "CVTSI2SS", "CVTTSS2SI", "CVTPS2PD") and len(ops) == 2:
            dst, src = ops
            size = dst.size or (x86.reg_size(dst.reg) if dst.kind == "reg" else 0)
            if src.kind == "mem":
                v, _ = self._deref(src, state, src.size or (size if m == "MOV" else 0))
                self._note_load(src, v, dst.reg if dst.kind == "reg" else None, state, facts, insn, fn)
            else:
                v = self._value(src, state)
            if m.startswith("CVT"):
                v = UNKNOWN
            if dst.kind == "reg" and m == "MOV" and src.kind == "reg" and dst.reg == "RSP" and isinstance(v, StackAddr):
                state.sp = v.off
            if dst.kind == "reg" and src.kind == "reg" and src.reg == "RSP":
                v = StackAddr(state.sp)
            self._write(dst, v, state, facts, insn, fn)
            return
        if m == "LEA" and len(ops) == 2:
            v = self._addr(ops[1], state)
            if ops[1].base == "RSP":
                v = StackAddr(state.sp + ops[1].disp)
            self._write(ops[0], v, state, facts, insn, fn)
            if not is_unknown(v) and not isinstance(v, (StackAddr, Const)):
                facts.addrs.append(Addr(insn.rva, v, ops[0].reg, fn))
            return
        if m in ("ADD", "SUB") and len(ops) == 2:
            dst, src = ops
            if dst.kind == "reg" and dst.reg == "RSP" and src.kind == "imm":
                state.sp += src.imm if m == "ADD" else -src.imm
                return
            a = self._value(dst, state)
            b = self._value(src, state)
            self._note_load(src, b, None, state, facts, insn, fn)
            if isinstance(b, Const):
                v = add(a, b.n if m == "ADD" else -b.n)
            elif isinstance(a, Const) and m == "ADD":
                v = add(b, a.n)
            elif is_unknown(a) or is_unknown(b):
                v = UNKNOWN
            else:
                v = Bin("+" if m == "ADD" else "-", a, b)
            if dst.kind == "reg":
                self._write(dst, v, state, facts, insn, fn)
                if isinstance(v, (Add, Bin)):
                    facts.addrs.append(Addr(insn.rva, v, dst.reg, fn))
            return
        if m in ("XOR", "XORPS", "PXOR", "XORPD") and len(ops) == 2 and ops[0].kind == "reg" and ops[1].kind == "reg" and ops[0].reg == ops[1].reg:
            self._write(ops[0], Const(0), state, facts, insn, fn)
            return
        if m in ("INC", "DEC") and ops and ops[0].kind == "reg":
            self._write(ops[0], add(self._value(ops[0], state), 1 if m == "INC" else -1), state, facts, insn, fn)
            return
        if m in ("SHL", "SAL") and len(ops) == 2 and ops[0].kind == "reg" and ops[1].kind == "imm":
            v = self._value(ops[0], state)
            self._write(ops[0], Mul(v, Const(1 << ops[1].imm)) if not is_unknown(v) else UNKNOWN, state, facts, insn, fn)
            return
        if m in ("SHR", "SAR") and len(ops) == 2 and ops[0].kind == "reg" and ops[1].kind == "imm":
            v = self._value(ops[0], state)
            self._write(ops[0], Bin(">>", v, Const(ops[1].imm)) if not is_unknown(v) else UNKNOWN, state, facts, insn, fn)
            return
        if m == "IMUL" and len(ops) >= 2 and ops[0].kind == "reg":
            a = self._value(ops[0] if len(ops) == 2 else ops[1], state)
            b = self._value(ops[-1], state)
            self._note_load(ops[-1], b, None, state, facts, insn, fn)
            v = Mul(a, b) if not (is_unknown(a) or is_unknown(b)) else UNKNOWN
            self._write(ops[0], v, state, facts, insn, fn)
            if isinstance(v, Mul):
                facts.addrs.append(Addr(insn.rva, v, ops[0].reg, fn))
            return
        if m.startswith("CMOV") and len(ops) == 2 and ops[0].kind == "reg":
            # dst = cond ? src : dst, so afterwards it holds one of the two (e.g. a lookup's "found ? node : end").
            # Only when both are known; otherwise unknown, as before.
            dst, src = ops
            old = self._value(dst, state)
            if src.kind == "mem":
                new, _ = self._deref(src, state, src.size or x86.reg_size(dst.reg))
                self._note_load(src, new, None, state, facts, insn, fn)
            else:
                new = self._value(src, state)
            v = UNKNOWN if is_unknown(old) or is_unknown(new) else many([old, new])
            self._write(dst, v, state, facts, insn, fn)
            return
        if m in ("AND", "OR", "SAR", "SHR", "NEG", "NOT", "BT", "BTS", "BTR", "XCHG", "XADD", "CMPXCHG", "SETZ", "SETNZ",
                 "CDQE", "CDQ", "CQO", "MOVSXD", "SBB", "ADC", "ROL", "ROR", "DIV", "IDIV", "MUL"):
            # Reads still count as loads (e.g. AND EDI,[R10+R9*4]); results are unknown.
            for op in ops:
                if op.kind == "mem":
                    v, _ = self._deref(op, state)
                    self._note_load(op, v, None, state, facts, insn, fn)
            if m in ("DIV", "IDIV", "MUL"):
                if ops:
                    facts.divs.append(Cmp(insn.rva, self._value(ops[-1], state), Const(0), fn))
                for r in ("RAX", "RDX"):
                    state.regs.pop(r, None)
            if ops and ops[0].kind == "reg":
                self._write(ops[0], UNKNOWN, state, facts, insn, fn)
            if m == "XADD" and len(ops) == 2 and ops[1].kind == "reg":
                self._write(ops[1], UNKNOWN, state, facts, insn, fn)
            return
        # Generic fallback: memory sources are loads, the first operand is clobbered.
        for op in ops[1:]:
            if op.kind == "mem":
                v, _ = self._deref(op, state)
                self._note_load(op, v, None, state, facts, insn, fn)
        if ops:
            if ops[0].kind == "reg":
                self._write(ops[0], UNKNOWN, state, facts, insn, fn)
            elif ops[0].kind == "mem" and m not in ("PREFETCHT0", "PREFETCHW", "PREFETCHNTA"):
                self._write(ops[0], UNKNOWN, state, facts, insn, fn)

    def _call(self, insn, state, facts, fn, inline):
        model = self.model
        target = model.resolve(insn.target) if insn.target is not None else None
        via = None
        if target is None and insn.ops and insn.ops[0].kind == "mem":
            via, _ = self._deref(insn.ops[0], state)
        elif target is None and insn.ops and insn.ops[0].kind == "reg":
            via = state.reg(insn.ops[0].reg)
        args = [state.reg(r) for r in ARG_REGS]
        for j in range(4):
            slot = state.sp + 0x20 + 8 * j
            if slot in state.dirty and slot in state.stack:
                args.append(state.stack[slot])
            else:
                break
        args = tuple(args)
        facts.calls.append(Call(insn.rva, target, via, args, fn))

        ret = Ret(insn.rva, target)
        summary = self.summary(target) if (inline and target is not None) else None
        if summary is not None:
            leaf_facts, value = summary
            ret = subst(value, args)
            for l in leaf_facts.loads:
                v = subst(l.value, args)
                if isinstance(v, Deref):
                    facts.loads.append(Load(l.rva, v, l.dest, l.fn))
            for c in leaf_facts.cmps:
                facts.cmps.append(Cmp(c.rva, subst(c.a, args), subst(c.b, args), c.fn))
        for r in self.CALL_CLOBBERS:
            state.regs.pop(r, None)
        state.regs["RAX"] = ret
        # Stack addresses passed as arguments are out-parameters: the callee may have filled them.
        for i, a in enumerate(args):
            for member in a.members():
                if isinstance(member, StackAddr):
                    for off in range(member.off, member.off + OUT_PARAM_SPAN, 8):
                        state.stack[off] = Deref(Out(insn.rva, i, target), off - member.off, 8)
        state.dirty.clear()
