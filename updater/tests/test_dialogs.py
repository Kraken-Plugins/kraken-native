"""The dialog packets recipe (dialogs.py) over a miniature of the cs2 runtime's 3100 range handler: the dispatch
through a case table and the cases that build the count, name, string and item dialog answers."""
import struct
import unittest
from types import SimpleNamespace

from native_updater import dialogs
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel
from .test_menuops import packet, send

IMAGE = 0x140000000
TABLE = 0x7000
CASES = 16


class DialogPacketsTests(unittest.TestCase):
    HANDLER, CREATE, SEND, RELEASE, WRITE_BYTE, WRITE_SHORT, WRITE_INT, MEMCPY = 0x1000, 0x8000, 0xa000, 0xb000, 0xf000, 0xf100, 0xf200, 0xf300

    def dispatch(self):
        return ["MOV RBX,RCX", "ADD EDX,0xfffff3e4", f"CMP EDX,{CASES - 1:#x}", "JA 0x140001f00", "MOVSXD RAX,EDX",
                f"LEA RDX,[{IMAGE:#x}]", f"MOV ECX,dword ptr [RDX + RAX*0x4 + {TABLE:#x}]", "ADD RCX,RDX", "JMP RCX"]

    def case(self, opcode, writer, copies=False):
        """A case: pop an int, create the packet, write it on the buffer with the writer (then copy the characters in
        with memcpy, for a string), send, release, return."""
        body = ["MOV R14D,dword ptr [RBX + 0x4c]", *packet(opcode, IMAGE + self.CREATE),
                "LEA RCX,[RSP + 0x48]", "MOV EDX,R14D", f"CALL {IMAGE + writer:#x}"]
        if copies:
            body += ["LEA RCX,[RSP + 0x60]", "MOV RDX,RSI", "MOV R8,R15", f"CALL {IMAGE + self.MEMCPY:#x}"]
        return body + [*send(IMAGE + self.SEND, IMAGE + self.RELEASE), "RET"]

    def program(self):
        """The handler: the dispatch, then the cases laid out one after another; the table points at each."""
        cases = {
            4: self.case(0x44, self.WRITE_INT),
            5: self.case(0x21, self.WRITE_BYTE, copies=True),
            6: self.case(0x3a, self.WRITE_BYTE, copies=True),
            15: self.case(0x3, self.WRITE_SHORT),
        }
        handler = list(self.dispatch())
        default = self.HANDLER + 4 * len(handler)
        handler.append("RET")
        entries = {}
        for index, body in cases.items():
            entries[index] = self.HANDLER + 4 * len(handler)
            handler += body
        self.table = struct.pack("<16I", *[entries.get(i, default) for i in range(CASES)])
        writer = ["MOV RAX,qword ptr [RCX + 0x18]", "MOV R8,qword ptr [RCX + 0x10]", "RET"]
        return {
            self.HANDLER: handler,
            self.CREATE: ["RET"], self.SEND: ["RET"], self.RELEASE: ["RET"], self.MEMCPY: ["RET"],
            self.WRITE_BYTE: writer, self.WRITE_SHORT: writer, self.WRITE_INT: writer,
        }

    def session(self):
        program = self.program()
        model = FakeModel(program, data={TABLE: self.table})
        values = {"functions.packetCreate": self.CREATE, "functions.packetSend": self.SEND,
                  "functions.packetRelease": self.RELEASE, "functions.packetWriteByte": self.WRITE_BYTE,
                  "functions.packetWriteShort": self.WRITE_SHORT, "functions.packetWriteInt": self.WRITE_INT}
        s = SimpleNamespace(model=model, prov=Provenance(model), values=values, notes=[])
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_the_four_cases_give_their_opcodes(self):
        s = self.session()
        out = {k: v[0] for k, v in dialogs.dialog_packets(s, {}).items()}
        self.assertEqual(out, {"enums.clientProt.RESUME_P_COUNTDIALOG": 0x44, "enums.clientProt.RESUME_P_NAMEDIALOG": 0x21,
                               "enums.clientProt.RESUME_P_STRINGDIALOG": 0x3a, "enums.clientProt.RESUME_P_OBJDIALOG": 3})
        self.assertEqual(s.notes, [])

    def test_the_handler_and_its_table_are_read_from_the_dispatch(self):
        s = self.session()
        fn, cases = dialogs._range_handler(s, 3100)
        self.assertEqual(fn, self.HANDLER)
        self.assertEqual(len(cases), CASES)
        self.assertEqual(len({cases[i] for i in (4, 5, 6, 15)}), 4, "each dialog op has its own case")
        self.assertIsNone(dialogs._range_handler(s, 5000), "no handler subtracts 5000")

    def test_a_case_writing_with_the_wrong_writer_is_refused(self):
        s = self.session()
        s.values["functions.packetWriteInt"], s.values["functions.packetWriteShort"] = self.WRITE_SHORT, self.WRITE_INT
        out = dialogs.dialog_packets(s, {})
        self.assertNotIn("enums.clientProt.RESUME_P_COUNTDIALOG", out)
        self.assertNotIn("enums.clientProt.RESUME_P_OBJDIALOG", out)
        self.assertIn("enums.clientProt.RESUME_P_NAMEDIALOG", out)
        self.assertEqual(len([n for n in s.notes if "writes its packet with" in n]), 2)
