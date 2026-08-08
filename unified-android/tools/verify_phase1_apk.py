#!/usr/bin/env python3
"""Verify that a Phase 1 qualification APK is complete and self-auditing."""

from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path


REGISTRY = "assets/engine-registry.json"
OPT_IN = "assets/engine-qualification-opt-in.json"
ARTIFACTS = "assets/phase1-engine-artifacts.json"
SBOM = "assets/phase1-sbom.spdx.json"
NOTICES = "assets/PHASE1-CORE-NOTICES.txt"


def read_json(archive: zipfile.ZipFile, name: str) -> dict:
    return json.loads(archive.read(name).decode("utf-8"))


def verify(path: Path) -> list[str]:
    errors: list[str] = []
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as exc:
        return [f"cannot open APK: {exc}"]
    with archive:
        names = set(archive.namelist())
        required = {REGISTRY, OPT_IN, ARTIFACTS, SBOM, NOTICES}
        missing = sorted(required - names)
        if missing:
            return [f"missing Phase 1 qualification payload: {name}" for name in missing]
        try:
            registry = read_json(archive, REGISTRY)
            opt_in = read_json(archive, OPT_IN)
            artifacts = read_json(archive, ARTIFACTS)
            sbom = read_json(archive, SBOM)
            notices = archive.read(NOTICES).decode("utf-8")
        except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            return [f"invalid Phase 1 qualification payload: {exc}"]

        if opt_in.get("qualificationOnly") is not True:
            errors.append("Phase 1 opt-in is not qualification-only")
        registry_rows = {
            row.get("id"): row for row in registry.get("engines", [])
            if isinstance(row, dict)
        }
        artifact_rows = {
            row.get("engineId"): row for row in artifacts.get("artifacts", [])
            if isinstance(row, dict)
        }
        sbom_rows = {
            row.get("SPDXID", "").removeprefix("SPDXRef-Package-"): row
            for row in sbom.get("packages", []) if isinstance(row, dict)
        }
        enabled_rows = opt_in.get("engines")
        if not isinstance(enabled_rows, list) or not enabled_rows:
            return errors + ["Phase 1 opt-in has no engines"]
        enabled_ids: set[str] = set()
        for enabled in enabled_rows:
            if not isinstance(enabled, dict) or not isinstance(enabled.get("id"), str):
                errors.append("Phase 1 opt-in contains an invalid engine")
                continue
            engine_id = enabled["id"]
            if engine_id in enabled_ids:
                errors.append(f"duplicate Phase 1 opt-in engine: {engine_id}")
                continue
            enabled_ids.add(engine_id)
            row = registry_rows.get(engine_id)
            identity = artifact_rows.get(engine_id)
            package = sbom_rows.get(engine_id)
            if row is None or identity is None or package is None:
                errors.append(f"incomplete Phase 1 identity: {engine_id}")
                continue
            source = row.get("source") or {}
            library_name = enabled.get("libraryName")
            core_path = f"lib/arm64-v8a/{library_name}"
            if core_path not in names:
                errors.append(f"missing Phase 1 core: {core_path}")
                continue
            actual = hashlib.sha256(archive.read(core_path)).hexdigest()
            if row.get("phase") != 1 or row.get("shipped") is not False:
                errors.append(f"Phase 1 core is not an unshipped registry row: {engine_id}")
            if enabled.get("commit") != source.get("commit"):
                errors.append(f"source commit differs in opt-in: {engine_id}")
            if identity.get("sourceCommit") != source.get("commit"):
                errors.append(f"source commit differs in artifact manifest: {engine_id}")
            if identity.get("sourceArchiveSha256") != source.get("archiveSha256"):
                errors.append(f"source archive differs in artifact manifest: {engine_id}")
            if identity.get("fileName") != library_name or identity.get("sha256") != actual:
                errors.append(f"packaged core hash differs from artifact manifest: {engine_id}")
            checksums = package.get("checksums") or []
            if not any(
                item.get("algorithm") == "SHA256"
                and item.get("checksumValue") == source.get("archiveSha256")
                for item in checksums if isinstance(item, dict)
            ):
                errors.append(f"SPDX source checksum differs: {engine_id}")
            if f"Engine id: {engine_id}" not in notices:
                errors.append(f"human-readable notice is absent: {engine_id}")

        if set(artifact_rows) != enabled_ids:
            errors.append("Phase 1 artifact manifest does not exactly match opt-in")
        if set(sbom_rows) != enabled_ids:
            errors.append("Phase 1 SPDX package set does not exactly match opt-in")
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
    print("Phase 1 qualification payload verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
