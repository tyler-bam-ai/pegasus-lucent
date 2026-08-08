import hashlib
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REGISTRY_PATH = ROOT / "engines" / "phase2-registry.json"
LOCK_PATH = ROOT / "engines" / "armsx2-source-lock.json"
PATCH_PATH = ROOT / "engines" / "patches" / "armsx2-libretro-android-build.patch"
BUILD_PATH = ROOT / "engines" / "build_core.sh"
VALIDATOR_PATH = ROOT / "tools" / "validate_phase2_registry.py"
SPEC = importlib.util.spec_from_file_location("validate_phase2_registry", VALIDATOR_PATH)
VALIDATOR = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(VALIDATOR)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PhaseTwoArmsx2Test(unittest.TestCase):
    def setUp(self):
        self.registry = json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))
        self.row = next(row for row in self.registry["engines"]
                        if row["id"] == "armsx2")
        self.lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        self.recipe = BUILD_PATH.read_text(encoding="utf-8")

    def test_source_lock_is_exact_and_validator_bound(self):
        self.assertEqual(VALIDATOR.ARMSX2_COMMIT, self.lock["core"]["commit"])
        self.assertEqual(VALIDATOR.ARMSX2_ARCHIVE_SHA,
                         self.lock["core"]["archiveSha256"])
        self.assertEqual(VALIDATOR.ARMSX2_DEPENDENCY_LOCK_SHA, sha256(LOCK_PATH))
        self.assertEqual(7, len(self.lock["dependencies"]))
        self.assertEqual(7, len({item["path"] for item in self.lock["dependencies"]}))

    def test_patch_identity_is_locked_and_matches_recipe(self):
        self.assertEqual([{
            "path": VALIDATOR.ARMSX2_PATCH,
            "sha256": VALIDATOR.ARMSX2_PATCH_SHA,
        }], self.lock["patches"])
        self.assertEqual(VALIDATOR.ARMSX2_PATCH_SHA, sha256(PATCH_PATH))
        self.assertIn(VALIDATOR.ARMSX2_PATCH_SHA, self.recipe)

    def test_registry_core_lock_recipe_and_proof_are_bound(self):
        source = self.row["source"]
        self.assertEqual(VALIDATOR.ARMSX2_DEPENDENCY_LOCK,
                         source["dependencyLock"])
        self.assertEqual(self.lock["core"]["repository"], source["repository"])
        self.assertEqual(self.lock["core"]["commit"], source["commit"])
        self.assertEqual(self.lock["core"]["archiveSha256"],
                         source["archiveSha256"])
        self.assertEqual("engines/build_core.sh armsx2",
                         self.row["build"]["recipe"])
        self.assertRegex(self.row["build"]["proofArtifactSha256"],
                         r"^[0-9a-f]{64}$")

    def test_recipe_enforces_host_abi_toolchain_and_deterministic_environment(self):
        case = self.recipe.split("    armsx2)", 1)[1].split("\n    play)", 1)[0]
        self.assertIn('"$ABI" != arm64-v8a', case)
        self.assertIn('"$(uname -s)" != Darwin', case)
        for field in ("ndkSourcePropertiesSha256", "cmakeExecutableSha256",
                      "ninjaExecutableSha256"):
            value = self.lock["toolchain"][field]
            self.assertIn(value, case)
        self.assertGreaterEqual(case.count(
            'LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH="$source_date_epoch"'), 2)
        self.assertIn("-DANDROID_PLATFORM=android-26", case)
        self.assertIn('-DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=none"', case)

    def test_checked_in_registry_passes_strict_armsx2_policy(self):
        self.assertEqual([], VALIDATOR.validate(REGISTRY_PATH))


if __name__ == "__main__":
    unittest.main()
