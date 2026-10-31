"""The component recipes (components.py) over miniatures of two cs2 handlers. The handler of ops 1300-1399 resolves
the component an op acts on (for an if_ op through a lookup, for a cc_ op the runner's slot runner + 0xded8 + operand *
16, its pointer at +8), then dispatches through a byte table and a case table; cc_setdragdeadzone and
cc_setdragdeadtime pop an int into the component. The handler of ops 1100-1199 resolves its component the same way and
dispatches through a plain case table; cc_settext assigns the text to the component's IfType.text and invalidates the
component with the client and the resolver's slot. The handler of ops 1800-1899 dispatches through a byte table;
cc_gettargetmask calls the click mask getter, which looks the component up in the server's map of slot ranges."""
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


GET_OPS = 3
GET_INDEX, GET_CASES = 0x7800, 0x7900
LOOKUP = [
    "MOV R10D,dword ptr [R8]", "MOV R9,RDX", "MOV R8D,dword ptr [RCX + 0x10]", "XOR EDX,EDX",
    "MOV R11,qword ptr [RCX + 0x8]", "MOV EAX,R10D", "DIV R8", "MOV EAX,EDX", "MOV R8,qword ptr [R11 + RAX*0x8]",
    "TEST R8,R8", "JZ @miss",
    ":chain",
    "CMP R10D,dword ptr [R8]", "JZ @hit", "MOV R8,qword ptr [R8 + 0x18]", "TEST R8,R8", "JNZ @chain",
    ":miss",
    "MOV qword ptr [R9],R8", "MOV RAX,R9", "RET",
    ":hit",
    "MOV qword ptr [R9],R8", "MOV qword ptr [R9 + 0x10],R8", "MOV RAX,R9", "RET",
]
WALK = ["CMP R9D,dword ptr [RCX + 0x4]", "JL @step", "CMP R9D,dword ptr [RCX + 0x8]", "JLE @found"]


class ClickMaskTests(unittest.TestCase):
    """The handler of ops 1800-1802 dispatches through a byte table; cc_gettargetmask (op 1800) calls the getter with
    the client and the active component and pushes bits 11-16 of its return. The getter looks the component's id up in
    the map at client + 0x413d68 (an equal_range lookup over buckets at +8 and the count at +0x10, then the range's
    begin and end), walks the nodes comparing the component's slot with each node's first (+4) and last (+8) slot, and
    returns the node's events (+0xc) or the component's own (+0x2b0)."""
    HANDLER, GETTER, LOOKUP, BEGIN, END = 0x1000, 0x2000, 0x3000, 0x3800, 0x3c00

    def session(self, case=None, walk=None, own="MOV EAX,dword ptr [RAX + 0x2b0]"):
        labels = {}
        body = assemble(self.HANDLER, [
            "MOV RBX,RCX", "MOV R9,qword ptr [0x140e98c18]", "LEA EAX,[R8 + -0x708]", f"CMP EAX,{GET_OPS - 1:#x}",
            "JA @done", f"LEA R10,[{IMAGE:#x}]", "CDQE", f"MOVZX EAX,byte ptr [R10 + RAX*0x1 + {GET_INDEX:#x}]",
            f"MOV R8D,dword ptr [R10 + RAX*0x4 + {GET_CASES:#x}]", "ADD R8,R10",
            ":dispatch",
            "JMP R8",
            ":done",
            "RET",
            ":mask",
            *(case or ["LEA RDX,[RBX + 0xded8]", "MOV RCX,R9", "CALL @getter", "SAR EAX,0xb", "AND EAX,0x3f",
                       "MOV dword ptr [RBX + 0x50],EAX", "RET"]),
            ":other",
            "JMP @done",
        ], labels)
        getter = assemble(self.GETTER, [
            "PUSH RBX", "SUB RSP,0x40", "MOV RAX,qword ptr [RDX + 0x8]", "MOV RBX,RDX", "ADD RCX,0x413d68",
            "LEA RDX,[RSP + 0x20]", "MOV R8D,dword ptr [RAX + 0x4]", "MOV dword ptr [RSP + 0x50],R8D",
            "LEA R8,[RSP + 0x50]", "CALL @lookup", "LEA RCX,[RSP + 0x20]", "CALL @end", "LEA RCX,[RSP + 0x20]",
            "MOV R10,RAX", "CALL @begin", "MOV R8,RAX", "MOV RCX,qword ptr [RAX]", "CMP RCX,qword ptr [R10]",
            "JZ @own",
            ":walk",
            "MOV RDX,qword ptr [RBX + 0x8]", "MOV R9D,dword ptr [RDX + 0x8]",
            *(walk or WALK),
            ":step",
            "MOV RCX,qword ptr [RCX + 0x18]", "MOV qword ptr [R8],RCX", "CMP RCX,qword ptr [R10]", "JNZ @walk",
            "JMP @own",
            ":found",
            "MOV EAX,dword ptr [RCX + 0xc]", "RET",
            ":own",
            "MOV RAX,qword ptr [RBX + 0x8]", own, "ADD RSP,0x40", "POP RBX", "RET",
        ], labels)
        lookup = assemble(self.LOOKUP, LOOKUP, labels)
        labels.update(getter=self.GETTER, lookup=self.LOOKUP, begin=self.BEGIN, end=self.END)
        targets = [labels["mask"], labels["other"]]
        index = bytes([0, 1, 1])
        table = b"".join(t.to_bytes(4, "little") for t in targets)
        functions = {
            self.HANDLER: resolve(body, labels),
            self.GETTER: resolve(getter, labels),
            self.LOOKUP: resolve(lookup, labels),
            self.BEGIN: ["MOV RAX,RCX", "RET"],
            self.END: ["LEA RAX,[RCX + 0x10]", "RET"],
        }
        s = session_over(functions, {GET_INDEX: index, GET_CASES: table}, {labels["dispatch"]: tuple(targets)})
        s.values["IfType.subId"] = 8
        return s

    def test_map_layout_and_events_fields(self):
        s = self.session()
        found = components.cs2_click_mask(s, {})
        self.assertEqual({k: v[0] for k, v in found.items()}, {
            "Client.ifEvents.buckets": 0x413d70, "Client.ifEvents.bucketCount": 0x413d78,
            "IfEventsNode.key": 0, "IfEventsNode.next": 0x18, "IfEventsNode.from": 4, "IfEventsNode.to": 8,
            "IfEventsNode.events": 0xc, "IfType.events": 0x2b0})
        self.assertEqual(found["IfType.events"][1][0]["fn"], self.GETTER)
        self.assertEqual(s.notes, [])

    def test_the_bounds_follow_the_jumps_not_the_offsets(self):
        walk = ["CMP R9D,dword ptr [RCX + 0x8]", "JL @step", "CMP R9D,dword ptr [RCX + 0x4]", "JLE @found"]
        out = {k: v[0] for k, v in components.cs2_click_mask(self.session(walk=walk), {}).items()}
        self.assertEqual((out["IfEventsNode.from"], out["IfEventsNode.to"]), (8, 4))

    def test_a_getter_comparing_one_bound_gives_nothing(self):
        s = self.session(walk=["CMP R9D,dword ptr [RCX + 0x8]", "JLE @found"])
        self.assertEqual(components.cs2_click_mask(s, {}), {})
        self.assertEqual(s.notes, [f"components: the click mask getter {self.GETTER:#x} doesn't compare the slot with "
                                   f"a first and a last slot of one node"])

    def test_a_getter_without_its_own_events_gives_nothing(self):
        s = self.session(own="MOV EAX,0x0")
        self.assertEqual(components.cs2_click_mask(s, {}), {})
        self.assertEqual(s.notes, [f"components: the click mask getter {self.GETTER:#x} returns 1 node fields and 0 "
                                   f"component fields, and steps along the chain 1 times"])

    def test_a_case_not_calling_with_the_client_gives_nothing(self):
        s = self.session(case=["LEA RDX,[RBX + 0xded8]", "MOV RCX,RBX", "CALL @getter", "RET"])
        self.assertEqual(components.cs2_click_mask(s, {}), {})
        self.assertEqual(s.notes, ["components: cc_gettargetmask's case (op 1800) calls 0 functions with the client"])
