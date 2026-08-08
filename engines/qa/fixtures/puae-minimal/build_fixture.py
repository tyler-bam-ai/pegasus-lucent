#!/usr/bin/env python3
"""Build the CC0 deterministic Amiga boot-block qualification fixture."""

from __future__ import annotations

import argparse
from pathlib import Path


IMAGE_SIZE = 901120
BOOT_BLOCK_SIZE = 1024
EXPECTED_SHA256 = "e5692a1ef7a769936b283b465f1dd965979a2cae1e16e4f8af32e41c310724b1"


def _end_around_sum(block: bytes) -> int:
    total = 0
    for offset in range(0, len(block), 4):
        value = int.from_bytes(block[offset:offset + 4], "big")
        previous = total
        total = (total + value) & 0xFFFFFFFF
        if total < previous:
            total = (total + 1) & 0xFFFFFFFF
    return total


def build() -> bytes:
    boot = bytearray(BOOT_BLOCK_SIZE)
    boot[0:4] = b"DOS\0"
    boot[8:12] = (880).to_bytes(4, "big")
    # move.w #$0f00,$00dff180 ; bra.s *
    boot[12:22] = bytes.fromhex("33fc0f0000dff18060fe")
    boot[4:8] = ((~_end_around_sum(boot)) & 0xFFFFFFFF).to_bytes(4, "big")
    if _end_around_sum(boot) != 0xFFFFFFFF:
        raise RuntimeError("invalid Amiga boot-block checksum")
    image = bytearray(IMAGE_SIZE)
    image[:BOOT_BLOCK_SIZE] = boot
    return bytes(image)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.write_bytes(build())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
