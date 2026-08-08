import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "unified-android" / "tools" / "verify_one_app_apk.py"
SPEC = importlib.util.spec_from_file_location("one_app_verifier", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


MANIFEST = '''
E: manifest
  A: package="com.thorium.preview" (Raw: "com.thorium.preview")
  E: application
    A: android:label="Lucent" (Raw: "Lucent")
    A: android:name="com.thorium.preview.LucentApplication" (Raw: "com.thorium.preview.LucentApplication")
    E: activity
      A: android:name="org.pegasus_frontend.android.MainActivity" (Raw: "org.pegasus_frontend.android.MainActivity")
      A: android:launchMode(0x0101001d)=(type 0x10)0x2
      E: intent-filter
        E: action
          A: android:name="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
        E: category
          A: android:name="android.intent.category.LAUNCHER" (Raw: "android.intent.category.LAUNCHER")
    E: activity
      A: android:name="com.thorium.preview.PreviewActivity" (Raw: "com.thorium.preview.PreviewActivity")
      A: android:exported(0x01010010)=(type 0x12)0x0
      A: android:taskAffinity="com.thorium.preview.preview" (Raw: "com.thorium.preview.preview")
      A: android:excludeFromRecents(0x01010017)=(type 0x12)0xffffffff
    E: activity
      A: android:name="com.thorium.preview.BrowserActivity" (Raw: "com.thorium.preview.BrowserActivity")
      A: android:exported(0x01010010)=(type 0x12)0x0
'''


class OneAppApkVerifierTest(unittest.TestCase):
    def test_accepts_exact_lucent_activity_boundary(self):
        self.assertEqual([], MODULE.verify_manifest(MANIFEST))

    def test_rejects_second_game_activity(self):
        value = MANIFEST + '''
    E: activity
      A: android:name="com.thorium.preview.game.LucentGameActivity" (Raw: "com.thorium.preview.game.LucentGameActivity")
'''
        errors = MODULE.verify_manifest(value)
        self.assertTrue(any(
            "Activities outside the Lucent boundary" in error for error in errors))

    def test_rejects_external_emulator_launcher(self):
        value = MANIFEST.replace(
            "com.thorium.preview.BrowserActivity",
            "org.dolphinemu.dolphinemu.ui.main.MainActivity",
        )
        errors = MODULE.verify_manifest(value)
        self.assertTrue(any(
            "Activities outside the Lucent boundary" in error for error in errors))

    def test_allows_nonexported_external_route_trampoline(self):
        value = MANIFEST + '''
    E: activity
      A: android:name="com.thorium.preview.RomLaunchActivity" (Raw: "com.thorium.preview.RomLaunchActivity")
      A: android:exported(0x01010010)=(type 0x12)0x0
      A: android:excludeFromRecents(0x01010005)=(type 0x12)0xffffffff
'''
        errors = MODULE.verify_manifest(value)
        self.assertFalse(any(
            "outside the Lucent boundary" in error or "trampoline" in error
            for error in errors))

    def test_rejects_exported_external_route_trampoline(self):
        value = MANIFEST + '''
    E: activity
      A: android:name="com.thorium.preview.RomLaunchActivity" (Raw: "com.thorium.preview.RomLaunchActivity")
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
'''
        self.assertTrue(any(
            "trampoline must be non-exported" in error
            for error in MODULE.verify_manifest(value)))

    def test_rejects_legacy_external_app_authority(self):
        value = MANIFEST.replace(
            "  E: application",
            '  E: uses-permission\n'
            '    A: android:name="android.permission.QUERY_ALL_PACKAGES" '
            '(Raw: "android.permission.QUERY_ALL_PACKAGES")\n'
            "  E: application",
        )
        errors = MODULE.verify_manifest(value)
        self.assertTrue(any("QUERY_ALL_PACKAGES" in error for error in errors))

    def test_rejects_obsolete_exported_menu_receiver(self):
        with_receiver = MANIFEST + '''
    E: receiver
      A: android:name="com.thorium.preview.GameLaunchReceiver" (Raw: "com.thorium.preview.GameLaunchReceiver")
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
'''
        self.assertTrue(any(
            "obsolete exported GameLaunchReceiver" in error
            for error in MODULE.verify_manifest(with_receiver)
        ))

    def test_exact_apk_requires_qml_preserving_frontend_patch(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        apk = Path(temporary.name) / "lucent.apk"
        payload = bytearray(
            MODULE.FRONTEND_LAUNCH_PATCH_OFFSET +
            len(MODULE.FRONTEND_LAUNCH_PATCH)
        )
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr(
                "lib/arm64-v8a/libpegasus-fe_arm64-v8a.so", payload
            )
        self.assertTrue(any(
            "does not preserve QML" in error
            for error in MODULE.verify_frontend_launch_lifecycle(apk)
        ))
        payload[
            MODULE.FRONTEND_LAUNCH_PATCH_OFFSET:
            MODULE.FRONTEND_LAUNCH_PATCH_OFFSET +
            len(MODULE.FRONTEND_LAUNCH_PATCH)
        ] = MODULE.FRONTEND_LAUNCH_PATCH
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr(
                "lib/arm64-v8a/libpegasus-fe_arm64-v8a.so", payload
            )
        self.assertEqual([], MODULE.verify_frontend_launch_lifecycle(apk))

    def test_branding_policy_has_natural_equal_length_replacements(self):
        for before, after in MODULE._branding_replacements().items():
            self.assertEqual(len(before), len(after))
            self.assertNotIn("  ", after)
            self.assertFalse(after.endswith(" "))


if __name__ == "__main__":
    unittest.main()
