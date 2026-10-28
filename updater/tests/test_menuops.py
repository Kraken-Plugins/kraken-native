"""The menu ops recipe (menuops.py) over a miniature of the NPC menu builder, its row body and sender, the packet
helpers and their writers, the network step's mouse click and the Walk here row body."""
import struct
import unittest
from types import SimpleNamespace

from native_updater import menuops
from native_updater.analysis.provenance import Provenance

from .fake_model import FakeModel

CLIENT = 0xe98c18
SIZES = 0xc02220
LAST_WALK = 0x155e590


def packet(opcode, create):
    """Create a packet on the stack with the connection's cipher: create(&pkt, opcode, [client + 0x31a0] + 0xa08)."""
    return ["MOV RAX,qword ptr [0x140e98c18]", "MOV R8,qword ptr [RAX + 0x31a0]", "ADD R8,0xa08",
            f"MOV EDX,{opcode:#x}", "LEA RCX,[RSP + 0x40]", f"CALL {create:#x}"]


def send(send_fn, release):
    return ["MOV RAX,qword ptr [0x140e98c18]", "MOV RCX,qword ptr [RAX + 0x31a0]", "LEA RDX,[RSP + 0x40]",
            f"CALL {send_fn:#x}", "LEA RCX,[RSP + 0x48]", f"CALL {release:#x}"]


def writer(step):
    """A buffer writer: store at [data + position], then advance the position by step."""
    advance = "INC qword ptr [RCX + 0x18]" if step == 1 else f"ADD qword ptr [RCX + 0x18],{step:#x}"
    return ["MOV RAX,qword ptr [RCX + 0x18]", "MOV R8,qword ptr [RCX + 0x10]", "MOV byte ptr [R8 + RAX*0x1],DL", advance,
            "RET"]


class MenuOpsTests(unittest.TestCase):
    BUILDER, SETTER, ADDER, BODY, LOOKUP, NPC_LOOKUP, SENDER = 0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x5100, 0x6000
    TRAMPOLINE, CREATE, SEND, RELEASE, WRITE = 0x9000, 0x8000, 0xa000, 0xb000, 0xf000
    WRITE_BYTE, WRITE_INT, WRITE_STRING, STRINGS = 0xf100, 0xf200, 0xf300, 0xf400
    DECODE, HANDLER, CYCLE = 0xc000, 0xd000, 0xe000
    TILE_BUILDER, WALK_STUB, WALK = 0x1100, 0x7000, 0x7100
    IMAGE = 0x140000000

    def program(self):
        strings = []
        for i in range(menuops.STRING_WRITER_MIN_USES):
            strings += [*packet(0x20 + i, self.IMAGE + self.CREATE),
                        "LEA RCX,[RSP + 0x48]", "MOV EDX,R14D", f"CALL {self.IMAGE + self.WRITE_INT:#x}",
                        "LEA RCX,[RSP + 0x48]", "MOV RDX,RSI", "MOV R8,RDI", "MOV R9D,0x0",
                        f"CALL {self.IMAGE + self.WRITE_STRING:#x}", *send(self.IMAGE + self.SEND, self.IMAGE + self.RELEASE)]
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
            self.SEND: ["RET"], self.RELEASE: ["RET"],
            self.WRITE: writer(2), self.WRITE_BYTE: writer(1), self.WRITE_INT: writer(4),
            self.WRITE_STRING: ["MOV RAX,qword ptr [RCX + 0x18]", "TEST R9B,R9B", "RET"],
            # five packets written with the string writer (buffer, chars, length, 0)
            self.STRINGS: strings + ["RET"],
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
            # the tile builder takes the walk stub's address; the stub jumps to the body, which compares its
            # {level, x, z} captures with the last walk target before building the walk packet
            self.TILE_BUILDER: [f"LEA RAX,[{self.IMAGE + self.WALK_STUB:#x}]", "RET"],
            self.WALK_STUB: [f"JMP {self.IMAGE + self.WALK:#x}"],
            self.WALK: [f"MOV EAX,dword ptr [{self.IMAGE + LAST_WALK:#x}]", "CMP dword ptr [RDX],EAX",
                        f"MOV EAX,dword ptr [{self.IMAGE + LAST_WALK + 4:#x}]", "CMP dword ptr [RDX + 0x4],EAX",
                        f"MOV EAX,dword ptr [{self.IMAGE + LAST_WALK + 8:#x}]", "CMP dword ptr [RDX + 0x8],EAX",
                        *packet(0x37, self.IMAGE + self.CREATE), "LEA RCX,[RSP + 0x48]", "MOV EDX,0x5",
                        f"CALL {self.IMAGE + self.WRITE_BYTE:#x}", *send(self.IMAGE + self.SEND, self.IMAGE + self.RELEASE),
                        "RET"],
        }

    def session(self):
        sizes = struct.pack("<16i", *([3] * 7 + [6] + [3] * 8))
        model = FakeModel(self.program(), data={SIZES: sizes})
        values = {"MinimenuEntry.subjectType": 0x146, "enums.minimenuSubject": {"TILE": 1, "NPC": 2, "LOC": 3, "OBJ": 4,
                                                                                 "PLAYER": 6, "COMPONENT": 7},
                  "functions.npcLookup": self.NPC_LOOKUP, "functions.playerLookup": 0x5200,
                  "functions.npcInfoDecode": self.DECODE, "global.client": CLIENT, "Client.minimenu": 0x4133f0}
        s = SimpleNamespace(model=model, prov=Provenance(model), values=values, notes=[])
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def test_npc_body_helpers_writers_click_and_walk(self):
        s = self.session()
        out = {k: v[0] for k, v in menuops.menu_ops(s, {}).items()}
        self.assertEqual(out, {
            "functions.menuNpcOp": self.BODY, "functions.packetCreate": self.CREATE, "functions.packetSend": self.SEND,
            "functions.packetRelease": self.RELEASE, "Client.connection": 0x31a0, "Connection.outCipher": 0xa08,
            "functions.packetWriteShort": self.WRITE, "enums.clientProt": {"EVENT_MOUSE_CLICK": 7},
            "functions.packetWriteByte": self.WRITE_BYTE, "functions.packetWriteInt": self.WRITE_INT,
            "functions.packetWriteString": self.WRITE_STRING, "functions.menuWalk": self.WALK_STUB})
        for note in ("menu ops: 0 functions store subject kind LOC (3)", "menu ops: 0 functions store subject kind OBJ (4)",
                     "menu ops: 0 functions store subject kind PLAYER (6)",
                     "menu ops: 0 functions store subject kind COMPONENT (7)",
                     "menu ops: 0 functions look like the selection getter"):
            self.assertIn(note, s.notes)

    def test_a_body_passing_other_captures_is_not_the_op(self):
        s = self.session()
        # the sender gets [captures + 4] and [captures + 8], the {index, op, flag} shape, and no other
        self.assertTrue(menuops._hands_captures(s, self.BODY, self.SENDER, (4, 8)))
        self.assertFalse(menuops._hands_captures(s, self.BODY, self.SENDER, (4, 0xc)))
        self.assertIsNone(menuops._sender(s, self.TRAMPOLINE, None), "a body that builds no packets has no sender")

    def test_the_walk_body_is_the_stub_a_builder_takes_the_address_of(self):
        s = self.session()
        row, rva, fn = menuops._walk_body(s, self.CREATE)
        self.assertEqual((row, fn), (self.WALK_STUB, self.WALK))
        self.assertEqual(rva, self.WALK + 4, "the first compare")
        self.assertEqual(menuops._row_address(s.model, self.SENDER), None, "no row holds the sender's address")

    def test_writers_are_told_apart_by_their_position_step(self):
        s = self.session()
        self.assertEqual(menuops._position_field(s.model, self.WRITE), 0x18)
        self.assertEqual(menuops._position_step(s.model, self.WRITE_BYTE, 0x18), 1)
        self.assertEqual(menuops._position_step(s.model, self.WRITE_INT, 0x18), 4)
        self.assertIsNone(menuops._position_step(s.model, self.WRITE_STRING, 0x18))
        found = menuops._writers(s, self.CREATE, self.SEND, self.RELEASE, self.WRITE)
        self.assertEqual({k: v[0] for k, v in found.items()},
                         {"Byte": self.WRITE_BYTE, "Int": self.WRITE_INT, "String": self.WRITE_STRING})
        self.assertEqual(found["String"][3], menuops.STRING_WRITER_MIN_USES, "every use counted")
