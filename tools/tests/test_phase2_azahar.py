import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class PhaseTwoAzaharTest(unittest.TestCase):
    def setUp(self):
        self.lock_path = ROOT / "engines" / "azahar-source-lock.json"
        self.lock = json.loads(self.lock_path.read_text(encoding="utf-8"))
        self.registry = json.loads(
            (ROOT / "engines" / "phase2-registry.json").read_text(encoding="utf-8"))
        self.row = next(row for row in self.registry["engines"]
                        if row["id"] == "azahar")
        self.recipe = (ROOT / "engines" / "build_core.sh").read_text(
            encoding="utf-8")

    def test_official_source_and_release_artifact_are_exactly_locked(self):
        self.assertEqual("2125.1.3", self.lock["core"]["tag"])
        self.assertEqual("b42d0916ba9799297ae0e27c07d56801da1b5de5",
                         self.lock["core"]["commit"])
        self.assertEqual("4946db52ba9a559834cb3db075544480ac71fa4ae08b090a6708825a012a7b1b",
                         self.lock["releaseArtifact"]["archiveSha256"])
        self.assertEqual("d723066fa7c812618b5695d94b0fd85594e6c196e9e08ca9ba7134371a8da645",
                         self.lock["releaseArtifact"]["memberSha256"])
        self.assertEqual([], self.lock["dependencies"])
        self.assertEqual([], self.lock["patches"])

    def test_registry_recipe_and_proof_are_bound(self):
        self.assertEqual(self.lock["core"]["commit"],
                         self.row["source"]["commit"])
        self.assertEqual(self.lock["core"]["archiveSha256"],
                         self.row["source"]["archiveSha256"])
        self.assertEqual("engines/azahar-source-lock.json",
                         self.row["source"]["dependencyLock"])
        self.assertEqual(self.lock["releaseArtifact"]["memberSha256"],
                         self.row["build"]["proofArtifactSha256"])
        proof = ROOT / self.row["build"]["proofArtifactPath"]
        self.assertEqual(self.row["build"]["proofArtifactSha256"],
                         hashlib.sha256(proof.read_bytes()).hexdigest())
        self.assertIn("    azahar)", self.recipe)
        self.assertIn(self.lock["releaseArtifact"]["archiveSha256"], self.recipe)
        self.assertIn(self.lock["releaseArtifact"]["memberSha256"], self.recipe)

    def test_unreproduced_upstream_binary_remains_fail_closed(self):
        self.assertEqual("build-published",
                         self.row["android"]["integrationEvidence"])
        self.assertFalse(self.row["build"]["reproducible"])
        self.assertFalse(self.row["gates"]["androidArm64"])
        self.assertFalse(self.row["shipped"])

    def test_decrypted_cci_scope_does_not_require_external_keys(self):
        self.assertFalse(self.row["firmware"]["required"])
        self.assertEqual([], self.row["firmware"]["requiredForSystems"])
        self.assertIn("decrypted user-owned CCI",
                      self.row["firmware"]["notes"])
        self.assertIn("Encrypted content remains unsupported",
                      self.row["firmware"]["notes"])

    def test_thor_dual_surface_is_owned_by_lucent_and_split_in_vulkan(self):
        backend = (ROOT / "unified-android" / "native" /
                   "lucent_android_vulkan_backend.c").read_text(encoding="utf-8")
        jni = (ROOT / "unified-android" / "native" /
               "lucent_libretro_vulkan_jni.c").read_text(encoding="utf-8")
        session = (ROOT / "unified-android" / "src" / "com" / "thorium" /
                   "preview" / "game" / "PpssppGlesEngineSession.java").read_text(
                           encoding="utf-8")
        router = (ROOT / "android-companion" / "src" / "com" / "thorium" /
                  "preview" / "SecondaryGameplaySurfaceRouter.java").read_text(
                          encoding="utf-8")
        self.assertIn("LUCENT_SCREEN_TOP", backend)
        self.assertIn("LUCENT_SCREEN_BOTTOM", backend)
        self.assertIn("lucent_android_vulkan_attach_secondary", backend)
        self.assertIn("present.swapchainCount = present_count", backend)
        self.assertIn("nativeAttachSecondarySurfaceVulkan", jni)
        self.assertIn("SecondaryGameplaySurfaceRouter.Listener", session)
        self.assertIn("DualScreenLayout.lowerScreenPointerY", session)
        self.assertIn("active.detachSecondarySurfaceAndWait()", session)
        stop = session.split("@Override public void stop", 1)[1].split(
            "@Override public void release", 1)[0]
        self.assertLess(stop.index("saveQuickResume(true)"),
                        stop.index("detachSecondaryBeforeBlank(active)"))
        self.assertIn("PreviewActivity.class", router)
        self.assertIn("setLaunchDisplayId", router)


if __name__ == "__main__":
    unittest.main()
