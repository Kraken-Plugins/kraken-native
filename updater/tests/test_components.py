"""The component recipes (components.py) over miniatures of two cs2 handlers. The handler of ops 1300-1399 resolves
the component an op acts on (for an if_ op through a lookup, for a cc_ op the runner's slot runner + 0xded8 + operand *
16, its pointer at +8), then dispatches through a byte table and a case table; cc_setdragdeadzone and
cc_setdragdeadtime pop an int into the component. The handler of ops 1100-1199 resolves its component the same way and
dispatches through a plain case table; cc_settext assigns the text to the component's IfType.text and invalidates the
component with the client and the resolver's slot."""
import unittest
from types import SimpleNamespace

from native_updater import components
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel
from .test_scripts import assemble, resolve

IMAGE = 0x140000000
INDEX_TABLE, CASE_TABLE, SET_TABLE = 0x7000, 0x7100, 0x7400
OPS = 6
SET_OPS = 14
SLOT = ["MOVZX EAX,R9B", "SHL RAX,0x4", "ADD RAX,0xded8", "ADD RAX,RCX", "RET"]
VALUES = {"container.shared_ptr.ptr": 8, "ScriptRunner.intStack": 0x50, "ScriptRunner.intStackSize": 0xff0,
          "IfType.text": 0x158, "global.client": 0xe98c18}
TEXT_CASE = ["LEA RCX,[RSP + 0x40]", "CALL @copy", "LEA RCX,[RSI + 0x158]", "MOV RDX,qword ptr [RSP + 0x40]",
             "MOV R8,qword ptr [RSP + 0x48]", "CALL @assign", "MOV RDX,R15", "MOV RCX,R14", "CALL @invalidate",
             "JMP @done"]


def session_over(functions, data, flows):
    model = FakeModel(functions, data=data, flows=flows)
    s = SimpleNamespace(model=model, prov=Provenance(model), notes=[], values=dict(VALUES))
    s.value = lambda key: s.values[key]
    s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
    return s


def pop_into(offset):
    """A case popping the int stack into the resolved component at `offset`."""
    return ["MOVSXD RCX,dword ptr [RSI]", "LEA EAX,[RCX + -0x1]", "MOV dword ptr [RSI],EAX",
            "MOV ECX,dword ptr [RDI + RCX*0x4 + 0x4c]", f"MOV dword ptr [R14 + {offset:#x}],ECX", "JMP @done"]


class ComponentTests(unittest.TestCase):
    HANDLER, RESOLVER, LOOKUP = 0x1000, 0x3000, 0x3800

    def program(self, zone=None, time=None, resolver=None):
        labels = {}
        body = assemble(self.HANDLER, [
            "MOV R15D,EDX", "MOV RDI,RCX", "LEA RSI,[RCX + 0xff0]", "MOV EDX,0xffffffff", "CALL @resolver",
            "MOV R14,qword ptr [RAX + 0x8]",
            "LEA EAX,[R15 + -0x514]", f"CMP EAX,{OPS - 1:#x}", "JA @done", f"LEA RDX,[{IMAGE:#x}]", "CDQE",
            f"MOVZX EAX,byte ptr [RDX + RAX*0x1 + {INDEX_TABLE:#x}]", f"MOV ECX,dword ptr [RDX + RAX*0x4 + {CASE_TABLE:#x}]",
            "ADD RCX,RDX",
            ":dispatch",
            "JMP RCX",
            ":done",
            "RET",
            ":zone",
            *(zone or pop_into(0x320)),
            ":time",
            *(time or pop_into(0x324)),
            ":other",
            "JMP @done",
        ], labels)
        resolver_lines = assemble(self.RESOLVER, resolver or ["TEST R8B,R8B", "JZ @cc", "CALL @lookup", "RET", ":cc", *SLOT],
                                  labels)
        labels.update(resolver=self.RESOLVER, lookup=self.LOOKUP)
        cases = {op: labels["other"] for op in range(OPS)}
        cases[3], cases[4] = labels["zone"], labels["time"]
        targets = sorted(set(cases.values()))
        index = bytes(targets.index(cases[op]) for op in range(OPS))
        table = b"".join(t.to_bytes(4, "little") for t in targets)
        functions = {
            self.HANDLER: resolve(body, labels),
            self.RESOLVER: resolve(resolver_lines, labels),
            self.LOOKUP: ["MOV RAX,qword ptr [RCX + 0x10]", "RET"],
        }
        return functions, {INDEX_TABLE: index, CASE_TABLE: table}, {labels["dispatch"]: tuple(targets)}

    def session(self, **kwargs):
        return session_over(*self.program(**kwargs))

    def test_drag_fields_and_current_components(self):
        s = self.session()
        found = components.cs2_components(s, {})
        out = {k: v[0] for k, v in found.items()}
        self.assertEqual(out, {"IfType.dragDeadZone": 0x320, "IfType.dragDeadTime": 0x324,
                               "ScriptRunner.activeComponent": 0xded8, "ScriptRunner.dotComponent": 0xdee8})
        self.assertEqual(found["ScriptRunner.activeComponent"][1][0]["fn"], self.RESOLVER)
        self.assertEqual(s.notes, [])

    def test_a_case_storing_a_constant_gives_no_field(self):
        s = self.session(time=["MOV dword ptr [R14 + 0x324],0x5", "JMP @done"])
        out = {k: v[0] for k, v in components.cs2_components(s, {}).items()}
        self.assertNotIn("IfType.dragDeadTime", out)
        self.assertEqual(out["IfType.dragDeadZone"], 0x320)
        self.assertEqual(s.notes, ["components: cc_setdragdeadtime's case (op 1304) stores the popped int to 0 "
                                   "component fields"])

    def test_a_resolver_summarised_into_the_handler_gives_the_same_slots(self):
        s = self.session(resolver=SLOT)
        out = {k: v[0] for k, v in components.cs2_components(s, {}).items()}
        self.assertEqual(out, {"IfType.dragDeadZone": 0x320, "IfType.dragDeadTime": 0x324,
                               "ScriptRunner.activeComponent": 0xded8, "ScriptRunner.dotComponent": 0xdee8})

    def test_a_resolver_not_indexing_the_runner_gives_no_components(self):
        s = self.session(resolver=["TEST R8B,R8B", "JZ @cc", "CALL @lookup", "RET",
                                   ":cc", "MOV RAX,qword ptr [RCX + 0xded8]", "RET"])
        out = {k: v[0] for k, v in components.cs2_components(s, {}).items()}
        self.assertEqual(out, {"IfType.dragDeadZone": 0x320, "IfType.dragDeadTime": 0x324})
        self.assertEqual(s.notes, ["components: the resolver returns 0 runner slots"])


class SetTextTests(unittest.TestCase):
    HANDLER, RESOLVER, LOOKUP, COPY, ASSIGN, INVALIDATE = 0x1000, 0x3000, 0x3800, 0x4000, 0x4400, 0x4800

    def session(self, text=None):
        """The handler of ops 1100-1113: cc_setcolour (op 1101) stores to the component and invalidates it, cc_settext
        (op 1112) runs `text`, the rest return."""
        labels = {}
        body = assemble(self.HANDLER, [
            "MOV R12D,EDX", "MOV RDI,RCX", "MOV R14,qword ptr [0x140e98c18]", "MOV EDX,0xffffffff", "CALL @resolver",
            "MOV R15,RAX", "MOV RSI,qword ptr [RAX + 0x8]",
            "LEA EAX,[R12 + -0x44c]", f"CMP EAX,{SET_OPS - 1:#x}", "JA @done", f"LEA RDX,[{IMAGE:#x}]", "CDQE",
            f"MOV ECX,dword ptr [RDX + RAX*0x4 + {SET_TABLE:#x}]", "ADD RCX,RDX",
            ":dispatch",
            "JMP RCX",
            ":done",
            "RET",
            ":colour",
            "MOV dword ptr [RSI + 0x98],0x1", "MOV RDX,R15", "MOV RCX,R14", "CALL @invalidate", "JMP @done",
            ":text",
            *(text or TEXT_CASE),
            ":other",
            "JMP @done",
        ], labels)
        resolver = assemble(self.RESOLVER, ["TEST R8B,R8B", "JZ @cc", "CALL @lookup", "RET", ":cc", *SLOT], labels)
        labels.update(resolver=self.RESOLVER, lookup=self.LOOKUP, copy=self.COPY, assign=self.ASSIGN,
                      invalidate=self.INVALIDATE)
        cases = [labels["other"]] * SET_OPS
        cases[1], cases[12] = labels["colour"], labels["text"]
        functions = {
            self.HANDLER: resolve(body, labels),
            self.RESOLVER: resolve(resolver, labels),
            self.LOOKUP: ["MOV RAX,qword ptr [RCX + 0x10]", "RET"],
            self.COPY: ["RET"], self.ASSIGN: ["RET"], self.INVALIDATE: ["RET"],
        }
        table = b"".join(c.to_bytes(4, "little") for c in cases)
        return session_over(functions, {SET_TABLE: table}, {labels["dispatch"]: tuple(sorted(set(cases)))})

    def test_text_assign_and_invalidate(self):
        s = self.session()
        found = components.cs2_set_text(s, {})
        self.assertEqual({k: v[0] for k, v in found.items()},
                         {"functions.stringAssign": self.ASSIGN, "functions.ifInvalidate": self.INVALIDATE})
        self.assertEqual(found["functions.stringAssign"][1][0]["fn"], self.HANDLER)
        self.assertEqual(s.notes, [])

    def test_an_assign_to_another_field_gives_no_functions(self):
        text = [line.replace("0x158", "0x170") for line in TEXT_CASE]
        s = self.session(text=text)
        self.assertEqual(components.cs2_set_text(s, {}), {})
        self.assertEqual(s.notes, ["components: cc_settext's case (op 1112) assigns IfType.text with 0 functions"])

    def test_a_case_that_does_not_invalidate_gives_only_the_assign(self):
        text = [line for line in TEXT_CASE if line != "CALL @invalidate"]
        s = self.session(text=text)
        out = {k: v[0] for k, v in components.cs2_set_text(s, {}).items()}
        self.assertEqual(out, {"functions.stringAssign": self.ASSIGN})
        self.assertEqual(s.notes, ["components: after assigning the text, cc_settext's case calls 0 functions with "
                                   "the client and the component"])
