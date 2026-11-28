"""The second mouse click recipe (clicks.py) and the stretch emulator it runs (analysis/emulate.py), over miniatures of
the network step as 243.1 and 241.3 build the two click packets."""
import struct
import unittest
from types import SimpleNamespace

from native_updater import clicks
from native_updater.analysis.emulate import Emulator
from native_updater.analysis.provenance import Provenance

from .fake_model import IMAGE_BASE, FakeModel

CLIENT = 0xe98c18
SIZES = 0xc02220
DECODE, HANDLER, CYCLE, CREATE, SEND, RELEASE, WRITE = 0xc000, 0xd000, 0x10000, 0x8000, 0xa000, 0xb000, 0xf000

# The six-byte click, then the gate on the second pending click's x, its clamps and its create (243.1's 0x6db56).
V1 = ["MOV R8,qword ptr [RSI + 0x31e8]", "LEA RCX,[RSP + 0x40]", "ADD R8,0xa08", "MOV EDX,0x2b",
      f"CALL {IMAGE_BASE + CREATE:#x}",
      "LEA RCX,[RSP + 0x48]", "MOV EDX,EBX", f"CALL {IMAGE_BASE + WRITE:#x}",
      "LEA RCX,[RSP + 0x48]", "MOV EDX,R14D", f"CALL {IMAGE_BASE + WRITE:#x}",
      "LEA RCX,[RSP + 0x48]", "MOV EDX,EDI", f"CALL {IMAGE_BASE + WRITE:#x}",
      "MOV RCX,qword ptr [RSI + 0x31e8]", "LEA RDX,[RSP + 0x40]", f"CALL {IMAGE_BASE + SEND:#x}",
      "LEA RCX,[RSP + 0x48]", f"CALL {IMAGE_BASE + RELEASE:#x}"]


def gate(x, y, time, last, opcode, xreg, yreg, width=0x6c, height=0x70):
    """The pending click's x compared with -1, the delay since the last click capped at R15, x and y clamped to the
    canvas at [R13 + width] and [R13 + height] (labels: SKIP, XDONE, YDONE), then the create."""
    return [f"MOV ECX,dword ptr [RSI + {x:#x}]", "CMP ECX,-0x1", "JZ SKIP",
            f"MOV RAX,qword ptr [RSI + {time:#x}]", "MOV R14,RAX", f"SUB R14,qword ptr [RSI + {last:#x}]",
            "CMP R14,R15", f"MOV qword ptr [RSI + {last:#x}],RAX", f"MOV {xreg},dword ptr [R13 + {width:#x}]",
            f"MOV EAX,dword ptr [RSI + {y:#x}]", "CMOVA R14,R15", f"CMP {xreg},ECX", "JL XDONE",
            "TEST ECX,ECX", f"MOV {xreg},ECX", f"CMOVS {xreg},R12D",
            f"XDONE: MOV {yreg},dword ptr [R13 + {height:#x}]", f"CMP {yreg},EAX", "JL YDONE", "TEST EAX,EAX",
            f"MOV {yreg},EAX", f"CMOVS {yreg},R12D",
            "YDONE: MOV R8,qword ptr [RSI + 0x31e8]", "LEA RCX,[RSP + 0x60]", "ADD R8,0xa08", f"MOV EDX,{opcode:#x}",
            f"CALL {IMAGE_BASE + CREATE:#x}"]


TAIL = ["MOV RCX,qword ptr [RSI + 0x31e8]", "LEA RDX,[RSP + 0x60]", f"CALL {IMAGE_BASE + SEND:#x}",
        "LEA RCX,[RSP + 0x68]", f"CALL {IMAGE_BASE + RELEASE:#x}", "SKIP: RET"]

# 243.1 (EDI = x, EBX = y, R14 = delay): x little-endian, y plus 128 on its low byte, the button byte plus 128, the
# delay doubled with 128 on its low byte
STORES_243 = ["MOV R8,qword ptr [RSP + 0x80]", "MOV RDX,qword ptr [RSP + 0x78]", "MOV byte ptr [RDX + R8*0x1],DIL",
              "SAR EDI,0x8", "MOV byte ptr [RDX + R8*0x1 + 0x1],DIL", "MOV EAX,EBX", "SAR EAX,0x8", "ADD BL,0x80",
              "MOV byte ptr [RDX + R8*0x1 + 0x2],AL", "MOV byte ptr [RDX + R8*0x1 + 0x3],BL",
              "MOVZX ECX,byte ptr [RSI + 0x40f2cc]", "MOVZX EAX,byte ptr [RSI + 0x40f2c8]", "AND CL,0x1", "ADD AL,AL",
              "OR CL,AL", "ADD CL,0x80", "MOV byte ptr [RDX + R8*0x1 + 0x4],CL", "LEA ECX,[R14 + R14*0x1]",
              "MOV EAX,ECX", "SAR EAX,0x8", "MOV byte ptr [RDX + R8*0x1 + 0x5],AL", "ADD CL,0x80",
              "LEA RAX,[R8 + 0x7]", "MOV byte ptr [RDX + R8*0x1 + 0x6],CL", "MOV qword ptr [RSP + 0x80],RAX"]

# 241.3 (EDI = x, EBX = y): y and x little-endian with 128 on their low bytes, the delay, the negated button byte
STORES_241 = ["MOV R8,qword ptr [RSP + 0x80]", "LEA EAX,[RBX + 0x80]", "MOV RDX,qword ptr [RSP + 0x78]",
              "SAR EBX,0x8", "MOV byte ptr [RDX + R8*0x1],AL", "MOV byte ptr [RDX + R8*0x1 + 0x1],BL",
              "LEA EAX,[RDI + 0x80]", "SAR EDI,0x8", "MOV byte ptr [RDX + R8*0x1 + 0x2],AL",
              "MOV byte ptr [RDX + R8*0x1 + 0x3],DIL", "LEA ECX,[R14 + R14*0x1]", "MOV EAX,ECX", "SAR EAX,0x8",
              "ADD CL,0x80", "MOV byte ptr [RDX + R8*0x1 + 0x4],AL", "MOV byte ptr [RDX + R8*0x1 + 0x5],CL",
              "MOVZX ECX,byte ptr [RSI + 0x40f28c]", "LEA RAX,[R8 + 0x7]", "MOV qword ptr [RSP + 0x80],RAX",
              "AND CL,0x1", "MOVZX EAX,byte ptr [RSI + 0x40f288]", "ADD AL,AL", "OR CL,AL", "NEG CL",
              "MOV byte ptr [RDX + R8*0x1 + 0x6],CL"]


def cycle(body):
    """The network step's lines with labels resolved to the FakeModel's 4-byte layout."""
    lines = ["MOV RSI,RCX", f"CALL {IMAGE_BASE + HANDLER:#x}", *body]
    labels = {}
    for n, line in enumerate(lines):
        if ": " in line:
            label, _, _ = line.partition(": ")
            labels[label] = CYCLE + 4 * n
    out = []
    for line in lines:
        line = line.partition(": ")[2] if ": " in line else line
        for label, rva in labels.items():
            if line.endswith(" " + label):
                line = line[:-len(label)] + f"{IMAGE_BASE + rva:#x}"
        out.append(line)
    return out


def session(body, sizes=None):
    table = sizes or {0x2b: 6, 0x11: 7, 0x1b: 7}
    program = {
        DECODE: ["RET"], HANDLER: [f"CALL {IMAGE_BASE + DECODE:#x}", "RET"],
        CREATE: ["MOVSXD RSI,EDX", f"LEA RCX,[{IMAGE_BASE + SIZES:#x}]", "MOV EDX,dword ptr [RCX + RSI*0x4]", "RET"],
        SEND: ["RET"], RELEASE: ["RET"],
        WRITE: ["MOV RAX,qword ptr [RCX + 0x18]", "MOV R8,qword ptr [RCX + 0x10]", "MOV word ptr [R8 + RAX*0x1],DX",
                "ADD qword ptr [RCX + 0x18],0x2", "RET"],
        CYCLE: cycle(body),
    }
    data = struct.pack("<64i", *[table.get(n, 3) for n in range(64)])
    model = FakeModel(program, data={SIZES: data})
    values = {"functions.packetCreate": CREATE, "functions.packetSend": SEND, "global.client": CLIENT,
              "functions.npcInfoDecode": DECODE, "enums.clientProt": {"EVENT_MOUSE_CLICK": 0x2b}}
    s = SimpleNamespace(model=model, prov=Provenance(model), values=values, notes=[])
    s.value = lambda key: s.values[key]
    s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "slot": slot}
    return s


class MouseClickV2Tests(unittest.TestCase):
    def test_the_243_layout_is_fitted_from_its_stores(self):
        s = session(V1 + gate(0x411228, 0x41122c, 0x411230, 0x413408, 0x11, "EDI", "EBX") + STORES_243 + TAIL)
        out = {k: v[0] for k, v in clicks.mouse_click_v2(s, {}).items()}
        self.assertEqual(out, {
            "enums.clientProt.EVENT_MOUSE_CLICK_V2": 0x11,
            "enums.mouseClickV2": {"X_LOW": 0, "X_HIGH": 1, "Y_HIGH": 2, "Y_LOW": 3, "BUTTONS": 4, "DELAY_HIGH": 5,
                                   "DELAY_LOW": 6},
            "enums.mouseClickV2Transform": {"X_LOW": 0, "X_HIGH": 0, "Y_HIGH": 0, "Y_LOW": 1, "BUTTONS": 1,
                                            "DELAY_HIGH": 0, "DELAY_LOW": 1}}, s.notes)
        note = clicks.mouse_click_v2(s, {})["enums.mouseClickV2"][2]
        self.assertIn("the button from client + 0x40f2c8, the flag from client + 0x40f2cc", note)

    def test_the_241_layout_has_another_order_and_a_negated_button_byte(self):
        s = session(V1 + gate(0x4111e8, 0x4111ec, 0x4111f0, 0x4133c8, 0x1b, "EDI", "EBX") + STORES_241 + TAIL)
        out = {k: v[0] for k, v in clicks.mouse_click_v2(s, {}).items()}
        self.assertEqual(out["enums.clientProt.EVENT_MOUSE_CLICK_V2"], 0x1b)
        self.assertEqual(out["enums.mouseClickV2"], {"Y_LOW": 0, "Y_HIGH": 1, "X_LOW": 2, "X_HIGH": 3, "DELAY_HIGH": 4,
                                                     "DELAY_LOW": 5, "BUTTONS": 6})
        self.assertEqual(out["enums.mouseClickV2Transform"], {"Y_LOW": 1, "Y_HIGH": 0, "X_LOW": 1, "X_HIGH": 0,
                                                              "DELAY_HIGH": 0, "DELAY_LOW": 1, "BUTTONS": 2})

    def test_registers_swapped_between_x_and_y_are_followed(self):
        s = session(V1 + gate(0x411228, 0x41122c, 0x411230, 0x413408, 0x11, "EBX", "EDI") + STORES_243 + TAIL)
        out = clicks.mouse_click_v2(s, {})["enums.mouseClickV2"][0]
        self.assertEqual((out["Y_LOW"], out["Y_HIGH"], out["X_HIGH"], out["X_LOW"]), (0, 1, 2, 3))

    def test_a_gated_field_clamped_by_the_height_is_refused_as_y(self):
        s = session(V1 + gate(0x411228, 0x41122c, 0x411230, 0x413408, 0x11, "EDI", "EBX", width=0x70, height=0x6c)
                    + STORES_243 + TAIL)
        self.assertEqual(clicks.mouse_click_v2(s, {}), {})
        self.assertIn("mouse click v2: a click beyond the canvas came back as 0x170, 0x16c, not clamped by the width "
                      "and then the height", s.notes)

    def test_a_packet_of_another_size_after_the_click_is_refused(self):
        s = session(V1 + gate(0x411228, 0x41122c, 0x411230, 0x413408, 0x11, "EDI", "EBX") + STORES_243 + TAIL,
                    sizes={0x2b: 6, 0x11: 6})
        self.assertEqual(clicks.mouse_click_v2(s, {}), {})
        self.assertIn("mouse click v2: the packet after the six-byte click (opcode 17) is 6 bytes", s.notes)

    def test_a_byte_no_source_explains_is_refused(self):
        stores = [line.replace("ADD CL,0x80", "ADD CL,0x81") for line in STORES_243]
        s = session(V1 + gate(0x411228, 0x41122c, 0x411230, 0x413408, 0x11, "EDI", "EBX") + stores + TAIL)
        self.assertEqual(clicks.mouse_click_v2(s, {}), {})
        self.assertIn("mouse click v2: packet byte 4 fits 0 source bytes", s.notes)

    def test_a_call_inside_the_stretch_stops_the_run(self):
        stores = STORES_243[:3] + [f"CALL {IMAGE_BASE + RELEASE:#x}"] + STORES_243[3:]
        s = session(V1 + gate(0x411228, 0x41122c, 0x411230, 0x413408, 0x11, "EDI", "EBX") + stores + TAIL)
        self.assertEqual(clicks.mouse_click_v2(s, {}), {})
        self.assertTrue(any("a call other than the packet's create" in n for n in s.notes), s.notes)


class EmulatorTests(unittest.TestCase):
    def run_lines(self, lines, regs=None, load=lambda addr, size, op: 0):
        model = FakeModel({0x1000: lines + ["RET"]})
        insns = model.insns(0x1000)
        emu = Emulator(dict(regs or {}), load)
        self.assertTrue(emu.run(insns, insns[0].rva, insns[-1].rva), emu.stopped)
        return emu

    def test_byte_writes_merge_and_dword_writes_clear_the_upper_half(self):
        emu = self.run_lines(["MOV RAX,-0x1", "MOV AL,0x12", "MOV RBX,-0x1", "MOV EBX,0x34"])
        self.assertEqual(emu.regs["RAX"], 0xffffffffffffff12)
        self.assertEqual(emu.regs["RBX"], 0x34)

    def test_compares_drive_signed_and_unsigned_conditions(self):
        emu = self.run_lines(["MOV EAX,0x5", "MOV ECX,-0x1", "CMP EAX,ECX", "SETL DL", "SETA BL", "MOV R8D,0x7",
                              "CMOVG R8D,EAX"])
        self.assertEqual(emu.regs["RDX"] & 0xff, 0, "5 < -1 is false signed")
        self.assertEqual(emu.regs["RBX"] & 0xff, 0, "5 > 0xffffffff is false unsigned")
        self.assertEqual(emu.regs["R8"], 5, "5 > -1 signed")

    def test_neg_and_shifts_and_loads(self):
        emu = self.run_lines(["MOVZX ECX,byte ptr [RSI + 0x10]", "NEG CL", "MOV EAX,-0x100", "SAR EAX,0x4",
                              "SHR EAX,0x18"], load=lambda addr, size, op: 3)
        self.assertEqual(emu.regs["RCX"] & 0xff, 0xfd)
        self.assertEqual(emu.regs["RAX"], 0xff)

    def test_a_condition_on_a_flag_a_shift_left_undefined_stops(self):
        model = FakeModel({0x1000: ["MOV EAX,0x3", "SHR EAX,0x1", "SETC CL", "RET"],
                           0x2000: ["MOV EAX,0x3", "SHR EAX,0x1", "SETZ CL", "RET"]})
        insns = model.insns(0x1000)
        emu = Emulator({}, lambda addr, size, op: 0)
        self.assertFalse(emu.run(insns, insns[0].rva, insns[-1].rva))
        self.assertIn("condition C on an undefined flag", emu.stopped)
        insns = model.insns(0x2000)
        emu = Emulator({}, lambda addr, size, op: 0)
        self.assertTrue(emu.run(insns, insns[0].rva, insns[-1].rva), "the zero flag is defined after a shift")
        self.assertEqual(emu.regs["RCX"] & 0xff, 0)

    def test_a_read_of_memory_stored_in_part_stops(self):
        model = FakeModel({0x1000: ["MOV byte ptr [RSI + 0x10],0x5", "MOV EAX,dword ptr [RSI + 0x10]", "RET"]})
        insns = model.insns(0x1000)
        emu = Emulator({"RSI": 0x1000}, lambda addr, size, op: 0)
        self.assertFalse(emu.run(insns, insns[0].rva, insns[-1].rva))
        self.assertIn("a read of memory stored only in part", emu.stopped)

    def test_unknown_instructions_and_high_bytes_stop(self):
        model = FakeModel({0x1000: ["MOV AH,0x1", "RET"], 0x2000: ["PUSHFQ", "RET"]})
        for fn, why in ((0x1000, "high-byte register"), (0x2000, "PUSHFQ")):
            insns = model.insns(fn)
            emu = Emulator({}, lambda addr, size, op: 0)
            self.assertFalse(emu.run(insns, insns[0].rva, insns[-1].rva))
            self.assertIn(why, emu.stopped)


if __name__ == "__main__":
    unittest.main()
