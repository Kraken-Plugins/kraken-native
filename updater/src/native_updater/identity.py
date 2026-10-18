"""Build identity (client version + sha256) and the content-addressed binary store."""
import hashlib
import os
import shutil
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from .pe import PE
from .winpaths import windows_home

DEFAULT_INSTALL = Path(r"C:\Program Files (x86)\Jagex Launcher\Games\Old School RuneScape\Client\osclient.exe")


def default_root():
    """KRAKEN_NATIVE_HOME, else .kraken-native in the Windows user's profile (shared by Windows and WSL runs)."""
    return Path(os.environ.get("KRAKEN_NATIVE_HOME") or windows_home() / ".kraken-native")


@dataclass
class BuildIdentity:
    sha256: str
    version: str        # "241.3": major.minor of the exe's file version; names mappings/<version>.json
    timestamp: str      # PE link time


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def identify(path) -> BuildIdentity:
    pe = PE.load(path)
    v = pe.file_version()
    if v is None:
        raise ValueError(f"{path} has no version resource, so there is no version to name the mapping after")
    linked = datetime.fromtimestamp(pe.timestamp, timezone.utc).isoformat().replace("+00:00", "Z")
    return BuildIdentity(sha256_of(path), f"{v[0]}.{v[1]}", linked)


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
