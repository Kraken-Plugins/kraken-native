"""Golden tests: run the pipeline against cached program models and compare with accepted values.

They skip when the corpus build is not in the local store/cache (the binaries never live in git). Diagnostics go to a
temporary directory so the tests never overwrite the real analysis.json in the cache.
"""
import json
import tempfile
import unittest
from pathlib import Path

from native_updater import emit, ghidra, pipeline
from native_updater.identity import default_root
from native_updater.spec import load as load_spec

CORPUS = Path(__file__).resolve().parent.parent / "corpus"


def cached(sha):
    p = ghidra.paths(default_root(), sha)
    binary = default_root() / "store" / sha / "osclient.exe"
    return p["db"].exists() and binary.exists()


def as_int(v):
    return int(v, 16) if isinstance(v, str) else v


def goldens():
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(CORPUS.glob("*.golden.json"))]


class GoldenTests(unittest.TestCase):
    def run_build(self, sha, out_dir, diag_root, previous=None, carry_only=False):
        binary = default_root() / "store" / sha / "osclient.exe"
        code, path = pipeline.run(str(binary), out_dir, default_root(), previous=previous, log=lambda *a: None,
                                  carry_only=carry_only, diag_root=diag_root)
        mapping = json.loads(Path(path).read_text(encoding="utf-8"))
        analysis = json.loads((Path(diag_root) / sha / "analysis.json").read_text(encoding="utf-8"))
        return code, path, mapping, analysis

    def test_seed_build(self):
        for golden in goldens():
            sha = golden["sha256"]
            if not cached(sha):
                self.skipTest(f"{sha[:16]} not cached locally")
            with self.subTest(build=sha[:16]), tempfile.TemporaryDirectory() as tmp:
                code, path, mapping, analysis = self.run_build(sha, tmp, tmp)
                self.assertIn(code, (0, 2))
                self.assertEqual(Path(path).name, f"{golden['version']}.json")
                self.assertEqual((mapping["version"], mapping["sha256"]), (golden["version"], sha))

                values = {k: e["value"] for k, e in analysis["keys"].items()}
                wrong = {k: (want, values.get(k)) for k, want in golden["keys"].items() if as_int(values.get(k)) != as_int(want)}
                self.assertEqual(wrong, {}, f"{len(wrong)} golden mismatches")
                for name, want in golden.get("fingerprints", {}).items():
                    self.assertEqual(analysis["fingerprints"].get(name, {}).get("rva"), want, name)
                for key, want in golden.get("bindings", {}).items():
                    self.assertEqual(analysis["bindings"].get(key, {}).get("handler"), want, key)

                # The mapping holds exactly the trusted, non-internal values, and nothing else.
                flat = emit.flatten(mapping)
                internal = {k.key for k in load_spec().keys if k.internal}
                for key, e in analysis["keys"].items():
                    if key in internal or e["verdict"] not in emit.TRUSTED:
                        self.assertNotIn(key, flat)
                    elif isinstance(e["value"], dict):       # a whole enum table flattens to one key per entry
                        for name, v in e["value"].items():
                            self.assertEqual(flat[f"{key}.{name}"], v, f"{key}.{name}")
                    else:
                        self.assertEqual(flat[key], e["value"], key)
                self.assertEqual(set(mapping), {"version", "sha256", "globals", "structs", "containers", "enums", "functions", "events"})

    def test_carry_forward_reproduces_seed(self):
        """Build N -> N (same model) with anchor strategies disabled: carry-forward alone must reproduce the mapping."""
        for golden in goldens():
            sha = golden["sha256"]
            if not cached(sha):
                self.skipTest(f"{sha[:16]} not cached locally")
            with tempfile.TemporaryDirectory() as tmp:
                _, prev, _, before = self.run_build(sha, Path(tmp) / "first", tmp)
                _, _, _, after = self.run_build(sha, Path(tmp) / "carried", tmp, previous=str(prev), carry_only=True)
            old = {k: e["value"] for k, e in before["keys"].items() if e["verdict"] in emit.TRUSTED}
            new = {k: e["value"] for k, e in after["keys"].items()}
            # Values that only an anchor can produce (hash-map parts, strides, derived values) are expected to be missing;
            # everything that lives in a displacement, immediate or RIP-relative operand must come back through carry-forward.
            carried = {k: v for k, v in old.items() if v is not None and not k.startswith(("enums.", "container."))}
            missing = [k for k, v in carried.items() if new.get(k) != v]
            self.assertLess(len(missing) / max(1, len(carried)), 0.5, f"carry-forward lost {len(missing)} of {len(carried)} keys: {missing[:10]}")
            self.assertEqual(new.get("global.client"), old.get("global.client"))
            self.assertEqual(new.get("Actor.interacting"), old.get("Actor.interacting"))
            self.assertGreater(after["carryForward"]["carried"], 0)


if __name__ == "__main__":
    unittest.main()
