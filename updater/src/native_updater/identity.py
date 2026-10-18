"""Build identity and the content-addressed binary store."""
import hashlib
import os
import re
import shutil
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from pathlib import Path

from .pe import PE

DEFAULT_INSTALL = Path(r"C:\Program Files (x86)\Jagex Launcher\Games\Old School RuneScape\Client\osclient.exe")
TAG_RE = re.compile(rb"OSRS-[A-Z0-9]+-[A-Z0-9]+")


def default_root():
    return Path(os.environ.get("KRAKEN_NATIVE_HOME", Path.home() / ".kraken-native"))


@dataclass
class BuildIdentity:
    sha256: str
    path: str
    timestamp: str
    size_of_image: int
    sections: list = field(default_factory=list)
    tag: str = ""
    client_version: str = None

    @property
    def prefix(self):
        return self.sha256[:16]

    def to_json(self):
        d = asdict(self)
        d["sizeOfImage"] = f"{self.size_of_image:#x}"
        d["clientVersion"] = d.pop("client_version")
        d.pop("size_of_image")
        return d


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def identify(path, model=None) -> BuildIdentity:
    pe = PE.load(path)
    tags = sorted({m.group(0).decode() for m in TAG_RE.finditer(pe.data)})
    ident = BuildIdentity(sha256_of(path), str(path), datetime.fromtimestamp(pe.timestamp, timezone.utc).isoformat().replace("+00:00", "Z"),
                          pe.size_of_image, [{"name": s.name, "rva": f"{s.rva:#x}", "vsize": f"{s.vsize:#x}", "perms": s.perms} for s in pe.sections],
                          tags[0] if tags else "")
    if model is not None:
        ident.client_version = _client_version(model)
    return ident


def _client_version(model):
    """Best effort: the two integer arguments next to the "Client version: %d.%d" format string."""
    from .analysis.provenance import Const, Global
    for insn_rva, fn in model.string_refs("Client version: %d.%d"):
        if fn is None:
            continue
        from .analysis.provenance import Provenance
        facts = Provenance(model).facts(fn)
        for c in facts.calls:
            if c.rva > insn_rva and any(isinstance(a, Global) for a in c.args):
                ints = [a.n for a in c.args if isinstance(a, Const)]
                if len(ints) >= 2:
                    return f"{ints[0]}.{ints[1]}"
            if c.rva > insn_rva + 64:
                break
    return None


class BinaryStore:
    """<root>/store/<sha256>/osclient.exe, kept outside git."""

    def __init__(self, root=None):
        self.root = Path(root or default_root()) / "store"

    def path(self, sha):
        return self.root / sha / "osclient.exe"

    def archive(self, path):
        sha = sha256_of(path)
        dst = self.path(sha)
        if not dst.exists():
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, dst)
        return sha, dst

    def builds(self):
        return sorted(p.name for p in self.root.glob("*") if (p / "osclient.exe").exists())
