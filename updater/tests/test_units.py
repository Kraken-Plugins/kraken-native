"""Unit tests that need neither Ghidra nor the client binary."""
import os
import struct
import unittest
from pathlib import Path, PurePath
from types import SimpleNamespace
from unittest import mock

from native_updater import emit, extractors, identity, strategies, winpaths, x86, verify
from native_updater.analysis.bindings import BindingTableParser
from native_updater.analysis.provenance import Add, Addr, Arg, Bin, Call, Cmp, Const, Deref, Facts, Global, Load, Mul, Provenance, Ret
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


class GetterSelectorTests(unittest.TestCase):
    """Getter selectors over a FakeModel, with a minimal stand-in for the run session."""

    @staticmethod
    def session(functions, values=None):
        model = FakeModel(functions)
        s = SimpleNamespace(model=model, prov=Provenance(model), values=dict(values or {}))
        s.fns = lambda ref: [ref]
        s.fn = lambda ref: ref
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def values(self, session, params):
        return sorted(c.value for c in strategies.getter(session, {"in": 0x1000, **params}, "key"))

    def test_cmp_with_key_finds_a_vector_end(self):
        s = self.session({0x1000: ["MOV RAX,qword ptr [RCX + 0xcc58]", "MOV R8,qword ptr [RCX + 0xcc60]", "CMP RAX,R8",
                                   "MOV EAX,dword ptr [RAX]", "RET"]}, {"Vec.begin": 0xCC58})
        self.assertEqual(self.values(s, {"base": "arg0", "load": "ptr", "select": {"used_in_cmp": True}}), [0xCC58, 0xCC60])
        self.assertEqual(self.values(s, {"base": "arg0", "load": "ptr", "select": {"cmp_with": {"key": "Vec.begin"}}}), [0xCC60])

    def test_cmp_with_arg(self):
        s = self.session({0x1000: ["MOV RAX,qword ptr [RCX + 0x10]", "CMP R9D,dword ptr [RAX + 0x944]",
                                   "CMP EDX,dword ptr [RAX + 0x948]", "RET"]})
        base = {"field": {"of": "arg0", "offset": 0x10}}
        self.assertEqual(self.values(s, {"base": base, "load": "i32", "select": {"cmp_with": {"arg": 3}}}), [0x944])
        self.assertEqual(self.values(s, {"base": base, "load": "i32", "select": {"cmp_with": {"arg": 1}}}), [0x948])

    def test_index_uses_arg(self):
        s = self.session({0x1000: ["MOV RAX,qword ptr [RCX + 0x3d0]", "MOV R10,qword ptr [RCX + 0x3e8]",
                                   "MOV EAX,dword ptr [RAX + RDX*0x4]", "MOV EAX,dword ptr [R10 + R8*0x4]", "RET"]})
        self.assertEqual(self.values(s, {"base": "arg0", "load": "ptr", "select": {"index_uses": {"arg": 1}}}), [0x3D0])
        self.assertEqual(self.values(s, {"base": "arg0", "load": "ptr", "select": {"index_uses": {"arg": 2}}}), [0x3E8])


class RecipeTests(unittest.TestCase):
    """New selectors, roles and extractors. Facts are built by hand (or read from a FakeModel) so no binary is needed."""

    def values(self, session, params):
        return sorted(c.value for c in strategies.getter(session, {"in": 0x1000, **params}, "key"))

    def test_lowest_picks_the_base_of_a_float_run(self):
        s = GetterSelectorTests.session({0x1000: ["MOV EAX,dword ptr [RCX + 0x420]", "MOV EAX,dword ptr [RCX + 0x424]",
                                                  "MOV EAX,dword ptr [RCX + 0x418]", "MOV EAX,dword ptr [RCX + 0x41c]", "RET"]})
        self.assertEqual(self.values(s, {"base": "arg0", "load": "f32", "select": {"lowest": True}}), [0x418])

    def test_lowest_refuses_scattered_fields(self):
        s = GetterSelectorTests.session({0x1000: ["MOV EAX,dword ptr [RCX + 0x418]", "MOV EAX,dword ptr [RCX + 0x420]",
                                                  "MOV EAX,dword ptr [RCX + 0x424]", "RET"]})
        self.assertEqual(self.values(s, {"base": "arg0", "load": "f32", "select": {"lowest": True}}), [])

    def test_add_rebases_the_offset_onto_the_parent_struct(self):
        s = GetterSelectorTests.session({0x1000: ["MOV EAX,dword ptr [RCX + 0x20]", "MOV EDX,dword ptr [RCX + 0x24]", "RET"]})
        self.assertEqual(self.values(s, {"base": "arg0", "load": "i32", "add": 0x10,
                                                              "select": {"min": 0x20, "max": 0x24, "nth": 1}}), [0x34])

    def test_ret_indirect_matches_the_result_of_a_vtable_call(self):
        s = GetterSelectorTests.session({0x1000: ["CALL qword ptr [RAX + 0x8]", "MOV ECX,dword ptr [RAX + 0x18]",
                                                  "MOV EAX,dword ptr [RAX + 0x14]", "RET"]})
        self.assertEqual(self.values(s, {"base": {"ret_indirect": 0x1000}, "load": "i32"}), [0x14, 0x18])

    def test_sol_self_is_the_object_a_property_lambda_gets_on_the_stack(self):
        s = GetterSelectorTests.session({0x1000: ["MOV RAX,qword ptr [RSP + 0x48]", "MOV RCX,qword ptr [RAX]",
                                                  "MOVZX EAX,word ptr [RCX + 0x4]", "RET"]})
        self.assertEqual(self.values(s, {"base": "sol_self", "load": "u16"}), [0x4])
        self.assertEqual(self.values(s, {"base": "sol_self", "load": "ptr"}), [])

    def test_stride_when_the_scaled_index_lands_in_the_base_slot(self):
        # [(i * 16) + [slots]*1 + 8]: the array is the operand that isn't scaled.
        s = GetterSelectorTests.session({0x1000: ["MOV RAX,qword ptr [RCX + 0x10]", "SHL RDX,0x4",
                                                  "MOV RAX,qword ptr [RDX + RAX*0x1 + 0x8]", "RET"]})
        base = {"field": {"of": "arg0", "offset": 0x10}}
        self.assertEqual(self.values(s, {"kind": "stride", "base": base}), [16])

    def test_ret_indirect_ignores_direct_calls(self):
        s = GetterSelectorTests.session({0x2000: ["RET"], 0x1000: ["CALL 0x140002000", "MOV EAX,dword ptr [RAX + 0x14]", "RET"]})
        self.assertEqual(self.values(s, {"base": {"ret_indirect": 0x1000}, "load": "i32"}), [])

    @staticmethod
    def walk_core_facts():
        """The shape of the walk core: origin subtracted from x and z, bounds on x and z, and the flag column load."""
        rec = Arg(0)
        column = Bin("-", Arg(1), Deref(rec, 0, 4))
        term = Mul(Mul(column, Const(3)), Const(8))
        return Facts(fn=0x1000, addrs=[Addr(0x10, Bin("-", Arg(1), Deref(rec, 0, 4)), "EAX", 0x1000),
                                       Addr(0x14, Bin("-", Arg(2), Deref(rec, 4, 4)), "EDX", 0x1000)],
                     cmps=[Cmp(0x18, Bin("-", Arg(3), Deref(rec, 0, 4)), Deref(rec, 8, 4), 0x1000),
                           Cmp(0x1C, Bin("-", Arg(4), Deref(rec, 4, 4)), Deref(rec, 0xC, 4), 0x1000)],
                     loads=[Load(0x20, Deref(term, 0x10, 8, Deref(rec, 0x20, 8), 1), "RAX", 0x1000)])

    def test_collision_record_reads_origins_bounds_and_columns(self):
        fields = {k: v[0] for k, v in extractors.collision_record_fields(self.walk_core_facts()).items()}
        self.assertEqual(fields, {"CollisionMapRecord.originX": 0, "CollisionMapRecord.originZ": 4,
                                  "CollisionMapRecord.sizeX": 8, "CollisionMapRecord.sizeZ": 0xC,
                                  "CollisionMapRecord.flags": 0x20, "CollisionMapRecord.columnData": 0x10,
                                  "CollisionMapRecord.columnStride": 24})

    def test_collision_column_with_the_flags_as_base(self):
        """The accessor form: [[rec + 0x20] + x * 3 * 8 + 0x10] reads the same record fields, with scale 8 on the index."""
        facts = Facts(fn=0x1000, loads=[Load(0x20, Deref(Deref(Arg(0), 0x20, 8), 0x10, 8, Mul(Arg(1), Const(3)), 8), "RAX", 0x1000)])
        fields = extractors.collision_record_fields(facts)
        self.assertEqual(fields["CollisionMapRecord.flags"], (0x20, 0x20))
        self.assertEqual(fields["CollisionMapRecord.columnStride"], (24, 0x20))
        self.assertEqual(fields["CollisionMapRecord.columnData"], (0x10, 0x20))

    def test_renderer_layout_finds_the_viewport_beside_the_holder(self):
        wts, proj, ctor = 0x21EB80, 0x6AA7C0, 0x61ADF0
        renderer = Deref(Global(0xE98C18), 0x90, 8)
        facts = {
            wts: Facts(fn=wts, calls=[Call(0x21EC33, proj, None, (Arg(0), Add(renderer, 0x1B0), Arg(3)), wts)]),
            ctor: Facts(fn=ctor, calls=[
                Call(0x61AE70, 0x6577A0, None, (Add(Arg(0), 0xF8), Add(Arg(0), 0x40)), ctor),
                Call(0x61AE83, 0x65B370, None, (Add(Arg(0), 0x1B0), Add(Arg(0), 0xF8)), ctor)]),
        }
        session = SimpleNamespace(
            fn=lambda ref: {"@WorldToScreen": wts, "@ProjectionCore": proj}[ref],
            prov=SimpleNamespace(facts=lambda fn: facts[fn]),
            model=SimpleNamespace(insns_with_disp=lambda disp, mnem=None: [(0x61AE83, ctor)] if disp == 0x1B0 else []),
            evidence=lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot})
        out = extractors.renderer_layout(session, {})
        self.assertEqual(out["Renderer.holder"][0], 0x1B0)
        self.assertEqual(out["Renderer.viewport"][0], 0xF8)
        self.assertEqual(out["RendererCtor"][0], ctor)

    def test_loc_tag_kind_is_the_shift_of_the_decoder_compared_with_two(self):
        tile, kind_check, kind_decoder, bitfield = 0x6A2B80, 0x652110, 0x652230, 0x63CBE0
        facts = {
            tile: Facts(fn=tile, calls=[Call(0x6A2C00, kind_check, None, (Deref(Arg(0), 0x38, 8),), tile)]),
            kind_check: Facts(fn=kind_check, cmps=[Cmp(0x652119, Ret(0x652114, kind_decoder), Const(2), kind_check)]),
            kind_decoder: Facts(fn=kind_decoder, calls=[Call(0x652236, bitfield, None, (Arg(0), Const(0x10), Arg(2)), kind_decoder)]),
        }
        session = SimpleNamespace(
            fn=lambda ref: tile,
            prov=SimpleNamespace(facts=lambda fn: facts[fn]),
            evidence=lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot})
        out = extractors.loc_tag_kind(session, {})
        self.assertEqual(out["Loc.tagKindShift"][0], 0x10)


class BindingParserTests(unittest.TestCase):
    def test_property_getter_is_followed_to_its_lambda(self):
        # LEA name; CALL setter. Each setter LEAs its own binding plus a helper all setters share; the binding calls a
        # shared self check and the lambda.
        model = FakeModel({
            0x1000: ["LEA RAX,[0x140900000]", "MOV qword ptr [RBP + -0x38],RAX", "CALL 0x140002000",
                     "LEA RAX,[0x140900010]", "MOV qword ptr [RBP + -0x38],RAX", "CALL 0x140002100", "RET"],
            0x2000: ["LEA RDX,[0x140003000]", "LEA R8,[0x140005000]", "RET"],
            0x2100: ["LEA RDX,[0x140003100]", "LEA R8,[0x140005000]", "RET"],
            0x3000: ["CALL 0x140004000", "CALL 0x140004100", "RET"],
            0x3100: ["CALL 0x140004000", "CALL 0x140004200", "RET"],
            0x4000: ["RET"], 0x4100: ["RET"], 0x4200: ["RET"], 0x5000: ["RET"],
        }, strings={0x900000: "text", 0x900010: "colour"})
        out = {b.name: (b.kind, b.handler) for b in BindingTableParser(model).parse(0x1000, "T")}
        self.assertEqual(out, {"text": ("property", 0x4100), "colour": ("property", 0x4200)})


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
