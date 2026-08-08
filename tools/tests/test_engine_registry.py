import copy
import importlib.util
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "validate_engine_registry", ROOT / "tools" / "validate_engine_registry.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class EngineRegistryTest(unittest.TestCase):
    def setUp(self):
        self.path = ROOT / "engines" / "registry.json"
        self.data = json.loads(self.path.read_text(encoding="utf-8"))

    def validate_copy(self, data):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "registry.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            return MODULE.validate(path)

    def test_checked_in_registry_is_valid(self):
        self.assertEqual([], MODULE.validate(self.path))

    def test_duplicate_engine_is_rejected(self):
        data = copy.deepcopy(self.data)
        data["engines"].append(copy.deepcopy(data["engines"][0]))
        self.assertTrue(any("duplicate engine id" in error for error in self.validate_copy(data)))

    def test_unpinned_source_is_rejected(self):
        data = copy.deepcopy(self.data)
        data["engines"][0]["source"]["commit"] = "main"
        self.assertTrue(any("full Git commit" in error for error in self.validate_copy(data)))

    def test_unapproved_engine_cannot_ship(self):
        data = copy.deepcopy(self.data)
        data["engines"][0]["shipped"] = True
        self.assertTrue(any("only approved engines" in error for error in self.validate_copy(data)))

    def test_missing_required_field_is_rejected(self):
        data = copy.deepcopy(self.data)
        del data["engines"][0]["source"]["repository"]
        self.assertTrue(any("source missing" in error for error in self.validate_copy(data)))

    def test_phase_1b_set_is_exact_and_release_blocked(self):
        rows = {row["id"]: row for row in self.data["engines"] if row["tier"] == "1B"}
        self.assertEqual(MODULE.PHASE_ONE_B_IDS, set(rows))
        for row in rows.values():
            self.assertEqual("license-blocked", row["status"])
            self.assertFalse(row["shipped"])
            self.assertFalse(row["state"]["qualified"])
            self.assertFalse(row["build"]["reproducible"])

    def test_phase_1b_cannot_be_approved(self):
        data = copy.deepcopy(self.data)
        row = next(row for row in data["engines"] if row["tier"] == "1B")
        row["status"] = "approved"
        self.assertTrue(any("must remain license-blocked" in error
                            for error in self.validate_copy(data)))

    def test_archive_must_match_repository_and_commit(self):
        data = copy.deepcopy(self.data)
        data["engines"][0]["source"]["archive"] = (
            "https://github.com/example/wrong/archive/" +
            data["engines"][0]["source"]["commit"] + ".tar.gz"
        )
        self.assertTrue(any("must belong" in error for error in self.validate_copy(data)))
        data = copy.deepcopy(self.data)
        data["engines"][0]["source"]["archive"] = (
            data["engines"][0]["source"]["repository"] + "/archive/" + "0" * 40 + ".tar.gz"
        )
        self.assertTrue(any("must pin" in error for error in self.validate_copy(data)))

    def test_conditional_firmware_must_reference_mapped_system(self):
        data = copy.deepcopy(self.data)
        data["engines"][0]["firmware"]["requiredForSystems"] = ["not-mapped"]
        self.assertTrue(any("contains unmapped systems" in error
                            for error in self.validate_copy(data)))

    def test_approved_firmware_dependency_requires_hashes(self):
        data = copy.deepcopy(self.data)
        row = data["engines"][0]
        row["status"] = "approved"
        row["state"]["qualified"] = True
        row["build"]["reproducible"] = True
        row["firmware"]["required"] = True
        self.assertTrue(any("firmware dependency requires hashes" in error
                            for error in self.validate_copy(data)))

    def test_default_qualification_manifest_is_non_autoselecting(self):
        path = ROOT / "engines" / "qualification-opt-in.json"
        self.assertEqual([], MODULE.validate_qualification_opt_in(path, self.path))
        with TemporaryDirectory() as directory:
            altered = json.loads(path.read_text(encoding="utf-8"))
            altered["autoSelect"] = True
            altered_path = Path(directory) / "qualification-opt-in.json"
            altered_path.write_text(json.dumps(altered), encoding="utf-8")
            self.assertTrue(any("autoSelect=false" in error for error in
                                MODULE.validate_qualification_opt_in(altered_path, self.path)))


if __name__ == "__main__":
    unittest.main()
