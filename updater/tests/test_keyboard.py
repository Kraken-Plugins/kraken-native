"""The keyboard recipes (keyboard.py) over miniatures of the window class registration and the cs2 listener handler:
the window procedure stored into the WNDCLASSEXA registered as "JagRenderView", and the key listener slot the
cc_setonkey case points at, with the count and argument fields the shared tail resizes and fills."""
import struct
import unittest
from types import SimpleNamespace

from native_updater import keyboard

from .fake_model import FakeModel

IMAGE = 0x140000000
CLASS_NAME_RVA = 0xf000
CASE_TABLE = 0xf100


class KeyboardTests(unittest.TestCase):
    REGISTER, WNDPROC, HANDLER, RESIZE = 0x1000, 0x2000, 0x3000, 0x4000

    def session(self, functions, strings=None, data=None):
        model = FakeModel(functions, strings=strings, data=data)
        s = SimpleNamespace(model=model, values={}, notes=[])
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_window_procedure_is_the_one_stored_after_the_class_size(self):
        s = self.session({
            self.REGISTER: ["MOV dword ptr [RSP + 0x20],0x50", f"LEA RAX,[{IMAGE + CLASS_NAME_RVA:#x}]",
                            f"LEA RCX,[{IMAGE + self.WNDPROC:#x}]", "MOV qword ptr [RSP + 0x28],RCX", "RET"],
            self.WNDPROC: ["RET"],
        }, strings={CLASS_NAME_RVA: "JagRenderView"})
        out = {k: v[0] for k, v in keyboard.render_view_wndproc(s, {}).items()}
        self.assertEqual(out, {"functions.renderViewWndProc": self.WNDPROC})

    def test_a_function_stored_elsewhere_in_the_class_is_not_the_procedure(self):
        s = self.session({
            self.REGISTER: ["MOV dword ptr [RSP + 0x20],0x50", f"LEA RAX,[{IMAGE + CLASS_NAME_RVA:#x}]",
                            f"LEA RCX,[{IMAGE + self.WNDPROC:#x}]", "MOV qword ptr [RSP + 0x30],RCX", "RET"],
            self.WNDPROC: ["RET"],
        }, strings={CLASS_NAME_RVA: "JagRenderView"})
        self.assertEqual(keyboard.render_view_wndproc(s, {}), {})
        self.assertEqual(s.notes, ["keyboard: 0 window procedures registered with 'JagRenderView'"])

    def handler(self):
        """The handler of ops 1400-1499: a case table of 20, case 19 pointing its slot at component + 0x758, the rest at
        another slot, and a shared tail that resizes the slot and loads its argument array."""
        body = ["LEA EAX,[RDX + -0x578]", "CMP EAX,0x13", f"JA {IMAGE + self.HANDLER + 0x38:#x}",
                f"MOV ECX,dword ptr [R9 + RAX*0x4 + {CASE_TABLE:#x}]", "ADD RCX,R9", "JMP RCX",
                "LEA RBX,[RCX + 0x758]", f"JMP {IMAGE + self.HANDLER + 0x28:#x}",
                "LEA RBX,[RCX + 0x700]", f"JMP {IMAGE + self.HANDLER + 0x28:#x}",
                "MOV RCX,RBX", "MOV RDX,R8", f"CALL {IMAGE + self.RESIZE:#x}", "MOV RAX,qword ptr [RBX + 0x10]", "RET"]
        cases = [self.HANDLER + 0x20] * 19 + [self.HANDLER + 0x18]
        return body, struct.pack("<20I", *cases)

    def test_key_listener_slot_count_and_args(self):
        body, table = self.handler()
        s = self.session({
            self.HANDLER: body,
            self.RESIZE: ["CMP RDX,qword ptr [RCX + 0x8]", "RET"],
        }, data={CASE_TABLE: table})
        out = {k: v[0] for k, v in keyboard.component_listeners(s, {}).items()}
        self.assertEqual(out, {"IfType.onKey": 0x758, "IfListener.count": 0x8, "IfListener.args": 0x10})
        self.assertEqual(s.notes, [])

    def test_a_handler_whose_cases_leave_the_function_is_refused(self):
        body, _ = self.handler()
        cases = [self.HANDLER + 0x20] * 19 + [self.RESIZE]
        s = self.session({
            self.HANDLER: body,
            self.RESIZE: ["CMP RDX,qword ptr [RCX + 0x8]", "RET"],
        }, data={CASE_TABLE: struct.pack("<20I", *cases)})
        self.assertEqual(keyboard.component_listeners(s, {}), {})
        self.assertEqual(s.notes, ["keyboard: no cs2 handler subtracts 1400 and jumps through a case table"])
