"""The spot animation recipe (spotanims.py) over a miniature of the client's: the NPC update decoder's mask decoder
passing the client cycle to the setter (and to another function taking the cycle), the setter looking the slot up in
the actor's vector, the actor's draw walking the vector and loading each spot animation's SpotAnimType, and the spot
animation constructor and the graphics object constructor, both SpotAnimType loader callers."""
import re
import unittest
from types import SimpleNamespace

from native_updater import spotanims
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

IMAGE = 0x140000000
CYCLE = 0xe98c14
VECTOR, OBJECT, STRIDE = 0x6f0, 0x10, 0x18
ID, HEIGHT, START, ANIM = 0x260, 0x268, 0x26c, 0x278


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


class ActorSpotAnimTests(unittest.TestCase):
    DECODER, MASKS, SETTER, LOWER, OTHER = 0x1000, 0x1400, 0x1800, 0x1c00, 0x2000
    DRAW, CTOR, GRAPHIC_CTOR, SEQUENCE, SEQ_LOADER, LOADER = 0x2400, 0x2800, 0x2c00, 0x3000, 0x3400, 0x3800

    @staticmethod
    def call_with_cycle(target):
        """(actor, slot, id, height, delay, cycle)."""
        return ["MOV R10D,0x2", "MOV RCX,R14", "MOV EDX,EDI", "MOV R8D,EBX", "MOV R9D,ESI",
                "MOV dword ptr [RSP + 0x20],R10D",
                f"MOV EAX,dword ptr [{IMAGE + CYCLE:#x}]", "MOV dword ptr [RSP + 0x28],EAX", f"CALL @{target}"]

    @staticmethod
    def setter(compares=True):
        """Looks the slot up in the vector and checks the found element's key."""
        check = ["CMP EBX,dword ptr [RAX]"] if compares else []
        return ["MOV RDI,RCX", "MOV EBX,EDX", f"MOV RDX,qword ptr [RCX + {VECTOR + 8:#x}]",
                f"MOV RCX,qword ptr [RCX + {VECTOR:#x}]", "CALL @lower", *check, "RET"]

    @staticmethod
    def draw(vector=VECTOR):
        """Walks the actor's elements: skips one that hasn't started, loads the SpotAnimType of one that has."""
        return [
            f"MOV R12,qword ptr [R8 + {vector + 8:#x}]", f"MOV RBP,qword ptr [R8 + {vector:#x}]",
            ":loop",
            f"MOV R14,qword ptr [RBP + {OBJECT:#x}]", f"MOV EAX,dword ptr [R14 + {START:#x}]",
            f"CMP dword ptr [{IMAGE + CYCLE:#x}],EAX", "JL @next",
            f"MOV EDX,dword ptr [R14 + {ID:#x}]", "LEA RCX,[RSP + 0x50]", "CALL @loader",
            ":next",
            f"ADD RBP,{STRIDE:#x}", "CMP RBP,R12", "JNZ @loop",
            "RET",
        ]

    @staticmethod
    def ctor():
        """(object, id, height, start cycle, flag): stores the fields, loads the SpotAnimType, sets the sequence."""
        return ["MOV RBX,RCX", f"MOV dword ptr [RBX + {ID:#x}],EDX", f"MOV dword ptr [RBX + {HEIGHT:#x}],R8D",
                f"MOV dword ptr [RBX + {START:#x}],R9D", f"LEA RDI,[RBX + {ANIM:#x}]", "LEA RCX,[RSP + 0x20]",
                "CALL @loader", "MOV RCX,RDI", "MOV EDX,0x5", "CALL @sequence", "RET"]

    @staticmethod
    def graphic_ctor():
        """(object, world, id, ...): stores the id from its third argument and loads its SpotAnimType."""
        return ["MOV RBX,RCX", "MOV dword ptr [RBX + 0x274],R8D", "MOV EDX,R8D", "LEA RCX,[RSP + 0x20]",
                "CALL @loader", "RET"]

    def program(self, compares=True, vector=VECTOR):
        labels = {}
        parts = {
            self.DECODER: ["CALL @masks", "RET"],
            self.MASKS: [*self.call_with_cycle("other"), *self.call_with_cycle("setter"), "RET"],
            self.SETTER: self.setter(compares),
            self.LOWER: ["RET"],
            self.OTHER: ["MOV EAX,dword ptr [RSP + 0x30]", "RET"],
            self.DRAW: assemble(self.DRAW, self.draw(vector), labels),
            self.CTOR: self.ctor(),
            self.GRAPHIC_CTOR: self.graphic_ctor(),
            self.SEQUENCE: ["CALL @seq_loader", "RET"],
            self.SEQ_LOADER: ["RET"],
            self.LOADER: ["RET"],
        }
        labels.update(masks=self.MASKS, setter=self.SETTER, lower=self.LOWER, other=self.OTHER, loader=self.LOADER,
                      sequence=self.SEQUENCE, seq_loader=self.SEQ_LOADER)
        return {entry: resolve(lines, labels) for entry, lines in parts.items()}

    def session(self, **kwargs):
        model = FakeModel(self.program(**kwargs))
        s = SimpleNamespace(model=model, prov=Provenance(model), notes=[],
                            values={"global.cycle": CYCLE, "functions.npcInfoDecode": self.DECODER})
        s.value = lambda key: s.values[key]
        s.fn = lambda ref: {"@ConfigLoader.13": self.LOADER, "@ConfigLoader.12": self.SEQ_LOADER}[ref]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_every_key_comes_from_the_setter_the_draw_and_the_constructor(self):
        s = self.session()
        out = {k: v[0] for k, v in spotanims.actor_spot_anims(s, {}).items()}
        self.assertEqual(out, {"functions.setSpotAnim": self.SETTER, "SpotAnimSlot.slot": 0,
                               "Actor.spotAnims": VECTOR, "Actor.spotAnimsEnd": VECTOR + 8,
                               "SpotAnimSlot.spotAnim": OBJECT, "SpotAnimSlot.size": STRIDE,
                               "ActorSpotAnim.id": ID, "ActorSpotAnim.startCycle": START,
                               "ActorSpotAnim.height": HEIGHT, "ActorSpotAnim.animState": ANIM})
        self.assertEqual(s.notes, [])

    def test_a_function_taking_the_cycle_without_a_slot_lookup_is_no_setter(self):
        s = self.session(compares=False)
        self.assertEqual(spotanims.actor_spot_anims(s, {}), {})
        self.assertTrue(any("0 functions under the NPC update decoder" in n for n in s.notes), s.notes)

    def test_a_draw_walking_another_vector_leaves_the_layout_out(self):
        s = self.session(vector=VECTOR + 0x20)
        out = {k: v[0] for k, v in spotanims.actor_spot_anims(s, {}).items()}
        self.assertEqual(out, {"functions.setSpotAnim": self.SETTER, "SpotAnimSlot.slot": 0})
        self.assertTrue(any("0 SpotAnim loader callers walk the vector" in n for n in s.notes), s.notes)


if __name__ == "__main__":
    unittest.main()
