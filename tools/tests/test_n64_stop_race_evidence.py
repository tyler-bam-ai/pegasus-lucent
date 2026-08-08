import importlib.util
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "unified-android" / "tools" / "verify_n64_stop_race_evidence.py"
SPEC = importlib.util.spec_from_file_location("n64_stop_race_evidence", TOOL)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class N64StopRaceEvidenceTest(unittest.TestCase):
    def test_818_r2_surface_race_fails_despite_2ms_return(self):
        result = MODULE.audit("""
I/LucentInWindow: Exit to Lucent invoked engine=mupen64plus-next system=n64 reason=thor-stop-hold
E/BufferQueueProducer: dequeueBuffer: BufferQueue has been abandoned
E/LucentPhase2Engine: Renderer stopped engine=mupen64plus-next system=n64
E/LucentPhase2Engine: java.lang.IllegalStateException: Android EGL swap failed (0x300d)
I/LucentInWindow: Returned to Lucent immediately in same window engine=mupen64plus-next system=n64 latencyMs=2
E/LucentInWindow: Engine session error engine=mupen64plus-next system=n64 message=Lucent could not protect Quick Resume.
E/LucentInWindow: java.lang.IllegalStateException: engine state vault is not ready
W/LucentInWindow: Exit checkpoint failed after library return
E/libEGL: call to OpenGL ES API with no current context
""")
        self.assertFalse(result.passed)
        self.assertEqual(result.return_latencies_ms, (2,))
        self.assertGreaterEqual(result.surface_race_count, 3)
        self.assertGreaterEqual(result.session_error_count, 2)
        self.assertGreaterEqual(result.save_failure_count, 2)

    def test_clean_background_commit_after_instant_return_passes(self):
        result = MODULE.audit("""
I/LucentInWindow: Exit to Lucent invoked engine=mupen64plus-next system=n64 reason=thor-stop-hold
I/LucentInWindow: Returned to Lucent immediately in same window engine=mupen64plus-next system=n64 latencyMs=3
I/LucentPhase2Engine: Committed Quick Resume before stop engine=mupen64plus-next commit=f275caf
I/LucentInWindow: Exit checkpoint finished after library return engine=mupen64plus-next system=n64
""")
        self.assertTrue(result.passed, result.errors)

    def test_fast_return_without_commit_fails_closed(self):
        result = MODULE.audit("""
I/LucentInWindow: Exit to Lucent invoked engine=mupen64plus-next system=n64
I/LucentInWindow: Returned to Lucent immediately in same window engine=mupen64plus-next system=n64 latencyMs=1
""")
        self.assertFalse(result.passed)
        self.assertTrue(any("Quick Resume commit" in error
                            for error in result.errors))

    def test_commit_before_visible_return_is_rejected(self):
        result = MODULE.audit("""
I/LucentInWindow: Exit to Lucent invoked engine=mupen64plus-next system=n64
I/LucentPhase2Engine: Committed Quick Resume before stop engine=mupen64plus-next
I/LucentInWindow: Returned to Lucent immediately in same window engine=mupen64plus-next system=n64 latencyMs=40
I/LucentInWindow: Exit checkpoint finished after library return engine=mupen64plus-next system=n64
""")
        self.assertFalse(result.passed)
        self.assertTrue(any("exit ordering" in error for error in result.errors))

    def test_slow_return_is_not_hidden_by_successful_commit(self):
        result = MODULE.audit("""
I/LucentInWindow: Exit to Lucent invoked engine=mupen64plus-next system=n64
I/LucentInWindow: Returned to Lucent immediately in same window engine=mupen64plus-next system=n64 latencyMs=120
I/LucentPhase2Engine: Committed Quick Resume before stop engine=mupen64plus-next
I/LucentInWindow: Exit checkpoint finished after library return engine=mupen64plus-next system=n64
""")
        self.assertFalse(result.passed)
        self.assertTrue(any("exceeded 50ms" in error for error in result.errors))


if __name__ == "__main__":
    unittest.main()
