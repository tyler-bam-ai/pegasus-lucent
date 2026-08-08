import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "unified-android" / "tools" / "generate_phase2_sbom.py"
SPEC = importlib.util.spec_from_file_location("generate_phase2_sbom", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PhaseTwoSbomTest(unittest.TestCase):
    def setUp(self):
        self.registry = json.loads((ROOT / "engines/phase2-registry.json").read_text())
        self.lock = json.loads((ROOT / "engines/ppsspp-source-lock.json").read_text())
        row = next(row for row in self.registry["engines"] if row["id"] == "ppsspp")
        self.artifacts = {"artifacts": [{
            "engineId": "ppsspp",
            "sourceCommit": row["source"]["commit"],
            "fileName": "liblucent_core_ppsspp.so",
            "sha256": row["build"]["proofArtifactSha256"],
        }]}

    def test_sbom_is_deterministic_and_covers_exact_source_closure(self):
        first = MODULE.generate(self.registry, self.lock, self.artifacts)
        second = MODULE.generate(self.registry, self.lock, self.artifacts)
        self.assertEqual(first, second)
        self.assertEqual("SPDX-2.3", first["spdxVersion"])
        self.assertEqual("CC0-1.0", first["dataLicense"])

        packages = {package["name"]: package for package in first["packages"]}
        self.assertIn("PPSSPP source", packages)
        self.assertIn("liblucent_core_ppsspp.so", packages)
        for dependency in self.lock["dependencies"]:
            package = packages[dependency["path"]]
            self.assertEqual(dependency["commit"], package["versionInfo"])
            self.assertEqual(
                dependency["archiveSha256"],
                package["checksums"][0]["checksumValue"],
            )

    def test_sbom_never_invents_dependency_license_conclusions(self):
        sbom = MODULE.generate(self.registry, self.lock, self.artifacts)
        for package in sbom["packages"]:
            self.assertEqual("NOASSERTION", package["licenseConcluded"])

    def test_play_patches_are_first_class_sbom_inputs(self):
        lock = json.loads((ROOT / "engines/play-source-lock.json").read_text())
        row = next(row for row in self.registry["engines"] if row["id"] == "play")
        artifacts = {"artifacts": [{
            "engineId": "play",
            "sourceCommit": row["source"]["commit"],
            "fileName": "liblucent_core_play.so",
            "sha256": row["build"]["proofArtifactSha256"],
        }]}
        sbom = MODULE.generate(self.registry, {"play": lock}, artifacts)
        packages = {package["name"]: package for package in sbom["packages"]}
        relationships = sbom["relationships"]
        for patch in lock["patches"]:
            package = packages[patch["path"]]
            self.assertEqual(patch["sha256"],
                             package["checksums"][0]["checksumValue"])
            self.assertTrue(any(
                relation["spdxElementId"] == package["SPDXID"] and
                relation["relationshipType"] == "PATCH_FOR"
                for relation in relationships
            ))


if __name__ == "__main__":
    unittest.main()
