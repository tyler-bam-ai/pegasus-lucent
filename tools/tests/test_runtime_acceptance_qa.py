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

    def test_catalog_display_names_map_folder_to_on_screen_name(self):
        qml = b'''ListModel { id: systemCatalog
          ListElement { name: "ALL"; collectionName: ""; folder: "all" }
          ListElement { name: "GAME BOY"; collectionName: "GB"; folder: "gb" }
          ListElement { name: "GAME BOY COLOR"; collectionName: "GBC"; folder: "gbc" }
          ListElement { name: "GAME BOY ADVANCE"; collectionName: "GBA"; folder: "gba" }
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
        self.assertEqual(MODULE.catalog_display_names(apk), {
            "all": "all",
            "gb": "gameboy",
            "gbc": "gameboycolor",
            "gba": "gameboyadvance",
        })

    def test_resolve_list_folder_prefers_longest_display_name(self):
        names = {
            "gb": "gameboy",
            "gbc": "gameboycolor",
            "gba": "gameboyadvance",
            "n64": "nintendo64",
        }
        # The longest contained display name wins so "GAME BOY" never shadows
        # "GAME BOY ADVANCE".
        self.assertEqual(
            MODULE.resolve_list_folder("GAME BOY ADVANCE", names), "gba")
        self.assertEqual(MODULE.resolve_list_folder("GAME BOY", names), "gb")
        self.assertEqual(
            MODULE.resolve_list_folder("GAME BOY COLOR", names), "gbc")
        self.assertEqual(
            MODULE.resolve_list_folder("NINTENDO 64", names), "n64")
        # Unreadable / unknown headers do not resolve to any folder.
        self.assertIsNone(MODULE.resolve_list_folder("", names))
        self.assertIsNone(MODULE.resolve_list_folder("PLAYSTATION", names))

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

    # ---- route closure after startup: external routing is legitimate --------

    MAIN_ACTIVITY = (
        "com.thorium.preview/org.pegasus_frontend.android.MainActivity")

    def _internal_launch(self, engine, system):
        return ("launch: am start -a com.thorium.preview.LAUNCH_INTERNAL_GAME "
                f"-n {self.MAIN_ACTIVITY} "
                f"--es engine_id {engine} --es system {system}")

    def _external_view_install(self, package):
        return ("launch: am start -a android.intent.action.VIEW "
                f"-d https://play.google.com/store/apps/details?id={package}")

    def _external_trampoline(self, package):
        return ("launch: am start -a com.thorium.preview.LAUNCH_FILE "
                "-n com.thorium.preview/com.thorium.preview.RomLaunchActivity "
                '--es path "{file.path}" '
                f"--es target_package {package} "
                "--es target_activity .MainActivity "
                "--es target_action android.intent.action.VIEW")

    def _foreign_broadcast(self):
        return ("launch: am broadcast -a android.intent.action.MAIN "
                "-n org.foreign.launcher/.Receiver")

    @staticmethod
    def _route_text(*blocks):
        lines = []
        for shortname, launch in blocks:
            lines.append(f"collection: {shortname.upper()}")
            lines.append(f"shortname: {shortname}")
            lines.append(launch)
        return "\n".join(lines) + "\n"

    def _post_startup_route_errors(self, route_text, sim_routes):
        """Reproduce the harness's device-independent post-startup checks.

        Mirrors run_runtime_acceptance_qa.main's invalidMetadataLaunchers and
        internal route-closure comparison (the APK-bound verify() step needs a
        real device and is exercised separately).
        """
        rc = MODULE.route_closure
        errors = []
        for row in rc.invalid_launch_lines(route_text):
            errors.append("non-lucent launcher: " + str(row["commandSha256"]))
        externals = MODULE.external_route_systems(route_text)
        installed = rc.routes_from_text(route_text)
        internal_installed = {r for r in installed if r.system not in externals}
        expected_internal = {r for r in sim_routes if r.system not in externals}
        if internal_installed != expected_internal:
            errors.append("internal route difference")
        return errors, externals

    def test_startup_route_closure_tolerates_external_routes(self):
        Route = MODULE.route_closure.Route
        # Startup metadata mixes an internal route (nes->mesen), an external
        # install page (switch, no packaged internal engine) and a user-set
        # external system that DOES have an internal engine (n64->mupen in the
        # internal-only simulation) routed out through the RomLaunchActivity
        # trampoline. All three must pass the after-startup route-closure check.
        route_text = self._route_text(
            ("nes", self._internal_launch("mesen", "nes")),
            ("switch", self._external_view_install("org.eden")),
            ("n64", self._external_trampoline("org.mupen")),
        )
        sim_routes = {Route("nes", "mesen"), Route("n64", "mupen")}
        errors, externals = self._post_startup_route_errors(route_text, sim_routes)
        self.assertEqual(errors, [])
        self.assertEqual(externals, {"switch", "n64"})

    def test_startup_route_closure_still_fails_broken_internal_route(self):
        Route = MODULE.route_closure.Route
        # nes claims an internal in-window route to an engine the candidate does
        # not package. It is NOT an external route, so it stays in the strict
        # comparison and diverges from the simulation.
        route_text = self._route_text(
            ("nes", self._internal_launch("unpackaged", "nes")),
            ("switch", self._external_view_install("org.eden")),
        )
        sim_routes = {Route("nes", "mesen")}
        errors, externals = self._post_startup_route_errors(route_text, sim_routes)
        self.assertIn("internal route difference", errors)
        self.assertNotIn("nes", externals)

    def test_startup_route_closure_still_fails_non_lucent_launcher(self):
        Route = MODULE.route_closure.Route
        route_text = self._route_text(
            ("nes", self._internal_launch("mesen", "nes")),
            ("gb", self._foreign_broadcast()),
        )
        sim_routes = {Route("nes", "mesen")}
        errors, externals = self._post_startup_route_errors(route_text, sim_routes)
        self.assertTrue(any(e.startswith("non-lucent launcher") for e in errors))
        # A foreign broadcast is never mistaken for a legitimate external route.
        self.assertEqual(externals, set())

    def test_external_route_systems_ignores_internal_and_stale_launchers(self):
        # Only lines that audit as "external-route" exempt their system.
        stale_same_activity = (
            "launch: am start "
            f"-n {self.MAIN_ACTIVITY} --es engine_id mesen --es system nes")
        route_text = self._route_text(
            ("nes", self._internal_launch("mesen", "nes")),
            ("snes", stale_same_activity),
        )
        self.assertEqual(MODULE.external_route_systems(route_text), set())

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

    def _dual_case(self):
        return MODULE.SystemCase(
            "nds", ("nds", "ds"), ("melonds-ds",), 1,
            dual_screen=True, lower_touch=True,
            required_titles=("Cobalt Demo",),
        )

    def _single_case(self):
        return MODULE.SystemCase("nes", ("nes",), ("mesen",), 1)

    def _start_line(self, intent, tail=""):
        return ("01-01 00:00:00.000  1234  1234 I ActivityTaskManager: "
                f"START u0 {{{intent}}}{tail}")

    def test_dual_screen_secondary_gameplay_activity_is_whitelisted(self):
        # Lucent's OWN PreviewActivity on the physical lower display, with the
        # SECONDARY_GAMEPLAY action, is the single permitted second Activity.
        secondary = self._start_line(
            "act=com.thorium.preview.SECONDARY_GAMEPLAY "
            "cmp=com.thorium.preview/.PreviewActivity",
            " from uid 10123",
        )
        routed = "\n".join([
            "In-window route accepted engine=melonds-ds system=nds",
            secondary,
            "performResumeActivity com.thorium.preview displayId 4",
        ])
        count, violations = MODULE.classify_new_activity_starts(
            routed, "", self._dual_case())
        self.assertEqual(count, 1)
        self.assertEqual(violations, [])
        # Corroborated by a resume on a non-primary display.
        self.assertIsNotNone(MODULE.SECONDARY_GAMEPLAY_RESUME.search(routed))

    def test_dual_screen_rejects_foreign_package_activity(self):
        routed = "\n".join([
            self._start_line(
                "act=android.intent.action.MAIN "
                "cmp=org.melonds.emulator/.EmulatorActivity"),
        ])
        count, violations = MODULE.classify_new_activity_starts(
            routed, "", self._dual_case())
        self.assertEqual(count, 0)
        self.assertEqual(len(violations), 1)

    def test_dual_screen_rejects_display_zero_and_main_activity(self):
        # A MainActivity relaunch (top-screen / display 0) is never whitelisted.
        main_relaunch = self._start_line(
            "act=android.intent.action.MAIN "
            "cmp=com.thorium.preview/org.pegasus_frontend.android.MainActivity")
        _count, violations = MODULE.classify_new_activity_starts(
            main_relaunch, "", self._dual_case())
        self.assertEqual(len(violations), 1)
        # Even a SECONDARY_GAMEPLAY-labelled start pinned to display 0 is rejected.
        pinned = self._start_line(
            "act=com.thorium.preview.SECONDARY_GAMEPLAY "
            "cmp=com.thorium.preview/.PreviewActivity",
            " from uid 10123 on displayId=0")
        count, violations = MODULE.classify_new_activity_starts(
            pinned, "", self._dual_case())
        self.assertEqual(count, 0)
        self.assertEqual(len(violations), 1)

    def test_single_screen_never_whitelists_a_second_activity(self):
        secondary = self._start_line(
            "act=com.thorium.preview.SECONDARY_GAMEPLAY "
            "cmp=com.thorium.preview/.PreviewActivity")
        count, violations = MODULE.classify_new_activity_starts(
            secondary, "", self._single_case())
        self.assertEqual(count, 0)
        self.assertEqual(len(violations), 1)

    def test_baseline_activity_starts_are_not_counted_as_new(self):
        secondary = self._start_line(
            "act=com.thorium.preview.SECONDARY_GAMEPLAY "
            "cmp=com.thorium.preview/.PreviewActivity")
        # The same start already present in the pre-launch baseline snapshot is
        # not treated as a new launch.
        count, violations = MODULE.classify_new_activity_starts(
            secondary, secondary, self._dual_case())
        self.assertEqual(count, 0)
        self.assertEqual(violations, [])

    def test_in_process_assertions_include_dual_screen_whitelist(self):
        source = (TOOLS / "run_runtime_acceptance_qa.py").read_text(encoding="utf-8")
        self.assertIn("classify_new_activity_starts", source)
        self.assertIn(
            "dual-screen secondary gameplay window never resumed", source)
        self.assertIn("SECONDARY_GAMEPLAY_RESUME", source)
        self.assertIn(
            "menu A launch started an Activity instead of staying in-process",
            source)

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
