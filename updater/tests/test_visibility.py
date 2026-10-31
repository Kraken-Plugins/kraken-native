"""The renderable recipe (visibility.py) over a miniature of the constructors: each entity constructor first calls the
entity base with the object, which first calls the renderable base, which clears the hidden byte."""
import unittest

from native_updater import visibility
from native_updater.session import Blocked, Session
from native_updater.spec import Spec

from .fake_model import FakeModel

IMAGE = 0x140000000
ROOT_CLEARS_ONE = ["XOR EAX,EAX", "MOV dword ptr [RCX + 0x18],0x3e8", "MOV qword ptr [RCX + 0x8],RAX",
                   "MOV byte ptr [RCX + 0x20],0x1", "MOV byte ptr [RCX + 0x1c],0x0", "RET"]


class VisibilityTests(unittest.TestCase):
    GRAPHICS, KEYED, BASE, ROOT = 0x1000, 0x1400, 0x2000, 0x3000

    def session(self, root=None, fingerprints=None):
        model = FakeModel({
            self.GRAPHICS: ["PUSH RBX", "MOV RBX,RCX", f"CALL {IMAGE + self.BASE:#x}", "MOV dword ptr [RBX + 0x274],ESI",
                            "MOV byte ptr [RBX + 0x2c0],0x0", "POP RBX", "RET"],
            self.KEYED: ["PUSH RBX", "MOV RBX,RCX", f"CALL {IMAGE + self.BASE:#x}", "MOV byte ptr [RBX + 0x300],0x0",
                         "POP RBX", "RET"],
            self.BASE: ["SUB RSP,0x28", f"CALL {IMAGE + self.ROOT:#x}", "XOR EDX,EDX", "MOV word ptr [RCX + 0x34],0x100",
                        "MOV byte ptr [RCX + 0x68],DL", "ADD RSP,0x28", "RET"],
            self.ROOT: root or ROOT_CLEARS_ONE,
        })
        s = Session(model, Spec())
        if fingerprints is None:
            fingerprints = {"GraphicsObjectCtor": self.GRAPHICS, "KeyedProjectileCtor": self.KEYED}
        s.fingerprints = fingerprints
        return s

    def run_for(self, s, ctor):
        return {k: v[0] for k, v in visibility.renderable_hidden(s, {"ctor": ctor}).items()}

    def test_each_flag_is_the_byte_the_renderable_base_clears(self):
        s = self.session()
        self.assertEqual(self.run_for(s, "@GraphicsObjectCtor"), {"GraphicsObject.hidden": 0x1c})
        self.assertEqual(self.run_for(s, "@KeyedProjectileCtor"), {"KeyedProjectile.hidden": 0x1c})
        found = visibility.renderable_hidden(s, {"ctor": "@GraphicsObjectCtor"})
        self.assertEqual(found["GraphicsObject.hidden"][1][0]["fn"], self.ROOT)
        self.assertEqual(s.notes, [])

    def test_a_base_clearing_two_bytes_gives_no_flag(self):
        s = self.session(root=["MOV byte ptr [RCX + 0x1c],0x0", "MOV byte ptr [RCX + 0x1d],0x0", "RET"])
        self.assertEqual(self.run_for(s, "@GraphicsObjectCtor"), {})
        self.assertEqual(s.notes, [f"visibility: the renderable base {self.ROOT:#x} clears 2 bytes"])

    def test_a_constructor_not_yet_located_blocks(self):
        s = self.session(fingerprints={"GraphicsObjectCtor": None, "KeyedProjectileCtor": self.KEYED})
        with self.assertRaises(Blocked):
            self.run_for(s, "@GraphicsObjectCtor")
        self.assertEqual(self.run_for(s, "@KeyedProjectileCtor"), {"KeyedProjectile.hidden": 0x1c})
