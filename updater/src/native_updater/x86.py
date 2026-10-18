"""Ghidra x86-64 instruction text (e.g. "MOV EAX,dword ptr [RCX + RAX*0x4 + 0x3350]") to structured operands.

Ghidra prints RIP-relative operands as absolute addresses ("[0x140e98c18]"). The parser keeps them absolute;
the importer turns them into image RVAs with base "RIP".
"""
import re
from dataclasses import dataclass
from typing import Optional

SIZES = {"byte": 1, "word": 2, "dword": 4, "qword": 8, "tword": 10, "xmmword": 16, "ymmword": 32, "zmmword": 64}

# Every general purpose register alias -> (64-bit name, size in bytes).
REGS = {}
for _r in ("AX", "BX", "CX", "DX"):
    REGS["R" + _r] = ("R" + _r, 8)
    REGS["E" + _r] = ("R" + _r, 4)
    REGS[_r] = ("R" + _r, 2)
    REGS[_r[0] + "L"] = ("R" + _r, 1)
    REGS[_r[0] + "H"] = ("R" + _r, 1)
for _r in ("SI", "DI", "SP", "BP"):
    REGS["R" + _r] = ("R" + _r, 8)
    REGS["E" + _r] = ("R" + _r, 4)
    REGS[_r] = ("R" + _r, 2)
    REGS[_r + "L"] = ("R" + _r, 1)
for _n in range(8, 16):
    REGS[f"R{_n}"] = (f"R{_n}", 8)
    REGS[f"R{_n}D"] = (f"R{_n}", 4)
    REGS[f"R{_n}W"] = (f"R{_n}", 2)
    REGS[f"R{_n}B"] = (f"R{_n}", 1)
for _n in range(32):
    REGS[f"XMM{_n}"] = (f"XMM{_n}", 16)
    REGS[f"YMM{_n}"] = (f"XMM{_n}", 32)

ARG_REGS = ("RCX", "RDX", "R8", "R9")                       # Microsoft x64: first four integer arguments
FLOAT_ARG_REGS = ("XMM0", "XMM1", "XMM2", "XMM3")
VOLATILE = ("RAX", "RCX", "RDX", "R8", "R9", "R10", "R11") + tuple(f"XMM{n}" for n in range(6))


@dataclass(frozen=True)
class Op:
    kind: str                     # "reg", "imm" or "mem"
    size: int = 0                 # bytes; 0 when the text does not say
    reg: Optional[str] = None     # reg: canonical 64-bit name (EAX -> RAX)
    imm: Optional[int] = None
    base: Optional[str] = None    # mem: canonical base register, "RIP" for image-relative, None if none
    index: Optional[str] = None
    scale: int = 1
    disp: int = 0                 # mem: displacement (an RVA when base is "RIP")
    seg: Optional[str] = None

    def __str__(self):
        if self.kind == "reg":
            return self.reg
        if self.kind == "imm":
            return hex(self.imm)
        parts = [p for p in (self.base, self.index and f"{self.index}*{self.scale}") if p]
        if self.disp or not parts:
            parts.append(hex(self.disp))
        return f"{self.seg + ':' if self.seg else ''}[{' + '.join(parts)}]"


@dataclass(frozen=True)
class Insn:
    rva: int
    mnem: str                     # base mnemonic without Ghidra's ".LOCK"/".REP" suffixes
    ops: tuple
    length: int = 0
    text: str = ""
    fallthrough: Optional[int] = None
    flows: tuple = ()             # every branch/call target Ghidra knows, including resolved jump tables

    @property
    def is_call(self):
        return self.mnem == "CALL"

    @property
    def is_ret(self):
        return self.mnem == "RET"

    @property
    def is_jump(self):
        return self.mnem == "JMP"

    @property
    def is_cond_jump(self):
        return self.mnem.startswith("J") and self.mnem != "JMP"

    @property
    def target(self):
        """Direct branch or call target (an absolute address as printed; RVA after import)."""
        if (self.is_call or self.mnem.startswith("J")) and self.ops and self.ops[0].kind == "imm":
            return self.ops[0].imm
        return None


_MEM = re.compile(r"^(?:(\w+) ptr )?(?:(\w+):)?\[(.*)\]$")


def parse_operand(text: str) -> Op:
    text = text.strip()
    m = _MEM.match(text)
    if m:
        base = index = None
        scale, disp = 1, 0
        for term in m.group(3).split(" + "):
            if "*" in term:
                reg, factor = term.split("*")
                index, scale = REGS.get(reg, (reg, 0))[0], int(factor, 16)
            elif term[0].isdigit() or term[0] == "-":
                disp += int(term, 16)
            else:
                base = REGS.get(term, (term, 0))[0]
        return Op("mem", size=SIZES.get(m.group(1), 0), base=base, index=index, scale=scale, disp=disp, seg=m.group(2))
    if text[0].isdigit() or text[0] == "-":
        return Op("imm", imm=int(text, 16))
    name, size = REGS.get(text, (text, 0))
    return Op("reg", size=size, reg=name)


def parse(text: str, rva: int = 0, length: int = 0, image_base: int = 0, fallthrough=None, flows=()) -> Insn:
    """Parse one Ghidra instruction line; absolute addresses become RVAs when image_base is given."""
    head, _, rest = text.partition(" ")
    mnem = head.split(".")[0]
    ops = tuple(parse_operand(o) for o in rest.split(",")) if rest else ()
    if image_base:
        ops = tuple(_rebase(op, mnem, image_base) for op in ops)
    return Insn(rva, mnem, ops, length, text, fallthrough, tuple(flows))


def _rebase(op: Op, mnem: str, image_base: int) -> Op:
    if op.kind == "mem" and op.base is None and op.index is None and op.seg is None and op.disp >= image_base:
        return Op("mem", size=op.size, base="RIP", disp=op.disp - image_base)
    if op.kind == "imm" and (mnem == "CALL" or mnem.startswith("J")) and op.imm >= image_base:
        return Op("imm", imm=op.imm - image_base)
    return op


def reg_size(name: str) -> int:
    return REGS.get(name, (name, 0))[1]
