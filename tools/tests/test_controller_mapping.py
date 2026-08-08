"""Keeps the controller mapping doc, the runtime tables and the engine sessions
in agreement.

The Thor reports its D-pad as a hat and its left stick as ABS_X/ABS_Y, so both
arrive as axes in one MotionEvent and both resolve to the same RetroPad IDs on a
D-pad-only console. Writing each source straight to setJoypadButton made the
last one written win, which is why one axis of the stick and one axis of the hat
each appeared dead on hardware. These tests pin the fix and the per-system
tables that depend on it.
"""

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "docs" / "controller-mapping.md"
INPUT_DIR = ROOT / "unified-android" / "src" / "com" / "thorium" / "lucent" / "input"
LAYOUT = INPUT_DIR / "LibretroJoypadLayout.java"
LEDGER = INPUT_DIR / "JoypadPressLedger.java"
SYSTEM_LAYOUTS = INPUT_DIR / "SystemControlLayouts.java"
GAME_DIR = ROOT / "unified-android" / "src" / "com" / "thorium" / "preview" / "game"
LIBRETRO_SESSION = GAME_DIR / "LibretroEngineSession.java"
GLES_SESSION = GAME_DIR / "PpssppGlesEngineSession.java"
LEDGER_TEST = (ROOT / "unified-android" / "test" / "com" / "thorium" / "lucent" /
               "input" / "JoypadPressLedgerTest.java")
QA_HARNESS = ROOT / "unified-android" / "tools" / "run_runtime_acceptance_qa.py"

# Consoles whose core reads RETRO_DEVICE_ANALOG index 0 as its own control, so
# the left stick must not also press the digital D-pad.
ANALOG_STICK_SYSTEMS = {
    "n64", "nintendo64", "gc", "gamecube", "nintendogamecube", "wii",
    "nintendowii", "dreamcast", "naomi", "atomiswave", "psp",
}


def switch_body(source: str, signature: str) -> dict:
    """Parses one `case CONTROL: return <id>;` switch into {control: id}."""
    body = source.split(signature, 1)[1].split("\n    }", 1)[0]
    return {name: int(value)
            for name, value in re.findall(r"case (\w+): return (-?\d+);", body)}


class ControllerMappingDocTests(unittest.TestCase):
    def test_doc_exists_and_names_the_target_hardware(self):
        doc = DOC.read_text(encoding="utf-8")
        # The handover's recorded Thor facts. A doc that drifts from these is
        # describing a different device.
        for fact in ("BTN_SOUTH` 304", "BTN_SELECT` 314", "ABS_HAT0X",
                     "ABS_HAT0Y", "ABS_Z", "ABS_RZ",
                     "BTN_TL2` 312", "BTN_TR2` 313"):
            self.assertIn(fact, doc, f"the doc must record {fact}")

    def test_doc_covers_every_registered_system(self):
        doc = DOC.read_text(encoding="utf-8").lower()
        missing = []
        for name in ("registry.json", "phase2-registry.json", "phase3-registry.json"):
            registry = json.loads(
                (ROOT / "engines" / name).read_text(encoding="utf-8"))
            for engine in registry["engines"]:
                for system in engine["systems"]:
                    if f"`{system.lower()}`" not in doc:
                        missing.append(system)
        self.assertEqual([], sorted(set(missing)),
                         "every registered system needs a row in the mapping doc")


class RuntimeMappingTests(unittest.TestCase):
    def test_n64_uses_the_mupen_default_map_not_the_generic_retropad(self):
        # mupen64plus-next ships alt-map=False: A=B(0), B=Y(1), Z=L2(12),
        # L=L(10), R=R(11), R2(13) is the C-buttons modifier and the four C
        # directions come from the right stick. The generic table put N64 B on
        # RetroPad A(8), which this core reads as a C-button.
        n64 = switch_body(LAYOUT.read_text(encoding="utf-8"),
                          "private static int nintendo64(CanonicalControl control)")
        self.assertEqual(0, n64["SOUTH"])
        self.assertEqual(1, n64["EAST"])
        self.assertEqual(10, n64["L1"])
        self.assertEqual(11, n64["R1"])
        self.assertEqual(12, n64["L2"])
        self.assertEqual(13, n64["R2"])
        self.assertEqual(-1, n64["SELECT"])

    def test_the_right_stick_is_analog_on_every_system(self):
        layout = LAYOUT.read_text(encoding="utf-8")
        head = layout.split("public static int idFor(", 1)[1].split(
            "if (isNintendo64(", 1)[0]
        self.assertIn("case RIGHT_X_NEGATIVE: case RIGHT_X_POSITIVE:", head)
        self.assertIn("case RIGHT_Y_NEGATIVE: case RIGHT_Y_POSITIVE:", head)
        self.assertIn("return -1;", head)
        # ...and no per-system table may resurrect it as a button.
        for signature in ("private static int nintendo64(CanonicalControl control)",
                          "private static int gameCube(CanonicalControl control)",
                          "private static int wii(CanonicalControl control)",
                          "private static int retroPad(CanonicalControl control,"):
            table = switch_body(layout, signature)
            for control in ("RIGHT_X_NEGATIVE", "RIGHT_X_POSITIVE",
                            "RIGHT_Y_NEGATIVE", "RIGHT_Y_POSITIVE"):
                self.assertNotIn(control, table)

    def test_left_stick_doubles_as_the_dpad_except_on_analog_consoles(self):
        layout = LAYOUT.read_text(encoding="utf-8")
        head = layout.split("public static int idFor(", 1)[1]
        for control, dpad in (("LEFT_Y_NEGATIVE", 4), ("LEFT_Y_POSITIVE", 5),
                              ("LEFT_X_NEGATIVE", 6), ("LEFT_X_POSITIVE", 7)):
            self.assertIn(f"case {control}: return analogStick ? -1 : {dpad};", head)
        analog = layout.split("public static boolean hasAnalogStick(", 1)[1].split(
            "\n    }", 1)[0]
        self.assertEqual(ANALOG_STICK_SYSTEMS,
                         set(re.findall(r'case "(\w+)":', analog)))

    def test_dpad_only_systems_declare_the_stick_as_their_dpad(self):
        # SystemControlLayouts.base() is what the remap editor shows. It must
        # not claim a console has an analog stick the runtime maps to the
        # D-pad, or the editor and the game disagree.
        base = SYSTEM_LAYOUTS.read_text(encoding="utf-8").split(
            "private static Map<CanonicalControl, String> base()", 1)[1].split(
            "\n    }", 1)[0]
        for control, label in (("LEFT_Y_NEGATIVE", "UP"), ("LEFT_Y_POSITIVE", "DOWN"),
                               ("LEFT_X_NEGATIVE", "LEFT"), ("LEFT_X_POSITIVE", "RIGHT")):
            self.assertIn(f'CanonicalControl.{control}, "{label}"', base)

    def test_wii_asks_for_the_nunchuk_port_device(self):
        layout = LAYOUT.read_text(encoding="utf-8")
        # Dolphin defines RETRO_DEVICE_WIIMOTE_NC as (3 << 8) | JOYPAD.
        self.assertIn("WIIMOTE_NUNCHUK = (3 << 8) | RETRO_DEVICE_JOYPAD", layout)
        self.assertIn("RETRO_DEVICE_JOYPAD = 1", layout)
        self.assertIn("isWii(normalize(systemId)) ? WIIMOTE_NUNCHUK "
                      ": RETRO_DEVICE_JOYPAD", layout)
        # The doc has to say the native host cannot select it yet, otherwise a
        # reader assumes nunchuk-only titles already work.
        doc = DOC.read_text(encoding="utf-8")
        self.assertIn("set_controller_port_device(0, RETRO_DEVICE_JOYPAD)", doc)
        self.assertIn("dolphin_ir_mode", doc)


class LedgerWiringTests(unittest.TestCase):
    def test_the_ledger_ors_every_source_of_one_id(self):
        ledger = LEDGER.read_text(encoding="utf-8")
        self.assertIn(
            "public synchronized void apply(Object source, int retroId, "
            "boolean pressed", ledger)
        # A release from a source that never pressed must not clear an ID that
        # another source is holding: the motion sweep reports every axis on
        # every event, so those releases arrive constantly.
        self.assertIn("if (previous == null) return;", ledger)

    def test_both_sessions_write_the_joypad_only_through_the_ledger(self):
        for path in (LIBRETRO_SESSION, GLES_SESSION):
            source = path.read_text(encoding="utf-8")
            self.assertIn("JoypadPressLedger joypad = new JoypadPressLedger()", source)
            self.assertIn("JoypadPressLedger.Sink joypadSink", source)
            # Exactly one raw setJoypadButton call remains: the sink itself.
            raw = re.findall(r"\.setJoypadButton\(0, \w+, \w+\)", source)
            self.assertEqual(
                1, len(raw),
                f"{path.name} must reach setJoypadButton only through the sink")
            for dispatcher in ("dispatchKeyEvent", "dispatchVirtualControl",
                               "dispatchAxis"):
                self.assertIn(dispatcher, source)
            self.assertEqual(
                3, len(re.findall(r"joypad\.apply\(", source)),
                f"{path.name} routes keys, axes and on-screen controls through "
                "the ledger")

    def test_the_reported_hardware_failure_has_a_regression_test(self):
        test = LEDGER_TEST.read_text(encoding="utf-8")
        # Both halves of the user report, on both named systems.
        self.assertIn('holdSurvivesTheOtherSourceCentring("snes")', test)
        self.assertIn('holdSurvivesTheOtherSourceCentring("genesis")', test)
        self.assertIn('theThorAxisSweepOrderNoLongerDecidesTheWinner("snes")', test)
        self.assertIn('theThorAxisSweepOrderNoLongerDecidesTheWinner("genesis")', test)
        self.assertIn("AXIS_HAT_X", test)
        self.assertIn("AXIS_HAT_Y", test)

    def test_the_qa_harness_still_drives_the_hat_and_the_right_stick(self):
        # The acceptance harness is the only place real hardware input is
        # produced, so the doc's evdev facts have to match what it sends.
        harness = QA_HARNESS.read_text(encoding="utf-8")
        self.assertIn("A = 304", harness)
        self.assertIn("STOP = 314", harness)
        self.assertIn("HAT_X = 16", harness)
        self.assertIn("HAT_Y = 17", harness)
        self.assertIn('AXIS_CODES = {"ABS_Z": 2', harness)


if __name__ == "__main__":
    unittest.main()
