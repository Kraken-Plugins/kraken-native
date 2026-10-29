"""The interface open and close recipe (interfaces.py) over a miniature of the client's: the packet handler closing a
component's group and opening one, the open function inserting the node and running on-load through a stub, the close
function erasing it and unloading the group, and the on-load runner raising ON_IF_LOAD."""
import re
import unittest
from types import SimpleNamespace

from native_updater import interfaces
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

IMAGE = 0x140000000
MAP = 0x413b98
TOP = 0x413bc8
STORE = 0x413bd8


def assemble(entry, lines, labels):
    """4-byte instructions laid out from entry: a ':name' line marks a label. Fills labels and returns the
    instruction lines."""
    out, rva = [], entry
    for line in lines:
        if line.startswith(":"):
            labels[line[1:]] = rva
        else:
            out.append(line)
            rva += 4
    return out


def resolve(lines, labels):
    return [re.sub(r"@(\w+)", lambda m: f"{IMAGE + labels[m.group(1)]:#x}", line) for line in lines]


class SubInterfaceTests(unittest.TestCase):
    HANDLER, OPEN, CLOSE, STUB, RUNNER, RAISER, INSERT, ERASE, UNLOAD, LAYOUT, OTHER = (
        0x1000, 0x2000, 0x2400, 0x2800, 0x2c00, 0x3000, 0x3400, 0x3800, 0x3c00, 0x4000, 0x4400)

    def open_sub(self):
        """Inserts the component's node, stores the group and the mode into it, runs on-load through the stub with the
        group, then lays the top-level interface out unless it is -1."""
        return [
            "MOV RBP,RCX", "MOV ESI,R9D", "MOV R12D,R8D", f"ADD RCX,{MAP:#x}", "LEA RDX,[RSP + 0x40]", "CALL @insert",
            "MOV R15,qword ptr [RSP + 0x40]", "ADD R15,0x4", "MOV dword ptr [R15],R12D",
            "MOV dword ptr [R15 + 0x4],ESI", "MOV ECX,R12D", "CALL @stub",
            f"MOV EDX,dword ptr [RBP + {TOP:#x}]", "CMP EDX,-0x1", "JZ @open_done", "MOV RCX,RBP", "CALL @layout",
            ":open_done",
            "RET",
        ]

    def close_sub(self, top=TOP):
        """Erases the component's node, unloads the group when asked, then lays the top-level interface out unless it
        is -1."""
        return [
            "MOV RDI,RCX", "MOV ESI,R8D", "MOVZX EBX,R9B", f"ADD RCX,{MAP:#x}", "LEA RDX,[RSP + 0x38]", "CALL @erase",
            "TEST BL,BL", "JZ @keep", f"MOV RCX,qword ptr [RDI + {STORE:#x}]", "MOV EDX,ESI", "CALL @unload",
            ":keep",
            f"MOV EDX,dword ptr [RDI + {top:#x}]", "CMP EDX,-0x1", "JZ @close_done", "MOV RCX,RDI", "CALL @layout",
            ":close_done",
            "RET",
        ]

    def handler(self):
        """An open-sub case: closes what the component holds, opens the new group; and a new top-level interface,
        whose on-load it runs itself."""
        return [
            "MOV R13,RCX", f"LEA RCX,[R13 + {MAP:#x}]", "CALL @erase",
            "MOV RCX,R13", "MOV EDX,0x10", "MOV R8D,0x5", "MOV R9B,0x1", "CALL @close",
            "MOV RCX,R13", "MOV EDX,0x10", "MOV R8D,0x6", "MOV R9D,0x1", "CALL @open",
            f"MOV ECX,dword ptr [R13 + {TOP:#x}]", "CALL @stub",
            "RET",
        ]

    def program(self, runners=1, close_top=TOP):
        labels = {}
        parts = {
            self.HANDLER: assemble(self.HANDLER, self.handler(), labels),
            self.OPEN: assemble(self.OPEN, self.open_sub(), labels),
            self.CLOSE: assemble(self.CLOSE, self.close_sub(close_top), labels),
            self.STUB: assemble(self.STUB, ["MOV EDX,ECX", "JMP @runner"], labels),
            self.RUNNER: assemble(self.RUNNER, ["MOV EBX,EDX", "CALL @raiser", "RET"], labels),
            self.RAISER: assemble(self.RAISER, ["RET"], labels),
        }
        for entry in (self.INSERT, self.ERASE, self.UNLOAD, self.LAYOUT):
            parts[entry] = ["RET"]
        if runners > 1:
            parts[self.OTHER] = ["CALL @raiser", "RET"]
        labels.update(insert=self.INSERT, erase=self.ERASE, unload=self.UNLOAD, layout=self.LAYOUT, open=self.OPEN,
                      close=self.CLOSE, stub=self.STUB, runner=self.RUNNER, raiser=self.RAISER)
        return {entry: resolve(lines, labels) for entry, lines in parts.items()}

    def session(self, group=4, **kwargs):
        model = FakeModel(self.program(**kwargs))
        s = SimpleNamespace(model=model, prov=Provenance(model), notes=[],
                            values={"Client.subInterfaces.buckets": MAP + 8, "SubInterfaceNode.group": group,
                                    "events.ON_IF_LOAD": [{"fn": self.RAISER, "id": 47, "args": 3}]})
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_every_key_comes_from_the_open_and_close_functions(self):
        s = self.session()
        out = {k: v[0] for k, v in interfaces.sub_interfaces(s, {}).items()}
        self.assertEqual(out, {"functions.ifOpenSub": self.OPEN, "functions.ifCloseSub": self.CLOSE,
                               "Client.topLevelInterface": TOP, "SubInterfaceNode.type": 8})
        self.assertEqual(s.notes, [])

    def test_a_group_stored_elsewhere_than_the_mapped_group_leaves_the_mode_out(self):
        s = self.session(group=0x10)
        out = interfaces.sub_interfaces(s, {})
        self.assertNotIn("SubInterfaceNode.type", out)
        self.assertEqual(out["functions.ifCloseSub"][0], self.CLOSE)
        self.assertTrue(any("SubInterfaceNode.group is 0x10" in n for n in s.notes), s.notes)

    def test_a_second_on_load_raiser_stops_the_recipe(self):
        s = self.session(runners=2)
        self.assertEqual(interfaces.sub_interfaces(s, {}), {})
        self.assertTrue(any("2 functions raise ON_IF_LOAD" in n for n in s.notes), s.notes)

    def test_no_shared_compare_with_minus_one_leaves_the_top_level_out(self):
        s = self.session(close_top=0x413bcc)
        out = interfaces.sub_interfaces(s, {})
        self.assertNotIn("Client.topLevelInterface", out)
        self.assertTrue(any("compare 0 Client fields" in n for n in s.notes), s.notes)


if __name__ == "__main__":
    unittest.main()
