import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "unified-android" / "tools" / "verify_runtime_return_evidence.py"
SPEC = importlib.util.spec_from_file_location("runtime_return_evidence", TOOL)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class RuntimeReturnEvidenceTest(unittest.TestCase):
    def test_activity_start_and_resume_are_release_failures(self):
        log = """
E/ActivityTaskManager: START u0 {act=com.thorium.preview.LAUNCH_INTERNAL_GAME cmp=com.thorium.preview/org.pegasus_frontend.android.MainActivity}
I/LucentInWindow: In-window route accepted engine=mesen system=nes
I/ActivityThread: performResumeActivity com.thorium.preview displayId 0
I/LucentInWindow: Returned to Lucent immediately in same window engine=mesen system=nes latencyMs=1
"""
        result = MODULE.audit(log, {"return-01.png": "Nintendo menu"})
        self.assertFalse(result.passed)
        self.assertEqual(result.activity_launch_count, 1)
        self.assertEqual(result.lifecycle_resume_count, 1)
        self.assertTrue(any("ActivityTaskManager START" in error
                            for error in result.errors))

    def test_visible_splash_overrules_fast_host_marker(self):
        log = (
            "I/LucentInWindow: Returned to Lucent immediately in same window "
            "engine=mesen system=nes latencyMs=1\n"
        )
        result = MODULE.audit(log, {
            "return-01.png": "gameplay",
            "return-02.png": "LUCENT POWERED BY PEGASUS",
        })
        self.assertFalse(result.passed)
        self.assertEqual(result.host_return_latencies_ms, (1,))
        self.assertEqual(result.visible_reset_frames, ("return-02.png",))
        self.assertTrue(any("despite host latency marker min=1ms" in error
                            for error in result.errors))

    def test_receiver_route_with_clean_frames_passes(self):
        log = """
I/LucentLaunchReceiver: received launch
I/LucentInWindow: In-window route accepted engine=mesen system=nes
I/LucentInWindow: Returned to Lucent immediately in same window engine=mesen system=nes latencyMs=7
"""
        result = MODULE.audit(log, {
            "return-01.png": "Nintendo Entertainment System 10-Yard Fight",
            "return-02.png": "Nintendo Entertainment System 1943",
        })
        self.assertTrue(result.passed, result.errors)

    def test_missing_return_marker_is_not_inferred_from_clean_screen(self):
        result = MODULE.audit(
            "I/LucentInWindow: In-window route accepted engine=mesen system=nes",
            {"return-01.png": "Nintendo menu"},
        )
        self.assertFalse(result.passed)
        self.assertIn("no in-window return marker was captured", result.errors)

    def test_stored_pass_cannot_hide_stored_splash_ocr(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "results.json"
        path.write_text(json.dumps({
            "complete": True,
            "counts": {"PASS": 1, "FAIL": 0},
            "systems": [{"titles": [{"heldStop": {
                "hostReturnLatencyMs": 3,
                "interstitialOcr": [{
                    "path": "/evidence/megadrive-return-02.png",
                    "ocr": "G<_ LUCENT",
                }],
            }}]}],
        }), encoding="utf-8")
        ocr, latencies = MODULE.stored_result_evidence(path)
        self.assertEqual(ocr, {"megadrive-return-02.png": "G<_ LUCENT"})
        self.assertEqual(latencies, (3,))
        result = MODULE.audit(
            "I/LucentInWindow: Returned to Lucent immediately in same window "
            "latencyMs=3",
            ocr,
        )
        self.assertFalse(result.passed)
        self.assertEqual(result.visible_reset_frames,
                         ("megadrive-return-02.png",))


if __name__ == "__main__":
    unittest.main()
