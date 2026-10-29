"""The varc setter recipe (varcs.py) over a miniature of the client's: the interpreter's opcode dispatch with its
GET_VARC_INT, SET_VARC_INT and SET_VARC_STRING cases, each calling into the varc store, and the store's getter and
setters (the setters insert into the store's entries, the getter only looks the id up)."""
import re
import unittest
from types import SimpleNamespace

from native_updater import varcs
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

IMAGE = 0x140000000
INDEX_TABLE, CASE_TABLE = 0x7000, 0x7100
OPS = 0x40
CLIENT = 0xe98c18
VARCS = 0x417ca8
ENTRIES = 0x48


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


class VarcSetterTests(unittest.TestCase):
    INNER, GET_INT, SET_INT, SET_STRING, SET_LONG, LOWER, INSERT = (0x1000, 0x2000, 0x2400, 0x2800, 0x2c00, 0x3000,
                                                                    0x3400)

    def interpreter(self, int_extra=(), string_setter="set_string", string_store=VARCS):
        """Fetches the op and jumps through the byte table and the case table. Each varc case loads the store off the
        client and calls into it with the id from the script's int operands; the set cases pop the value first."""
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
            ":get_case",
            f"MOV RAX,qword ptr [{IMAGE + CLIENT:#x}]", f"MOV RCX,qword ptr [RAX + {VARCS:#x}]",
            "MOV RAX,qword ptr [RBX + 0x48]", "MOV EDX,dword ptr [RAX + RSI*0x4]", "CALL @get_int",
            "JMP @loop",
            ":set_int_case",
            "MOVSXD R8,dword ptr [RDI + 0xff0]", f"MOV RAX,qword ptr [{IMAGE + CLIENT:#x}]",
            f"MOV RCX,qword ptr [RAX + {VARCS:#x}]", "LEA EAX,[R8 + -0x1]", "MOV dword ptr [RDI + 0xff0],EAX",
            "MOV RDX,qword ptr [RBX + 0x48]", "MOV R8D,dword ptr [RDI + R8*0x4 + 0x4c]",
            "MOV EDX,dword ptr [RDX + RSI*0x4]", "CALL @set_int",
            *int_extra,
            "JMP @loop",
            ":set_string_case",
            f"MOV RAX,qword ptr [{IMAGE + CLIENT:#x}]", f"MOV RCX,qword ptr [RAX + {string_store:#x}]",
            "DEC dword ptr [RDI + 0xac38]", "MOVSXD RAX,dword ptr [RDI + 0xac38]", "LEA R8,[0x1ff + RAX*0x4]",
            "ADD R8,RAX", "MOV RAX,qword ptr [RBX + 0x48]", "MOV EDX,dword ptr [RAX + RSI*0x4]",
            "LEA R8,[RDI + R8*0x8]", f"CALL @{string_setter}",
            "JMP @loop",
            ":other",
            "JMP @loop",
        ]

    @staticmethod
    def setter():
        """Finds the id's entry with the lower bound and inserts one into the entries."""
        return ["MOV RSI,RCX", f"MOV RDX,qword ptr [RCX + {ENTRIES + 8:#x}]", f"MOV RCX,qword ptr [RCX + {ENTRIES:#x}]",
                "CALL @lower", f"LEA RCX,[RSI + {ENTRIES:#x}]", "MOV RDX,RAX", "CALL @insert", "RET"]

    @staticmethod
    def getter():
        """Finds the id's entry with the lower bound and reads it."""
        return [f"MOV RDX,qword ptr [RCX + {ENTRIES + 8:#x}]", f"MOV RCX,qword ptr [RCX + {ENTRIES:#x}]",
                "CALL @lower", "MOV EAX,dword ptr [RAX + 0x8]", "RET"]

    def program(self, **kwargs):
        labels = {}
        parts = {
            self.INNER: assemble(self.INNER, self.interpreter(**kwargs), labels),
            self.GET_INT: assemble(self.GET_INT, self.getter(), labels),
            self.SET_INT: assemble(self.SET_INT, self.setter(), labels),
            self.SET_STRING: assemble(self.SET_STRING, self.setter(), labels),
            self.SET_LONG: assemble(self.SET_LONG, self.setter(), labels),
            self.LOWER: ["RET"],
            self.INSERT: ["RET"],
        }
        labels.update(get_int=self.GET_INT, set_int=self.SET_INT, set_string=self.SET_STRING, set_long=self.SET_LONG,
                      lower=self.LOWER, insert=self.INSERT)
        cases = {op: labels["other"] for op in range(OPS)}
        cases[42] = labels["get_case"]
        cases[43] = labels["set_int_case"]
        cases[50] = labels["set_string_case"]
        targets = sorted(set(cases.values()))
        index = bytes(targets.index(cases[op]) for op in range(OPS))
        table = b"".join(t.to_bytes(4, "little") for t in targets)
        functions = {entry: resolve(lines, labels) for entry, lines in parts.items()}
        return functions, {INDEX_TABLE: index, CASE_TABLE: table}, {labels["dispatch"]: tuple(targets)}

    def session(self, **kwargs):
        functions, data, flows = self.program(**kwargs)
        model = FakeModel(functions, data=data, flows=flows)
        s = SimpleNamespace(model=model, prov=Provenance(model), notes=[],
                            values={"functions.executeScriptInner": self.INNER, "global.client": CLIENT,
                                    "Client.varcs": VARCS, "Varcs.entries": ENTRIES})
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_each_set_case_gives_its_setter(self):
        s = self.session()
        out = {k: v[0] for k, v in varcs.varc_setters(s, {}).items()}
        self.assertEqual(out, {"functions.setVarcInt": self.SET_INT, "functions.setVarcString": self.SET_STRING})
        self.assertEqual(s.notes, [])

    def test_a_case_calling_two_setters_leaves_its_key_out(self):
        extra = [f"MOV RAX,qword ptr [{IMAGE + CLIENT:#x}]", f"MOV RCX,qword ptr [RAX + {VARCS:#x}]", "CALL @set_long"]
        s = self.session(int_extra=extra)
        out = {k: v[0] for k, v in varcs.varc_setters(s, {}).items()}
        self.assertEqual(out, {"functions.setVarcString": self.SET_STRING})
        self.assertTrue(any("SET_VARC_INT case (op 43) calls 2 functions" in n for n in s.notes), s.notes)

    def test_a_call_on_another_client_field_is_not_a_setter(self):
        s = self.session(string_store=VARCS + 8)
        out = {k: v[0] for k, v in varcs.varc_setters(s, {}).items()}
        self.assertEqual(out, {"functions.setVarcInt": self.SET_INT})
        self.assertTrue(any("SET_VARC_STRING case (op 50) calls 0 functions" in n for n in s.notes), s.notes)

    def test_both_cases_calling_one_setter_stops_the_recipe(self):
        s = self.session(string_setter="set_int")
        self.assertEqual(varcs.varc_setters(s, {}), {})
        self.assertTrue(any("call the same setter" in n for n in s.notes), s.notes)


if __name__ == "__main__":
    unittest.main()
