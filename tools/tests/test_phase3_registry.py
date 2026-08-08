import copy, importlib.util, json, unittest
from pathlib import Path

ROOT=Path(__file__).parents[2]
SPEC=importlib.util.spec_from_file_location("phase3",ROOT/"tools/validate_phase3_registry.py")
MODULE=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)

class PhaseThreeRegistryTest(unittest.TestCase):
    def setUp(self): self.data=json.loads((ROOT/"engines/phase3-registry.json").read_text())
    def test_checked_in_registry_is_fail_closed_and_complete(self): self.assertEqual([],MODULE.validate(self.data))
    def test_every_system_has_one_in_app_owner(self):
        systems=[s for row in self.data["engines"] for s in row["systems"]]
        self.assertEqual(MODULE.EXPECTED,set(systems)); self.assertEqual(len(systems),len(set(systems)))
        self.assertNotIn("external",{row["route"] for row in self.data["engines"]})
    def test_cannot_claim_a_gate_or_shipment(self):
        data=copy.deepcopy(self.data); data["engines"][0]["gates"]["source"]=True
        self.assertTrue(MODULE.validate(data))
        data=copy.deepcopy(self.data); data["engines"][0]["shipped"]=True
        self.assertTrue(MODULE.validate(data))
    def test_source_identity_is_required(self):
        data=copy.deepcopy(self.data); data["engines"][0]["source"]["commit"]="main"
        self.assertTrue(MODULE.validate(data))
        data=copy.deepcopy(self.data); del data["engines"][0]["source"]["archiveSha256"]
        self.assertTrue(MODULE.validate(data))
    def test_unified_apk_validates_and_packages_the_authority(self):
        build=(ROOT/"unified-android/build.sh").read_text()
        self.assertIn("validate_phase3_registry.py",build)
        self.assertIn("phase3-engine-registry.json",build)

if __name__ == "__main__": unittest.main()
