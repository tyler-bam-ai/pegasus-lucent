import hashlib
import importlib.util
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
VERIFIER_PATH = ROOT / "unified-android" / "tools" / "verify_n64_core_abi.py"
SPEC = importlib.util.spec_from_file_location("verify_n64_core_abi", VERIFIER_PATH)
verifier = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = verifier
SPEC.loader.exec_module(verifier)


def align(value: int, boundary: int = 8) -> int:
    return (value + boundary - 1) & ~(boundary - 1)


def make_elf(
    *,
    needed: tuple[str, ...] = ("libc.so", "liblog.so"),
    undefined: tuple[str, ...] = ("memcpy", "__cxa_atexit@LIBC"),
    load_alignment: int = 0x4000,
) -> bytes:
    shstr = b"\0.shstrtab\0.dynstr\0.dynsym\0.dynamic\0"
    sh_name = {
        ".shstrtab": shstr.index(b".shstrtab"),
        ".dynstr": shstr.index(b".dynstr"),
        ".dynsym": shstr.index(b".dynsym"),
        ".dynamic": shstr.index(b".dynamic"),
    }

    dynstr = bytearray(b"\0")
    offsets: dict[str, int] = {}
    for name in needed + undefined:
        if name not in offsets:
            offsets[name] = len(dynstr)
            dynstr.extend(name.encode("utf-8") + b"\0")

    dynsym = bytearray(24)  # Required null symbol.
    for name in undefined:
        dynsym.extend(struct.pack("<IBBHQQ", offsets[name], 0x10, 0, 0, 0, 0))

    dynamic = bytearray()
    for name in needed:
        dynamic.extend(struct.pack("<qQ", 1, offsets[name]))
    dynamic.extend(struct.pack("<qQ", 0, 0))

    cursor = 0x80
    shstr_offset = cursor
    cursor = align(cursor + len(shstr))
    dynstr_offset = cursor
    cursor = align(cursor + len(dynstr))
    dynsym_offset = cursor
    cursor = align(cursor + len(dynsym))
    dynamic_offset = cursor
    cursor = align(cursor + len(dynamic))
    section_offset = cursor
    section_count = 5
    total_size = section_offset + section_count * 64
    data = bytearray(total_size)

    # ELF64 header, AArch64 shared object, one program header, five sections.
    data[:16] = b"\x7fELF" + bytes((2, 1, 1, 0)) + bytes(8)
    struct.pack_into(
        "<HHIQQQIHHHHHH", data, 16,
        3, 183, 1, 0, 64, section_offset, 0, 64, 56, 1, 64, section_count, 1,
    )
    struct.pack_into(
        "<IIQQQQQQ", data, 64,
        1, 5, 0, 0, 0, total_size, total_size, load_alignment,
    )

    data[shstr_offset:shstr_offset + len(shstr)] = shstr
    data[dynstr_offset:dynstr_offset + len(dynstr)] = dynstr
    data[dynsym_offset:dynsym_offset + len(dynsym)] = dynsym
    data[dynamic_offset:dynamic_offset + len(dynamic)] = dynamic

    def section(index, name, section_type, offset, size, link=0, entry_size=0, alignment=1):
        struct.pack_into(
            "<IIQQQQIIQQ", data, section_offset + index * 64,
            sh_name[name], section_type, 0, 0, offset, size, link, 0, alignment, entry_size,
        )

    section(1, ".shstrtab", 3, shstr_offset, len(shstr))
    section(2, ".dynstr", 3, dynstr_offset, len(dynstr))
    section(3, ".dynsym", 11, dynsym_offset, len(dynsym), link=2, entry_size=24, alignment=8)
    section(4, ".dynamic", 6, dynamic_offset, len(dynamic), link=2, entry_size=16, alignment=8)
    return bytes(data)


def make_apk(path: Path, core: bytes) -> tuple[str, str]:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(verifier.CORE_MEMBER, core)
    return (
        hashlib.sha256(path.read_bytes()).hexdigest(),
        hashlib.sha256(core).hexdigest(),
    )


class N64CoreAbiTest(unittest.TestCase):
    def verify(self, core: bytes):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "candidate.apk"
            apk_sha, core_sha = make_apk(apk, core)
            return verifier.verify_apk(
                apk,
                expected_apk_sha256=apk_sha,
                expected_core_sha256=core_sha,
            )

    def test_static_cpp_core_passes(self):
        result = self.verify(make_elf())
        self.assertTrue(result.passed, result.errors)
        self.assertEqual((0x4000,), result.load_alignments)

    def test_libcxx_shared_dependency_fails(self):
        result = self.verify(make_elf(needed=("libc.so", "libc++_shared.so")))
        self.assertFalse(result.passed)
        self.assertTrue(any("forbidden DT_NEEDED" in error for error in result.errors))

    def test_unresolved_ndk_cpp_runtime_symbol_fails(self):
        symbol = "_ZTTNSt6__ndk118basic_stringstreamIcNS_11char_traitsIcEENS_9allocatorIcEEEE"
        result = self.verify(make_elf(undefined=(symbol,)))
        self.assertFalse(result.passed)
        self.assertIn(symbol, result.unresolved_cpp_runtime_symbols)

    def test_unresolved_cxxabi_symbol_fails(self):
        result = self.verify(make_elf(undefined=("__gxx_personality_v0",)))
        self.assertFalse(result.passed)
        self.assertIn("__gxx_personality_v0", result.unresolved_cpp_runtime_symbols)

    def test_bionic_cxa_symbols_are_allowed(self):
        result = self.verify(make_elf(undefined=("__cxa_atexit@LIBC", "__cxa_finalize@LIBC")))
        self.assertTrue(result.passed, result.errors)

    def test_sub_16k_load_alignment_fails(self):
        result = self.verify(make_elf(load_alignment=0x1000))
        self.assertFalse(result.passed)
        self.assertTrue(any("below 0x4000" in error for error in result.errors))

    def test_exact_apk_and_core_hashes_are_enforced(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "candidate.apk"
            apk_sha, core_sha = make_apk(apk, make_elf())
            result = verifier.verify_apk(
                apk,
                expected_apk_sha256="0" * 64,
                expected_core_sha256="f" * 64,
            )
            self.assertFalse(result.passed)
            self.assertEqual(apk_sha, result.apk_sha256)
            self.assertEqual(core_sha, result.core_sha256)
            self.assertTrue(any("APK SHA-256 mismatch" in error for error in result.errors))
            self.assertTrue(any("N64 core SHA-256 mismatch" in error for error in result.errors))


if __name__ == "__main__":
    unittest.main()
