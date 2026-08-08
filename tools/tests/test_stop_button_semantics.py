import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "android-launch-bridge" / "src" / "com" / "thorium" \
    / "launchbridge" / "StopButtonService.java"


class StopButtonSemanticsTest(unittest.TestCase):
    def setUp(self):
        self.source = SOURCE.read_text(encoding="utf-8")

    def test_short_release_is_select_not_global_return(self):
        release = self.source.split(
            "if (event.getAction() == KeyEvent.ACTION_UP)", 1
        )[1].split(
            "if (event.getAction() != KeyEvent.ACTION_DOWN)", 1
        )[0]
        self.assertIn("return false;", release)
        self.assertNotIn("returnToPegasus", release)

    def test_hold_is_the_only_scheduled_return(self):
        self.assertIn("handler.postDelayed", self.source)
        self.assertIn("HOLD_TO_EXIT_MS", self.source)
        hold = self.source.split("handler.postDelayed", 1)[1].split(
            "}, HOLD_TO_EXIT_MS);", 1
        )[0]
        self.assertIn("returnToPegasus(contentPackage);", hold)
        self.assertIn("Stop hold completed", hold)

    def test_external_down_and_repeat_are_not_consumed(self):
        self.assertIn(
            "if (event.getRepeatCount() != 0)\n            return false;",
            self.source,
        )
        self.assertIn(
            "// Never consume the original key. A tap remains Select;",
            self.source,
        )


if __name__ == "__main__":
    unittest.main()
