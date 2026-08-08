import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "phase1_compliance",
    ROOT / "unified-android" / "tools" / "generate_phase1_compliance_bundle.py",
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class Phase1ComplianceBundleTest(unittest.TestCase):
    def setUp(self):
        self.registry = ROOT / "engines" / "registry.json"
        self.opt_in = ROOT / "engines" / "qualification-opt-in.json"

    def libraries(self, directory: Path):
        opt_in = json.loads(self.opt_in.read_text(encoding="utf-8"))
        for index, row in enumerate(opt_in["engines"]):
            (directory / row["libraryName"]).write_bytes(
                (row["id"] + f"-{index}").encode("utf-8")
            )

    def test_bundle_covers_every_and_only_opted_in_engine(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.libraries(directory)
            manifest, sbom, notice = MODULE.generate(
                self.registry, self.opt_in, directory
            )
        expected = {
            row["id"] for row in json.loads(
                self.opt_in.read_text(encoding="utf-8")
            )["engines"]
        }
        self.assertEqual(expected, {row["engineId"] for row in manifest["artifacts"]})
        self.assertEqual(expected, {
            row["SPDXID"].removeprefix("SPDXRef-Package-")
            for row in sbom["packages"]
        })
        for engine_id in expected:
            self.assertIn(f"Engine id: {engine_id}", notice)
        self.assertTrue(manifest["qualificationOnly"])
        self.assertEqual("SPDX-2.3", sbom["spdxVersion"])
        for package in sbom["packages"]:
            self.assertNotEqual("NOASSERTION", package["licenseDeclared"])
            self.assertEqual(package["licenseDeclared"], package["licenseConcluded"])

    def test_output_is_deterministic(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.libraries(directory)
            first = MODULE.generate(self.registry, self.opt_in, directory)
            second = MODULE.generate(self.registry, self.opt_in, directory)
        self.assertEqual(first, second)

    def test_missing_library_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "library is absent"):
                MODULE.generate(self.registry, self.opt_in, Path(temporary))

    def test_build_packages_machine_readable_provenance(self):
        build = (ROOT / "unified-android" / "build.sh").read_text(encoding="utf-8")
        for asset in (
            "phase1-engine-artifacts.json",
            "phase1-sbom.spdx.json",
            "PHASE1-CORE-NOTICES.txt",
        ):
            self.assertIn(asset, build)
        self.assertIn("generate_phase1_compliance_bundle.py", build)


if __name__ == "__main__":
    unittest.main()
