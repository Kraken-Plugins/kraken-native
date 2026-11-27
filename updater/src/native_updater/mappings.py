"""The mappings already written (<dir>/<version>.json, by default the API's bundled ones): which one belongs to a
build, and which one a new build should carry forward from."""
import json
import re
from dataclasses import dataclass
from pathlib import Path

_NAME = re.compile(r"^\d+(\.\d+)*$")


@dataclass
class Entry:
    version: str
    sha256: str
    path: Path


def version_key(version):
    """A version as a tuple that sorts numerically: "242.10" after "242.9"."""
    return tuple(int(p) for p in version.split("."))


def index(mappings_dir):
    """Every <version>.json in the folder, oldest version first."""
    out = []
    for path in Path(mappings_dir).glob("*.json"):
        if not _NAME.match(path.stem):
            continue
        sha = json.loads(path.read_text(encoding="utf-8")).get("sha256", "")
        out.append(Entry(path.stem, sha, path))
    return sorted(out, key=lambda e: version_key(e.version))


def for_build(mappings_dir, sha256):
    """The mapping made from exactly this binary, or None."""
    return next((e for e in index(mappings_dir) if e.sha256 == sha256), None)


def previous(mappings_dir, version, sha256):
    """The mapping a new build carries forward from: the newest one at or below its version made from a different
    binary (the same version again when Jagex rebuilt it), else the oldest one above it. None if there are none."""
    others = [e for e in index(mappings_dir) if e.sha256 != sha256]
    below = [e for e in others if version_key(e.version) <= version_key(version)]
    if below:
        return below[-1]
    return others[0] if others else None
