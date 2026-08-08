"""Phase 3 native-adapter scaffolding (Wii U/Cemu).

Structural, source-scanning coverage in the established tools/tests house style
that locks the fail-closed native-adapter foundation a compiled Cemu adapter
plugs into: the pinned registry identity, the NativeAdapterCatalog present+hash
gate, honest capability reporting, and the routing that stays EXTERNAL until the
in-process adapter is real.
"""

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


REGISTRY = json.loads((ROOT / "engines" / "phase3-registry.json").read_text())
SOURCE_LOCK = json.loads((ROOT / "engines" / "cemu-source-lock.json").read_text())
CATALOG = _read(ROOT / "unified-android" / "src" / "com" / "thorium" /
                "preview" / "game" / "NativeAdapterCatalog.java")
SESSION = _read(ROOT / "unified-android" / "src" / "com" / "thorium" /
                "preview" / "game" / "NativeAdapterEngineSession.java")
BOOTSTRAP = _read(ROOT / "unified-android" / "src" / "com" / "thorium" /
                  "preview" / "game" / "InternalEngineBootstrap.java")
ROUTER = _read(ROOT / "android-companion" / "src" / "com" / "thorium" /
               "preview" / "GameLaunchRouter.java")
ROUTE_STORE = _read(ROOT / "android-companion" / "src" / "com" / "thorium" /
                    "preview" / "EngineRouteStore.java")
ABI_HEADER = _read(ROOT / "unified-android" / "native" / "include" /
                   "lucent_native_adapter.h")
HOST_C = _read(ROOT / "unified-android" / "native" / "lucent_native_adapter_host.c")
JNI_C = _read(ROOT / "unified-android" / "native" / "lucent_native_adapter_jni.c")
MOCK_C = _read(ROOT / "unified-android" / "native" / "tests" /
               "mock_native_adapter.c")
HOST_TEST_C = _read(ROOT / "unified-android" / "native" / "tests" /
                    "native_adapter_host_test.c")
RUN_TESTS = _read(ROOT / "unified-android" / "native" / "run_tests.sh")


class Phase3RegistryCemuTest(unittest.TestCase):
    def _cemu(self):
        rows = [row for row in REGISTRY["engines"] if row["id"] == "cemu"]
        self.assertEqual(1, len(rows), "exactly one cemu registry row")
        return rows[0]

    def test_cemu_is_a_native_adapter_at_the_pinned_commit(self):
        cemu = self._cemu()
        self.assertEqual("native-adapter", cemu["route"])
        self.assertEqual(["wiiu"], cemu["systems"])
        self.assertEqual("daacdda0bab55dd78a2b5aac2c12d6ebe8c8e868",
                         cemu["source"]["commit"])
        self.assertEqual(
            "6e29804a6a7900ec35a4197b91044e0ae752ad44af3437656a86afcd176b397b",
            cemu["source"]["archiveSha256"])

    def test_cemu_registry_row_stays_fail_closed(self):
        cemu = self._cemu()
        self.assertEqual("research", cemu["status"])
        self.assertFalse(cemu["shipped"])
        self.assertTrue(all(v is False for v in cemu["gates"].values()))

    def test_source_lock_reuses_the_registry_identity_and_is_not_reproducible(self):
        self.assertEqual("native-adapter", SOURCE_LOCK["route"])
        self.assertIs(False, SOURCE_LOCK["reproducible"])
        cemu = self._cemu()
        self.assertEqual(cemu["source"]["commit"], SOURCE_LOCK["core"]["commit"])
        self.assertEqual(cemu["source"]["archiveSha256"],
                         SOURCE_LOCK["core"]["archiveSha256"])
        self.assertEqual("lucent_native_adapter_entry",
                         SOURCE_LOCK["adapterAbi"]["entrySymbol"])
        self.assertEqual("liblucent_native_adapter_cemu.so",
                         SOURCE_LOCK["adapterAbi"]["expectedLibraryName"])


class NativeAdapterCatalogGateTest(unittest.TestCase):
    def test_catalog_is_present_plus_hash_verified_and_fail_closed(self):
        # The adapter .so must be bundled in the APK's native library dir AND
        # hash exactly to the signed manifest, or the entry fails closed.
        self.assertIn("context.getApplicationInfo().nativeLibraryDir", CATALOG)
        self.assertIn("InternalEngineCatalog.verifiedSha256(context, core)", CATALOG)
        self.assertIn("core.isFile()", CATALOG)
        self.assertIn("libraryRoot.equals(core.getParentFile())", CATALOG)
        self.assertIn('"[0-9a-f]{64}"', CATALOG)
        self.assertIn('"native-adapter".equals(runtime)', CATALOG)
        self.assertIn('"native-adapter".equals(row.optString("route"))', CATALOG)

    def test_catalog_absent_opt_in_yields_empty_catalog(self):
        # No opt-in asset ships today; its absence must return an empty catalog
        # rather than throwing or enabling an unverified adapter.
        self.assertIn("readAssetOrNull(context, OPT_IN)", CATALOG)
        self.assertIn("if (optInRaw == null) return result;", CATALOG)
        self.assertIn("Collections.<String, Entry>emptyMap()", CATALOG)

    def test_catalog_is_bootstrapped_off_main_thread_like_the_others(self):
        self.assertIn("NativeAdapterCatalog.expectBootstrapOn(verifier)", BOOTSTRAP)
        self.assertIn("NativeAdapterCatalog.bootstrapComplete()", BOOTSTRAP)
        self.assertIn("new NativeAdapterEngineSession(sessionContext, entry)", BOOTSTRAP)

    def test_ambiguous_owner_fails_closed(self):
        self.assertIn("if (match != null) return null;", CATALOG)


class NativeAdapterRoutingTest(unittest.TestCase):
    def test_router_prefers_internal_engines_then_falls_through_to_native_adapter(self):
        block = ROUTER[ROUTER.index("private static String engineIdForSystem"):]
        self.assertIn("InternalEngineCatalog.availableForSystem", block)
        self.assertIn("Phase2QualificationCatalog.libraryEngineIdForSystem", block)
        # Native adapter is the LAST fallback, so a system with no bundled
        # adapter resolves empty and EngineRouteStore emits its external route.
        self.assertIn("NativeAdapterCatalog.libraryEngineIdForSystem(context, normalized)",
                      block)
        phase2_at = block.index("Phase2QualificationCatalog.libraryEngineIdForSystem")
        adapter_at = block.index("NativeAdapterCatalog.libraryEngineIdForSystem")
        self.assertLess(phase2_at, adapter_at)

    def test_wiiu_prefers_external_until_the_adapter_is_present(self):
        # EngineRouteStore.resolve returns EXTERNAL when no internal engine
        # supports the system; supportsSystem is empty until the catalog gates
        # a bundled+hashed adapter in, so Wii U launches through external Cemu.
        self.assertIn("return internalAvailable ? INTERNAL : EXTERNAL;", ROUTE_STORE)
        self.assertIn("GameLaunchRouter.supportsSystem(context,", ROUTE_STORE)
        self.assertIn("libraryEngineIdForSystem", CATALOG)

    def test_session_reports_capabilities_honestly(self):
        # No fake Quick Resume: stop() flushes persistent saves and reports no
        # restore availability when has_quick_resume is false.
        self.assertIn("capabilities.hasPersistentSave", SESSION)
        self.assertIn("onRestoreAvailabilityChanged(false)", SESSION)
        self.assertIn("public boolean openRestoreHistory()", SESSION)
        self.assertIn("Native adapter is not installed", SESSION)
        # Dual screen through the shared secondary-display router.
        self.assertIn("SecondaryGameplaySurfaceRouter.request", SESSION)


class NativeAdapterAbiAndTestsTest(unittest.TestCase):
    def test_abi_version_is_pinned(self):
        self.assertIn("#define LUCENT_NATIVE_ADAPTER_ABI_VERSION 1u", ABI_HEADER)
        self.assertIn('#define LUCENT_NATIVE_ADAPTER_ENTRY_SYMBOL '
                      '"lucent_native_adapter_entry"', ABI_HEADER)

    def test_host_fails_closed_on_every_load_gate(self):
        for guard in ("cannot load adapter",
                      "adapter is missing required symbol",
                      "adapter entry returned no vtable",
                      "unsupported adapter ABI",
                      "adapter vtable has a null function pointer",
                      "adapter must be inside Lucent's trusted directory"):
            self.assertIn(guard, HOST_C)
        # Serialize/unserialize are refused without Quick Resume.
        self.assertIn("if (!host->capabilities.has_quick_resume) return 0;", HOST_C)
        self.assertIn("does not support Quick Resume", HOST_C)

    def test_mock_adapter_reports_the_documented_capabilities(self):
        self.assertIn('out->engine_id = "mock-wiiu";', MOCK_C)
        self.assertIn("out->has_quick_resume = false;", MOCK_C)
        self.assertIn("out->has_persistent_save = true;", MOCK_C)
        self.assertIn("out->dual_screen = true;", MOCK_C)
        # Fail closed on NULL content; serialize returns 0/false.
        self.assertIn("content path is required", MOCK_C)

    def test_host_test_is_wired_into_run_tests_with_asan_ubsan(self):
        self.assertIn("mock_native_adapter.c", RUN_TESTS)
        self.assertIn("-DMOCK_ABI_MISMATCH=1", RUN_TESTS)
        self.assertIn("native_adapter_host_test", RUN_TESTS)
        self.assertIn("trusted native-adapter path rejection", RUN_TESTS)
        # Runs under the sanitizer pass exactly like the other native suites.
        sanitized = RUN_TESTS[RUN_TESTS.index("SANITIZER_FLAGS="):]
        self.assertIn("native_adapter_host_test", sanitized)

    def test_host_test_asserts_the_required_lifecycle_and_fail_closed_paths(self):
        for assertion in ("adapter with mismatched ABI was accepted",
                          "adapter loaded NULL content",
                          "serialize was not refused without Quick Resume",
                          "flush_save did not write the sentinel file"):
            self.assertIn(assertion, HOST_TEST_C)

    def test_jni_bridge_exposes_the_complete_lifecycle(self):
        for symbol in ("nativeOpen", "nativeCreate", "nativeLoadContent",
                       "nativeStart", "nativeRunFrame", "nativeSetControl",
                       "nativePause", "nativeResume", "nativeFlushSave",
                       "nativeSerialize", "nativeUnserialize",
                       "nativeSurfaceRecreated", "nativeStop", "nativeDestroy",
                       "nativeDescribe"):
            self.assertIn("Java_com_thorium_preview_NativeAdapterHost_" + symbol,
                          JNI_C)
        self.assertIn("ANativeWindow_fromSurface", JNI_C)


if __name__ == "__main__":
    unittest.main()
