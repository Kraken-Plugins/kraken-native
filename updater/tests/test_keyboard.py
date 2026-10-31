"""The keyboard recipes (keyboard.py) over miniatures of the window class registration and the cs2 listener handler:
the window procedure stored into the WNDCLASSEXA registered as "JagRenderView", the key and mouse-repeat listener
slots the cc_setonkey and cc_setonmouserepeat cases point at, the count and argument fields the shared tail resizes
and fills, and the function the resize empties a slot with."""
import struct
import unittest
from types import SimpleNamespace

from native_updater import keyboard
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel
from .test_scripts import assemble, resolve

IMAGE = 0x140000000
CLASS_NAME_RVA = 0xf000
CASE_TABLE = 0xf100


class KeyboardTests(unittest.TestCase):
    REGISTER, WNDPROC, HANDLER, RESIZE, CLEAR, ZERO, RESET, DESTROY = (0x1000, 0x2000, 0x3000, 0x4000, 0x4800, 0x4c00,
                                                                       0x5000, 0x5400)

    def session(self, functions, strings=None, data=None):
        model = FakeModel(functions, strings=strings, data=data)
        s = SimpleNamespace(model=model, prov=Provenance(model), values={}, notes=[])
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
        """The handler of ops 1400-1499: a case table of 20, case 19 pointing its slot at component + 0x758, case 12 at
        component + 0x470, the rest at another slot, and a shared tail that resizes the slot and loads its argument
        array."""
        body = ["LEA EAX,[RDX + -0x578]", "CMP EAX,0x13", f"JA {IMAGE + self.HANDLER + 0x40:#x}",
                f"MOV ECX,dword ptr [R9 + RAX*0x4 + {CASE_TABLE:#x}]", "ADD RCX,R9", "JMP RCX",
                "LEA RBX,[RCX + 0x758]", f"JMP {IMAGE + self.HANDLER + 0x30:#x}",
                "LEA RBX,[RCX + 0x700]", f"JMP {IMAGE + self.HANDLER + 0x30:#x}",
                "LEA RBX,[RCX + 0x470]", f"JMP {IMAGE + self.HANDLER + 0x30:#x}",
                "MOV RCX,RBX", "MOV RDX,R8", f"CALL {IMAGE + self.RESIZE:#x}", "MOV RAX,qword ptr [RBX + 0x10]", "RET"]
        cases = [self.HANDLER + 0x20] * 20
        cases[19] = self.HANDLER + 0x18
        cases[12] = self.HANDLER + 0x28
        return body, struct.pack("<20I", *cases)

    def slot_functions(self, clear=None):
        """The slot resize: a same-count reset, an empty on a new count of 0, and otherwise a new array after emptying
        the old one; the clear destroys the arguments and ends in a call that zeroes the count and array."""
        labels = {}
        resize = assemble(self.RESIZE, [
            "MOV RBX,qword ptr [RCX + 0x8]", "MOV RBP,RCX", "MOV RDI,RDX", "CMP RDX,RBX", "JZ @same",
            "TEST RDX,RDX", "JNZ @grow", "CALL @clear", "RET",
            ":grow",
            "MOV RCX,RBP", "CALL @clear", "MOV qword ptr [RBP + 0x8],RDI", "RET",
            ":same",
            "JMP @reset",
        ], labels)
        labels.update(clear=self.CLEAR, reset=self.RESET, zero=self.ZERO, destroy=self.DESTROY)
        clear = clear or ["PUSH RBX", "MOV RBX,RCX", "MOV RDX,qword ptr [RCX + 0x10]", "CALL @destroy", "MOV RCX,RBX",
                          "POP RBX", "JMP @zero"]
        return {
            self.RESIZE: resolve(resize, labels),
            self.CLEAR: resolve(clear, labels),
            self.ZERO: ["XOR EAX,EAX", "MOV qword ptr [RCX + 0x10],RAX", "MOV qword ptr [RCX + 0x8],RAX", "RET"],
            self.RESET: ["MOV RAX,qword ptr [RCX + 0x10]", "RET"],
            self.DESTROY: ["RET"],
        }

    def test_listener_slots_count_args_and_clear(self):
        body, table = self.handler()
        s = self.session({self.HANDLER: body, **self.slot_functions()}, data={CASE_TABLE: table})
        out = {k: v[0] for k, v in keyboard.component_listeners(s, {}).items()}
        self.assertEqual(out, {"IfType.onKey": 0x758, "IfType.onMouseRepeat": 0x470, "IfListener.count": 0x8,
                               "IfListener.args": 0x10, "functions.ifListenerClear": self.CLEAR})
        self.assertEqual(s.notes, [])

    def test_a_clear_that_leaves_the_array_is_not_the_clear(self):
        body, table = self.handler()
        clear = ["PUSH RBX", "MOV RBX,RCX", "CALL @destroy", "XOR EAX,EAX", "MOV qword ptr [RBX + 0x8],RAX", "POP RBX",
                 "RET"]
        s = self.session({self.HANDLER: body, **self.slot_functions(clear)}, data={CASE_TABLE: table})
        out = {k: v[0] for k, v in keyboard.component_listeners(s, {}).items()}
        self.assertNotIn("functions.ifListenerClear", out)
        self.assertEqual(out["IfType.onMouseRepeat"], 0x470)
        self.assertEqual(s.notes, [f"keyboard: the slot resize {self.RESIZE:#x} calls 0 functions that empty the slot"])

    def test_a_handler_whose_cases_leave_the_function_is_refused(self):
        body, _ = self.handler()
        cases = [self.HANDLER + 0x20] * 19 + [self.RESIZE]
        s = self.session({self.HANDLER: body, **self.slot_functions()}, data={CASE_TABLE: struct.pack("<20I", *cases)})
        self.assertEqual(keyboard.component_listeners(s, {}), {})
        self.assertEqual(s.notes, ["keyboard: no cs2 handler subtracts 1400 and jumps through a case table"])
