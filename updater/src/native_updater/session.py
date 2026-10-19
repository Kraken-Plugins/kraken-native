"""Per-run state: the program model, analysis caches, parsed bindings, fingerprints and resolved key values.

Function references used throughout the spec:
  "ClientOp.npcCoord"      a Lua binding handler (Table.name)
  "@NpcLookup"             a fingerprinted function
  "fn:functions.setSequence"  a function key resolved earlier in the run
  {"string": "..."}        the function that references this exact string
"""
from dataclasses import dataclass, field

from .analysis.bindings import BindingTable, BindingTableParser, parse_enum_tables
from .analysis.provenance import Provenance

# Anchor classes (design §2). Verdicts count distinct classes.
CLASS_OF = {
    "getter": "A", "global-load": "A", "hash-map": "A", "const": "A", "member": "A", "member-table": "A", "binding": "A",
    "enum-table": "A", "string-anchored": "B", "loader-caller": "C", "extractor": "E", "fingerprint": "E", "derive": "A",
    "ctor-signature": "D", "carry-forward": "F", "delta-propagation": "G",
}
STRONG_CLASSES = {"A", "B", "C"}


@dataclass
class Candidate:
    value: object
    strategy: str
    cls: str
    evidence: list = field(default_factory=list)   # [{insn, fn, text, slot}]
    note: str = ""


class Blocked(Exception):
    """A strategy cannot run yet because a fingerprint or key it depends on is unresolved."""

    def __init__(self, what):
        super().__init__(what)
        self.what = what


class Session:
    def __init__(self, model, spec, log=print):
        self.model = model
        self.spec = spec
        self.log = log
        self.prov = Provenance(model)
        self.bindings = BindingTable()
        self.enum_tables = []
        self.fingerprints = {}          # name -> rva (None when searched and not found)
        self.fp_evidence = {}           # name -> dict
        self.values = {}                # key -> accepted value (set by consensus after each round)
        self.candidates = {}            # key -> [Candidate]
        self.blocked = {}               # key -> reason (last round)
        self.notes = []                 # free-text findings for the report

    def parse_bindings(self):
        parser = BindingTableParser(self.model)
        for table, cfg in self.spec.tables.items():
            fns = parser.registration_functions(cfg["anchors"])
            if not fns:
                self.notes.append(f"binding table {table}: no registration function references {cfg['anchors']}")
                continue
            if len(fns) > 1:
                self.notes.append(f"binding table {table}: several registration functions {[hex(f) for f in fns]}, using the first")
            for b in parser.parse(fns[0], table, namespace_strings=[table]):
                self.bindings.add(b)
        self.enum_tables = parse_enum_tables(self.model)

    def fn(self, ref):
        """Resolve a function reference to an RVA; raises Blocked when it is not (yet) known."""
        if isinstance(ref, dict) and "string" in ref:
            refs = self.model.string_refs(ref["string"])
            fns = sorted({fn for _, fn in refs if fn is not None})
            if not fns:
                raise Blocked(f"string {ref['string']!r} not referenced")
            return fns[0]
        if isinstance(ref, int):
            return ref
        if ref.startswith("@"):
            name = ref[1:]
            if name not in self.fingerprints:
                raise Blocked(f"fingerprint {name} not run")
            if self.fingerprints[name] is None:
                raise Blocked(f"fingerprint {name} not found")
            return self.fingerprints[name]
        if ref.startswith("fn:"):
            v = self.values.get(ref[3:])
            if v is None:
                raise Blocked(f"key {ref[3:]} unresolved")
            return v
        b = self.bindings.get(ref)
        if b is None or b.handler is None:
            raise Blocked(f"binding {ref} not found")
        return b.handler

    def fns(self, ref):
        """A reference that may be a wildcard over a binding table ('ClientOp.*')."""
        if isinstance(ref, str) and ref.endswith(".*"):
            table = ref[:-2]
            handlers = self.bindings.handlers(table)
            if not handlers:
                raise Blocked(f"binding table {table} empty")
            return sorted(set(handlers))
        return [self.fn(ref)]

    def value(self, key):
        v = self.values.get(key)
        if v is None:
            raise Blocked(f"key {key} unresolved")
        return v

    def evidence(self, rva, fn=None, slot="disp"):
        insn = self.model.insn(rva)
        return {"insn": rva, "fn": fn if fn is not None else self.model.function_containing(rva), "text": insn.text if insn else "", "slot": slot}
