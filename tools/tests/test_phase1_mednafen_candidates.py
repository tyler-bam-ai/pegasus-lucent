import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class PhaseOneMednafenCandidatesTest(unittest.TestCase):
    def setUp(self):
        registry = json.loads((ROOT / "engines/registry.json").read_text(encoding="utf-8"))
        self.rows = {row["id"]: row for row in registry["engines"]}
        self.opt_in = json.loads((
            ROOT / "engines/qualification-opt-in.json"
        ).read_text(encoding="utf-8"))
        lock = json.loads((
            ROOT / "engines/reproducibility-lock.json"
        ).read_text(encoding="utf-8"))
        self.proofs = {row["id"]: row for row in lock["engines"]}

    def test_compatible_candidates_are_pinned_reproducible_and_fail_closed(self):
        expected = {
            "beetle-pce-fast": (
                "ec3ef1874f0590ab6d5cd4bba86969ee085e8bef2de4b4e5f2422fe38a2c2d3d",
                "5c92dc4f2db1cca7a8a701f53fcd8d97be3fa598517e812bb26814c3b12ed6f7",
            ),
            "beetle-neopop": (
                "ffeabf2a357548f3a55423d542c31bb1e08571d81d7104f8213df3bfa2eb9c9f",
                "5cfa9b6da0874181c6c09966977115e4c9874307ddaa19ccc890ce880f86a937",
            ),
            "beetle-cygne": (
                "bce15c0e2505e15b7b55fa1d51b4a12219d07a67be62bbca5c26678d0d89c659",
                "62d8f5b67fc91d52e38002de7889f5202cff3a9a230e49c0ac9fe4207c9a040a",
            ),
        }
        for engine_id, (archive_sha, artifact_sha) in expected.items():
            with self.subTest(engine=engine_id):
                row = self.rows[engine_id]
                proof = self.proofs[engine_id]
                self.assertEqual("1A", row["tier"])
                self.assertEqual("experimental", row["status"])
                self.assertEqual("compatible-candidate", row["license"]["distributionGate"])
                self.assertEqual(archive_sha, row["source"]["archiveSha256"])
                self.assertTrue(row["build"]["reproducible"])
                self.assertFalse(row["state"]["qualified"])
                self.assertFalse(row["shipped"])
                self.assertEqual(archive_sha, proof["sourceArchiveSha256"])
                self.assertEqual(artifact_sha, proof["artifactSha256"])
                self.assertEqual(2, proof["independentBuilds"])
                self.assertTrue(proof["byteIdentical"])

    def test_qualification_manifest_exposes_artifacts_but_never_auto_selects(self):
        selected = {row["id"]: row for row in self.opt_in["engines"]}
        for engine_id in ("beetle-pce-fast", "beetle-neopop", "beetle-cygne"):
            with self.subTest(engine=engine_id):
                self.assertIn(engine_id, selected)
                self.assertEqual(
                    "liblucent_core_" + engine_id.replace("-", "_") + ".so",
                    selected[engine_id]["libraryName"],
                )
                self.assertEqual(self.rows[engine_id]["source"]["commit"],
                                 selected[engine_id]["commit"])
        self.assertNotIn("beetle-vb", selected)
        self.assertTrue(self.opt_in["qualificationOnly"])
        self.assertFalse(self.opt_in["autoSelect"])

    def test_pc_engine_cd_is_not_advertised_without_an_enforced_firmware_contract(self):
        row = self.rows["beetle-pce-fast"]
        self.assertEqual(["pcengine"], row["systems"])
        self.assertNotIn("pcenginecd", row["systems"])
        self.assertIn("PC Engine CD", row["firmware"]["notes"])

    def test_cygne_does_not_advertise_unqualified_monochrome_wonderswan(self):
        row = self.rows["beetle-cygne"]
        self.assertEqual(["wonderswancolor"], row["systems"])
        self.assertIn("Monochrome WonderSwan", row["statusReason"])

    def test_virtual_boy_remains_license_blocked_and_unpackageable(self):
        row = self.rows["beetle-vb"]
        audit = json.loads((
            ROOT / "engines/audits/beetle-vb-license.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual("1B", row["tier"])
        self.assertEqual("license-blocked", row["status"])
        self.assertEqual("blocked", row["license"]["distributionGate"])
        self.assertFalse(row["build"]["reproducible"])
        self.assertFalse(row["state"]["qualified"])
        self.assertFalse(row["shipped"])
        self.assertIn("SoftFloat", audit["aggregateConclusion"])
        self.assertNotIn("beetle-vb", {entry["id"] for entry in self.opt_in["engines"]})

    def test_exact_recipe_is_single_abi_and_strips_linker_build_ids(self):
        recipe = (ROOT / "engines/build_core.sh").read_text(encoding="utf-8")
        self.assertIn("build_mednafen_software_core()", recipe)
        self.assertIn("arm64-v8a) core_target=aarch64-linux-android", recipe)
        self.assertIn("-Wl,--build-id=none", recipe)
        for engine_id in (
            "beetle-pce-fast", "beetle-neopop", "beetle-cygne", "beetle-vb"
        ):
            self.assertIn("    " + engine_id + ")", recipe)

    def test_configuration_audits_match_exact_source_identity(self):
        for engine_id in (
            "beetle-pce-fast", "beetle-neopop", "beetle-cygne", "beetle-vb"
        ):
            with self.subTest(engine=engine_id):
                audit = json.loads((
                    ROOT / "engines/audits" / (engine_id + "-license.json")
                ).read_text(encoding="utf-8"))
                row = self.rows[engine_id]
                self.assertEqual(row["source"]["commit"], audit["sourceCommit"])
                self.assertEqual(row["source"]["archiveSha256"],
                                 audit["sourceArchiveSha256"])
                self.assertIn("not legal advice", audit["scope"])

    def test_one_app_one_main_activity_invariant_remains_unchanged(self):
        build = (ROOT / "unified-android/build.sh").read_text(encoding="utf-8")
        self.assertIn(
            'android:launchMode="singleTask" '
            'android:name="org.pegasus_frontend.android.MainActivity"',
            build,
        )
        self.assertNotIn('android:name="com.thorium.preview.game.LucentGameActivity"',
                         build)
        self.assertNotIn(
            'android:name="com.thorium.preview.game.InternalGameLaunchActivity"',
            build,
        )


if __name__ == "__main__":
    unittest.main()
