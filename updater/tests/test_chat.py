"""The chat history recipe (chat.py) over a miniature of the client's: the add function inserting into the type's
buffer map, filling a line in two calls down, filing it in the id map and raising ON_CHAT_TRANSMIT; the fill function
taking an id from the counter and storing the cycle, the type and the three strings."""
import re
import unittest
from types import SimpleNamespace

from native_updater import chat
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

IMAGE = 0x140000000
CYCLE = 0xe98c14
COUNTER = 0xe98b70
BUFFERS = 0x154fa18
LINES = 0x154fa48


def g(rva):
    return f"{IMAGE + rva:#x}"


def resolve(lines, labels):
    return [re.sub(r"@(\w+)", lambda m: f"{IMAGE + labels[m.group(1)]:#x}", line) for line in lines]


def assemble(entry, lines, labels):
    out, rva = [], entry
    for line in lines:
        if line.startswith(":"):
            labels[line[1:]] = rva
        else:
            out.append(line)
            rva += 4
    return out


class ChatHistoryTests(unittest.TestCase):
    ADD, BUF_INSERT, LINE_INSERT, BUF_ADD, FILL, NEXT_ID, ASSIGN, RAISER, OTHER = (
        0x1000, 0x2000, 0x2400, 0x2800, 0x2c00, 0x3000, 0x3400, 0x3800, 0x3c00)

    def add(self, line_key=True):
        """(type, name, text, sender): finds the type's buffer, adds a line to it, files the line under its id."""
        key = ["MOV R9,qword ptr [R14]", "MOV EAX,dword ptr [R9 + 0x10]"] if line_key else ["MOV EAX,EDI"]
        return [
            "MOV EDI,ECX", "MOV R13,RDX", "MOV R12,R8", "MOV R15,R9",
            "MOV qword ptr [RSP + 0x20],RDI", "LEA RDX,[RSP + 0x40]", "CALL @buf_insert",
            "MOV RAX,qword ptr [RSP + 0x40]", "MOV RCX,qword ptr [RAX + 0x8]", "MOV EDX,EDI", "MOV R8,R13",
            "MOV R9,R12", "MOV qword ptr [RSP + 0x20],R15", "CALL @buf_add",
            "MOV R14,qword ptr [RAX + 0x8]", *key, "MOV qword ptr [RSP + 0x20],RAX", "LEA RDX,[RSP + 0x58]",
            "CALL @line_insert",
            "MOV RCX,qword ptr [0x140e98c18]", "CALL @raiser",
            "RET",
        ]

    def fill(self, sender=True):
        """(line, type, name, text, sender): a fresh id, the cycle, the type, then each string unless it is the field."""
        tail = ["MOV RDX,qword ptr [RSP + 0x50]", "LEA RCX,[RDI + 0xa0]", "CMP RDX,RCX", "JZ @no_sender",
                "CALL @assign", ":no_sender"] if sender else []
        return [
            "MOV RDI,RCX", "MOV RSI,R9", "MOV R14,R8", "MOV EBX,EDX", "LEA RCX,[RSP + 0x30]", "CALL @next_id",
            f"MOV EAX,dword ptr [{g(CYCLE)}]", "MOV dword ptr [RDI + 0x8],EAX", "MOV dword ptr [RDI + 0x40],EBX",
            "LEA R9,[RDI + 0x48]", "CMP R14,R9", "JZ @no_name", "MOV RDX,R14", "MOV RCX,R9", "CALL @assign",
            ":no_name",
            "LEA RCX,[RDI + 0x70]", "CMP RSI,RCX", "JZ @no_text", "MOV RDX,RSI", "CALL @assign",
            ":no_text",
            *tail,
            "RET",
        ]

    @staticmethod
    def insert(buckets):
        """A hash map insert: the bucket count, then the bucket the key falls in."""
        return [f"MOV R8,qword ptr [{g(buckets + 8)}]", f"MOV RAX,qword ptr [{g(buckets)}]",
                "MOV RAX,qword ptr [RAX + RSI*0x8]", "RET"]

    def program(self, line_key=True, sender=True, second_adder=False):
        labels = {}
        parts = {
            self.ADD: assemble(self.ADD, self.add(line_key), labels),
            self.FILL: assemble(self.FILL, self.fill(sender), labels),
            self.BUF_INSERT: self.insert(BUFFERS),
            self.LINE_INSERT: self.insert(LINES),
            self.BUF_ADD: ["MOV RCX,qword ptr [RCX + 0x8]", "CALL @fill", "RET"],
            self.NEXT_ID: [f"MOV EAX,dword ptr [{g(COUNTER)}]", "INC EAX", f"MOV dword ptr [{g(COUNTER)}],EAX",
                           "RET"],
            self.ASSIGN: ["RET"],
            self.RAISER: ["RET"],
        }
        if second_adder:
            parts[self.OTHER] = ["CALL @raiser", "RET"]
        labels.update(buf_insert=self.BUF_INSERT, line_insert=self.LINE_INSERT, buf_add=self.BUF_ADD,
                      fill=self.FILL, next_id=self.NEXT_ID, assign=self.ASSIGN, raiser=self.RAISER)
        return {entry: resolve(lines, labels) for entry, lines in parts.items()}

    def session(self, **kwargs):
        model = FakeModel(self.program(**kwargs))
        s = SimpleNamespace(model=model, prov=Provenance(model), notes=[],
                            values={"global.cycle": CYCLE,
                                    "events.ON_CHAT_TRANSMIT": [{"fn": self.RAISER, "id": 14, "args": 1}]})
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_every_key_comes_from_the_add_and_fill_functions(self):
        s = self.session()
        out = {k: v[0] for k, v in chat.chat_history(s, {}).items()}
        self.assertEqual(out, {"functions.chatAddLine": self.ADD, "ChatLine.type": 0x40, "ChatLine.cycle": 0x8,
                               "ChatLine.name": 0x48, "ChatLine.text": 0x70, "ChatLine.sender": 0xa0,
                               "global.chatLines.nextId": COUNTER, "global.chatLines.buckets": LINES,
                               "global.chatLines.bucketCount": LINES + 8})
        self.assertEqual(s.notes, [])

    def test_a_second_caller_of_the_raiser_stops_the_recipe(self):
        s = self.session(second_adder=True)
        self.assertEqual(chat.chat_history(s, {}), {})
        self.assertTrue(any("2 functions call the ON_CHAT_TRANSMIT wrappers" in n for n in s.notes), s.notes)

    def test_a_line_without_a_third_string_leaves_the_layout_out(self):
        s = self.session(sender=False)
        out = chat.chat_history(s, {})
        self.assertEqual(set(out), {"functions.chatAddLine"})
        self.assertTrue(any("0 functions under" in n for n in s.notes), s.notes)

    def test_both_maps_keyed_by_the_type_leave_the_line_map_out(self):
        s = self.session(line_key=False)
        out = chat.chat_history(s, {})
        self.assertNotIn("global.chatLines.buckets", out)
        self.assertIn("ChatLine.text", out)
        self.assertTrue(any("2 maps keyed by the type and 0 others" in n for n in s.notes), s.notes)


if __name__ == "__main__":
    unittest.main()
