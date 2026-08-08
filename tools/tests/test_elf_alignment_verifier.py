import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "unified-android" / "tools" / "verify_elf_alignment.py"
SPEC = importlib.util.spec_from_file_location("verify_elf_alignment", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def elf_with_alignment(alignment: int) -> bytes:
    data = bytearray(64 + 56)
    data[:4] = b"\x7fELF"
    data[4] = 2
    data[5] = 1
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<H", data, 54, 56)
    struct.pack_into("<H", data, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<Q", data, 64 + 48, alignment)
    return bytes(data)


class ElfAlignmentVerifierTest(unittest.TestCase):
    def make_apk(self, alignment: int) -> Path:
        handle = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        handle.close()
        apk = Path(handle.name)
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr(
                "lib/arm64-v8a/liblucent_test.so",
                elf_with_alignment(alignment),
            )
        self.addCleanup(apk.unlink)
        return apk

    def test_accepts_16k_alignment(self):
        self.assertEqual([], MODULE.verify(self.make_apk(0x4000)))

    def test_rejects_4k_alignment(self):
        errors = MODULE.verify(self.make_apk(0x1000))
        self.assertEqual(1, len(errors))
        self.assertIn("below 0x4000", errors[0])


if __name__ == "__main__":
    unittest.main()
