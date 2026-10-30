"""The menu edit points recipe (menuedits.py) over a miniature of the minimenu's rebuild, rows clear, tree builder and
entry run: the view rebuild the rebuild hands owner + view to, the node destroy the clear calls on each row's children,
the node vector reserve the builder calls, and the string assignment the run's copy uses for both entry strings."""
import unittest
from types import SimpleNamespace

from native_updater import menuedits
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

IMAGE = 0x140000000
NODE_MAGIC = 0xaaaaaaaaaaaaaaab   # the multiply that divides by the 0x18-byte node size


class MenuEditsTests(unittest.TestCase):
    REBUILD, VIEW_REBUILD, CLEAR, BUILDER, DESTROY, RESERVE, REALLOC = 0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x6000, 0x7000
    RUN, COPY, ASSIGN, OTHER = 0x8000, 0x9000, 0xa000, 0xb000

    def program(self, reserve=None, destroy_recursive=True):
        return {
            # the rebuild hands owner + Minimenu.view to the view rebuild
            self.REBUILD: ["MOV RBX,RCX", "LEA RCX,[RBX + 0x140]", f"CALL {IMAGE + self.VIEW_REBUILD:#x}", "RET"],
            # the view rebuild clears the rows, then builds them again
            self.VIEW_REBUILD: ["MOV RBX,RCX", "LEA RCX,[RBX + 0x140]", f"CALL {IMAGE + self.CLEAR:#x}",
                                "LEA RCX,[RBX + 0x140]", f"CALL {IMAGE + self.BUILDER:#x}", "RET"],
            # the clear destroys each node's children slot
            self.CLEAR: ["MOV RSI,qword ptr [RCX]", "LEA RCX,[RSI + 0x10]", f"CALL {IMAGE + self.DESTROY:#x}", "RET"],
            self.DESTROY: [f"CALL {IMAGE + (self.DESTROY if destroy_recursive else self.OTHER):#x}", "RET"],
            self.OTHER: ["RET"],
            self.BUILDER: [f"CALL {IMAGE + self.RESERVE:#x}", "RET"],
            # the reserve divides the capacity span by the node size and tail-jumps to the reallocation
            self.RESERVE: reserve or ["MOV RAX,qword ptr [RCX + 0x10]", "SUB RAX,qword ptr [RCX]",
                                      f"MOV R9,{NODE_MAGIC:#x}", "IMUL R9", "SAR RDX,0x4", "CMP RDX,R8",
                                      f"JMP {IMAGE + self.REALLOC:#x}"],
            self.REALLOC: ["RET"],
            # the run copies the entry into the view's clicked copy
            self.RUN: [f"CALL {IMAGE + self.COPY:#x}", "RET"],
            self.COPY: ["MOV RBX,RCX", "MOV RSI,RDX", "LEA RCX,[RBX + 0x2e8]", "LEA RDX,[RSI + 0x50]",
                        f"CALL {IMAGE + self.ASSIGN:#x}", "LEA RCX,[RBX + 0x350]", "LEA RDX,[RSI + 0xb8]",
                        f"CALL {IMAGE + self.ASSIGN:#x}", "RET"],
            self.ASSIGN: ["RET"],
        }

    def session(self, program):
        model = FakeModel(program)
        values = {"Minimenu.view": 0x140, "MinimenuView.rows": 0x140, "container.minimenu.node.children": 0x10,
                  "container.minimenu.node.size": 0x18, "MinimenuView.clicked": 0x298, "MinimenuEntry.target": 0x50,
                  "MinimenuEntry.option": 0xb8}
        fns = {"@MinimenuRebuild": self.REBUILD, "@MinimenuRun": self.RUN}
        s = SimpleNamespace(model=model, prov=Provenance(model), values=values, notes=[])
        s.value = lambda key: s.values[key]
        s.fn = lambda ref: fns[ref]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_finds_the_four_edit_points(self):
        s = self.session(self.program())
        out = {k: v[0] for k, v in menuedits.menu_edit_points(s, {}).items()}
        self.assertEqual(out, {"functions.minimenuViewRebuild": self.VIEW_REBUILD,
                               "functions.minimenuNodeDestroy": self.DESTROY,
                               "functions.minimenuNodeReserve": self.RESERVE,
                               "functions.minimenuTextAssign": self.ASSIGN})
        self.assertEqual(s.notes, [])

    def test_a_destroy_that_does_not_recurse_is_not_the_node_destroy(self):
        s = self.session(self.program(destroy_recursive=False))
        out = menuedits.menu_edit_points(s, {})
        self.assertNotIn("functions.minimenuNodeDestroy", out)
        self.assertIn("menuedits: no callee of the view rebuild destroys each row's children", s.notes)

    def test_a_reserve_dividing_by_another_size_is_refused(self):
        wrong_size = ["MOV RAX,qword ptr [RCX + 0x10]", "SUB RAX,qword ptr [RCX]", f"MOV R9,{NODE_MAGIC:#x}", "IMUL R9",
                      "SAR RDX,0x3", "CMP RDX,R8", f"JMP {IMAGE + self.REALLOC:#x}"]
        s = self.session(self.program(reserve=wrong_size))
        out = menuedits.menu_edit_points(s, {})
        self.assertNotIn("functions.minimenuNodeReserve", out)
        self.assertIn("menuedits: 0 node vector reserves among the tree builder's callees", s.notes)
        self.assertIn("functions.minimenuTextAssign", out)
