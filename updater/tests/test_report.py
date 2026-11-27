"""Picking a build's mapping and the one it carries forward from (mappings.py), and the pull request description
(report.py) over small mappings and analyses."""
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from native_updater import mappings, report


def write(folder, version, sha):
    (Path(folder) / f"{version}.json").write_text(json.dumps({"version": version, "sha256": sha}), encoding="utf-8")


class Mappings(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        for version, sha in (("241.3", "a" * 64), ("242.9", "b" * 64), ("242.10", "c" * 64), ("243.1", "d" * 64)):
            write(self.dir.name, version, sha)
        (Path(self.dir.name) / "notes.json").write_text("{}", encoding="utf-8")

    def tearDown(self):
        self.dir.cleanup()

    def test_versions_sort_numerically_and_other_files_are_ignored(self):
        self.assertEqual([e.version for e in mappings.index(self.dir.name)], ["241.3", "242.9", "242.10", "243.1"])

    def test_finds_the_mapping_made_from_a_binary(self):
        self.assertEqual(mappings.for_build(self.dir.name, "c" * 64).version, "242.10")
        self.assertIsNone(mappings.for_build(self.dir.name, "e" * 64))

    def test_a_new_build_carries_forward_from_the_newest_older_mapping(self):
        self.assertEqual(mappings.previous(self.dir.name, "243.2", "e" * 64).version, "243.1")
        self.assertEqual(mappings.previous(self.dir.name, "242.11", "e" * 64).version, "242.10")

    def test_a_rebuild_of_the_same_version_carries_forward_from_the_old_binary(self):
        self.assertEqual(mappings.previous(self.dir.name, "243.1", "e" * 64).sha256, "d" * 64)

    def test_the_mapped_build_itself_is_never_its_own_previous(self):
        self.assertEqual(mappings.previous(self.dir.name, "243.1", "d" * 64).version, "242.10")

    def test_a_build_older_than_every_mapping_takes_the_oldest(self):
        self.assertEqual(mappings.previous(self.dir.name, "240.1", "e" * 64).version, "241.3")


OLD = {"version": "243.1", "sha256": "1" * 64, "globals": {"client": "0xeaff30"},
       "structs": {"Client": {"a": "0x10", "b": "0x20", "c": "0x30", "d": "0x40"}, "Actor": {"x": "0x8"}},
       "containers": {}, "enums": {"gameState": {"LOGGED_IN": 30}}, "functions": {"menuLocOp": "0x32d5a0"},
       "events": {"ON_GAME_TICK": [{"fn": "0xc6a30", "idArg": 0, "args": 1}]}}
NEW = {"version": "243.2", "sha256": "2" * 64, "globals": {"client": "0xeb0000"},
       "structs": {"Client": {"a": "0x10", "b": "0x28", "c": "0x30", "d": "0x48"}, "Actor": {"x": "0x8"}},
       "containers": {}, "enums": {"gameState": {"LOGGED_IN": 30}}, "functions": {},
       "events": {"ON_GAME_TICK": [{"fn": "0xc7000", "idArg": 0, "args": 1}]}}
SPEC = SimpleNamespace(keys=[SimpleNamespace(key="functions.menuLocOp", priority="P1"),
                             SimpleNamespace(key="Client.b", priority="P0")])


def analysis(sha, keys, previous_model=True):
    verdicts = {v: sum(1 for e in keys.values() if e["verdict"] == v)
                for v in ("PROVEN", "ANCHORED", "INFERRED", "NEEDS_REVIEW", "FAILED")}
    return {"sha256": sha, "linked": "2026-11-19T14:16:29Z", "ghidra": "12.1.4", "verdicts": verdicts, "keys": keys,
            "carryForward": {"previous": "243.1", "matchedFunctions": 1100, "carried": 290, "propagated": 0,
                             "previousModel": previous_model},
            "notes": ["menu ops: 0 LOC row bodies look like the op sender"], "fingerprints": {
                "NpcLookup": {"rva": "0x119ec0", "status": "MATCHED"}, "Gone": {"rva": None, "status": "NOT_FOUND",
                                                                               "note": "no function matches"}}}


PREV_KEYS = {"Client.b": {"value": "0x20", "verdict": "PROVEN", "classes": "AF"},
             "functions.menuLocOp": {"value": "0x32d5a0", "verdict": "INFERRED", "classes": "F"}}
NEW_KEYS = {"Client.b": {"value": "0x28", "verdict": "ANCHORED", "classes": "A"},
            "Actor.poseAnimCount": {"value": "0xf", "verdict": "INFERRED", "classes": "D", "note": "derived from the -1 run"},
            "functions.menuLocOp": {"value": None, "verdict": "FAILED", "classes": "", "reasons": ["no candidate"]}}


class Shifts(unittest.TestCase):
    def test_fields_that_shift_together_form_bands(self):
        from native_updater.emit import flatten
        bands = report.struct_bands(flatten(OLD), flatten(NEW))
        self.assertEqual(bands["Client"], [(0, 0x10, 0x10, 1), (8, 0x20, 0x20, 1), (0, 0x30, 0x30, 1), (8, 0x40, 0x40, 1)])
        self.assertEqual(bands["Actor"], [(0, 0x8, 0x8, 1)])
        self.assertNotIn("global", bands, "globals, functions and events are not structs")

    def test_a_field_that_stays_while_both_neighbours_move_is_an_outlier(self):
        from native_updater.emit import flatten
        self.assertEqual(report.outliers(flatten(OLD), flatten(NEW)),
                         [("Client.b", "0x20", "0x28", 8, 0), ("Client.c", "0x30", "0x30", 0, 8)])


class Render(unittest.TestCase):
    def body(self, exit_code=2, previous=OLD, previous_model=True):
        prev_analysis = analysis(OLD["sha256"], PREV_KEYS) if previous else None
        release = {"environment": "production", "catalog": "e5" * 32, "scanTime": 1795168076}
        return report.render(NEW, analysis(NEW["sha256"], NEW_KEYS, previous_model), previous, prev_analysis, SPEC,
                             exit_code, release, "https://example.invalid/run/1", "a6f1a4f0123456789")

    def test_names_both_builds(self):
        body = self.body()
        self.assertIn("Maps native client **243.2**", body)
        self.assertIn("carried forward from 243.1", body)
        self.assertIn("| SHA-256 | `1111111111111111` | `2222222222222222` |", body)
        self.assertIn("(`osrs-win.production`)", body)

    def test_exit_2_warns_and_lists_the_keys_left_out(self):
        body = self.body(exit_code=2)
        self.assertIn("> [!WARNING]", body)
        self.assertIn("| `functions.menuLocOp` | P1 | FAILED | INFERRED `0x32d5a0` | no candidate |", body)

    def test_exit_0_says_so(self):
        body = self.body(exit_code=0)
        self.assertNotIn("[!WARNING]", body)
        self.assertIn("The updater exited 0", body)

    def test_lists_inferred_keys_and_weaker_verdicts(self):
        body = self.body()
        self.assertIn("| `Actor.poseAnimCount` |  | D | `0xf` | derived from the -1 run |", body)
        self.assertIn("| `Client.b` | PROVEN (AF) | ANCHORED (A) |", body)
        self.assertIn("New keys (1): `Actor.poseAnimCount`", body)

    def test_shows_what_moved(self):
        body = self.body()
        self.assertIn("| Struct Client | 2 | 0 | 0 |", body)
        self.assertIn("| Functions | 0 | 0 | 1 |", body)
        self.assertIn("`Client.c`: 0x30 to 0x30 (0, its neighbours +0x8)", body)
        self.assertIn("| `events.ON_GAME_TICK` | `0xc6a30` | `0xc7000` | moved |", body)

    def test_notes_and_unmatched_fingerprints(self):
        body = self.body()
        self.assertIn("- menu ops: 0 LOC row bodies look like the op sender", body)
        self.assertIn("- Fingerprint `Gone` NOT_FOUND: no function matches", body)
        self.assertNotIn("NpcLookup", body)

    def test_a_missing_previous_model_is_called_out(self):
        self.assertIn("> [!NOTE]", self.body(previous_model=False))
        self.assertNotIn("> [!NOTE]", self.body(previous_model=True))

    def test_without_a_previous_mapping_there_is_nothing_to_compare(self):
        body = self.body(previous=None)
        self.assertNotIn("carried forward from", body)
        self.assertNotIn("## What moved", body)
        self.assertNotIn("## Verdict changes", body)
        self.assertIn("--dest <scratch>", body)

    def test_the_reproduce_steps_name_the_catalog_and_previous_mapping(self):
        body = self.body()
        self.assertIn(f"uv run native-updater fetch --catalog {'e5' * 32} --dest <scratch>", body)
        self.assertIn("--previous ../api/src/main/resources/mappings/243.1.json --out <scratch>", body)

    def test_a_rebuild_of_the_same_version_reproduces_from_masters_mapping(self):
        rebuilt = dict(NEW, version="243.1")
        body = report.render(rebuilt, analysis(NEW["sha256"], NEW_KEYS), OLD, None, SPEC, 0)
        self.assertIn("git show origin/master:api/src/main/resources/mappings/243.1.json > <scratch>/previous.json", body)
        self.assertIn("--previous <scratch>/previous.json --out <scratch>", body)

    def test_a_long_change_table_is_cut_to_fit(self):
        old = dict(OLD, functions={f"f{i}": hex(0x1000 + i) for i in range(4000)})
        new = dict(NEW, functions={f"f{i}": hex(0x2000 + i) for i in range(4000)})
        body = report.render(new, analysis(NEW["sha256"], NEW_KEYS), old, analysis(OLD["sha256"], PREV_KEYS), SPEC, 0)
        self.assertLessEqual(len(body), report.MAX_BODY)
        self.assertRegex(body, r"\| _\d+ more rows are in the run's artifact_ \|")
        self.assertIn("| Key | 243.1 | 243.2 | Change |\n|---|---|---|---|\n| `", body, "the header and some rows stay")
        self.assertTrue(body.rstrip().endswith("."), "the sections after the table stay")


if __name__ == "__main__":
    unittest.main()
