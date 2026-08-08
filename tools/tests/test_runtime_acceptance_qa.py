import importlib.util
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "unified-android" / "tools"
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location(
    "runtime_acceptance", TOOLS / "run_runtime_acceptance_qa.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class RuntimeAcceptanceQaTest(unittest.TestCase):
    def test_matrix_contains_every_current_user_gate(self):
        matrix = MODULE.load_matrix(TOOLS / "runtime-acceptance-matrix.json")
        folders = {case.folder for case in matrix}
        self.assertTrue({
            "nes", "megadrive", "gb", "gamegear", "snes", "psx", "n64",
            "dreamcast", "gbc", "ps2", "gba", "gc", "nds", "wii", "n3ds",
            "ps3", "wiiu", "windows", "switch",
        }.issubset(folders))
        dual = {case.folder for case in matrix if case.dual_screen}
        self.assertEqual(dual, {"nds", "n3ds", "wiiu"})
        self.assertTrue(next(case for case in matrix if case.folder == "wii").flicker_burst)
        named = {case.folder: case.required_titles for case in matrix
                 if case.required_titles}
        self.assertEqual(named["gc"], ("Metroid Prime",))
        self.assertEqual(named["wii"],
                         ("Super Mario Galaxy", "Super Mario Galaxy 2"))
        self.assertEqual(named["nds"], ("Cobalt Demo",))
        self.assertEqual(named["n3ds"],
                         ("The Legend of Zelda: A Link Between Worlds",))
        touched = {case.folder for case in matrix if case.lower_touch}
        self.assertEqual(touched, {"nds", "n3ds"})

    def test_embedded_theme_catalog_drives_menu_order(self):
        qml = b'''ListModel { id: systemCatalog
          ListElement { name: "ALL"; collectionName: ""; folder: "all" }
          ListElement { name: "NES"; collectionName: "NES"; folder: "nes" }
          ListElement { name: "N64"; collectionName: "N64"; folder: "n64" }
        }
        // Predecode official platform logotypes'''
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as theme:
            theme.writestr("theme.qml", qml)
            theme.writestr("theme.cfg", b"name: test")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        apk = Path(temporary.name) / "test.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("assets/pegasus-lucent-theme.zip", nested.getvalue())
        _qml, _cfg, order = MODULE.embedded_theme(apk)
        self.assertEqual(order, ["all", "nes", "n64"])

    def test_visible_order_uses_live_library_index_not_fixed_assumptions(self):
        # Exercise the ordering join without coupling the test to a device.
        matrix = MODULE.load_matrix(TOOLS / "runtime-acceptance-matrix.json")
        catalog = ["all", "nes", "megadrive", "gb", "n64"]
        active = {"systems": {"n64": {}, "nes": {}}}
        with unittest.mock.patch.object(MODULE, "embedded_theme",
                                        return_value=(b"", b"", catalog)):
            self.assertEqual(MODULE.visible_system_order(Path("x.apk"), active),
                             ["all", "nes", "n64"])
        self.assertGreater(len(matrix), 0)

    def test_controller_axis_parser_accepts_thor_z_rz_pair(self):
        value = '''
          ABS_Z                : value 0, min -32768, max 32767, fuzz 0, flat 4096
          ABS_RZ               : value 0, min -32768, max 32767, fuzz 0, flat 4096
        '''
        axes = MODULE.PhysicalController.parse_axes(value)
        self.assertEqual(axes["ABS_Z"], (-32768, 32767, 0))
        self.assertEqual(axes["ABS_RZ"], (-32768, 32767, 0))

    def test_harness_has_no_direct_game_intent_or_virtual_navigation(self):
        source = (TOOLS / "run_runtime_acceptance_qa.py").read_text(encoding="utf-8")
        self.assertNotIn("LAUNCH_INTERNAL_GAME", source)
        self.assertNotIn('"input", "keyevent"', source)
        self.assertIn("sendevent", source)
        self.assertIn("physical-a-launch", source)
        self.assertIn("QtActivityDelegate.createSurface", source)
        self.assertIn("performResumeActivity com.thorium.preview displayId 0", source)
        self.assertIn("exactSelectionRestored", source)
        self.assertIn("Stop returned to a different library selection", source)
        self.assertIn("physical A did not reach the in-process launch interceptor", source)

    def test_named_title_matching_does_not_accept_shared_franchise_only(self):
        self.assertTrue(MODULE.selected_title_matches(
            "Super Mario Galaxy 2", "SUPER MARIO GALAXY 2 CRITICS 9.1"
        ))
        self.assertFalse(MODULE.selected_title_matches(
            "Super Mario Galaxy 2", "SUPER MARIO GALAXY CRITICS 9.1"
        ))
        self.assertTrue(MODULE.selected_title_matches(
            "The Legend of Zelda: A Link Between Worlds",
            "THE LEGEND OF ZELDA A LINK BETWEEN WORLDS",
        ))
        self.assertTrue(MODULE.selected_title_matches(
            "1943: The Battle of Midway",
            "NINTENDO ENTERTAINMENT SYSTEM The Battle of Midw USERS 7.2",
        ))

    def test_required_titles_are_exactly_resolved_and_then_filled(self):
        case = MODULE.SystemCase(
            "wii", ("wii",), ("dolphin",), 2,
            required_titles=("Super Mario Galaxy", "Super Mario Galaxy 2"),
        )
        keys = ["wii|Donkey Kong Country Returns",
                "wii|Super Mario Galaxy", "wii|Super Mario Galaxy 2"]
        self.assertEqual(MODULE.title_position(keys, "Super Mario Galaxy 2"), 2)
        self.assertEqual(MODULE.acceptance_titles(case, keys, 3), [
            "Super Mario Galaxy", "Super Mario Galaxy 2",
            "Donkey Kong Country Returns",
        ])
        with self.assertRaises(RuntimeError):
            MODULE.title_position(keys, "Metroid Prime")

    def test_active_sort_detector_uses_selected_accent_in_frozen_geometry(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "sort.png"
        image = Image.new("RGB", (1920, 1080), (3, 5, 9))
        draw = ImageDraw.Draw(image)
        for index in range(4):
            left = 48 + index * 150
            fill = (12, 16, 22) if index != 2 else (35, 184, 116)
            draw.rounded_rectangle((left, 350, left + 142, 394), 7, fill=fill)
        image.save(path)
        self.assertEqual(MODULE.active_sort_index(path), 2)

    def test_stop_gate_is_five_hundred_milliseconds_and_background_save(self):
        source = (TOOLS / "run_runtime_acceptance_qa.py").read_text(encoding="utf-8")
        self.assertIn("visible_latency > 500", source)
        self.assertIn("background Quick Resume commit did not complete", source)
        self.assertIn("assert_no_interstitial", source)
        self.assertIn('"activityStart"', source)
        self.assertIn('"performResume"', source)
        self.assertIn('"qtCreateSurface"', source)
        self.assertIn('"input", "touchscreen", "-d", "4"', source)
        self.assertIn('"swipe", "960", "270", "960", "270", "220"', source)
        self.assertIsNotNone(MODULE.PROHIBITED_TEXT.search("LUCENT"))
        self.assertIsNotNone(MODULE.PROHIBITED_TEXT.search("POWERED BY PEGASUS"))


if __name__ == "__main__":
    unittest.main()
