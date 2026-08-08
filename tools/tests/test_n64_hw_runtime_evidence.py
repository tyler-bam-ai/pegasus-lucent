import importlib.util
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "unified-android" / "tools" / "verify_n64_hw_runtime_evidence.py"
SPEC = importlib.util.spec_from_file_location("n64_hw_runtime_evidence", TOOL)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class N64HardwareRuntimeEvidenceTest(unittest.TestCase):
    def test_00616_style_gl_rejection_fails_closed(self):
        result = MODULE.audit("""
I/LucentInWindow: In-window route accepted engine=mupen64plus-next system=n64
I/LucentNativeHost: core library loaded path=liblucent_core_mupen64plus_next.so
I/LucentNativeHost: retro_load_game begin path=game.z64 needFullpath=0
E/LucentLibretroCore: mupen64plus: libretro frontend doesn't have OpenGL support
E/LucentPhase2Engine: Renderer stopped engine=mupen64plus-next system=n64
E/LucentPhase2Engine: java.lang.IllegalStateException: core rejected game content
""")
        self.assertFalse(result.passed)
        self.assertTrue(result.negotiation_rejected)
        self.assertTrue(any("SET_HW_RENDER" in error for error in result.errors))
        self.assertEqual(result.presented_frame_count, 0)

    def test_core_load_alone_is_never_a_pass(self):
        result = MODULE.audit("""
I/LucentInWindow: In-window route accepted engine=mupen64plus-next system=n64
I/LucentNativeHost: core library loaded path=liblucent_core_mupen64plus_next.so
""")
        self.assertFalse(result.passed)
        self.assertIn("retro_load_game never completed", result.errors)
        self.assertTrue(any("acceptance marker" in error
                            for error in result.errors))

    def test_complete_negotiated_gpu_path_passes(self):
        result = MODULE.audit("""
I/LucentInWindow: In-window route accepted engine=mupen64plus-next system=n64
I/LucentNativeHost: hardware render request accepted context=OPENGLES3 cacheContext=1
I/LucentNativeHost: retro_load_game complete
I/LucentLibretroCore: mupen64plus: context_reset()
I/LucentGlesBackend: pre-swap pixels sequence=1 policy=2 path=2 window=1,2,3,255 frontend=0,0,0,0 fbo=0
""")
        self.assertTrue(result.passed, result.errors)
        self.assertEqual(result.hardware_contexts, ("OPENGLES3",))
        self.assertEqual(result.context_reset_count, 1)
        self.assertEqual(result.presented_frame_count, 1)

    def test_zero_sequence_is_not_presented_frame_proof(self):
        result = MODULE.audit("""
I/LucentInWindow: In-window route accepted engine=mupen64plus-next system=n64
I/LucentNativeHost: hardware render accepted context=OPENGLES3
I/LucentNativeHost: retro_load_game complete
I/LucentNativeHost: hardware context reset complete
I/LucentGlesBackend: pre-swap pixels sequence=0 policy=2
""")
        self.assertFalse(result.passed)
        self.assertEqual(result.presented_frame_count, 0)


if __name__ == "__main__":
    unittest.main()
