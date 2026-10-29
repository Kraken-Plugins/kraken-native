"""The hitmark recipe (hitmarks.py) over a miniature of the client's: the NPC update decoder's mask decoder passing the
client cycle to the add function (and to another function taking the cycle), the add function building an entry with
a constructor and copying it into the actor's vector of 12-byte entries."""
import re
import unittest
from types import SimpleNamespace

from native_updater import hitmarks
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

IMAGE = 0x140000000
CYCLE = 0xe98c14
VECTOR = 0x330


def resolve(lines, labels):
    return [re.sub(r"@(\w+)", lambda m: f"{IMAGE + labels[m.group(1)]:#x}", line) for line in lines]


class ActorHitmarkTests(unittest.TestCase):
    DECODER, MASKS, ADD, CTOR, OTHER, PLAYER = 0x1000, 0x1400, 0x2000, 0x2400, 0x2800, 0x2c00

    @staticmethod
    def call_with_cycle(target):
        return ["MOV RCX,R14", "MOV EDX,R10D", "MOV R8D,EBX", "MOV R9D,ESI",
                f"MOV EAX,dword ptr [{IMAGE + CYCLE:#x}]", "MOV dword ptr [RSP + 0x20],EAX", f"CALL @{target}"]

    def add(self, end_load=True):
        """(actor, type, value, delay, cycle, slot): builds the entry, then copies it into the slot of the vector."""
        end = [f"MOV RAX,qword ptr [RBX + {VECTOR + 8:#x}]"] if end_load else []
        return [
            "MOV RBX,RCX", "MOV ESI,dword ptr [RSP + 0x30]", "MOV EAX,dword ptr [RSP + 0x28]", "ADD EAX,R9D",
            "MOV R9D,EAX", "LEA RCX,[RSP + 0x40]", "CALL @ctor",
            f"MOV R10,qword ptr [RBX + {VECTOR:#x}]", *end, "LEA RAX,[RSI + RSI*0x2]", "LEA RCX,[R10 + RAX*0x4]",
            "MOV RDX,qword ptr [RSP + 0x40]", "MOV qword ptr [RCX],RDX",
            "RET",
        ]

    def program(self, end_load=True):
        parts = {
            self.DECODER: ["CALL @masks", "RET"],
            self.MASKS: [*self.call_with_cycle("other"), *self.call_with_cycle("add"), "RET"],
            self.ADD: self.add(end_load),
            self.CTOR: ["MOV dword ptr [RCX],EDX", "MOV RAX,RCX", "MOV dword ptr [RCX + 0x4],R8D",
                        "MOV dword ptr [RCX + 0x8],R9D", "RET"],
            self.OTHER: ["MOV EAX,dword ptr [RSP + 0x28]", "RET"],
            self.PLAYER: [*self.call_with_cycle("add"), "RET"],
        }
        labels = dict(masks=self.MASKS, add=self.ADD, ctor=self.CTOR, other=self.OTHER)
        return {entry: resolve(lines, labels) for entry, lines in parts.items()}

    def session(self, **kwargs):
        model = FakeModel(self.program(**kwargs))
        s = SimpleNamespace(model=model, prov=Provenance(model), notes=[],
                            values={"global.cycle": CYCLE, "functions.npcInfoDecode": self.DECODER})
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_every_key_comes_from_the_add_function_and_its_entry_constructor(self):
        s = self.session()
        out = {k: v[0] for k, v in hitmarks.actor_hitmarks(s, {}).items()}
        self.assertEqual(out, {"functions.addHitmark": self.ADD, "Actor.hitmarks": VECTOR,
                               "Actor.hitmarksEnd": VECTOR + 8, "Hitmark.size": 12, "Hitmark.type": 0,
                               "Hitmark.value": 4, "Hitmark.endCycle": 8})
        self.assertEqual(s.notes, [])

    def test_a_vector_without_its_end_pointer_read_is_no_hitmark_vector(self):
        s = self.session(end_load=False)
        self.assertEqual(hitmarks.actor_hitmarks(s, {}), {})
        self.assertTrue(any("0 functions under the NPC update decoder" in n for n in s.notes), s.notes)


if __name__ == "__main__":
    unittest.main()
