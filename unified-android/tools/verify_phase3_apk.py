#!/usr/bin/env python3
"""Verify the fail-closed Phase 3 native-adapter qualification payload.

Phase 3 packages an already-built IN-PROCESS engine, not a libretro core, so the
gates differ from Phase 1/2 in one important way: there is no reproducibility
proof to check against. This verifier therefore proves only what is actually
true of the APK -- the adapter is present, its bytes hash to the signed
manifest, every asset agrees on the same source commit, the registry row is
still research/unshipped with every gate closed, the source lock does not claim
reproducibility, and no keys or firmware were bundled.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path


REGISTRY = "assets/phase3-engine-registry.json"
OPT_IN = "assets/phase3-qualification-opt-in.json"
ARTIFACTS = "assets/phase3-engine-artifacts.json"
SOURCE_LOCK = "assets/phase3-eden-source-lock.json"
ADAPTER_SOURCE = "assets/phase3-eden-lucent-adapter.cpp"
EXPECTED = {"eden"}
EXPECTED_LIBRARY_ROUTES = {"eden": ["switch"]}
ADAPTER_LIBRARY = "lib/arm64-v8a/liblucent_native_adapter_eden.so"
# Never bundle console keys or system firmware. These are the file names the
# runtime resolver reads from the user's own storage.
FORBIDDEN_RUNTIME_INPUT_NAMES = {"prod.keys", "title.keys", "console.keys"}
FORBIDDEN_RUNTIME_INPUT_SUFFIXES = (".keys", ".nca", ".xci", ".nsp")


def _read_json(archive: zipfile.ZipFile, name: str) -> dict:
    return json.loads(archive.read(name).decode("utf-8"))


def _by_id(rows: list, key: str = "id") -> dict:
    result: dict = {}
    for row in rows:
        value = row.get(key)
        if not isinstance(value, str) or value in result:
            raise ValueError(f"duplicate or invalid {key}")
        result[value] = row
    return result


def verify(path: Path) -> list:
    errors: list = []
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as exc:
        return [f"cannot open APK: {exc}"]
    with archive:
        names = set(archive.namelist())
        required = {REGISTRY, OPT_IN, ARTIFACTS, SOURCE_LOCK, ADAPTER_SOURCE,
                    ADAPTER_LIBRARY}
        for name in sorted(required - names):
            errors.append(f"missing Phase 3 qualification payload: {name}")
        if errors:
            return errors
        try:
            registry = _read_json(archive, REGISTRY)
            opt_in = _read_json(archive, OPT_IN)
            artifacts = _read_json(archive, ARTIFACTS)
            source_lock = _read_json(archive, SOURCE_LOCK)
            enabled = _by_id(opt_in.get("engines", []))
            identities = _by_id(artifacts.get("artifacts", []), "engineId")
            rows = _by_id(registry.get("engines", []))
        except (KeyError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
            return [f"invalid Phase 3 qualification JSON: {exc}"]

        if set(enabled) != EXPECTED or set(identities) != EXPECTED:
            errors.append("Phase 3 opt-in/artifact set is not the exact qualified set")
        if (opt_in.get("schemaVersion") != 1 or
                artifacts.get("schemaVersion") != 1 or
                registry.get("schemaVersion") != 1):
            errors.append("Phase 3 asset schema versions are not the pinned version 1")
        if opt_in.get("qualificationOnly") is not True or \
                opt_in.get("autoSelect") is not False:
            errors.append("Phase 3 qualification payload is not fail-closed")
        actual_routes = {
            engine_id: row.get("libraryRouteSystems", [])
            for engine_id, row in enabled.items()
        }
        if actual_routes != EXPECTED_LIBRARY_ROUTES:
            errors.append("Phase 3 normal-library routes are not the exact qualified subset")

        for engine_id in sorted(EXPECTED):
            row = rows.get(engine_id)
            enable = enabled.get(engine_id)
            identity = identities.get(engine_id)
            if row is None or enable is None or identity is None:
                continue
            commit = (row.get("source") or {}).get("commit")
            library = f"lib/arm64-v8a/liblucent_native_adapter_{engine_id.replace('-', '_')}.so"
            if library not in names:
                errors.append(f"missing Phase 3 adapter: {library}")
                continue
            actual = hashlib.sha256(archive.read(library)).hexdigest()
            if enable.get("runtime") != "native-adapter":
                errors.append(f"{engine_id} is not opted in as an in-process native adapter")
            if row.get("route") != "native-adapter":
                errors.append(f"{engine_id} registry row does not declare the native-adapter route")
            if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
                errors.append(f"{engine_id} registry source commit is not a pinned revision")
            if enable.get("commit") != commit or identity.get("sourceCommit") != commit:
                errors.append(f"{engine_id} source commit differs across qualification assets")
            if enable.get("libraryName") != Path(library).name or \
                    identity.get("fileName") != Path(library).name:
                errors.append(f"{engine_id} packaged adapter name differs across assets")
            if identity.get("sha256") != actual:
                errors.append(f"{engine_id} packaged adapter hash does not match the manifest")
            # No reproducibility claim exists for Phase 3, so the registry must
            # still be research/unshipped with every gate closed.
            if row.get("shipped") is not False or row.get("status") != "research":
                errors.append(f"{engine_id} registry unexpectedly claims a shipped engine")
            if any(value is not False for value in (row.get("gates") or {}).values()):
                errors.append(f"{engine_id} registry unexpectedly claims a qualification gate")

        lock_core = source_lock.get("core") or {}
        eden_commit = (rows.get("eden", {}).get("source") or {}).get("commit")
        if source_lock.get("route") != "native-adapter" or \
                source_lock.get("engineId") != "eden":
            errors.append("Eden source lock does not describe the eden native adapter")
        if lock_core.get("commit") != eden_commit:
            errors.append("Eden source lock commit differs from the packaged registry")
        if source_lock.get("reproducible") is not False:
            errors.append("Eden source lock claims reproducibility that was never proven")
        lock_artifact = source_lock.get("artifact") or {}
        packaged_hash = hashlib.sha256(archive.read(ADAPTER_LIBRARY)).hexdigest()
        if lock_artifact.get("sha256") != packaged_hash:
            errors.append("Eden source lock artifact hash differs from the packaged adapter")
        if lock_artifact.get("fileName") != Path(ADAPTER_LIBRARY).name:
            errors.append("Eden source lock artifact name differs from the packaged adapter")
        lock_patches = source_lock.get("patches", [])
        if len(lock_patches) != 1 or \
                lock_patches[0].get("path") != "engines/patches/eden-lucent-adapter.cpp":
            errors.append("Eden source lock does not identify its adapter translation unit")
        elif lock_patches[0].get("sha256") != \
                hashlib.sha256(archive.read(ADAPTER_SOURCE)).hexdigest():
            errors.append("Packaged Eden adapter source differs from the source lock")
        if (source_lock.get("runtimeInputs") or {}).get(
                "keysAndFirmwareBundled") is not False:
            errors.append("Eden source lock does not disclaim bundled keys/firmware")

        for name in names:
            base = Path(name).name
            if base in FORBIDDEN_RUNTIME_INPUT_NAMES or \
                    base.lower().endswith(FORBIDDEN_RUNTIME_INPUT_SUFFIXES):
                errors.append(f"Switch keys/firmware are unexpectedly bundled: {name}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    errors = verify(args.apk)
    if errors:
        for error in errors:
            print(error)
        return 1
    print("Phase 3 native-adapter qualification payload verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
