"""The script runtime recipe (scripts.py) over a miniature of the client's: the request entry that replaces the event
placeholders with the request's fields, the interpreter loop's opcode dispatch with its return and gosub cases, the
script lookup, and the wrappers that run a request or a trigger script on the static runner."""
import re
import unittest
from types import SimpleNamespace

from native_updater import scripts
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

IMAGE = 0x140000000
INDEX_TABLE, CASE_TABLE = 0x7000, 0x7100
OPS = 0x4d
OPBASE_STRING = 0x9000
RUNNER = 0x154fe50


def assemble(entry, lines, labels):
    """4-byte instructions laid out from entry: a ':name' line marks a label and '@name' in a line is that label's
    address. Fills labels and returns the instruction lines."""
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


class ScriptRunnerTests(unittest.TestCase):
    RUN, INNER, LOOKUP, SWAP, LOADER, WRAP, TRIGGER, OTHER = (0x1000, 0x2000, 0x3000, 0x3800, 0x3c00, 0x4000, 0x4400,
                                                              0x4800)

    def request_entry(self, component_id=0x4):
        """Checks the first argument is an int and looks its script up, then copies the other arguments into the
        locals: a long's eight bytes, a string (comparing it with "event_opbase", whose replacement is the request's
        base op name) or an int, replacing each placeholder with a request field. Then runs the interpreter."""
        placeholders = []
        for const, field, through in ((0x80000001, 0x48, None), (0x80000002, 0x4c, None), (0x80000003, 0x40, component_id),
                                      (0x80000004, 0x50, None), (0x80000005, 0x40, 0x8), (0x80000006, 0x60, component_id),
                                      (0x80000007, 0x60, 0x8), (0x80000008, 0x68, None), (0x80000009, 0x6c, None),
                                      (0x8000000a, 0x54, None)):
            label = f"after_{const:x}"
            placeholders += [f"CMP EAX,{const:#x}", f"JNZ @{label}"]
            if through is None:
                placeholders.append(f"MOV EAX,dword ptr [RSI + {field:#x}]")
            else:
                placeholders += [f"MOV RAX,qword ptr [RSI + {field:#x}]", f"MOV EAX,dword ptr [RAX + {through:#x}]"]
            placeholders.append(f":{label}")
        return [
            "MOV R15,RCX", "MOV RSI,RDX", "MOV R14D,R8D",
            "MOV RAX,qword ptr [RSI + 0x10]", "CMP byte ptr [RAX + 0x20],0x1", "JNZ @run_done",
            "MOV ECX,dword ptr [RAX]", "CALL @lookup",
            "MOV EDI,0x1",
            ":arg",
            "MOV RBX,qword ptr [RSI + 0x10]", "ADD RBX,0x28", "MOVSX RDX,byte ptr [RBX + 0x20]",
            "CMP DL,0x5", "JNZ @not_long", "MOV RAX,qword ptr [RBX]", "JMP @next",
            ":not_long",
            "CMP DL,0x2", "JNZ @not_string", f"LEA R9,[{IMAGE + OPBASE_STRING:#x}]", "LEA RDX,[RSI + 0x70]", "JMP @next",
            ":not_string",
            "CMP DL,0x1", "JNZ @next", "MOV EAX,dword ptr [RBX]",
            *placeholders,
            ":next",
            "INC EDI", "CMP EDI,dword ptr [RSI + 0x8]", "JL @arg",
            "MOV RCX,R15", "LEA RDX,[RSP + 0x40]", "MOV R8D,R14D", "CALL @inner",
            ":run_done",
            "RET",
        ]

    def interpreter(self):
        """Fetches the op, jumps through the byte table and the case table; the return case pops a frame (clearing
        its script) and swaps the caller's locals back, the gosub case looks the callee up by the int operand, pops
        its int and object arguments and swaps its locals in."""
        return [
            "MOV RDI,RCX", "LEA R15,[RDX + 0x8]", f"LEA R9,[{IMAGE:#x}]",
            ":loop",
            "MOV RBX,qword ptr [R15]", "MOV RAX,qword ptr [RBX + 0x30]", "INC R13D", "MOVSXD RSI,R13D",
            "MOVSXD RDX,dword ptr [RAX + RSI*0x4]", f"CMP EDX,{OPS - 1:#x}", "JA @default",
            f"MOVZX EAX,byte ptr [R9 + RDX*0x1 + {INDEX_TABLE:#x}]", f"MOV ECX,dword ptr [R9 + RAX*0x4 + {CASE_TABLE:#x}]",
            "ADD RCX,R9",
            ":dispatch",
            "JMP RCX",
            ":default",
            "RET",
            ":return",
            "MOVSXD RCX,dword ptr [RDI + 0xcbc0]", "TEST ECX,ECX", "JZ @default", "LEA EAX,[RCX + -0x1]", "XOR EDX,EDX",
            "MOV dword ptr [RDI + 0xcbc0],EAX", "LEA R14,[RCX + RCX*0x2]", "SHL R14,0x5", "ADD R14,0xcb68",
            "ADD R14,RDI", "MOV qword ptr [R14],RDX", "MOV qword ptr [R14 + 0x8],RDX",
            "LEA R9,[RDI + 0x38]", "LEA R8,[RDI + 0x20]", "MOV RCX,R14", "LEA RDX,[RDI + 0x8]", "CALL @swap",
            "JMP @loop",
            ":gosub",
            "MOVSXD RAX,dword ptr [RDI + 0xcbc0]", "LEA R12,[RAX + RAX*0x2]", "SHL R12,0x5", "ADD R12,0xcbc8",
            "ADD R12,RDI", "MOV RAX,qword ptr [RBX + 0x48]", "MOV ECX,dword ptr [RAX + RSI*0x4]", "CALL @lookup",
            "MOV RSI,qword ptr [R15]", "MOV ECX,dword ptr [RSI + 0x8c]", "MOV EAX,dword ptr [RDI + 0xff0]",
            "SUB EAX,ECX", "CDQE", "MOV EAX,dword ptr [RDI + RAX*0x4 + 0x50]",
            "MOV ECX,dword ptr [RSI + 0x94]", "XOR EAX,EAX", "SUB EAX,ECX", "ADD EAX,dword ptr [RDI + 0xac38]", "CDQE",
            "LEA RCX,[0x1ff + RAX*0x4]", "ADD RCX,RAX", "LEA R8,[RDI + RCX*0x8]", "MOVSX RCX,byte ptr [R8 + 0x20]",
            "LEA R9,[RDI + 0x38]", "LEA R8,[RDI + 0x20]", "MOV RCX,R12", "LEA RDX,[RDI + 0x8]", "CALL @swap",
            "INC dword ptr [RDI + 0xcbc0]", "JMP @loop",
            ":other",
            "JMP @loop",
        ]

    def lookup(self):
        """Loads the script into a shared_ptr through the loader and stores the id into it."""
        return ["MOV qword ptr [RSP + 0x18],RBX", "PUSH RSI", "SUB RSP,0x50", "MOVSXD R10,ECX", "MOV RBX,R10",
                "LEA RCX,[RSP + 0x30]", "CALL @loader", "MOV RAX,qword ptr [RSP + 0x38]",
                "MOV qword ptr [RAX + 0x18],RBX", "ADD RSP,0x50", "POP RSI", "RET"]

    def program(self, component_id=0x4, extra=None):
        labels = {}
        parts = {
            self.RUN: assemble(self.RUN, self.request_entry(component_id), labels),
            self.INNER: assemble(self.INNER, self.interpreter(), labels),
            self.LOOKUP: assemble(self.LOOKUP, self.lookup(), labels),
            self.SWAP: assemble(self.SWAP, ["RET"], labels),
            self.LOADER: assemble(self.LOADER, ["RET"], labels),
            self.WRAP: assemble(self.WRAP, [f"LEA RCX,[{IMAGE + RUNNER:#x}]", "CALL @run", "RET"], labels),
            self.TRIGGER: assemble(self.TRIGGER, [f"LEA RCX,[{IMAGE + RUNNER:#x}]", "LEA RDX,[RSP + 0x20]",
                                                  "CALL @inner", "RET"], labels),
        }
        if extra:
            parts[self.OTHER] = assemble(self.OTHER, extra, labels)
        labels.update(run=self.RUN, inner=self.INNER, lookup=self.LOOKUP, swap=self.SWAP, loader=self.LOADER)
        self.labels = labels
        cases = {op: labels["other"] for op in range(OPS)}
        cases[scripts.OP_RETURN] = labels["return"]
        cases[scripts.OP_GOSUB] = labels["gosub"]
        targets = sorted(set(cases.values()))
        index = bytes(targets.index(cases[op]) for op in range(OPS))
        table = b"".join(t.to_bytes(4, "little") for t in targets)
        functions = {entry: resolve(lines, labels) for entry, lines in parts.items()}
        return functions, {INDEX_TABLE: index, CASE_TABLE: table}, {labels["dispatch"]: tuple(targets)}

    def session(self, values=None, **kwargs):
        functions, data, flows = self.program(**kwargs)
        model = FakeModel(functions, strings={OPBASE_STRING: scripts.OPBASE}, data=data, flows=flows)
        s = SimpleNamespace(model=model, prov=Provenance(model), notes=[],
                            values=values if values is not None else {"container.shared_ptr.ptr": 8,
                                                                      "IfType.packedId": 4, "IfType.subId": 8})
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_every_key_comes_from_the_runtime(self):
        s = self.session()
        out = {k: v[0] for k, v in scripts.script_runner(s, {}).items()}
        self.assertEqual(out, {
            "functions.executeScript": self.RUN, "functions.executeScriptInner": self.INNER,
            "functions.scriptLookup": self.LOOKUP, "functions.scriptSwapLocals": self.SWAP,
            "global.scriptRunner": RUNNER,
            "ScriptRunner.intStack": 0x50, "ScriptRunner.intStackSize": 0xff0, "ScriptRunner.objStack": 0xff8,
            "ScriptRunner.objStackSize": 0xac38, "ScriptRunner.frameCount": 0xcbc0, "ScriptFrame.script": 0,
            "ClientScript.id": 0x18, "VarValue.type": 0x20, "VarValue.size": 0x28,
            "enums.varValueType": {"INT": 1, "STRING": 2, "LONG": 5},
            "HookReq.argCount": 0x8, "HookReq.args": 0x10, "HookReq.source": 0x40, "HookReq.target": 0x60,
            "HookReq.op": 0x50, "HookReq.subOp": 0x54, "HookReq.mouseX": 0x48, "HookReq.mouseY": 0x4c,
            "HookReq.keyCode": 0x68, "HookReq.keyChar": 0x6c, "HookReq.opbase": 0x70,
        })
        self.assertEqual(s.notes, [])

    def test_the_cases_come_from_the_byte_table_and_the_case_table(self):
        s = self.session()
        cases, bounds = scripts._op_cases(s.model, self.INNER)
        self.assertEqual(len(cases), OPS)
        self.assertEqual(cases[scripts.OP_RETURN], self.labels["return"])
        self.assertEqual(cases[scripts.OP_GOSUB], self.labels["gosub"])
        self.assertEqual(cases[0], self.labels["other"])
        self.assertEqual(bounds, sorted({self.labels["return"], self.labels["gosub"], self.labels["other"]}))

    def test_a_component_field_other_than_the_mapped_id_is_noted(self):
        s = self.session(component_id=0x10)
        out = scripts.script_runner(s, {})
        self.assertEqual(out["HookReq.source"][0], 0x40, "the request field is still the pointer")
        self.assertTrue(any("IfType.packedId is 0x4" in n for n in s.notes), s.notes)

    def test_a_second_reference_to_the_opbase_string_stops_the_recipe(self):
        s = self.session(extra=[f"LEA RCX,[{IMAGE + OPBASE_STRING:#x}]", "RET"])
        self.assertEqual(scripts.script_runner(s, {}), {})
        self.assertTrue(any("2 functions reference" in n for n in s.notes), s.notes)
