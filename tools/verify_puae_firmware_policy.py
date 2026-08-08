#!/usr/bin/env python3
"""Bind PUAE's embedded AROS snapshot and exclude proprietary CD32 firmware."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import tarfile
from pathlib import Path


AROS_PATH = "sources/src/aros.rom.c"
CD32_MD5 = {
    "f2f241bf094168cfb9e7805dc2856433",
    "5f8924d013dd57a89cf349f4cdedc6b1",
    "bb72565701b1b6faece07d68ea5da639",
}


def _digest(payload: bytes, algorithm: str) -> str:
    return hashlib.new(algorithm, payload).hexdigest()


def _extract_aros(source: str) -> bytes:
    match = re.search(r"unsigned char arosrom\[\]\s*=\s*\{(.*?)\};", source,
                      flags=re.DOTALL)
    if match is None:
        raise ValueError("AROS byte array is absent")
    return bytes(int(value, 16) for value in re.findall(r"0x([0-9a-fA-F]{2})",
                                                        match.group(1)))


def _source_members(path: Path) -> dict[str, bytes]:
    if path.is_dir():
        return {str(item.relative_to(path)): item.read_bytes()
                for item in path.rglob("*") if item.is_file()}
    with tarfile.open(path, "r:*") as archive:
        result = {}
        for member in archive.getmembers():
            if not member.isfile():
                continue
            stream = archive.extractfile(member)
            if stream is not None:
                relative = member.name.split("/", 1)[-1]
                result[relative] = stream.read()
        return result


def verify(core: Path, source: Path, policy_path: Path) -> list[str]:
    errors: list[str] = []
    try:
        policy = json.loads(policy_path.read_text(encoding="utf-8"))
        members = _source_members(source)
        source_bytes = members[AROS_PATH]
        compressed = _extract_aros(source_bytes.decode("ascii"))
        decompressed = gzip.decompress(compressed)
        core_bytes = core.read_bytes()
    except (OSError, KeyError, ValueError, UnicodeDecodeError, json.JSONDecodeError,
            gzip.BadGzipFile) as exc:
        return [f"cannot inspect PUAE firmware policy: {exc}"]

    for label, payload, expected in (
            ("compressed AROS", compressed, policy["amiga"]["compressedPayload"]),
            ("decompressed AROS", decompressed,
             policy["amiga"]["decompressedPayload"])):
        if len(payload) != expected.get("size"):
            errors.append(f"{label} size differs from policy")
        for algorithm in ("md5", "sha256"):
            if _digest(payload, algorithm) != expected.get(algorithm):
                errors.append(f"{label} {algorithm} differs from policy")
    if core_bytes.count(compressed) != 1:
        errors.append("proof core does not contain exactly one pinned AROS payload")

    policy_md5 = {
        item.get("md5")
        for alternative in policy["amigacd32"].get("alternatives", [])
        for item in alternative
    }
    if policy_md5 != CD32_MD5:
        errors.append("CD32 compatibility identities differ from the pinned policy")
    for name, payload in members.items():
        if _digest(payload, "md5") in CD32_MD5:
            errors.append(f"proprietary CD32 firmware is present in source: {name}")
    if policy.get("distribution", {}).get("proprietaryFirmwareBundled") is not False:
        errors.append("PUAE policy does not forbid bundled proprietary firmware")
    if any(policy.get("releaseGates", {}).values()):
        errors.append("PUAE release gates must remain open without device/provenance evidence")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    args = parser.parse_args()
    errors = verify(args.core, args.source, args.policy)
    if errors:
        for error in errors:
            print(error)
        return 1
    print("PUAE AROS identity bound; proprietary CD32 firmware excluded")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
