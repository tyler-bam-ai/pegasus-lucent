import json
import hashlib
import importlib.util
import re
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "engines" / "phase2-registry.json"
LOCK = ROOT / "engines" / "dolphin-source-lock.json"
RECIPE = ROOT / "engines" / "build_core.sh"
ACTIVITY_QA = ROOT / "unified-android" / "tools" / "run_phase2_activity_qa.py"
LIBRETRO_INPUT = ROOT / "unified-android" / "src" / "com" / "thorium" / "lucent" / "input" / "LibretroJoypadLayout.java"
LIBRETRO_HOST = ROOT / "unified-android" / "native" / "lucent_libretro_host.c"
RENDERED_DUPLICATE_PATCH = (
    ROOT / "engines" / "patches" /
    "dolphin-libretro-submit-rendered-duplicate-xfb.patch"
)
PHASE2_SESSION = ROOT / "unified-android" / "src" / "com" / "thorium" / "preview" / "game" / "PpssppGlesEngineSession.java"
CORRUPT_FBO_FIXTURE = (
    ROOT / "unified-android" / "test" / "fixtures" / "dolphin-corrupt-fbo.png"
)


class DolphinCompilerProofTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
        cls.engine = next(row for row in registry["engines"] if row["id"] == "dolphin")
        cls.lock = json.loads(LOCK.read_text(encoding="utf-8"))
        cls.recipe = RECIPE.read_text(encoding="utf-8")

    def test_core_and_dependency_closure_are_exact(self):
        source = self.engine["source"]
        self.assertEqual(source["dependencyLock"], "engines/dolphin-source-lock.json")
        self.assertEqual(self.lock["core"]["repository"], source["repository"])
        self.assertEqual(self.lock["core"]["commit"], source["commit"])
        self.assertEqual(self.lock["core"]["archiveSha256"], source["archiveSha256"])
        dependencies = self.lock["dependencies"]
        self.assertEqual(len(dependencies), 30)
        self.assertEqual(len({row["path"] for row in dependencies}), 30)
        for row in dependencies:
            self.assertTrue(row["repository"].startswith("https://"))
            self.assertRegex(row["commit"], r"^[0-9a-f]{40}$")
            self.assertRegex(row["archiveSha256"], r"^[0-9a-f]{64}$")

    def test_recipe_contains_every_locked_identity(self):
        self.assertIn("dolphin)", self.recipe)
        for row in self.lock["dependencies"]:
            self.assertIn(row["path"], self.recipe)
            self.assertIn(row["repository"], self.recipe)
            self.assertIn(row["commit"], self.recipe)
            self.assertIn(row["archiveSha256"], self.recipe)
        self.assertTrue(self.lock["cmakeOptions"]["LIBRETRO"])
        self.assertEqual(
            self.lock["cmakeOptions"]["normalizedByRemovingSection"],
            ".note.gnu.build-id",
        )
        self.assertEqual(
            self.engine["build"]["proofArtifactSha256"],
            self.lock["cmakeOptions"]["normalizedArtifactSha256"],
        )

    def test_compiler_proof_does_not_claim_runtime_qualification(self):
        engine = self.engine
        self.assertEqual(engine["route"], "libretro-core")
        self.assertFalse(engine["shipped"])
        self.assertTrue(engine["gates"]["androidArm64"])
        for gate in ("license", "dependencies", "legalContent", "renderer",
                     "state", "performance", "device"):
            self.assertFalse(engine["gates"][gate])
        self.assertEqual(engine["build"]["recipe"], "engines/build_core.sh dolphin")
        self.assertRegex(engine["build"]["proofArtifactSha256"], r"^[0-9a-f]{64}$")

    def test_recipe_builds_the_in_process_core_not_android_jni(self):
        dolphin_recipe = self.recipe.split("    dolphin)", 1)[1].split(
            "    ppsspp)", 1
        )[0]
        self.assertIn("https://github.com/libretro/dolphin", dolphin_recipe)
        self.assertIn("-DLIBRETRO=ON", dolphin_recipe)
        self.assertIn("--target dolphin_libretro", dolphin_recipe)
        self.assertIn("dolphin_libretro_android.so", dolphin_recipe)
        self.assertIn("max-page-size=16384", dolphin_recipe)
        self.assertIn("--remove-section=.note.gnu.build-id", dolphin_recipe)
        self.assertIn(
            self.lock["cmakeOptions"]["normalizedArtifactSha256"],
            dolphin_recipe,
        )
        self.assertNotIn("Source/Android/jni/libmain.so", dolphin_recipe)
        self.assertNotIn("dolphin_native.so", dolphin_recipe)

    def test_failed_checksum_cannot_replace_qualified_core(self):
        dolphin_recipe = self.recipe.split("    dolphin)", 1)[1].split(
            "    ppsspp)", 1
        )[0]
        self.assertIn("candidate_dir=$(mktemp -d", dolphin_recipe)
        self.assertIn(
            'normalized_core="$candidate_dir/dolphin_libretro.so"',
            dolphin_recipe,
        )
        checksum_check = dolphin_recipe.index(
            "Normalized Dolphin core checksum mismatch"
        )
        publish = dolphin_recipe.index(
            'mv "$normalized_core" "$OUTPUT_DIR/dolphin_libretro.so"'
        )
        self.assertLess(checksum_check, publish)

    def test_recipe_pins_nested_scm_and_lucent_frontend_patch(self):
        dolphin_recipe = self.recipe.split("    dolphin)", 1)[1].split(
            "    ppsspp)", 1
        )[0]
        path = "engines/tools/dolphin-git-shim/git"
        expected_sha = (
            "131517d95843d4cb23d50623faa078b7b4cf6db0c5b6824785e9cfeb2e2a91a3"
        )
        self.assertIn(path, dolphin_recipe)
        self.assertIn(expected_sha, dolphin_recipe)
        self.assertTrue((ROOT / path).is_file())
        self.assertIn('PATH="$dolphin_git_path:$PATH"', dolphin_recipe)
        self.assertEqual(len(self.lock["patches"]), 1)
        locked_patch = self.lock["patches"][0]
        self.assertEqual(
            locked_patch["path"],
            "engines/patches/dolphin-libretro-submit-rendered-duplicate-xfb.patch",
        )
        patch_sha = hashlib.sha256(RENDERED_DUPLICATE_PATCH.read_bytes()).hexdigest()
        self.assertEqual(locked_patch["sha256"], patch_sha)
        self.assertIn(locked_patch["path"], dolphin_recipe)
        self.assertIn(patch_sha, dolphin_recipe)

    def test_rendered_duplicate_xfb_is_submitted_to_lucent(self):
        patch = RENDERED_DUPLICATE_PATCH.read_text(encoding="utf-8")
        self.assertIn("void GLContextLR::Swap()", patch)
        self.assertIn(
            "Libretro::Video::video_cb(RETRO_HW_FRAME_BUFFER_VALID,",
            patch,
        )
        self.assertNotIn(
            "+  Libretro::Video::video_cb(VideoCommon::g_is_duplicate_frame ? nullptr",
            patch,
        )

    def test_dolphin_is_qualification_packaged_and_library_routed(self):
        opt_in = json.loads((ROOT / "engines" /
            "phase2-qualification-opt-in.json").read_text(encoding="utf-8"))
        row = next(value for value in opt_in["engines"] if value["id"] == "dolphin")
        self.assertEqual(row["libraryRouteSystems"], ["gamecube", "wii"])
        self.assertEqual(row["runtime"], "gles-libretro")
        self.assertEqual(row["systemAssetDestination"], "dolphin-emu")

    def test_activity_qa_accepts_android_logcat_pid_tag_format(self):
        harness = ACTIVITY_QA.read_text(encoding="utf-8")
        self.assertIn(r"LucentGlesBackend(?:\(\s*\d+\))?: ", harness)
        self.assertIn(r"presentation geometry.*sourcePadding=0", harness)
        self.assertNotIn(
            '"LucentGlesBackend: presentation geometry"', harness
        )

    def test_dolphin_controller_geometry_and_semantic_qa_are_explicit(self):
        mapping = LIBRETRO_INPUT.read_text(encoding="utf-8")
        phase2_session = PHASE2_SESSION.read_text(encoding="utf-8")
        harness = ACTIVITY_QA.read_text(encoding="utf-8")
        # Dolphin (GameCube/Wii) keeps A at the south position. The runtime
        # expresses this via southIsA, which is true for dolphin (and the
        # Nintendo handheld face group); GameCube/Wii still use the console
        # kidney layout for west/north.
        self.assertIn('boolean southIsA = dolphin || nintendoFace;', mapping)
        self.assertIn('case SOUTH: return southIsA ? 8 : 0;', mapping)
        self.assertIn('case EAST: return southIsA ? 0 : 8;', mapping)
        self.assertIn('case L1: return gameCube ? -1 : 10;', mapping)
        self.assertIn('case L2: return 12;', mapping)
        self.assertIn('case R2: return 13;', mapping)
        self.assertIn('case R1: return 11;', mapping)
        self.assertIn(
            'LibretroJoypadLayout.idFor(request.systemId, control)',
            phase2_session,
        )
        self.assertIn("GameCube physical A did not leave the initial prompt", harness)
        self.assertIn("Dolphin submitted incomplete frames after navigation", harness)
        self.assertIn('if visible_burst_frames != 16:', harness)

    def test_dolphin_shader_profile_uses_the_actual_synchronous_token(self):
        host = LIBRETRO_HOST.read_text(encoding="utf-8")
        profile = host.split('"dolphin_shader_compilation_mode"', 1)[1].split(
            '"dolphin_wait_for_shaders"', 1
        )[0]
        self.assertIn('options, "0", &value_size', profile)
        self.assertNotIn('options, "Synchronous", &value_size', profile)

    def test_activity_qa_rejects_exact_corrupt_dolphin_frame(self):
        self.assertEqual(
            hashlib.sha256(CORRUPT_FBO_FIXTURE.read_bytes()).hexdigest(),
            "f1feff4cb668fd6d5c89745185b93e212362478d32af7e786946396c94600ba7",
        )
        spec = importlib.util.spec_from_file_location(
            "lucent_phase2_activity_qa", ACTIVITY_QA
        )
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        metrics = module.image_metrics(CORRUPT_FBO_FIXTURE)
        self.assertTrue(metrics["edgeCorruptionDetected"])
        self.assertTrue(metrics["sideEdgeCorruptionDetected"])
        self.assertTrue(metrics["horizontalEdgeCorruptionDetected"])
        self.assertFalse(metrics["visualIntegrity"])
        self.assertFalse(metrics["visible"])

    def test_process_restore_reference_match_is_strict(self):
        spec = importlib.util.spec_from_file_location(
            "lucent_phase2_activity_qa_match", ACTIVITY_QA
        )
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            reference = Path(directory) / "reference.png"
            exact = Path(directory) / "exact.png"
            changed = Path(directory) / "changed.png"
            Image.new("RGB", (100, 100), (12, 18, 24)).save(reference)
            Image.new("RGB", (100, 100), (12, 18, 24)).save(exact)
            altered = Image.new("RGB", (100, 100), (12, 18, 24))
            for x in range(100):
                altered.putpixel((x, 0), (255, 255, 255))
            altered.save(changed)
            self.assertTrue(module.strict_frame_match(reference, exact)["matches"])
            mismatch = module.strict_frame_match(reference, changed)
            self.assertFalse(mismatch["matches"])
            self.assertGreater(mismatch["changedPixelFraction"], 0.005)



if __name__ == "__main__":
    unittest.main()
