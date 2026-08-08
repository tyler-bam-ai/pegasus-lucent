import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PATCH_PATH = (ROOT / "unified-android" / "tools" /
              "patch_pegasus_in_process_launch.py")
SPEC = importlib.util.spec_from_file_location("launch_patch", PATCH_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PegasusInProcessLaunchPatchTest(unittest.TestCase):
    def fixture(self) -> tuple[tempfile.TemporaryDirectory, Path]:
        temporary = tempfile.TemporaryDirectory()
        path = Path(temporary.name) / "frontend.so"
        payload = bytearray(MODULE.PATCH_OFFSET + len(MODULE.EXPECTED) + 16)
        payload[:6] = b"\x7fELF\x02\x01"
        payload[
            MODULE.PATCH_OFFSET:MODULE.PATCH_OFFSET + len(MODULE.EXPECTED)
        ] = MODULE.EXPECTED
        path.write_bytes(payload)
        return temporary, path

    def test_exact_pinned_subscriber_is_replaced(self):
        temporary, path = self.fixture()
        self.addCleanup(temporary.cleanup)
        before, after = MODULE.patch(path)
        self.assertNotEqual(before, after)
        data = path.read_bytes()
        self.assertEqual(
            data[MODULE.PATCH_OFFSET:
                 MODULE.PATCH_OFFSET + len(MODULE.REPLACEMENT)],
            MODULE.REPLACEMENT,
        )

    def test_unknown_or_repeated_input_fails_closed(self):
        temporary, path = self.fixture()
        self.addCleanup(temporary.cleanup)
        payload = bytearray(path.read_bytes())
        payload[MODULE.PATCH_OFFSET] ^= 1
        path.write_bytes(payload)
        with self.assertRaisesRegex(ValueError, "differs at locked offset"):
            MODULE.patch(path)

        temporary2, path2 = self.fixture()
        self.addCleanup(temporary2.cleanup)
        MODULE.patch(path2)
        with self.assertRaisesRegex(ValueError, "already contains"):
            MODULE.patch(path2)

    def test_patch_does_not_modify_file_size_or_non_target_bytes(self):
        temporary, path = self.fixture()
        self.addCleanup(temporary.cleanup)
        before = path.read_bytes()
        MODULE.patch(path)
        after = path.read_bytes()
        self.assertEqual(len(before), len(after))
        self.assertEqual(before[:MODULE.PATCH_OFFSET], after[:MODULE.PATCH_OFFSET])
        end = MODULE.PATCH_OFFSET + len(MODULE.EXPECTED)
        self.assertEqual(before[end:], after[end:])


if __name__ == "__main__":
    unittest.main()
