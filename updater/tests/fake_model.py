"""A ProgramModel stand-in built from Ghidra-style instruction text, for unit tests without a binary."""
from native_updater import x86
from native_updater.model import Function

IMAGE_BASE = 0x140000000


class FakePE:
    def __init__(self, code_ranges=((0x1000, 0x800000),)):
        self.code_ranges = code_ranges

    def is_code(self, rva):
        return any(lo <= rva < hi for lo, hi in self.code_ranges)


class FakeModel:
    """functions: {entry_rva: ["MOV RAX,qword ptr [0x140e98c18]", ...]} with 4-byte instructions laid out in order."""

    def __init__(self, functions, strings=None):
        self.image_base = IMAGE_BASE
        self.pe = FakePE()
        self._insns = {}
        self._functions = {}
        self._strings = strings or {}
        for entry, lines in functions.items():
            rva = entry
            out = []
            for k, text in enumerate(lines):
                length = 4
                fallthrough = rva + length if k + 1 < len(lines) else None
                insn = x86.parse(text, rva, length, IMAGE_BASE, fallthrough, ())
                if insn.target is not None and insn.mnem.startswith("J"):
                    insn = x86.Insn(insn.rva, insn.mnem, insn.ops, length, text, fallthrough if insn.mnem != "JMP" else None, (insn.target,))
                out.append(insn)
                rva += length
            self._insns[entry] = tuple(out)
            self._functions[entry] = Function(entry, f"FUN_{entry:x}", rva, rva - entry, 0, None, 1)

    def insns(self, fn):
        return self._insns.get(fn, ())

    def insn(self, rva):
        for body in self._insns.values():
            for i in body:
                if i.rva == rva:
                    return i
        return None

    def function(self, rva):
        return self._functions.get(rva)

    def function_containing(self, rva):
        for entry, body in self._insns.items():
            if body and body[0].rva <= rva < body[-1].rva + 4:
                return entry
        return None

    def resolve(self, rva):
        return rva

    def string_at(self, rva):
        return self._strings.get(rva)

    def call_sites(self, fn):
        return [(i.rva, entry) for entry, body in self._insns.items() for i in body if i.is_call and i.target == fn]

    def xrefs_to(self, rva, kinds=None):
        """LEA references only: (src, containing function, "lea")."""
        refs = [(i.rva, entry, "lea") for entry, body in self._insns.items() for i in body
                if i.mnem == "LEA" and len(i.ops) == 2 and i.ops[1].base == "RIP" and i.ops[1].disp == rva]
        return [r for r in refs if kinds is None or r[2] in kinds]
