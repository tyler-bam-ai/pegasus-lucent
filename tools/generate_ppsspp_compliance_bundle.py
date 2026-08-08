#!/usr/bin/env python3
"""Fail-closed PPSSPP linked-source, notice, and source-offer evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess


SOURCE_SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".m", ".mm", ".s", ".S"}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fail(message: str) -> None:
    raise SystemExit("PPSSPP compliance gate: " + message)


def sparse_closure(root: pathlib.Path) -> tuple[str, list[dict[str, str]]]:
    entries: list[dict[str, str]] = []
    digest = hashlib.sha256()
    for path in sorted(candidate for candidate in root.rglob("*") if candidate.is_file()):
        relative = path.relative_to(root).as_posix()
        file_sha = sha256(path)
        digest.update(relative.encode("utf-8") + b"\0" + file_sha.encode("ascii") + b"\0")
        entries.append({"path": relative, "sha256": file_sha})
    return digest.hexdigest(), entries


def classify(path: str, components: list[dict]) -> str:
    matches = [row for row in components if any(
        path == prefix.rstrip("/") or path.startswith(prefix)
        for prefix in row["sourcePrefixes"]
    )]
    if not matches:
        return "ppsspp"
    matches.sort(key=lambda row: max(map(len, row["sourcePrefixes"])), reverse=True)
    return matches[0]["id"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--build", required=True, type=pathlib.Path)
    parser.add_argument("--artifact", required=True, type=pathlib.Path)
    parser.add_argument("--audit", required=True, type=pathlib.Path)
    parser.add_argument("--lock", required=True, type=pathlib.Path)
    parser.add_argument("--repository", required=True, type=pathlib.Path)
    parser.add_argument("--ninja", required=True, type=pathlib.Path)
    parser.add_argument("--ar", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    audit = json.loads(args.audit.read_text(encoding="utf-8"))
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    base_lock_path = lock.get("baseLock")
    if base_lock_path:
        base_lock = json.loads((args.repository / base_lock_path).read_text(
            encoding="utf-8"))
        for key in ("core", "dependencies", "recipePatch", "toolchain"):
            lock.setdefault(key, base_lock[key])
    if sha256(args.artifact) != audit["artifactSha256"]:
        fail("artifact does not match the audited production binary")
    if lock["buildProfile"] != "android-arm64-libretro-ffmpeg":
        fail("source lock is not the FFmpeg-enabled production profile")

    target = "ppsspp_libretro_android.so"
    raw_inputs = subprocess.check_output(
        [str(args.ninja), "-C", str(args.build), "-t", "inputs", target],
        text=True,
    ).splitlines()
    compiled: list[dict[str, str]] = []
    counts: dict[str, int] = {}
    source_root = args.source.resolve()
    for value in sorted(set(raw_inputs)):
        path = pathlib.Path(value)
        if path.suffix not in SOURCE_SUFFIXES or not path.is_file():
            continue
        try:
            relative = path.resolve().relative_to(source_root).as_posix()
        except ValueError:
            # CMake's generated version source is covered by the locked patch
            # and build recipe, but is not an upstream source file.
            continue
        component = classify(relative, audit["components"])
        counts[component] = counts.get(component, 0) + 1
        compiled.append({
            "path": relative,
            "sha256": sha256(path),
            "component": component,
        })
    expected = {row["id"]: row["sourceFileCount"] for row in audit["components"]}
    expected["ppsspp"] = audit["ppssppSourceFileCount"]
    actual = {key: value for key, value in counts.items() if value}
    expected = {key: value for key, value in expected.items() if value}
    if actual != expected:
        fail(f"compiled-source counts differ: expected {expected!r}, found {actual!r}")

    ffmpeg_root = args.source / "ffmpeg"
    closure_sha, ffmpeg_files = sparse_closure(ffmpeg_root)
    ffmpeg_lock = lock["ffmpeg"]
    if len(ffmpeg_files) != ffmpeg_lock["sparseFileCount"] or \
            closure_sha != ffmpeg_lock["sparseClosureSha256"]:
        fail("FFmpeg sparse corresponding-source closure changed")

    archives = []
    for row in ffmpeg_lock["androidArm64Archives"]:
        path = ffmpeg_root / row["path"]
        if not path.is_file() or sha256(path) != row["sha256"]:
            fail("FFmpeg archive changed: " + row["path"])
        members = subprocess.check_output(
            [str(args.ar), "t", str(path)], text=True
        ).splitlines()
        archives.append({**row, "memberCount": len(members), "members": members})

    evidence = []
    roots = {
        "source": args.source,
        "ffmpeg": ffmpeg_root,
        "repository": args.repository,
    }
    for component in audit["components"] + [audit["ppsspp"]]:
        for index, record in enumerate(component["evidence"]):
            path = roots[record["root"]] / record["path"]
            if not path.is_file() or sha256(path) != record["sha256"]:
                fail(f"license evidence changed for {component['id']}: {path}")
            evidence.append((component, index, record, path))

    if args.output.exists():
        shutil.rmtree(args.output)
    licenses = args.output / "licenses"
    licenses.mkdir(parents=True)
    for component, index, record, path in evidence:
        suffix = path.suffix or ".txt"
        name = f"{component['id']}-{index + 1}{suffix}"
        shutil.copy2(path, licenses / name)

    manifest = {
        "schemaVersion": 1,
        "artifact": {"path": args.artifact.name, "sha256": sha256(args.artifact)},
        "compiledSourceFileCount": len(compiled),
        "compiledSources": compiled,
        "componentCounts": actual,
        "ffmpeg": {
            "repository": ffmpeg_lock["repository"],
            "commit": ffmpeg_lock["commit"],
            "sparseFileCount": len(ffmpeg_files),
            "sparseClosureSha256": closure_sha,
            "files": ffmpeg_files,
            "androidArm64Archives": archives,
            "embeddedConfiguration": ffmpeg_lock["embeddedConfiguration"],
        },
        "correspondingSource": {
            "core": lock["core"],
            "dependencies": lock["dependencies"],
            "recipePatch": lock["recipePatch"],
            "toolchain": lock["toolchain"],
        },
    }
    (args.output / "ppsspp-linked-sources.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    lines = [
        "PPSSPP Android ARM64 production-profile notices",
        "",
        "Artifact SHA-256: " + manifest["artifact"]["sha256"],
        f"Compiled source files audited: {len(compiled)}",
        f"FFmpeg corresponding-source files audited: {len(ffmpeg_files)}",
        "Combined binary license: " + audit["combinedLicenseConcluded"] + ".",
        "",
        "Linked and compile-input components:",
    ]
    for row in [audit["ppsspp"], *audit["components"]]:
        lines.append(f"- {row['id']}: {row['spdx']}")
    lines.extend([
        "",
        "Exact license texts and file-scoped notices are in licenses/.",
        "Exact compiled files, FFmpeg files/static-archive members, hashes,",
        "toolchain identity, patch, and source coordinates are recorded in",
        "ppsspp-linked-sources.json. Unknown components or changed counts fail.",
    ])
    (args.output / "PPSSPP-CORE-NOTICES.txt").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )
    print(json.dumps({
        "artifactSha256": manifest["artifact"]["sha256"],
        "compiledSources": len(compiled),
        "ffmpegFiles": len(ffmpeg_files),
        "components": len(actual),
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
