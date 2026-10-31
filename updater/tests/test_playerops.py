"""The player options recipe (playerops.py) over a miniature of the player row builder: the loop handing the owner's
option table to a string accessor and a priority accessor, and the cs2 case reading the same table through
Client.minimenu."""
import unittest
from types import SimpleNamespace

from native_updater import playerops
from native_updater.analysis.provenance import Provenance
from native_updater.session import Blocked

from .fake_model import FakeModel
from .test_scripts import assemble, resolve

IMAGE = 0x140000000
CLIENT, MINIMENU, TABLE, PRIORITIES = 0xe98c18, 0x4133f0, 0x898, 0x958
BODY, ADDER, BUILDER, IS_SET, NAME, PRIORITY, READER = 0x1000, 0x1400, 0x2000, 0x3000, 0x3400, 0x3800, 0x4000
VALUES = {"functions.menuPlayerOp": BODY, "global.client": CLIENT, "Client.minimenu": MINIMENU,
          "container.string.size": 0x18}


def wrap(model, values):
    s = SimpleNamespace(model=model, prov=Provenance(model), notes=[], values=dict(values))

    def value(key):
        if key not in s.values:
            raise Blocked(f"key {key} unresolved")
        return s.values[key]

    s.value = value
    s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
    return s


class PlayerOpsTests(unittest.TestCase):

    def program(self, count=8, stride_scale=0x8, reader=True, builders=1):
        """The row body and its adder, the builder (the owner is its fifth argument) looping over the eight options,
        the three accessors and the cs2 case loading the owner from the client."""
        labels = {}
        builder = assemble(BUILDER, [
            "MOV R15,qword ptr [RSP + 0x28]",
            f"LEA RDI,[R15 + {TABLE:#x}]",
            "XOR EBX,EBX",
            ":loop",
            "MOV RDX,RBX", "MOV RCX,RDI", f"CALL {IMAGE + IS_SET:#x}",
            "TEST AL,AL", "JZ @next",
            "MOV RDX,RBX", "MOV RCX,RDI", f"CALL {IMAGE + NAME:#x}",
            "MOV RDX,RBX", "MOV RCX,RDI", f"CALL {IMAGE + PRIORITY:#x}",
            "MOV RCX,R15", f"CALL {IMAGE + ADDER:#x}",
            ":next",
            "INC RBX", "CMP RBX,0x8", "JC @loop",
            "RET",
        ], labels)
        priority = assemble(PRIORITY, [
            f"CMP RDX,{count:#x}", "JNC @zero",
            f"MOVZX EAX,byte ptr [RDX + RCX*0x1 + {PRIORITIES - TABLE:#x}]", "RET",
            ":zero", "XOR AL,AL", "RET",
        ], labels)
        functions = {
            BODY: ["RET"],
            ADDER: [f"LEA RAX,[{IMAGE + BODY:#x}]", "MOV qword ptr [RCX + 0x20],RAX", "RET"],
            BUILDER: resolve(builder, labels),
            IS_SET: ["LEA RAX,[RDX + RDX*0x2]", "MOVSX RDX,byte ptr [RCX + RAX*0x8 + 0x17]", "MOV AL,0x1", "RET"],
            NAME: ["LEA RAX,[RDX + RDX*0x2]", f"LEA RAX,[RCX + RAX*{stride_scale:#x}]", "RET"],
            PRIORITY: resolve(priority, labels),
        }
        if reader:
            functions[READER] = [f"MOV RDI,qword ptr [{IMAGE + CLIENT:#x}]", f"MOV RAX,qword ptr [RDI + {MINIMENU:#x}]",
                                 f"LEA RCX,[RAX + {TABLE:#x}]", "MOV EDX,0x2", f"CALL {IMAGE + NAME:#x}", "RET"]
        if builders > 1:
            functions[0x5000] = [f"CALL {IMAGE + ADDER:#x}", "RET"]
        return FakeModel(functions)

    def test_the_builder_hands_the_table_to_both_accessors(self):
        s = wrap(self.program(), VALUES)
        found = playerops.player_ops(s, {})
        self.assertEqual({k: v[0] for k, v in found.items()},
                         {"Minimenu.playerOps": TABLE, "Minimenu.playerOpPriorities": PRIORITIES})
        self.assertEqual(found["Minimenu.playerOps"][1][0]["insn"], BUILDER + 4)
        self.assertEqual(found["Minimenu.playerOpPriorities"][1][0]["fn"], PRIORITY)
        self.assertEqual(s.notes, [])

    def test_a_table_nothing_reads_through_the_client_gives_nothing(self):
        s = wrap(self.program(reader=False), VALUES)
        self.assertEqual(playerops.player_ops(s, {}), {})
        self.assertEqual(s.notes, [f"player ops: no function hands the string accessor {NAME:#x} "
                                   f"[client + Client.minimenu] + {TABLE:#x}"])

    def test_a_priority_accessor_with_another_count_gives_nothing(self):
        s = wrap(self.program(count=6), VALUES)
        self.assertEqual(playerops.player_ops(s, {}), {})
        self.assertEqual(s.notes, [f"player ops: the priority accessor {PRIORITY:#x} checks the index against 6, "
                                   f"not 8"])

    def test_an_accessor_with_another_stride_is_not_the_string_accessor(self):
        s = wrap(self.program(stride_scale=0x4), VALUES)
        self.assertEqual(playerops.player_ops(s, {}), {})
        self.assertEqual(s.notes, [f"player ops: the player row builder {BUILDER:#x} hands 0 tables to a string "
                                   f"accessor and a priority accessor"])

    def test_an_adder_with_two_callers_gives_nothing(self):
        s = wrap(self.program(builders=2), VALUES)
        self.assertEqual(playerops.player_ops(s, {}), {})
        self.assertEqual(s.notes, ["player ops: the functions taking the player row body's address have 2 callers, "
                                   "not one builder"])

    def test_the_minimenu_must_be_solved_first(self):
        values = dict(VALUES)
        del values["Client.minimenu"]
        with self.assertRaises(Blocked):
            playerops.player_ops(wrap(self.program(), values), {})


if __name__ == "__main__":
    unittest.main()
