#!/usr/bin/env python3
"""Deploy exact screenshot fallbacks while preserving provenance."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
import subprocess


ADB = Path("/Users/tyleryoung/.codex/tools/android-platform-tools/adb")
SERIAL = "427c87b2"
DEVICE_ROOT = "/storage/6432-6661/PegasusMedia/game-wallpapers-screenshot-fallback"
DEVICE_METADATA_ROOTS = (
    "/storage/emulated/0/pegasus-frontend",
    "/storage/emulated/0/pegasus-frontend/metadata-systems",
    "/storage/emulated/0/Android/data/org.pegasus_frontend.android/files/pegasus-frontend/metadata",
)


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def update_metadata(path: Path, rows: dict[str, dict[str, str]]) -> bool:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    output: list[str] = []
    changed = False
    index = 0
    while index < len(lines):
        if not lines[index].startswith("game: "):
            output.append(lines[index])
            index += 1
            continue
        end = index + 1
        while end < len(lines) and not lines[end].startswith("game: "):
            end += 1
        block = lines[index:end]
        file_line = next((line for line in block if line.startswith("file: ")), "")
        rom_path = file_line.removeprefix("file: ").strip()
        row = rows.get(rom_path)
        # Never replace a real wallpaper or a previously audited source.
        if row and not any(line.startswith("assets.background: ") for line in block):
            filtered = [
                line for line in block
                if not line.startswith("x-background-source:") and
                   not line.startswith("x-background-source-url:")
            ]
            asset_positions = [
                position for position, line in enumerate(filtered)
                if line.startswith("assets.")
            ]
            insert_at = asset_positions[-1] + 1 if asset_positions else len(filtered)
            additions = [
                "assets.background: " + row["device_output"],
                "x-background-source: exact-gameplay-screenshot",
                "x-background-source-url: " + row.get("source_url", ""),
            ]
            filtered[insert_at:insert_at] = additions
            block = filtered
            changed = True
        output.extend(block)
        index = end
    if changed:
        path.write_text("\n".join(output).rstrip() + "\n", encoding="utf-8")
    return changed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    args = parser.parse_args()

    rows = {
        row["rom_path"]: row for row in read_tsv(args.report)
        if row.get("status") == "built" and row.get("output") and
           Path(row["output"]).is_file()
    }
    print(f"deployable screenshot fallbacks: {len(rows)}", flush=True)
    changed = [
        path for path in sorted(args.metadata_dir.glob("*.metadata.pegasus.txt"))
        if update_metadata(path, rows)
    ]
    subprocess.run(
        [str(ADB), "-s", SERIAL, "push", str(args.stage) + "/.", DEVICE_ROOT],
        check=True,
    )
    for root in DEVICE_METADATA_ROOTS:
        for metadata in changed:
            subprocess.run(
                [str(ADB), "-s", SERIAL, "push", str(metadata), f"{root}/{metadata.name}"],
                check=True,
                stdout=subprocess.DEVNULL,
            )
    print(f"metadata files updated: {len(changed)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
