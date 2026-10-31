"""Unit tests that need neither Ghidra nor the client binary."""
import os
import struct
import unittest
from pathlib import Path, PurePath
from types import SimpleNamespace
from unittest import mock

from native_updater import (emit, extractors, fingerprints, frames, highlights, identity, minimenu, strategies,
                            winpaths, x86, verify)
from native_updater.analysis.bindings import BindingTableParser
from native_updater.analysis.provenance import (Add, Addr, Arg, Bin, Call, Cmp, Const, Deref, Facts, Global, Load, Mul,
                                                Provenance, Ret, StackAddr, State, Store, Unknown)
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

    def test_projectile_walker_finds_the_cycles_the_step_and_the_drawn_angle(self):
        cycle = Deref(Global(0xE98C14), 0, 4)
        proj = Deref(Deref(Arg(0), 0, 8), 0x18, 8)
        facts = Facts(fn=0x1000,
                      cmps=[Cmp(0x10, cycle, Deref(proj, 0x28C, 4), 0x1000), Cmp(0x20, cycle, Deref(proj, 0x288, 4), 0x1000)],
                      loads=[Load(0x14, Deref(proj, 0x28C, 4), "RAX", 0x1000), Load(0x30, Deref(proj, 0x2B8, 4), "R8", 0x1000)],
                      calls=[Call(0x28, 0x2000, None, (proj, Arg(1)), 0x1000)])
        cycles, step, drawn = extractors.projectile_walker_fields(facts, proj, cycle)
        self.assertEqual(cycles, {0x28C: 0x10, 0x288: 0x20})
        self.assertEqual(step.target, 0x2000)
        self.assertEqual(drawn, {0x2B8})

    def test_projectile_walker_needs_two_cycle_fields(self):
        cycle = Deref(Global(0xE98C14), 0, 4)
        proj = Deref(Deref(Arg(0), 0, 8), 0x18, 8)
        facts = Facts(fn=0x1000, cmps=[Cmp(0x10, cycle, Deref(proj, 0x28C, 4), 0x1000)],
                      calls=[Call(0x28, 0x2000, None, (proj,), 0x1000)])
        self.assertIsNone(extractors.projectile_walker_fields(facts, proj, cycle)[1])

    def test_projectile_endpoint_roles_follow_what_the_lookup_does_with_each_argument(self):
        """x and z are rebased into out[0] and out[2], the actor reference is decremented, the level goes on."""
        bounds = Ret(0x50, 0xEC4A0)
        fine = lambda a, off: Add(Mul(Bin("-", Arg(a), Deref(bounds, off, 4)), Const(0x80)), 0x40)
        facts = Facts(fn=0xA2B70,
                      stores=[Store(0x60, Deref(Arg(0), 0, 4), fine(2, 0x24), 0xA2B70),
                              Store(0x64, Deref(Arg(0), 8, 4), fine(3, 0x28), 0xA2B70)],
                      calls=[Call(0x20, 0x187F20, None, (Arg(5), Add(Arg(1), -1)), 0xA2B70),
                             Call(0x70, 0x97980, None, (Deref(Global(0xE98C18), 0, 8), Arg(5), Arg(2), Arg(3), Arg(4)), 0xA2B70)])
        self.assertEqual(extractors.projectile_endpoint_roles(facts), {"X": 2, "Z": 3, "Actor": 1, "Level": 4})

    def test_projectile_step_fields(self):
        """The step's shape: the cycle check, the target lookup, the started test, the source lookup, the heights after
        each, the slope scaled by 2pi/256, the start distance, the position returned truncated, and two angles."""
        p, out, fn, end = Arg(0), Arg(1), 0xA2630, 0xA2B70
        f = lambda off, size=4: Deref(p, off, size)
        scale = Global(0xC4DBF0)
        unknown = Unknown()
        facts = Facts(fn=fn,
                      cmps=[Cmp(0x10, Arg(4), f(0x288), fn), Cmp(0x30, f(0x2C0, 1), Const(0), fn)],
                      calls=[Call(0x20, end, None, (Arg(1), f(0x284), f(0x278), f(0x27C), f(0x274), Arg(2)), fn),
                             Call(0x40, end, None, (Arg(1), f(0x270), f(0x264), f(0x268), f(0x260), Arg(2)), fn)],
                      loads=[Load(0x24, f(0x280), None, fn), Load(0x44, f(0x26C), None, fn),
                             Load(0x50, f(0x294), "XMM1", fn), Load(0x58, f(0x28C), "RAX", fn),
                             Load(0x60, f(0x290), "XMM0", fn), Load(0x64, Deref(scale, 0, 8), None, fn),
                             Load(0x80, f(0x2A0, 8), "RAX", fn), Load(0x88, f(0x2A8, 8), "RAX", fn),
                             Load(0x90, f(0x2B0, 8), "RAX", fn)],
                      stores=[Store(0x34, f(0x2C0, 1), Const(1), fn),
                              Store(0x70, f(0x2A0, 8), unknown, fn), Store(0x72, f(0x2A8, 8), unknown, fn),
                              Store(0x74, f(0x2B0, 8), unknown, fn), Store(0x76, f(0x2C8, 8), unknown, fn),
                              Store(0x78, f(0x2B8), unknown, fn), Store(0x7C, f(0x2BC), unknown, fn),
                              Store(0x84, Deref(out, 0, 4), unknown, fn), Store(0x8C, Deref(out, 4, 4), unknown, fn),
                              Store(0x94, Deref(out, 8, 4), unknown, fn)])
        roles = {"X": 2, "Z": 3, "Actor": 1, "Level": 4}
        fields = {k: v[0] for k, v in extractors.projectile_step_fields(facts, {0x288: 0x8, 0x28C: 0xC}, end, roles, {scale.rva}).items()}
        self.assertEqual(fields, {
            "Projectile.startCycle": 0x288, "Projectile.endCycle": 0x28C, "Projectile.started": 0x2C0,
            "Projectile.targetActor": 0x284, "Projectile.targetX": 0x278, "Projectile.targetZ": 0x27C,
            "Projectile.targetLevel": 0x274, "Projectile.sourceActor": 0x270, "Projectile.sourceX": 0x264,
            "Projectile.sourceZ": 0x268, "Projectile.sourceLevel": 0x260, "Projectile.endHeight": 0x280,
            "Projectile.startHeight": 0x26C, "Projectile.slope": 0x290, "Projectile.startDistance": 0x294,
            "Projectile.fineX": 0x2A0, "Projectile.fineY": 0x2A8, "Projectile.fineZ": 0x2B0,
            "Projectile.yaw": 0x2B8, "Projectile.pitch": 0x2BC})

    def test_projectile_step_without_a_started_test_keeps_only_the_cycles(self):
        fn = 0xA2630
        facts = Facts(fn=fn, cmps=[Cmp(0x10, Arg(4), Deref(Arg(0), 0x288, 4), fn)])
        fields = extractors.projectile_step_fields(facts, {0x288: 0x8, 0x28C: 0xC}, 0xA2B70, {}, set())
        self.assertEqual(set(fields), {"Projectile.startCycle", "Projectile.endCycle"})

    def test_keyed_projectile_walk_finds_the_map_the_cycles_the_step_and_the_drawn_angle(self):
        """The map the walk erases through and reads the buckets of; a node value's two cycle fields; the step."""
        fn, cycle = 0x735D0, Deref(Global(0xE98C14), 0, 4)
        node = Deref(Deref(Arg(0), 0xCC90, 8), 0, 8)
        p = Deref(node, 0x10, 8)
        facts = Facts(fn=fn,
                      loads=[Load(0x10, Deref(Arg(0), 0xCC90, 8), "RCX", fn), Load(0x20, Deref(Arg(0), 0xCC98, 8), "RAX", fn),
                             Load(0x60, Deref(p, 0x318, 4), "R8", fn)],
                      cmps=[Cmp(0x30, cycle, Deref(p, 0x2B0, 4), fn), Cmp(0x38, cycle, Deref(p, 0x2AC, 4), fn)],
                      calls=[Call(0x50, 0x31430, None, (p, Arg(9)), fn),
                             Call(0x90, 0xC37D0, None, (Add(Arg(0), 0xCC88), Arg(9)), fn)])
        walk = extractors.keyed_projectile_walk_fields(facts, cycle, 8, 0x10, 0x10)
        self.assertEqual(walk["map"], (0xCC88, 0x90))
        self.assertEqual(walk["cycles"], {0x2B0: 0x30, 0x2AC: 0x38})
        self.assertEqual(walk["step"].target, 0x31430)
        self.assertEqual(walk["drawn"], {0x318})
        facts.calls.pop()
        self.assertIsNone(extractors.keyed_projectile_walk_fields(facts, cycle, 8, 0x10, 0x10), "no map to erase through")

    def test_keyed_projectile_step_fields(self):
        """Start and end from the progress arithmetic, the started test, the returned position, the two angles."""
        fn, f = 0x31430, (lambda off, size=4: Deref(Arg(0), off, size))
        unknown = Unknown()
        facts = Facts(fn=fn,
                      cmps=[Cmp(0x10, f(0x300, 1), Const(0), fn), Cmp(0x18, f(0x308, 1), Const(0), fn)],
                      addrs=[Addr(0x40, Bin("-", Arg(4), f(0x2AC)), "RAX", fn), Addr(0x48, Bin("-", f(0x2B0), f(0x2AC)), "RAX", fn)],
                      stores=[Store(0x20, f(0x304), Const(0), fn), Store(0x60, f(0x2E0), unknown, fn),
                              Store(0x64, f(0x2E4), unknown, fn), Store(0x68, f(0x2E8), unknown, fn),
                              Store(0x70, f(0x318), unknown, fn), Store(0x74, f(0x31C), unknown, fn),
                              Store(0x84, Deref(Arg(1), 0, 4), unknown, fn), Store(0x8C, Deref(Arg(1), 4, 4), unknown, fn),
                              Store(0x94, Deref(Arg(1), 8, 4), unknown, fn)],
                      loads=[Load(0x80, f(0x2E0), "RAX", fn), Load(0x88, f(0x2E4), "RAX", fn), Load(0x90, f(0x2E8), "RAX", fn)])
        fields = extractors.keyed_projectile_step_fields(facts, {0x2AC: 0x38, 0x2B0: 0x30})
        self.assertEqual({k: v if k == "angles" else v[0] for k, v in fields.items()}, {
            "KeyedProjectile.startCycle": 0x2AC, "KeyedProjectile.endCycle": 0x2B0, "KeyedProjectile.started": 0x300,
            "KeyedProjectile.fineX": 0x2E0, "KeyedProjectile.fineY": 0x2E4, "KeyedProjectile.fineZ": 0x2E8,
            "angles": {0x318: 0x70, 0x31C: 0x74}})

    def test_keyed_projectile_lookup_reads_the_stack_coord_the_actor_and_the_offsets(self):
        fn, end, f = 0x31370, 0x31A70, (lambda off, size=4: Deref(Arg(0), off, size))
        state = State(stack={-0x18: f(0x268, 8), -0x10: f(0x270)})
        facts = Facts(fn=fn, calls=[Call(0x30, end, None, (Arg(0), StackAddr(-0x28), f(0x280), StackAddr(-0x18), Arg(1)), fn)],
                      loads=[Load(0x10, f(0x268, 8), "XMM0", fn), Load(0x34, f(0x274), "XMM0", fn),
                             Load(0x40, f(0x278), "XMM1", fn), Load(0x48, f(0x27C), "XMM0", fn)],
                      states={0x30: state})
        self.assertEqual(extractors.keyed_projectile_lookup(facts, end), {
            "coord": 0x268, "coordArg": 3, "actor": 0x280, "offset": 0x274, "rva": 0x30, "offsetRva": 0x34})
        facts.loads.pop()
        self.assertIsNone(extractors.keyed_projectile_lookup(facts, end), "needs three offsets")

    def test_keyed_projectile_coord_roles_follow_world_bounds(self):
        fn, bounds, c = 0x31A70, 0xEC4A0, (lambda off: Deref(Arg(3), off, 4))
        facts = Facts(fn=fn, calls=[Call(0x20, bounds, None, (Arg(4), c(4), c(8)), fn)],
                      loads=[Load(0x10, c(0), "RAX", fn), Load(0x14, c(8), "RDI", fn), Load(0x18, c(4), "RSI", fn)])
        self.assertEqual(extractors.keyed_projectile_coord_roles(facts, bounds, 3), {"Level": 0, "X": 4, "Z": 8})
        self.assertIsNone(extractors.keyed_projectile_coord_roles(facts, bounds, 2))

    GFX_UPDATE, GFX_STEP, GFX_DRAW = 0x73840, 0x38D70, 0x6A12F0

    def gfx_update_facts(self, draw_args=None):
        """A miniature of the per-frame update: the level and finished tests, the cycle test, the step, the draw."""
        fn, obj, world = self.GFX_UPDATE, Deref(Arg(1), 8, 8), Deref(Arg(0), 8, 8)
        f = lambda off, size=4: Deref(obj, off, size)
        args = draw_args or (Deref(world, 0x10, 8), f(0x264), f(0x268), f(0x26C), f(0x270))
        return Facts(fn=fn,
                     cmps=[Cmp(0x20, f(0x264), Deref(world, 0x18, 4), fn), Cmp(0x2D, f(0x288, 8), Const(0), fn),
                           Cmp(0x47, Deref(Global(0xE98C14), 0, 4), f(0x260), fn), Cmp(0x6D, f(0x288, 8), Const(0), fn),
                           Cmp(0x150, f(0x48, 8), Const(0), fn)],
                     calls=[Call(0x64, self.GFX_STEP, None, (obj, Deref(Deref(Arg(0), 0, 8), 0x26D8, 2)), fn),
                            Call(0xF2, self.GFX_DRAW, None, args, fn)]), obj

    def gfx_facts_of(self, fn):
        if fn == self.GFX_STEP:
            return Facts(fn=fn, cmps=[Cmp(0x4, Deref(Arg(0), 0x288, 8), Const(0), fn)])
        return Facts(fn=fn)

    def test_graphics_object_update_fields(self):
        """Level against the world's plane, the finished test, the start cycle, and the draw call's position."""
        facts, obj = self.gfx_update_facts()
        fields = extractors.graphics_object_update_fields(facts, obj, Deref(Global(0xE98C14), 0, 4), 0x18, 0x10, self.gfx_facts_of)
        self.assertEqual({k: v if k == "world" else v[0] for k, v in fields.items()}, {
            "GraphicsObject.level": 0x264, "GraphicsObject.animSeqType": 0x288, "GraphicsObject.startCycle": 0x260,
            "GraphicsObject.fineX": 0x268, "GraphicsObject.fineZ": 0x26C, "GraphicsObject.height": 0x270, "world": 8})

    def test_graphics_object_update_needs_the_draw_with_the_world_scene(self):
        f = lambda off: Deref(Deref(Arg(1), 8, 8), off, 4)
        facts, obj = self.gfx_update_facts(draw_args=(Deref(Arg(2), 0x10, 8), f(0x264), f(0x268), f(0x26C), f(0x270)))
        self.assertIsNone(extractors.graphics_object_update_fields(facts, obj, Deref(Global(0xE98C14), 0, 4), 0x18, 0x10, self.gfx_facts_of))

    def test_graphics_object_update_needs_a_step_testing_the_finished_field(self):
        facts, obj = self.gfx_update_facts()
        self.assertIsNone(extractors.graphics_object_update_fields(facts, obj, Deref(Global(0xE98C14), 0, 4), 0x18, 0x10,
                                                                   lambda fn: Facts(fn=fn)))

    def test_graphics_object_ctor_fields(self):
        """ctor(P, world, id, level, x, z, height, delay, cycle): the vtable, delay + cycle, the id it loads, the AnimState."""
        fn, loader, setter = 0x38C00, 0x5FEDF0, 0x5E4880
        a0 = Arg(0)
        facts = Facts(fn=fn,
                      stores=[Store(0x10, Deref(a0, 0, 8), Global(0xB95D48), fn),
                              Store(0x20, Deref(a0, 0x260, 4), Bin("+", Arg(7), Arg(8)), fn),
                              Store(0x30, Deref(a0, 0x268, 4), Arg(4), fn), Store(0x34, Deref(a0, 0x26C, 4), Arg(5), fn),
                              Store(0x38, Deref(a0, 0x270, 4), Arg(6), fn), Store(0x3C, Deref(a0, 0x278, 8), Arg(1), fn),
                              Store(0x40, Deref(a0, 0x274, 4), Arg(2), fn), Store(0x44, Deref(a0, 0x264, 4), Arg(3), fn)],
                      calls=[Call(0x50, loader, None, (Arg(9), Arg(2)), fn),
                             Call(0x60, setter, None, (Add(a0, 0x280), Deref(Arg(9), 8, 4)), fn)])
        fields = {k: v[0] for k, v in extractors.graphics_object_ctor_fields(facts, loader, {setter}).items()}
        self.assertEqual(fields, {"vtable": 0xB95D48, "GraphicsObject.startCycle": 0x260,
                                  "GraphicsObject.spotAnimId": 0x274, "GraphicsObject.animState": 0x280})

    def test_graphics_object_ctor_needs_the_start_cycle_sum(self):
        fn, loader = 0x31120, 0x5FEDF0
        facts = Facts(fn=fn, stores=[Store(0x40, Deref(Arg(0), 0x264, 4), Arg(2), fn)],
                      calls=[Call(0x50, loader, None, (Arg(9), Arg(2)), fn)])
        self.assertIsNone(extractors.graphics_object_ctor_fields(facts, loader, set()))

    def test_graphics_object_model_frame_is_the_word_inside_the_animation_block(self):
        fn, loader, build = 0x38E00, 0x5FEDF0, 0x5FF510
        facts = Facts(fn=fn, calls=[Call(0x10, loader, None, (Arg(9), Deref(Arg(0), 0x274, 4)), fn),
                                    Call(0x20, build, None, (Arg(9), Arg(1), Deref(Arg(0), 0x296, 2)), fn)])
        self.assertEqual(extractors.graphics_object_model_frame(facts, loader, 0x274, 0x280), (0x296, 0x20))
        self.assertIsNone(extractors.graphics_object_model_frame(facts, loader, 0x274, 0x2A0))
        self.assertIsNone(extractors.graphics_object_model_frame(facts, loader, 0x264, 0x280))


class EventRaiserTests(unittest.TestCase):
    """The event raiser recipe over a miniature of the client's raise wrappers (events.py)."""

    CLIENT = "MOV RCX,qword ptr [0x140e98c18]"
    GATE = ["ADD RCX,0x418690", "CALL 0x140003000", "TEST AL,AL"]
    EVENTS = {"ON_GAME_TICK": 44, "ON_NPC_LOAD": 42, "ON_NPC_UNLOAD": 43, "ON_GAME_STATE_CHANGED": 39,
              "ON_VARBIT_UPDATE": 46, "ON_STAT_TRANSMIT": 13, "ON_SCREEN_RESIZE": 45, "ON_MINIMENU_REBUILD": 36}

    def program(self):
        return {
            0x1000: ["INC dword ptr [R13 + 0x3198]", "MOV ECX,0x2c", "CALL 0x140002000", "RET"],
            # the no-payload dispatcher: id in ECX, passed through to fire0
            0x2000: ["PUSH RBX", "MOV EBX,ECX", self.CLIENT, *self.GATE, "JZ 0x140002030", "MOV RCX,qword ptr [RCX]",
                     "CALL 0x140004000", "MOV EDX,EBX", "MOV RCX,RAX", "JMP 0x140005000", "XOR AL,AL", "RET"],
            0x3000: ["RET"],                                            # gate test
            0x4000: ["MOV EDX,0x3", "JMP 0x140006000"],                 # runtime getter, immediate
            0x4100: ["MOV EDX,0x4", "JMP 0x140006000"],                 # runtime getter, deferred
            0x5000: ["RET"], 0x5100: ["RET"], 0x5200: ["RET"], 0x6000: ["RET"],
            # id in ECX plus two payload args, inlined fire body
            0x7000: ["PUSH RBX", "MOVSXD RBX,ECX", "MOV R14,R8", self.CLIENT, "MOV R15,RDX", *self.GATE, "JZ 0x140007040",
                     "MOV RCX,qword ptr [RCX]", "CALL 0x140004000", "MOV dword ptr [RSP + 0xf8],EBX",
                     "MOV qword ptr [RSP + 0x20],RBX", "CALL 0x140005100", "POP RBX", "RET", "XOR AL,AL", "RET"],
            # fixed id, inlined fire body, one payload arg
            0x7100: ["MOV dword ptr [RSP + 0x8],ECX", "PUSH R14", self.CLIENT, "MOV R14,RDX", *self.GATE, "JZ 0x140007134",
                     "MOV RCX,qword ptr [RCX]", "CALL 0x140004000", "MOV dword ptr [RSP + 0xe0],0x27",
                     "MOV qword ptr [RSP + 0x20],0x27", "CALL 0x140005100", "RET"],
            # fixed id in EDX, tail call into fire2, two payload args and ECX ignored
            0x7200: ["MOV RBX,R8", self.CLIENT, "MOV RDI,RDX", *self.GATE, "JZ 0x140007238", "MOV RCX,qword ptr [RCX]",
                     "CALL 0x140004000", "MOV R9,RBX", "MOV R8,RDI", "MOV EDX,0x2e", "MOV RCX,RAX", "JMP 0x140005100", "RET"],
            # deferred transmit: id in ECX, pending-flag setter through the other getter
            0x7300: ["PUSH RBX", "MOV EBX,ECX", self.CLIENT, *self.GATE, "JZ 0x140007330", "MOV RCX,qword ptr [RCX]",
                     "CALL 0x140004100", "MOV EDX,EBX", "MOV RCX,RAX", "JMP 0x140005200", "RET"],
            # tests the gate on another slot: not a raiser
            0x7400: [self.CLIENT, "ADD RCX,0x1234", "CALL 0x140003000", "RET"],
            0x8000: ["MOV ECX,0x2a", "LEA RDX,[RSP + 0x20]", "CALL 0x140007000", "MOV ECX,0x2b", "CALL 0x140007000",
                     "MOV ECX,0xd", "CALL 0x140007300", "MOV ECX,0x2d", "CALL 0x140002000", "MOV ECX,0x24", "JMP 0x140002000"],
        }

    def run_recipe(self):
        s = GetterSelectorTests.session(self.program(), {"Client.tickCount": 0x3198, "enums.events": self.EVENTS})
        s.notes = []
        return {k: v[0] for k, v in extractors.run(s, "event_raisers", {}).items()}

    def test_seed_and_gate(self):
        out = self.run_recipe()
        self.assertEqual(out["functions.dispatchEvent"], 0x2000)
        self.assertEqual(out["Client.pluginGate"], 0x418690)

    def test_ids_passed_in_ecx_come_from_the_call_and_jump_sites(self):
        out = self.run_recipe()
        self.assertEqual(out["events.ON_GAME_TICK"], [{"fn": 0x2000, "idArg": 0, "args": 1}])
        self.assertEqual(out["events.ON_SCREEN_RESIZE"], [{"fn": 0x2000, "idArg": 0, "args": 1}])
        self.assertEqual(out["events.ON_MINIMENU_REBUILD"], [{"fn": 0x2000, "idArg": 0, "args": 1}])
        self.assertEqual(out["events.ON_NPC_LOAD"], [{"fn": 0x7000, "idArg": 0, "args": 3}])
        self.assertEqual(out["events.ON_NPC_UNLOAD"], out["events.ON_NPC_LOAD"])
        self.assertEqual(out["events.ON_STAT_TRANSMIT"], [{"fn": 0x7300, "idArg": 0, "args": 1}])

    def test_fixed_ids_from_the_inlined_pair_or_edx(self):
        out = self.run_recipe()
        self.assertEqual(out["events.ON_GAME_STATE_CHANGED"], [{"fn": 0x7100, "id": 39, "args": 2}])
        self.assertEqual(out["events.ON_VARBIT_UPDATE"], [{"fn": 0x7200, "id": 46, "args": 3}])
        self.assertNotIn("events.ON_SUB_CHANGE", out)

    def test_cycle_function_and_game_state_setter(self):
        s = GetterSelectorTests.session({0x1000: ["INC dword ptr [0x140e98c14]", "RET"]}, {"global.cycle": 0xE98C14})
        self.assertEqual(extractors.run(s, "cycle_function", {})["CycleFunction"][0], 0x1000)
        s = GetterSelectorTests.session({
            0x1000: ["MOV EBP,dword ptr [RCX + 0x2160]", "CMP EBP,EDX", "JZ 0x140001010", "MOV dword ptr [RCX + 0x2160],EDX", "RET"],
            0x2000: ["MOV dword ptr [RDI + 0x2160],0x2", "RET"]}, {"Client.gameState": 0x2160})
        self.assertEqual(extractors.run(s, "game_state_setter", {})["GameStateSetter"][0], 0x1000)


class HookTargetTests(unittest.TestCase):
    """The ground item and player lifecycle recipes (hooks.py) over miniature zone-update and player-info code."""

    @staticmethod
    def session(functions, values, fns):
        s = GetterSelectorTests.session(functions, values)
        s.fn = lambda ref: fns.get(ref, ref)
        s.notes = []
        return s

    def test_obj_pile_ops(self):
        s = self.session({
            0x1000: ["MOV ECX,0x88", "CALL 0x140009000", "MOV dword ptr [RCX + 0x24],EAX", "MOV dword ptr [RCX + 0x20],R14D",
                     "MOV EDX,dword ptr [RBX + 0x3198]", "RET"],                                            # add
            0x2000: ["CMP dword ptr [RCX + 0x20],EDI", "CMP dword ptr [RCX + 0x24],EAX", "MOV dword ptr [RCX + 0x24],EAX", "RET"],  # count
            0x3000: ["CMP dword ptr [RCX + 0x20],ESI", "CMP dword ptr [RCX + 0x24],EDX", "CALL 0x14000a000", "RET"],  # del
            0x3100: ["CMP dword ptr [RCX + 0x20],R8D", "CMP dword ptr [RCX + 0x24],EDX", "RET"],           # no ObjType load
            0x4000: ["CALL 0x140001000", "CALL 0x140002000", "CALL 0x140003000", "CALL 0x140003100", "RET"],
            0x5000: ["CMP dword ptr [RCX + 0x20],EDI", "CMP dword ptr [RCX + 0x24],EAX", "MOV dword ptr [RCX + 0x24],EAX", "RET"],
            0x6000: ["CALL 0x140005000", "RET"],                                                            # not a sibling
            0x9000: ["RET"], 0xa000: ["RET"],
        }, {"GroundItem.id": 0x20, "GroundItem.count": 0x24, "Client.tickCount": 0x3198},
            {"@OperatorNew": 0x9000, "@ConfigLoader.10": 0xa000})
        out = {k: v[0] for k, v in extractors.run(s, "obj_pile_ops", {}).items()}
        self.assertEqual(out, {"ObjAdd": 0x1000, "ObjCount": 0x2000, "ObjDel": 0x3000})

    def test_player_lifecycle(self):
        s = self.session({
            0x1000: ["RET"],                                                        # Actor ctor
            0x1100: ["CALL 0x140001000", "RET"], 0x1200: ["CALL 0x140001000", "RET"],  # NPC ctor, Player ctor
            0x2000: ["CALL 0x140001100", "RET"],                                    # NPC add
            0x2100: ["MOV ECX,0x7d8", "CALL 0x140001200", "MOV dword ptr [R10 + 0x79c],R12D", "RET"],  # player add
            0x2200: ["CALL 0x140002100", "CALL 0x140002300", "RET"],                # adds the index list
            0x2300: ["MOV EDX,dword ptr [RSI + 0x4a0]", "MOV ECX,dword ptr [RSI + 0x79c]", "RET"],  # player remove
            0x2400: ["CALL 0x140002200", "RET"],                                    # player info decode
            0x2500: ["CALL 0x140002600", "RET"], 0x2600: ["RET"],                   # NPC info decode, update decoder
            0x3000: ["LEA R9,[RSP + 0x78]", "CALL 0x140002500", "LEA RDX,[RSP + 0x78]", "CALL 0x140002400",
                     "MOV RDX,RAX", "CALL 0x140002200", "RET"],                     # the packet handler
        }, {"Player.index": 0x79c, "Actor.worldId": 0x4a0}, {"@ActorCtor": 0x1000, "@NpcInfoDecode": 0x2500})
        out = {k: v[0] for k, v in extractors.run(s, "player_lifecycle", {}).items()}
        self.assertEqual(out, {"PlayerCtor": 0x1200, "PlayerAdd": 0x2100, "PlayerRemove": 0x2300, "PlayerInfoDecode": 0x2400})


class MinimenuTests(unittest.TestCase):
    """The minimenu recipe's entry run (minimenu.py) over a miniature of the code that runs a clicked entry."""

    def program(self):
        copy = []
        for off, reg in ((0x8, "RAX"), (0x120, "RAX"), (0x128, "EAX"), (0x12c, "EAX"), (0x130, "EAX"), (0x134, "EAX"),
                         (0x138, "RAX"), (0x140, "EAX")):
            size = "qword" if reg == "RAX" else "dword"
            copy += [f"MOV {reg},{size} ptr [RDX + {off:#x}]", f"MOV {size} ptr [RCX + {off + 0x298:#x}],{reg}"]
        return {
            # run(view, path): find the node, divide the children vector by the node size, run the entry
            0x1000: ["MOV R13,RCX", "LEA RCX,[R13 + 0x140]", "CALL 0x140001800", "MOV RSI,RAX",
                     "MOV R9,0x2aaaaaaaaaaaaaab", "MOV RAX,qword ptr [RSI + 0x10]", "MOV R8,qword ptr [RAX]",
                     "MOV RCX,qword ptr [RAX + 0x8]", "SUB RCX,R8", "MOV RAX,R9", "IMUL RCX", "SAR RDX,0x2",
                     "MOV RAX,qword ptr [RSI]", "IMUL RBP,qword ptr [RSI + 0x8],0x150", "ADD RBP,qword ptr [RAX + 0x18]",
                     "MOV RDX,RBP", "MOV RCX,R13", "CALL 0x140002000", "MOV RDX,RBP", "MOV RCX,R13", "CALL 0x140003000",
                     "RET"],
            0x1800: ["CALL 0x140001900", "RET"],                                    # the path lookup
            0x1900: ["RET"],
            0x2000: [*copy, "MOV byte ptr [RCX + 0x3f0],R9B", "MOV dword ptr [RCX + 0x3f4],0x0", "CALL 0x140001900",
                     "RET"],                                                        # copy into the view
            0x3000: ["RET"],                                                        # the op dispatch
        }

    def test_run_layout(self):
        s = GetterSelectorTests.session(self.program())
        s.notes = []
        out = {k: v[0] for k, v in minimenu._run_layout(s, 0x1000, 0x3000).items()}
        self.assertEqual(out, {
            "MinimenuEntry.size": 0x150, "container.minimenu.list.entries": 0x18, "container.minimenu.node.index": 0x8,
            "container.minimenu.node.list": 0x0, "container.minimenu.node.children": 0x10,
            "container.minimenu.node.size": 0x18, "MinimenuView.rows": 0x140, "MinimenuView.clicked": 0x298,
            "MinimenuView.clickCycles": 0x3f4, "MinimenuRun": 0x1000})

    def test_entry_shape_needs_one_node_for_index_and_list(self):
        node, other = Deref(Arg(0), 0, 8), Deref(Arg(1), 0, 8)
        entry = Bin("+", Mul(Deref(node, 8, 8), Const(0x150)), Deref(Deref(node, 0, 8), 0x18, 8))
        self.assertEqual(minimenu._entry_shape(entry), (0x150, 0x18, 0x8, 0x0))
        mixed = Bin("+", Mul(Deref(node, 8, 8), Const(0x150)), Deref(Deref(other, 0, 8), 0x18, 8))
        self.assertIsNone(minimenu._entry_shape(mixed))


class DrawTests(unittest.TestCase):
    """The draw recipes (highlights.py and the string fingerprint) over miniatures of the client's code."""

    CLIENT = 0xe98c18
    MANAGER = 0x89680
    CORES = {"@HighlightNpcOn": 0x5100, "@HighlightNpcOff": 0x5200, "@HighlightNpcTypeOn": 0x5300,
             "@HighlightNpcTypeOff": 0x5400, "@HighlightObjTypeOn": 0x5500, "@HighlightObjTypeOff": 0x5600,
             "@HighlightPlayerOn": 0x5700, "@HighlightPlayerOff": 0x5800, "@HighlightTileOn": 0x5900,
             "@HighlightTileOff": 0x5a00, "@HighlightConfigStore": 0x5b00,
             "ClientOp.highlightObjOn": 0x5c00, "ClientOp.highlightObjOff": 0x5d00}
    LOC_ON, LOC_OFF, CLEAR = 0x6100, 0x6200, 0x6300

    def session(self, functions, strings=None, data=None, values=None, fns=None):
        model = FakeModel(functions, strings, data)
        s = SimpleNamespace(model=model, prov=Provenance(model), values=dict(values or {}), notes=[],
                            fingerprints={}, fp_evidence={})
        refs = dict(fns or {})
        s.fn = lambda ref: refs[ref] if not (isinstance(ref, str) and ref.startswith("@") and ref[1:] in s.fingerprints)             else s.fingerprints[ref[1:]]
        s.value = lambda key: s.values[key]
        s.evidence = lambda rva, fn=None, slot="disp": {"insn": rva, "fn": fn, "text": "", "slot": slot}
        return s

    def script_ops(self, swap_npc=False):
        """A dispatcher over two and a half categories (NPC, NPCTYPE, LOC): a jump table on arg1 - 7000, five cases per
        category, each calling its function with client + manager."""
        def manager_call(target, category=None):
            setup = ["MOV RCX,qword ptr [0x140e98c18]", f"ADD RCX,{self.MANAGER:#x}"]
            if category is not None:
                setup.append(f"MOV EDX,{category:#x}")
            return setup + [f"CALL {0x140000000 + target:#x}", "JMP 0x140001ffc"]
        cores = self.CORES
        npc_on, npc_off = cores["@HighlightNpcOn"], cores["@HighlightNpcOff"]
        if swap_npc:
            npc_on, npc_off = npc_off, npc_on
        cases = [
            manager_call(cores["@HighlightConfigStore"], 0), manager_call(npc_on),
            manager_call(npc_off), ["JMP 0x140001ffc"], manager_call(self.CLEAR, 0),
            manager_call(cores["@HighlightConfigStore"], 1), manager_call(cores["@HighlightNpcTypeOn"]),
            manager_call(cores["@HighlightNpcTypeOff"]), ["JMP 0x140001ffc"], manager_call(self.CLEAR, 1),
            manager_call(cores["@HighlightConfigStore"], 2), manager_call(self.LOC_ON),
            manager_call(self.LOC_OFF), ["JMP 0x140001ffc"], manager_call(self.CLEAR, 2),
        ]
        head = ["MOV R15,qword ptr [0x140e98c18]", "ADD EDX,0xffffe4a8", f"CMP EDX,{len(cases) - 1:#x}",
                "JA 0x140001ffc", "MOVSXD RAX,EDX", "LEA RCX,[0x140000000]",
                "MOV EDX,dword ptr [RCX + RAX*0x4 + 0x9000]", "ADD RDX,RCX", "JMP RDX"]
        body, starts = list(head), []
        for case in cases:
            starts.append(0x1000 + 4 * len(body))
            body += case
        body += ["NOP"] * ((0xffc - 4 * len(body)) // 4) + ["RET"]
        functions = {0x1000: body}
        for target in list(cores.values()) + [self.LOC_ON, self.LOC_OFF, self.CLEAR]:
            functions[target] = ["RET"]
        functions[0x7000] = [f"CALL {0x140000000 + cores['@HighlightNpcOn']:#x}", "RET"]     # the Lua handler
        table = b"".join(s.to_bytes(4, "little") for s in starts)
        values = {"global.client": self.CLIENT, "Client.highlightManager": self.MANAGER,
                  "enums.highlightCategory": {"NPC": 0, "NPCTYPE": 1, "LOC": 2, "NUM_CATEGORIES": 3}}
        return self.session(functions, data={0x9000: table}, values=values, fns=cores)

    def test_script_ops_name_the_loc_and_clear_functions(self):
        s = self.script_ops()
        out = {k: v[0] for k, v in highlights.highlight_script_ops(s, {}).items()}
        self.assertEqual(out, {"HighlightScriptOps": 0x1000, "HighlightLocOn": self.LOC_ON, "HighlightLocOff": self.LOC_OFF,
                               "HighlightChannelClear": self.CLEAR}, s.notes)

    def test_script_ops_refuse_a_case_that_disagrees_with_the_lua_handler(self):
        s = self.script_ops(swap_npc=True)
        self.assertEqual(highlights.highlight_script_ops(s, {}), {})
        self.assertIn("NPC: case 7001 does not call 0x5100", s.notes[0])

    def test_config_fields_pair_each_name_with_its_offset(self):
        strings = {0x900000: "osrs.ClientOp.HighlightConfig", 0x900020: "highlightColour", 0x900040: "outlineWidth",
                   0x900060: "enableModelOutline", 0x900080: "isEnabled"}
        s = self.session({0x1000: [
            "LEA R8,[0x140900000]", "CALL 0x140002000", "XOR R13D,R13D",
            "LEA RAX,[0x140900020]", "MOV dword ptr [RBP + 0x67],R13D", "CALL 0x140002000",
            "LEA RAX,[0x140900040]", "MOV dword ptr [RBP + 0x67],0x4", "CALL 0x140002000",
            "MOV dword ptr [RBP + 0x67],0x6", "LEA RDX,[RBP + 0x67]", "LEA RAX,[0x140900060]", "CALL 0x140002000",
            "MOV qword ptr [RBP + 0x67],RAX", "LEA RAX,[0x140900080]", "CALL 0x140002000", "RET"],
            0x2000: ["RET"]}, strings=strings)
        out = {k: v[0] for k, v in highlights.highlight_config_fields(s, {}).items()}
        self.assertEqual(out, {"HighlightConfig.colour": 0, "HighlightConfig.outlineWidth": 4,
                               "HighlightConfig.enableModelOutline": 6})

    def test_references_finds_the_function_logging_a_string(self):
        s = self.session({0x1000: ["LEA RCX,[0x140900000]", "CALL 0x140003000", "RET"],
                          0x2000: ["CALL 0x140001000", "MOV EAX,0x3b9aca00", "RET"],
                          0x2100: ["CALL 0x140001000", "RET"], 0x3000: ["RET"]},
                         strings={0x900000: "SwapBuffers"})
        rva, ev = fingerprints.resolve(s, "Present", {"references": "SwapBuffers"})
        self.assertEqual((rva, ev["cls"]), (0x1000, "B"))
        s.fingerprints["Present"], s.fp_evidence["Present"] = rva, ev
        rva, ev = fingerprints.resolve(s, "AppUpdate", {"caller_of": "@Present", "imm": 0x3b9aca00})
        self.assertEqual((rva, ev["cls"]), (0x2000, "B"))
        self.assertIsNone(fingerprints.resolve(s, "Missing", {"references": "nowhere"})[0])


    def render_queue(self, loop_pops_recorded=False):
        """appUpdate begins a frame, ends it and presents the device itself after checking the recorded frame; the
        render loop pops a frame into the rendering slot (or, broken, the recorded one), runs it and presents it."""
        popped = 0x1068 if loop_pops_recorded else 0x1070
        functions = {
            0x1000: ["RET"],                                                    # present
            0x2000: ["MOV RAX,qword ptr [0x140e90a78]", "MOV RBX,qword ptr [RAX + 0x1b0]",
                     "MOV RCX,RBX", "CALL 0x140003000", "MOV RCX,RBX", "CALL 0x140003100",
                     "CMP qword ptr [RBX + 0x1068],0x0", "LEA RCX,[RBX + 0x10]", "CALL 0x140001000",
                     "MOV EAX,0x3b9aca00", "RET"],                              # appUpdate
            0x3000: ["MOV RBX,RCX", "MOV RCX,qword ptr [RCX + 0x1060]", "MOV RAX,qword ptr [RCX]",
                     "CALL qword ptr [RAX + 0x20]", "MOV qword ptr [RBX + 0x1068],RAX", "RET"],    # beginFrame
            0x3100: ["MOV RDI,RCX", "XOR R12D,R12D", "MOV qword ptr [RDI + 0x1068],R12", "RET"],  # endFrame
            0x4000: ["MOV RDI,RCX", "XOR R15D,R15D", f"MOV qword ptr [RDI + {popped:#x}],R15",
                     "MOV RCX,qword ptr [RDI + 0x1060]", "MOV RAX,qword ptr [RCX]", "CALL qword ptr [RAX + 0x10]",
                     f"MOV qword ptr [RDI + {popped:#x}],RAX", "LEA RCX,[RDI + 0x10]", "CALL 0x140001000",
                     f"MOV qword ptr [RDI + {popped:#x}],R15", "RET"],        # render loop
        }
        s = self.session(functions)
        s.fingerprints.update(Present=0x1000, AppUpdate=0x2000)
        return s

    def test_render_frames_find_the_end_of_frame_and_the_device_offsets(self):
        s = self.render_queue()
        out = {k: v[0] for k, v in frames.render_frames(s, {}).items()}
        self.assertEqual(out, {"RenderEndFrame": 0x3100, "GlDevice.context": 0x10, "GlDevice.recordingFrame": 0x1068,
                               "GlDevice.renderingFrame": 0x1070}, s.notes)

    def test_render_frames_refuse_a_loop_that_renders_from_the_recorded_frame(self):
        s = self.render_queue(loop_pops_recorded=True)
        self.assertEqual(frames.render_frames(s, {}), {})
        self.assertIn("0 candidate offsets", s.notes[0])

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
                   Result("events.ON_GAME_TICK", [{"fn": 0xC44D0, "idArg": 0, "args": 1}], "ANCHORED"),
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
            "events": {"ON_GAME_TICK": [{"fn": "0xc44d0", "idArg": 0, "args": 1}]},
        })

    def test_flatten_uses_spec_keys(self):
        flat = emit.flatten(self.build())
        self.assertEqual(flat["Client.worlds.map"], "0xc9d0")
        self.assertEqual(flat["container.hash_map.node.value"], "0x10")
        self.assertEqual(flat["enums.events.ON_GAME_TICK"], 9)
        self.assertEqual(flat["events.ON_GAME_TICK"], [{"fn": "0xc44d0", "idArg": 0, "args": 1}])
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
