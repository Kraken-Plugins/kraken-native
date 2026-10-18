"""Unit tests that need neither Ghidra nor the client binary."""
import os
import struct
import unittest
from pathlib import Path, PurePath
from types import SimpleNamespace
from unittest import mock

from native_updater import emit, identity, winpaths, x86, verify
from native_updater.analysis.provenance import Arg, Const, Deref, Global, Provenance, Ret
from native_updater.consensus import Result
from native_updater.pe import file_version
from native_updater.spec import KeySpec, lint, load as load_spec

from .fake_model import FakeModel


class X86Parse(unittest.TestCase):
    def test_memory_operand(self):
        insn = x86.parse("MOV EAX,dword ptr [RCX + RAX*0x4 + 0x3350]", 0x10, 7, 0x140000000)
        self.assertEqual(insn.mnem, "MOV")
        dst, src = insn.ops
        self.assertEqual((dst.kind, dst.reg, dst.size), ("reg", "RAX", 4))
        self.assertEqual((src.kind, src.base, src.index, src.scale, src.disp, src.size), ("mem", "RCX", "RAX", 4, 0x3350, 4))

    def test_rip_relative_becomes_rva(self):
        insn = x86.parse("MOV RBP,qword ptr [0x140e98c18]", 0x10, 7, 0x140000000)
        self.assertEqual((insn.ops[1].base, insn.ops[1].disp), ("RIP", 0xE98C18))

    def test_call_target(self):
        insn = x86.parse("CALL 0x1401878b0", 0x10, 5, 0x140000000)
        self.assertTrue(insn.is_call)
        self.assertEqual(insn.target, 0x1878B0)


class ProvenanceTests(unittest.TestCase):
    def test_client_field_getter(self):
        model = FakeModel({0x1000: ["MOV RAX,qword ptr [0x140e98c18]", "MOV EAX,dword ptr [RAX + 0x3198]", "RET"]})
        facts = Provenance(model).facts(0x1000)
        self.assertEqual(facts.returns[0].value, Deref(Deref(Global(0xE98C18), 0, 8), 0x3198, 4))

    def test_leaf_inlining_and_out_param(self):
        model = FakeModel({
            0x1000: ["SUB RSP,0x30", "LEA RDX,[RSP + 0x20]", "CALL 0x140002000", "MOV RCX,qword ptr [RSP + 0x28]",
                     "CALL 0x140003000", "MOV EDI,EAX", "ADD RSP,0x30", "RET"],
            0x2000: ["MOV qword ptr [RDX],RCX", "RET"],
            0x3000: ["MOV EAX,dword ptr [RCX + 0x3a8]", "RET"],
        })
        facts = Provenance(model).facts(0x1000)
        loads = [l.value for l in facts.loads]
        self.assertTrue(any(isinstance(v, Deref) and v.off == 0x3A8 and isinstance(v.base, Deref) and v.base.off == 8 for v in loads),
                        f"expected [[out+8]+0x3a8] among {loads}")

    def test_store_to_out_struct_and_args(self):
        model = FakeModel({0x1000: ["MOV RBX,RCX", "LEA R8,[RDX + 0xc9b8]", "CALL 0x140002000", "MOV RSI,qword ptr [RAX + 0x8]",
                                    "MOV EAX,dword ptr [RSI + 0x3f0]", "MOV dword ptr [RBX + 0x4],EAX", "RET"],
                           0x2000: ["MOV RAX,RCX", "RET"]})
        facts = Provenance(model).facts(0x1000)
        store = facts.stores[0]
        self.assertEqual(store.target, Deref(Arg(0), 4, 4))
        self.assertEqual(store.value.off, 0x3F0)
        self.assertEqual(facts.calls[0].args[2].off, 0xC9B8)

    def test_tail_call_and_shift(self):
        model = FakeModel({0x1000: ["MOV R8D,dword ptr [RDX + 0x8]", "JMP 0x140002000"], 0x2000: ["SHR RCX,0x20", "RET"]})
        facts = Provenance(model).facts(0x1000)
        self.assertEqual(facts.calls[0].target, 0x2000)
        self.assertEqual(facts.calls[0].args[2], Deref(Arg(1), 8, 4))


class SpecTests(unittest.TestCase):
    def test_spec_lints_clean(self):
        spec = load_spec()
        self.assertGreater(len(spec.keys), 100)
        self.assertEqual(lint(spec), [])

    def test_every_p0_key_has_a_live_check(self):
        for k in load_spec().keys:
            if k.priority == "P0":
                self.assertTrue(k.live, k.key)


class FileVersionTests(unittest.TestCase):
    def test_reads_vs_fixedfileinfo(self):
        info = struct.pack("<IIII", 0xFEEF04BD, 0x00010000, (241 << 16) | 3, (7 << 16) | 9)
        self.assertEqual(file_version(b"\0" * 100 + info + b"\0" * 40), (241, 3, 7, 9))

    def test_missing_resource(self):
        self.assertIsNone(file_version(b"MZ" + b"\0" * 200))


class MappingTests(unittest.TestCase):
    def build(self):
        spec = SimpleNamespace(keys=[KeySpec("Actor.nameSsoFlag", internal=True)])
        results = [Result("global.client", 0xE98C18, "PROVEN"), Result("Actor.pathX", 0x3F0, "ANCHORED"),
                   Result("Client.worlds.map", 0xC9D0, "INFERRED"), Result("container.hash_map.node.value", 0x10, "ANCHORED"),
                   Result("enums.gameState.LOGGED_IN", 30, "ANCHORED"), Result("enums.events", {"ON_GAME_TICK": 9}, "ANCHORED"),
                   Result("functions.worldToScreen", 0x21EB80, "ANCHORED"), Result("Actor.nameSsoFlag", 0x727, "ANCHORED"),
                   Result("Actor.anim", 0x4D8, "NEEDS_REVIEW"), Result("Npc.type", None, "FAILED")]
        return emit.build_mapping(identity.BuildIdentity("ab" * 32, "241.3", ""), results, spec)

    def test_values_only(self):
        self.assertEqual(self.build(), {
            "version": "241.3", "sha256": "ab" * 32,
            "globals": {"client": "0xe98c18"},
            "structs": {"Actor": {"pathX": "0x3f0"}, "Client": {"worlds.map": "0xc9d0"}},
            "containers": {"hash_map": {"node.value": "0x10"}},
            "enums": {"gameState": {"LOGGED_IN": 30}, "events": {"ON_GAME_TICK": 9}},
            "functions": {"worldToScreen": "0x21eb80"},
        })

    def test_flatten_uses_spec_keys(self):
        flat = emit.flatten(self.build())
        self.assertEqual(flat["Client.worlds.map"], "0xc9d0")
        self.assertEqual(flat["container.hash_map.node.value"], "0x10")
        self.assertEqual(flat["enums.events.ON_GAME_TICK"], 9)
        self.assertNotIn("Actor.anim", flat)

    def test_diff(self):
        old = self.build()
        new = self.build()
        new["structs"]["Actor"]["pathX"] = "0x3f8"
        del new["globals"]["client"]
        self.assertEqual(emit.diff(old, new), [("Actor.pathX", "0x3f0", "0x3f8", "moved"), ("global.client", "0xe98c18", None, "gone")])


class VerifyTests(unittest.TestCase):
    def test_expression(self):
        self.assertEqual(verify.evaluate("World.npcs + 8", {"World.npcs": 0x90}), 0x98)
        self.assertIsNone(verify.evaluate("Missing + 1", {}))


class WinPathsTests(unittest.TestCase):
    EXE = r"C:\Program Files (x86)\Jagex Launcher\osclient.exe"

    def test_windows_paths_unchanged_off_wsl(self):
        with mock.patch.object(winpaths, "is_wsl", return_value=False):
            self.assertEqual(str(winpaths.local(self.EXE)), str(PurePath(self.EXE)))

    def test_drive_path_maps_to_mnt_on_wsl(self):
        with mock.patch.object(winpaths, "is_wsl", return_value=True), mock.patch.object(winpaths, "_wslpath", return_value=None):
            self.assertEqual(winpaths.local(self.EXE).as_posix(), "/mnt/c/Program Files (x86)/Jagex Launcher/osclient.exe")
            self.assertEqual(winpaths.local("/home/me/x.json").as_posix(), "/home/me/x.json")

    def test_cache_root_override(self):
        with mock.patch.dict(os.environ, {"KRAKEN_NATIVE_HOME": "some/cache"}):
            self.assertEqual(identity.default_root(), Path("some/cache"))


if __name__ == "__main__":
    unittest.main()
