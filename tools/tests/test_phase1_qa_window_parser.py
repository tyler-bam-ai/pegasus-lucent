from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "unified-android" / "tools"))
import run_phase1a_activity_qa as qa


class Phase1QaWindowParserTest(unittest.TestCase):
    def test_discards_stale_anr_window_records(self):
        dump = """WINDOW MANAGER LAST ANR (dumpsys window lastanr)
  Window #0 Window{old u0 com.thorium.preview/org.pegasus_frontend.android.MainActivity}:
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 Window{live u0 com.thorium.preview/org.pegasus_frontend.android.MainActivity}:
    mDisplayId=0
WINDOW MANAGER POLICY STATE (dumpsys window policy)
  Window #0 Window{also-old u0 com.thorium.preview/.PreviewActivity}:
"""
        section = qa.canonical_window_section(dump)
        self.assertNotIn("old u0", section)
        self.assertNotIn("also-old", section)
        self.assertEqual(
            [qa.MAIN_ACTIVITY], qa.package_window_records(section))


if __name__ == "__main__":
    unittest.main()
