"""The menu ops recipe (menuops.py) over a miniature of the NPC menu builder, its row body and sender, the packet
helpers and the network step's mouse click."""
import struct
import unittest
from types import SimpleNamespace

from native_updater import menuops
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

CLIENT = 0xe98c18
SIZES = 0xc02220


def packet(opcode, create):
    """Create a packet on the stack with the connection's cipher: create(&pkt, opcode, [client + 0x31a0] + 0xa08)."""
    return ["MOV RAX,qword ptr [0x140e98c18]", "MOV R8,qword ptr [RAX + 0x31a0]", "ADD R8,0xa08",
            f"MOV EDX,{opcode:#x}", "LEA RCX,[RSP + 0x40]", f"CALL {create:#x}"]


def send(send_fn, release):
    return ["MOV RAX,qword ptr [0x140e98c18]", "MOV RCX,qword ptr [RAX + 0x31a0]", "LEA RDX,[RSP + 0x40]",
            f"CALL {send_fn:#x}", "LEA RCX,[RSP + 0x48]", f"CALL {release:#x}"]


class MenuOpsTests(unittest.TestCase):
    BUILDER, SETTER, ADDER, BODY, LOOKUP, NPC_LOOKUP, SENDER = 0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x5100, 0x6000
    TRAMPOLINE, CREATE, SEND, RELEASE, WRITE = 0x9000, 0x8000, 0xa000, 0xb000, 0xf000
    DECODE, HANDLER, CYCLE = 0xc000, 0xd000, 0xe000
    IMAGE = 0x140000000

    def program(self):
        return {
            self.BUILDER: [f"CALL {self.IMAGE + self.SETTER:#x}", f"CALL {self.IMAGE + self.ADDER:#x}", "RET"],
            self.SETTER: ["MOV byte ptr [RCX + 0x146],0x2", "RET"],
            self.ADDER: [f"LEA RAX,[{self.IMAGE + self.BODY:#x}]", f"LEA RCX,[{self.IMAGE + self.TRAMPOLINE:#x}]", "RET"],
            # body(unused, captures): look the NPC up by [captures], then sender(ctx, npc, [captures + 4], [captures + 8])
            self.BODY: ["MOV RSI,RDX", "MOV R8D,dword ptr [RDX]", "MOV RCX,qword ptr [0x140e98c18]", "LEA RDX,[RSP + 0x20]",
                        f"CALL {self.IMAGE + self.LOOKUP:#x}", "MOV RDX,qword ptr [RAX + 0x8]",
                        "MOV R8D,dword ptr [RSI + 0x4]", "MOV R9D,dword ptr [RSI + 0x8]", f"CALL {self.IMAGE + self.SENDER:#x}",
                        "RET"],
            self.LOOKUP: [f"CALL {self.IMAGE + self.NPC_LOOKUP:#x}", "RET"],
            self.NPC_LOOKUP: ["RET"],
            self.SENDER: [*packet(0x4c, self.IMAGE + self.CREATE), *packet(0x5c, self.IMAGE + self.CREATE),
                          *packet(0x2c, self.IMAGE + self.CREATE), *packet(0x56, self.IMAGE + self.CREATE),
                          *send(self.IMAGE + self.SEND, self.IMAGE + self.RELEASE), "RET"],
            self.TRAMPOLINE: ["RET"],
            # create(pkt, opcode, cipher): reads the size table with the opcode
            self.CREATE: ["MOVSXD RSI,EDX", f"LEA RCX,[{self.IMAGE + SIZES:#x}]", "MOV EDX,dword ptr [RCX + RSI*0x4]",
                          "MOV dword ptr [R14 + 0x4],EDX", "RET"],
            self.SEND: ["RET"], self.RELEASE: ["RET"], self.WRITE: ["RET"],
            self.DECODE: ["RET"],
            self.HANDLER: [f"CALL {self.IMAGE + self.DECODE:#x}", "RET"],
            # the network step: client in RCX; the mouse click packet is opcode 7, three shorts
            self.CYCLE: ["MOV RBX,RCX", f"CALL {self.IMAGE + self.HANDLER:#x}",
                         "MOV R8,qword ptr [RBX + 0x31a0]", "ADD R8,0xa08", "MOV EDX,0x7", "LEA RCX,[RSP + 0x40]",
                         f"CALL {self.IMAGE + self.CREATE:#x}",
                         "LEA RCX,[RSP + 0x48]", "MOV EDX,0x5", f"CALL {self.IMAGE + self.WRITE:#x}",
                         "LEA RCX,[RSP + 0x48]", "MOV EDX,0x6", f"CALL {self.IMAGE + self.WRITE:#x}",
                         "LEA RCX,[RSP + 0x48]", "MOV EDX,0x8", f"CALL {self.IMAGE + self.WRITE:#x}",
                         "MOV RCX,qword ptr [RBX + 0x31a0]", "LEA RDX,[RSP + 0x40]", f"CALL {self.IMAGE + self.SEND:#x}",
                         "RET"],
        }

    def session(self):
        sizes = struct.pack("<16i", *([3] * 7 + [6] + [3] * 8))
        model = FakeModel(self.program(), data={SIZES: sizes})
        values = {"MinimenuEntry.subjectType": 0x146, "enums.minimenuSubject": {"TILE": 1, "NPC": 2, "LOC": 3, "OBJ": 4,
                                                                                 "PLAYER": 6, "COMPONENT": 7},
                  "functions.npcLookup": self.NPC_LOOKUP, "functions.playerLookup": 0x5200,
                  "functions.npcInfoDecode": self.DECODE, "global.client": CLIENT}
        s = SimpleNamespace(model=model, prov=Provenance(model), values=values, notes=[])
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_npc_body_helpers_and_mouse_click(self):
        s = self.session()
        out = {k: v[0] for k, v in menuops.menu_ops(s, {}).items()}
        self.assertEqual(out, {
            "functions.menuNpcOp": self.BODY, "functions.packetCreate": self.CREATE, "functions.packetSend": self.SEND,
            "functions.packetRelease": self.RELEASE, "Client.connection": 0x31a0, "Connection.outCipher": 0xa08,
            "functions.packetWriteShort": self.WRITE, "enums.clientProt": {"EVENT_MOUSE_CLICK": 7}})
        self.assertEqual([n for n in s.notes if "LOC" in n or "OBJ" in n or "PLAYER" in n], [
            "menu ops: 0 functions store subject kind LOC (3)", "menu ops: 0 functions store subject kind OBJ (4)",
            "menu ops: 0 functions store subject kind PLAYER (6)"])

    def test_a_body_passing_other_captures_is_not_the_op(self):
        s = self.session()
        # the sender gets [captures + 4] and [captures + 8], the {index, op, flag} shape, and no other
        self.assertTrue(menuops._hands_captures(s, self.BODY, self.SENDER, (4, 8)))
        self.assertFalse(menuops._hands_captures(s, self.BODY, self.SENDER, (4, 0xc)))
        self.assertIsNone(menuops._sender(s, self.TRAMPOLINE, None), "a body that builds no packets has no sender")
