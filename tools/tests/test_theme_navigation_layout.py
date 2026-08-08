from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ThemeNavigationLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.theme = (ROOT / "theme" / "theme.qml").read_text(encoding="utf-8")
        cls.build = (ROOT / "unified-android" / "build.sh").read_text(encoding="utf-8")

    def test_system_list_uses_complete_eight_row_pages(self):
        self.assertIn("var visibleRows = 8", self.theme)
        self.assertIn("gameListRail.positionViewAtIndex(start, ListView.Beginning)", self.theme)
        self.assertIn("preferredHighlightBegin: 240", self.theme)
        self.assertIn("snapMode: ListView.SnapToItem", self.theme)
        self.assertIn("boundsBehavior: Flickable.StopAtBounds", self.theme)

    def test_home_list_left_right_pages_and_shoulders_own_categories(self):
        self.assertIn("function stepHomeListPage(direction)", self.theme)
        self.assertIn("api.keys.isPrevPage(event)", self.theme)
        self.assertIn("cycleHomeListCategory(-1)", self.theme)
        self.assertIn("cycleHomeListCategory(1)", self.theme)
        self.assertIn("event.key === Qt.Key_Left) {\n                    stepHomeListPage(-1)",
                      self.theme)
        self.assertIn("event.key === Qt.Key_Right) {\n                    stepHomeListPage(1)",
                      self.theme)

    def test_all_system_tabs_keep_box_art_reservation(self):
        self.assertIn("id: homeCategoryTabs", self.theme)
        self.assertIn("homeListBoxArt.x - homeListPanel.x", self.theme)
        self.assertIn("width: (homeCategoryTabs.width - 36) / 7", self.theme)

    def test_right_stick_and_optional_transitions_are_configured(self):
        self.assertIn("property bool rightStickViewSwitchingEnabled: true", self.theme)
        self.assertIn("property bool viewTransitionsEnabled: false", self.theme)
        for key in ("Qt.Key_F1", "Qt.Key_F2", "Qt.Key_F3", "Qt.Key_F4"):
            self.assertIn(key, self.theme)
        self.assertIn('"RIGHT STICK VIEW SWITCHING"', self.theme)
        self.assertIn('"VIEW TRANSITIONS"', self.theme)

    def test_settings_are_paginated(self):
        self.assertIn("readonly property int settingsPageSize: 6", self.theme)
        self.assertIn("Math.ceil(root.settingsOptionCount / root.settingsPageSize)", self.theme)
        self.assertIn('"PAGE " + (root.settingsPage + 1)', self.theme)

    def test_qt_launcher_is_patched_in_place_not_subclassed(self):
        self.assertNotIn("LucentMainActivity", self.build)
        self.assertIn("patch_main_activity_right_stick.py", self.build)
        self.assertFalse((ROOT / "unified-android" / "src" / "com" / "thorium" /
                          "preview" / "LucentMainActivity.java").exists())


if __name__ == "__main__":
    unittest.main()
