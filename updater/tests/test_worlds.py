"""The world recipe (worlds.py) over a miniature of the cs2 runtime's 3300 handler: the dispatch through a case table,
map_world's case loading the client's world field and worldflags' case loading Client.worldFlags."""
import unittest
from types import SimpleNamespace

from native_updater import worlds
from native_updater.analysis.provenance import Provenance
from native_updater.session import Blocked

from .fake_model import FakeModel
from .test_scripts import assemble, resolve

IMAGE = 0x140000000
CASE_TABLE = 0x7000
CASES = 40
WORLD, FLAGS = 0x32b4, 0x30a4
VALUES = {"global.client": 0xe98c18, "Client.worldFlags": FLAGS}


def wrap(model, values):
    s = SimpleNamespace(model=model, prov=Provenance(model), notes=[], values=dict(values))

    def value(key):
        if key not in s.values:
            raise Blocked(f"key {key} unresolved")
        return s.values[key]

    s.value = value
    s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
    return s


class WorldTests(unittest.TestCase):
    HANDLER = 0x1000

    def program(self, world=None, flags=None):
        """The handler: the op's dispatch, the default return, the shared push, then the cases: clientclock (op 3300),
        map_world (3318), runenergy (3321) and worldflags (3324). The table points every other op at the return."""
        labels = {}
        body = assemble(self.HANDLER, [
            "MOV R11,qword ptr [0x140e98c18]",
            "MOV RDI,RCX",
            "ADD EDX,0xfffff31c",
            "CMP EDX,0x27",
            "JA @done",
            "MOVSXD RAX,EDX",
            f"LEA RCX,[{IMAGE:#x}]",
            f"MOV EDX,dword ptr [RCX + RAX*0x4 + {CASE_TABLE:#x}]",
            "ADD RDX,RCX",
            ":dispatch",
            "JMP RDX",
            ":done",
            "RET",
            ":push",
            "MOVSXD RAX,dword ptr [RDI + 0xff0]",
            "RET",
            ":clock",
            "MOV EDX,dword ptr [0x140e98c14]",
            "JMP @push",
            ":world",
            *(world or ["MOV EDX,dword ptr [R11 + 0x32b4]", "JMP @push"]),
            ":energy",
            "MOV EDX,dword ptr [R11 + 0x34e0]",
            "JMP @push",
            ":flags",
            *(flags or ["MOV EDX,dword ptr [R11 + 0x30a4]", "JMP @push"]),
        ], labels)
        cases = [labels["done"]] * CASES
        cases[0], cases[18], cases[21], cases[24] = labels["clock"], labels["world"], labels["energy"], labels["flags"]
        functions = {self.HANDLER: resolve(body, labels)}
        data = {CASE_TABLE: b"".join(c.to_bytes(4, "little") for c in cases)}
        flows = {labels["dispatch"]: tuple(sorted(set(cases)))}
        return functions, data, flows

    def session(self, world=None, flags=None, values=VALUES):
        functions, data, flows = self.program(world, flags)
        return wrap(FakeModel(functions, data=data, flows=flows), values)

    def test_map_world_reads_the_world_field(self):
        s = self.session()
        found = worlds.cs2_world(s, {})
        self.assertEqual({k: v[0] for k, v in found.items()}, {"Client.currentWorld": WORLD})
        self.assertEqual(found["Client.currentWorld"][1][0]["fn"], self.HANDLER)
        self.assertEqual(s.notes, [])

    def test_a_worldflags_case_reading_another_field_gives_nothing(self):
        s = self.session(flags=["MOV EDX,dword ptr [R11 + 0x34e0]", "JMP @push"])
        self.assertEqual(worlds.cs2_world(s, {}), {})
        self.assertEqual(s.notes, ["worlds: worldflags' case (op 3324) loads 1 client fields, not Client.worldFlags alone"])

    def test_a_map_world_case_with_two_client_fields_gives_nothing(self):
        s = self.session(world=["MOV EDX,dword ptr [R11 + 0x32b4]", "MOV ECX,dword ptr [R11 + 0x34e0]", "JMP @push"])
        self.assertEqual(worlds.cs2_world(s, {}), {})
        self.assertEqual(s.notes, ["worlds: map_world's case (op 3318) loads 2 client fields"])

    def test_a_program_without_the_dispatch_gives_nothing(self):
        s = wrap(FakeModel({self.HANDLER: ["MOV EAX,0x1", "RET"]}), VALUES)
        self.assertEqual(worlds.cs2_world(s, {}), {})
        self.assertEqual(s.notes, ["worlds: no cs2 handler subtracts 3300 and jumps through a case table"])

    def test_worldflags_must_be_solved_first(self):
        s = self.session(values={"global.client": 0xe98c18})
        with self.assertRaises(Blocked):
            worlds.cs2_world(s, {})
