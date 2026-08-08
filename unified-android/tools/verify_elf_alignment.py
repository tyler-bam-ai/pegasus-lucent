#!/usr/bin/env python3
"""Verify that every packaged 64-bit ELF supports 16 KiB Android pages."""

from __future__ import annotations

import argparse
from pathlib import Path
import struct
import sys
import zipfile


ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1
MIN_ALIGNMENT = 0x4000


def load_alignments(data: bytes) -> list[int]:
    if len(data) < 64 or data[:4] != ELF_MAGIC:
        raise ValueError("not an ELF file")
    elf_class = data[4]
    byte_order = data[5]
    if elf_class != 2:
        raise ValueError("expected a 64-bit ELF")
    if byte_order == 1:
        endian = "<"
    elif byte_order == 2:
        endian = ">"
    else:
        raise ValueError("invalid ELF byte order")
    program_offset = struct.unpack_from(endian + "Q", data, 32)[0]
    entry_size = struct.unpack_from(endian + "H", data, 54)[0]
    entry_count = struct.unpack_from(endian + "H", data, 56)[0]
    if entry_size < 56:
        raise ValueError("invalid ELF program-header size")
    alignments: list[int] = []
    for index in range(entry_count):
        offset = program_offset + index * entry_size
        if offset + 56 > len(data):
            raise ValueError("truncated ELF program-header table")
        segment_type = struct.unpack_from(endian + "I", data, offset)[0]
        if segment_type == PT_LOAD:
            alignments.append(struct.unpack_from(endian + "Q", data, offset + 48)[0])
    if not alignments:
        raise ValueError("ELF has no PT_LOAD segments")
    return alignments


def verify(apk: Path) -> list[str]:
    errors: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        libraries = sorted(
            name for name in archive.namelist()
            if name.startswith("lib/arm64-v8a/") and name.endswith(".so")
        )
        if not libraries:
            return ["APK contains no ARM64 libraries"]
        for name in libraries:
            try:
                alignments = load_alignments(archive.read(name))
            except ValueError as exc:
                errors.append(f"{name}: {exc}")
                continue
            low = min(alignments)
            if low < MIN_ALIGNMENT:
                errors.append(
                    f"{name}: PT_LOAD alignment 0x{low:x} is below 0x{MIN_ALIGNMENT:x}"
                )
    return errors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    errors = verify(args.apk)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
    print("Lucent 16 KiB ELF alignment: PASS")


if __name__ == "__main__":
    main()
