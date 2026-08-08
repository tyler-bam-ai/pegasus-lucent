#!/usr/bin/env python3
"""Verify Phase 1 reproducibility claims against exact recipe and artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def recipe_sha256(path: Path, engine_id: str) -> str:
    text = path.read_text(encoding="utf-8")
    try:
        before_case = text.split('case "$ENGINE" in', 1)[0]
    except IndexError as exc:
        raise ValueError("build recipe has no engine case") from exc
    match = re.search(
        r"(?ms)^    " + re.escape(engine_id) + r"\)\n.*?^        ;;\n",
        text,
    )
    if match is None:
        raise ValueError(f"build recipe has no exact branch for {engine_id}")
    branch = match.group(0)

    # Lock the global toolchain/configuration stanza plus only the helper
    # functions that the selected branch can call. This keeps the proof strict
    # for a core while avoiding invalidation when an unrelated Phase 2 helper
    # is added to the same multi-engine script.
    first_function = re.search(r"(?m)^[a-zA-Z_][a-zA-Z0-9_]*\(\) \{\n", before_case)
    globals_text = before_case[:first_function.start()] if first_function else before_case
    helpers: dict[str, str] = {}
    for helper in re.finditer(
        r"(?ms)^([a-zA-Z_][a-zA-Z0-9_]*)\(\) \{\n.*?^\}\n",
        before_case,
    ):
        helpers[helper.group(1)] = helper.group(0)
    selected: set[str] = set()
    pending = [name for name in helpers if re.search(r"\b" + re.escape(name) + r"\b", branch)]
    while pending:
        name = pending.pop()
        if name in selected:
            continue
        selected.add(name)
        body = helpers[name]
        pending.extend(
            dependency for dependency in helpers
            if dependency not in selected
            and re.search(r"\b" + re.escape(dependency) + r"\b", body)
        )
    material = globals_text + "".join(helpers[name] for name in sorted(selected)) + branch
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def verify(registry_path: Path, lock_path: Path, artifact_dir: Path, root: Path) -> list[str]:
    errors: list[str] = []
    try:
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return [f"cannot read reproducibility inputs: {exc}"]
    registry_rows = {
        row.get("id"): row for row in registry.get("engines", [])
        if isinstance(row, dict)
    }
    lock_rows = {
        row.get("id"): row for row in lock.get("engines", [])
        if isinstance(row, dict)
    }
    expected = {
        engine_id for engine_id, row in registry_rows.items()
        if (row.get("build") or {}).get("reproducible") is True
    }
    if set(lock_rows) != expected:
        errors.append("reproducibility lock does not exactly match registry claims")
    recipe = lock.get("recipe") or {}
    recipe_path = root / str(recipe.get("path", ""))
    if not recipe_path.is_file():
        errors.append("reproducibility recipe is absent")
    for engine_id in sorted(expected):
        row = registry_rows[engine_id]
        locked = lock_rows.get(engine_id)
        if locked is None:
            continue
        source = row.get("source") or {}
        if locked.get("sourceCommit") != source.get("commit"):
            errors.append(f"source commit differs from reproducibility lock: {engine_id}")
        if locked.get("sourceArchiveSha256") != source.get("archiveSha256"):
            errors.append(f"source archive differs from reproducibility lock: {engine_id}")
        if recipe_path.is_file():
            try:
                current_recipe_sha = recipe_sha256(recipe_path, engine_id)
            except ValueError as exc:
                errors.append(str(exc))
            else:
                if current_recipe_sha != locked.get("recipeSha256"):
                    errors.append(f"engine recipe differs from reproducibility lock: {engine_id}")
        patches = locked.get("patches")
        if not isinstance(patches, dict):
            errors.append(f"reproducibility patch set is absent: {engine_id}")
        else:
            for relative, expected_sha in sorted(patches.items()):
                patch = root / relative
                if not patch.is_file() or sha256(patch) != expected_sha:
                    errors.append(f"patch differs from reproducibility lock: {engine_id}: {relative}")
        if locked.get("independentBuilds", 0) < 2 or locked.get("byteIdentical") is not True:
            errors.append(f"reproducibility proof is insufficient: {engine_id}")
        artifact = artifact_dir / f"{engine_id}_libretro.so"
        if not artifact.is_file():
            errors.append(f"reproducible artifact is absent: {engine_id}")
        elif sha256(artifact) != locked.get("artifactSha256"):
            errors.append(f"artifact differs from reproducibility lock: {engine_id}")
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", type=Path, default=root / "engines/registry.json")
    parser.add_argument("--lock", type=Path, default=root / "engines/reproducibility-lock.json")
    parser.add_argument("--artifact-dir", type=Path, default=root / "engines/build/arm64-v8a")
    args = parser.parse_args()
    errors = verify(args.registry, args.lock, args.artifact_dir, root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("Phase 1 reproducibility lock verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
