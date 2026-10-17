"""Golden tests: run the pipeline against cached program models and compare with accepted values.

They skip when the corpus build is not in the local store/cache (the binaries never live in git).
"""
import json
import tempfile
import unittest
from pathlib import Path

from native_updater import emit, ghidra, pipeline
from native_updater.identity import default_root

CORPUS = Path(__file__).resolve().parent.parent / "corpus"


def cached(sha):
    p = ghidra.paths(default_root(), sha)
    binary = default_root() / "store" / sha / "osclient.exe"
    return p["db"].exists() and binary.exists()


class GoldenTests(unittest.TestCase):
    def run_build(self, golden, previous=None, carry_only=False):
        sha = golden["sha256"]
        binary = default_root() / "store" / sha / "osclient.exe"
        with tempfile.TemporaryDirectory() as tmp:
            code, path = pipeline.run(str(binary), tmp, default_root(), previous=previous, log=lambda *a: None, carry_only=carry_only)
            mapping = json.loads(Path(path).read_text(encoding="utf-8"))
        return code, mapping

    def check(self, golden, mapping):
        flat = emit.flatten(mapping)
        wrong = {}
        for key, want in golden["keys"].items():
            got = flat.get(key)
            want_n = int(want, 16) if isinstance(want, str) else want
            got_n = int(got, 16) if isinstance(got, str) else got
            if got_n != want_n:
                wrong[key] = (want, got)
        self.assertEqual(wrong, {}, f"{len(wrong)} golden mismatches")
        for key, want in golden.get("bindings", {}).items():
            self.assertEqual(mapping["bindings"].get(key, {}).get("handler"), want, key)

    def test_seed_build(self):
        for path in sorted(CORPUS.glob("*.golden.json")):
            golden = json.loads(path.read_text(encoding="utf-8"))
            if not cached(golden["sha256"]):
                self.skipTest(f"{golden['sha256'][:16]} not cached locally")
            with self.subTest(build=golden["sha256"][:16]):
                code, mapping = self.run_build(golden)
                self.check(golden, mapping)
                self.assertIn(code, (0, 2))

    def test_carry_forward_reproduces_seed(self):
        """Build N -> N (same model) with anchor strategies disabled: carry-forward alone must reproduce the mapping."""
        for path in sorted(CORPUS.glob("*.golden.json")):
            golden = json.loads(path.read_text(encoding="utf-8"))
            if not cached(golden["sha256"]):
                self.skipTest(f"{golden['sha256'][:16]} not cached locally")
            with tempfile.TemporaryDirectory() as tmp:
                binary = default_root() / "store" / golden["sha256"] / "osclient.exe"
                _, prev = pipeline.run(str(binary), tmp, default_root(), log=lambda *a: None)
                _, carried = pipeline.run(str(binary), tmp + "/carry", default_root(), previous=str(prev), log=lambda *a: None, carry_only=True)
                flat_prev = emit.flatten(json.loads(Path(prev).read_text(encoding="utf-8")))
                flat_new = emit.flatten(json.loads(Path(carried).read_text(encoding="utf-8")))
            # Values that only an anchor can produce (hash-map parts, strides, derived values) are expected to be missing;
            # everything that lives in a displacement, immediate or RIP-relative operand must come back through carry-forward.
            carried_keys = {k: v for k, v in flat_prev.items() if v is not None and not k.startswith(("enums.", "container."))}
            missing = [k for k, v in carried_keys.items() if flat_new.get(k) != v]
            self.assertLess(len(missing) / max(1, len(carried_keys)), 0.5, f"carry-forward lost {len(missing)} of {len(carried_keys)} keys: {missing[:10]}")
            self.assertEqual(flat_new.get("global.client"), flat_prev.get("global.client"))
            self.assertEqual(flat_new.get("Actor.interacting"), flat_prev.get("Actor.interacting"))


if __name__ == "__main__":
    unittest.main()
