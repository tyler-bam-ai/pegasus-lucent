import hashlib
import json
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RECIPE = ROOT / "engines" / "build_core.sh"
FIXTURE = ROOT / "engines" / "qa" / "fixtures" / "ppsspp-minimal"
LOCK = ROOT / "engines" / "ppsspp-source-lock.json"
SESSION = ROOT / "unified-android" / "src" / "com" / "thorium" / \
    "preview" / "game" / "PpssppGlesEngineSession.java"


class PhaseTwoPpssppTest(unittest.TestCase):
    def setUp(self):
        self.recipe = RECIPE.read_text(encoding="utf-8")

    def test_recipe_pins_official_release_commit_and_archive(self):
        self.assertIn("ppsspp)", self.recipe)
        self.assertIn(
            "commit=fa50bb1976065c4f8b1b47af227d367fe9771555",
            self.recipe,
        )
        self.assertIn(
            "9054138072d49c306d65c17059bd85662b4ff46abe1ea6bb53d854dc80592ea6",
            self.recipe,
        )
        self.assertIn("https://github.com/hrydgard/ppsspp", self.recipe)
        self.assertIn("source=$(fetch_source_fresh ppsspp", self.recipe)
        self.assertIn("source_date_epoch=1778934711", self.recipe)

    def test_recipe_stages_required_nested_header_closure(self):
        for path in (
            "ext/armips/ext/filesystem",
            "ext/libadrenotools/lib/linkernsbypass",
            "ext/OpenXR-SDK",
            "ext/miniupnp",
            "libretro/libretro-common",
        ):
            self.assertIn(path, self.recipe)

    def test_default_proof_is_limited_and_ffmpeg_candidate_is_isolated(self):
        for flag in ("-DLIBRETRO=ON", "-DOPENXR=OFF", "-DUSE_MINIUPNPC=OFF"):
            self.assertIn(flag, self.recipe)
        self.assertIn("ppsspp_use_ffmpeg=OFF", self.recipe)
        self.assertIn('LUCENT_PPSSPP_FFMPEG_CANDIDATE:-0', self.recipe)
        self.assertIn("ppsspp_use_ffmpeg=ON", self.recipe)
        self.assertIn(
            "FFmpeg candidate builds require an isolated LUCENT_ENGINE_BUILD_DIR",
            self.recipe,
        )
        self.assertIn('-DUSE_FFMPEG="$ppsspp_use_ffmpeg"', self.recipe)

    def test_recipe_locks_toolchain_patch_paths_and_concurrency(self):
        ppsspp_case = self.recipe.split("    ppsspp)", 1)[1].split("    mame)", 1)[0]
        for token in (
            'ppsspp_ndk_dir="$SDK_DIR/ndk/27.0.12077973"',
            'ppsspp_cmake="$SDK_DIR/cmake/3.31.6/bin/cmake"',
            "expected_ppsspp_ndk_sha=1c4a54b31c5ed242a901b4a472d412819b5e08405df1c58066bd555f9dd52515",
            "expected_ppsspp_cmake_sha=94d4a3ce9e70cd9dae26e5fc7bb6ff4de67c58759762d4118a96ad2bc1b76be8",
            "expected_ppsspp_ninja_sha=3d508e91d5c159986bea2a472b1bfa849909da133aa05582a7174d33328af933",
            "Pinned PPSSPP patch checksum mismatch",
            'ppsspp_lock_dir="$BUILD_ROOT/locks/ppsspp"',
            "-ffile-prefix-map=",
            "-fdebug-prefix-map=",
            "-fmacro-prefix-map=",
        ):
            self.assertIn(token, ppsspp_case)
        self.assertNotIn('"$CMAKE" -S', ppsspp_case)
        self.assertNotIn('"$NDK_DIR/build/cmake/android.toolchain.cmake"', ppsspp_case)

    def test_wrong_api_fails_before_source_mutation(self):
        result = subprocess.run(
            [str(RECIPE), "ppsspp"],
            cwd=ROOT,
            env={
                "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
                "LUCENT_NATIVE_API": "24",
                "ANDROID_SDK_ROOT": "/Users/tyleryoung/Code/cemu/Cemu-0.5/android-sdk",
                "CMAKE": "/bin/false",
                "ANDROID_NDK_ROOT": "/definitely/not/the/pinned/ndk",
            },
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("requires ABI arm64-v8a and API 23", result.stderr)

    def test_no_retroarch_frontend_or_unlicensed_upstream_fixture(self):
        ppsspp_case = self.recipe.split("    ppsspp)", 1)[1].split("    mame)", 1)[0]
        self.assertNotIn("github.com/libretro/RetroArch", ppsspp_case)
        self.assertNotIn("com.retroarch", ppsspp_case)
        self.assertNotIn("pspautotests", ppsspp_case)

    def test_legal_fixture_is_source_only(self):
        self.assertTrue((FIXTURE / "main.c").is_file())
        self.assertTrue((FIXTURE / "LICENSE").is_file())
        self.assertIn("CC0-1.0", (FIXTURE / "LICENSE").read_text(encoding="utf-8"))
        forbidden = {".pbp", ".elf", ".iso", ".cso", ".prx"}
        self.assertFalse(
            [path for path in FIXTURE.rglob("*") if path.suffix.lower() in forbidden]
        )

    def test_source_lock_is_complete_and_matches_recipe(self):
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        self.assertEqual(
            "fa50bb1976065c4f8b1b47af227d367fe9771555",
            lock["core"]["commit"],
        )
        self.assertEqual(1778934711, lock["sourceDateEpoch"])
        self.assertEqual(
            "exact-staged-compile-inputs-for-this-build-profile",
            lock["closureScope"],
        )
        self.assertEqual("27.0.12077973", lock["toolchain"]["ndkVersion"])
        self.assertEqual("3.31.6-g38307f9", lock["toolchain"]["cmakeVersion"])
        proof = lock["limitedProfileReproducibilityProof"]
        self.assertEqual(2, proof["freshBuildCount"])
        self.assertTrue(proof["byteIdentical"])
        self.assertEqual(
            "734ba9e0c1e7040b16b0a1e6d2183914e8f9e203b9c3102899427425a925c3ba",
            proof["artifactSha256"],
        )
        self.assertEqual(
            "f791f34db0009b16b9a1b0e6713c90c115db10a1",
            proof["gnuBuildId"],
        )
        self.assertEqual(0, proof["absoluteBuilderPathMatches"])
        paths = {row["path"] for row in lock["dependencies"]}
        self.assertIn("ext/OpenXR-SDK", paths)
        self.assertIn("ext/miniupnp", paths)
        self.assertIn("ext/armips/ext/filesystem", paths)
        self.assertIn("ext/libadrenotools/lib/linkernsbypass", paths)
        patch = ROOT / lock["recipePatch"]["path"]
        self.assertTrue(patch.is_file())
        self.assertIn("v1.20.4-fa50bb1", patch.read_text(encoding="utf-8"))
        self.assertEqual(
            lock["recipePatch"]["sha256"],
            hashlib.sha256(patch.read_bytes()).hexdigest(),
        )
        for row in [lock["core"], *lock["dependencies"]]:
            self.assertRegex(row["commit"], r"^[0-9a-f]{40}$")
            self.assertRegex(row["archiveSha256"], r"^[0-9a-f]{64}$")
            self.assertIn(row["commit"], self.recipe)
            self.assertIn(row["archiveSha256"], self.recipe)

    def test_state_readiness_is_only_probed_for_a_pending_restore(self):
        session = SESSION.read_text(encoding="utf-8")
        self.assertIn(
            "if (pendingQuickResume != null &&\n"
            "                                            active.stateReady())",
            session,
        )
        self.assertNotIn(
            "if (active.stateReady()) restoreQuickResume(active);",
            session,
        )


if __name__ == "__main__":
    unittest.main()
