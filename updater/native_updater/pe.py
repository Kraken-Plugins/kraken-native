"""Minimal PE32+ reader: headers, sections, .pdata and reads by RVA."""
import re
import struct
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Section:
    name: str
    rva: int
    vsize: int
    raw_off: int
    raw_size: int
    characteristics: int

    @property
    def perms(self) -> str:
        c = self.characteristics
        return ("r" if c & 0x40000000 else "-") + ("w" if c & 0x80000000 else "-") + ("x" if c & 0x20000000 else "-")

    def contains(self, rva: int) -> bool:
        return self.rva <= rva < self.rva + max(self.vsize, self.raw_size)


class PE:
    def __init__(self, data: bytes):
        self.data = data
        if data[:2] != b"MZ":
            raise ValueError("not a PE file (missing MZ)")
        pe = struct.unpack_from("<I", data, 0x3C)[0]
        if data[pe:pe + 4] != b"PE\0\0":
            raise ValueError("not a PE file (missing PE signature)")
        machine, count, self.timestamp = struct.unpack_from("<HHI", data, pe + 4)
        if machine != 0x8664:
            raise ValueError("not an AMD64 image")
        opt_size = struct.unpack_from("<H", data, pe + 20)[0]
        opt = pe + 24
        if struct.unpack_from("<H", data, opt)[0] != 0x20B:
            raise ValueError("not PE32+")
        self.entry_rva = struct.unpack_from("<I", data, opt + 16)[0]
        self.image_base = struct.unpack_from("<Q", data, opt + 24)[0]
        self.size_of_image = struct.unpack_from("<I", data, opt + 56)[0]
        dirs = struct.unpack_from("<I", data, opt + 108)[0]
        self.directories = [struct.unpack_from("<II", data, opt + 112 + 8 * i) for i in range(min(dirs, 16))]
        self.sections = []
        for i in range(count):
            o = opt + opt_size + 40 * i
            name = data[o:o + 8].rstrip(b"\0").decode("ascii", "replace")
            vsize, rva, raw_size, raw_off = struct.unpack_from("<IIII", data, o + 8)
            chars = struct.unpack_from("<I", data, o + 36)[0]
            self.sections.append(Section(name, rva, vsize, raw_off, raw_size, chars))

    @classmethod
    def load(cls, path) -> "PE":
        return cls(Path(path).read_bytes())

    def section(self, name: str):
        return next((s for s in self.sections if s.name == name), None)

    def section_of(self, rva: int):
        return next((s for s in self.sections if s.contains(rva)), None)

    def is_code(self, rva: int) -> bool:
        s = self.section_of(rva)
        return s is not None and bool(s.characteristics & 0x20000000)

    def read(self, rva: int, size: int) -> bytes:
        """Bytes as mapped in memory; uninitialised data (past the raw size) reads as zeros."""
        s = self.section_of(rva)
        if s is None:
            raise ValueError(f"RVA {rva:#x} is outside every section")
        start = rva - s.rva
        raw = self.data[s.raw_off + start:s.raw_off + min(s.raw_size, start + size)] if start < s.raw_size else b""
        return raw + b"\0" * (size - len(raw))

    def u32(self, rva: int) -> int:
        return struct.unpack("<I", self.read(rva, 4))[0]

    def u64(self, rva: int) -> int:
        return struct.unpack("<Q", self.read(rva, 8))[0]

    def pointer_rva(self, rva: int):
        """The qword at rva as an RVA when it points into the image (vtables, function tables)."""
        va = self.u64(rva)
        return va - self.image_base if self.image_base <= va < self.image_base + self.size_of_image else None

    def cstring(self, rva: int, limit: int = 1024):
        """A NUL-terminated printable ASCII string at rva, or None."""
        s = self.section_of(rva)
        if s is None or rva - s.rva >= s.raw_size:
            return None
        off = s.raw_off + rva - s.rva
        end = self.data.find(b"\0", off, off + limit)
        if end <= off:
            return None
        raw = self.data[off:end]
        return raw.decode("ascii") if _PRINTABLE.fullmatch(raw) else None

    def strings(self, min_len: int = 4):
        """(rva, text) for every NUL-terminated printable ASCII run in the non-code sections."""
        for s in self.sections:
            if s.characteristics & 0x20000000 or s.name in (".pdata", ".reloc", ".rsrc"):
                continue
            blob = self.data[s.raw_off:s.raw_off + s.raw_size]
            for m in _RUN.finditer(blob):
                if len(m.group(1)) >= min_len:
                    yield s.rva + m.start(1), m.group(1).decode("ascii")

    def pdata(self):
        """(begin, end) RVAs of every RUNTIME_FUNCTION in the exception directory."""
        rva, size = self.directories[3] if len(self.directories) > 3 else (0, 0)
        blob = self.read(rva, size) if size else b""
        return [(b, e) for b, e, _ in struct.iter_unpack("<III", blob[:size - size % 12])]


_PRINTABLE = re.compile(rb"[\x20-\x7e\t\r\n]+")
_RUN = re.compile(rb"(?<![\x20-\x7e])([\x20-\x7e\t]+)\0")
