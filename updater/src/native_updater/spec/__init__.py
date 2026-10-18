"""The spec registry: YAML files describing binding tables, fingerprints, keys, capabilities.

A key entry:
  - key: Actor.interacting          # Struct.field, global.name, functions.name, enums.name, container.x.y
    type: i32
    priority: P0                    # P0 | P1 | P2
    strategies:                     # one-entry mappings {kind: params}; see strategies.py
      - getter: { in: osrs.Entity.isInteracting, base: sp_result, load: i32, select: { returned: true } }
    invariants:                     # see verify.py
      - adjacent: { key: Actor.interactingValid, delta: -1 }
    live: interacting               # name of the live-probe check (required for P0)
    doc: free text
Carry-forward and delta propagation apply to every key automatically when a previous mapping is supplied.
"""
import hashlib
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from ..session import CLASS_OF, STRONG_CLASSES

SPEC_DIR = Path(__file__).parent


@dataclass
class KeySpec:
    key: str
    type: str = "i32"
    priority: str = "P1"
    strategies: list = field(default_factory=list)      # [(kind, params)]
    invariants: list = field(default_factory=list)
    live: str = ""
    doc: str = ""
    alias: bool = False                                  # may share an offset with another field of the struct

    @property
    def struct(self):
        head = self.key.split(".")[0]
        return None if head in ("global", "functions", "enums", "container") else head

    @property
    def name(self):
        return self.key.split(".", 1)[1] if "." in self.key else self.key


@dataclass
class Spec:
    tables: dict = field(default_factory=dict)
    member_tables: list = field(default_factory=list)
    fingerprints: dict = field(default_factory=dict)
    keys: list = field(default_factory=list)
    capabilities: dict = field(default_factory=dict)
    sha256: str = ""

    def key(self, name):
        return next((k for k in self.keys if k.key == name), None)


def load(spec_dir=SPEC_DIR) -> Spec:
    spec = Spec()
    h = hashlib.sha256()
    for path in sorted(Path(spec_dir).glob("*.yaml")):
        text = path.read_text(encoding="utf-8")
        h.update(text.encode("utf-8"))
        doc = yaml.safe_load(text) or {}
        spec.tables.update(doc.get("tables", {}))
        spec.member_tables.extend(doc.get("member_tables", []))
        spec.fingerprints.update(doc.get("fingerprints", {}))
        spec.capabilities.update(doc.get("capabilities", {}))
        for raw in doc.get("keys", []):
            spec.keys.append(_key(raw))
    spec.sha256 = h.hexdigest()
    return spec


def _key(raw):
    strategies = []
    for s in raw.get("strategies", []):
        if isinstance(s, str):
            strategies.append((s, {}))
        else:
            (kind, params), = s.items()
            strategies.append((kind, params or {}))
    return KeySpec(raw["key"], raw.get("type", "i32"), raw.get("priority", "P1"), strategies,
                   raw.get("invariants", []), raw.get("live", ""), raw.get("doc", ""), raw.get("alias", False))


def lint(spec: Spec):
    """Problems a human should fix before running."""
    problems = []
    seen = set()
    for k in spec.keys:
        if k.key in seen:
            problems.append(f"{k.key}: duplicate key")
        seen.add(k.key)
        if k.priority not in ("P0", "P1", "P2"):
            problems.append(f"{k.key}: bad priority {k.priority}")
        classes = {CLASS_OF.get(kind, "?") for kind, _ in k.strategies}
        if "?" in classes:
            problems.append(f"{k.key}: unknown strategy kind in {[kind for kind, _ in k.strategies]}")
        if not classes & STRONG_CLASSES and "E" not in classes and "D" not in classes:
            problems.append(f"{k.key}: no anchor strategy (only carry-forward/delta would apply)")
        if k.priority == "P0" and not k.live:
            problems.append(f"{k.key}: P0 key without a live check")
        for kind, params in k.strategies:
            for ref in _refs(params):
                if ref.startswith("@") and ref[1:] not in spec.fingerprints:
                    problems.append(f"{k.key}: unknown fingerprint {ref}")
                if ref.startswith("fn:") and ref[3:] not in seen and spec.key(ref[3:]) is None:
                    problems.append(f"{k.key}: unknown function key {ref}")
    for name, cap in spec.capabilities.items():
        for key in cap.get("keys", []):
            if spec.key(key) is None and not any(mt["struct"] == key.split(".")[0] for mt in spec.member_tables):
                problems.append(f"capability {name}: unknown key {key}")
    for name, rule in spec.fingerprints.items():
        for ref in _refs(rule):
            if ref.startswith("@") and ref[1:] not in spec.fingerprints:
                problems.append(f"fingerprint {name}: unknown fingerprint {ref}")
    return problems


def _refs(obj):
    if isinstance(obj, str):
        if obj.startswith("@") or obj.startswith("fn:"):
            yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from _refs(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from _refs(v)
