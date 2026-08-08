#!/usr/bin/env python3
"""Build the pinned, redistributable Neo Geo qualification fixture.

The fixture uses dciabrin/ngdevkit's open nullbios and hello-world example.
It never downloads or reads SNK firmware or commercial game data. The
cross-toolchain is intentionally external; every accepted output is checked
against a known SHA-256 before QA can stage it.
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "engines" / "build"
SOURCES = BUILD / "sources"
WORK = BUILD / "work" / "ngdevkit-open-fixture"
OUTPUT = BUILD / "qa-open-neogeo"

NGDEVKIT_COMMIT = "b36a345dd65097040d48933408586e0a9d7c764b"
NGDEVKIT_TAG = "nightly-202607191609"
NGDEVKIT_ARCHIVE_SHA256 = "1099364b1661ad390cac5d8a7af8e51c09dcf690c2f8fd99afee4917a9ae6ac0"
EXAMPLES_COMMIT = "60f1bd113471ade1a1850e0dca945cffeef38231"
EXAMPLES_ARCHIVE_SHA256 = "4855effd60ecf57124c03eb76d12acb14f891b9544821fe2af7021bd73c60b7f"
AES_BIOS_SHA256 = "c9412cfd819b18b57c6c02d360b652ebe9a0fad80225ac3f30c376205c54516f"
MVS_BIOS_SHA256 = "567ce045cfb2d319e7b10aaefe8b8aebefcd24ba10ba7f8e16f4c951dc34d4cf"
HELLO_ZIP_SHA256 = "9c39bb9391f10974361c0383a4811a94fa2fe8a3b619294f4bfa019b7d82cca4"
HELLO_XML_SHA256 = "98eb7ec517e56e5c3998fd40b05310666ae8e4c65c00f0972c017aad50f6b4f6"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked_archive(name: str, url: str, expected: str) -> Path:
    SOURCES.mkdir(parents=True, exist_ok=True)
    destination = SOURCES / name
    if destination.is_file() and sha256(destination) == expected:
        return destination
    partial = destination.with_suffix(destination.suffix + ".partial")
    partial.unlink(missing_ok=True)
    urllib.request.urlretrieve(url, partial)
    actual = sha256(partial)
    if actual != expected:
        partial.unlink(missing_ok=True)
        raise SystemExit(f"source checksum mismatch for {name}: {actual}")
    partial.replace(destination)
    return destination


def require_tool(name: str) -> str:
    path = shutil.which(name)
    if path is None:
        raise SystemExit(
            f"missing {name}; install the official dciabrin/ngdevkit toolchain "
            "and the build prerequisites documented in engines/qa/README.md"
        )
    return path


def run(arguments: list[str], cwd: Path, env: dict[str, str]) -> None:
    subprocess.run(arguments, cwd=cwd, env=env, check=True)


def canonical_zip(source: Path, destination: Path) -> None:
    """Remove build-time timestamps while preserving only fixture members."""
    with zipfile.ZipFile(source, "r") as incoming, zipfile.ZipFile(
        destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
    ) as outgoing:
        outgoing.comment = (
            b"Open ngdevkit qualification data; no proprietary firmware or game data"
        )
        for name in sorted(incoming.namelist()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            outgoing.writestr(info, incoming.read(name), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def main() -> None:
    # Fetch both primary source archives even though ngdevkit's installed tools
    # are used below. This makes the exact source and license material locally
    # auditable alongside the generated fixture.
    ngdevkit_archive = checked_archive(
        f"ngdevkit-{NGDEVKIT_COMMIT}.tar.gz",
        f"https://github.com/dciabrin/ngdevkit/archive/{NGDEVKIT_TAG}.tar.gz",
        NGDEVKIT_ARCHIVE_SHA256,
    )
    examples_archive = checked_archive(
        f"ngdevkit-examples-{EXAMPLES_COMMIT}.tar.gz",
        f"https://github.com/dciabrin/ngdevkit-examples/archive/{EXAMPLES_COMMIT}.tar.gz",
        EXAMPLES_ARCHIVE_SHA256,
    )

    for tool in (
        "autoreconf", "pkg-config", "gmake", "magick", "sox", "rsync", "zip",
        "m68k-neogeo-elf-gcc", "m68k-neogeo-elf-objcopy",
        "z80-neogeo-ihx-sdasz80", "z80-neogeo-ihx-sdobjcopy",
    ):
        require_tool(tool)

    prefix = subprocess.run(
        ["pkg-config", "--variable=prefix", "ngdevkit"],
        check=True, text=True, capture_output=True,
    ).stdout.strip()
    share = Path(prefix) / "share" / "ngdevkit"
    aes = share / "aes.zip"
    mvs = share / "neogeo.zip"
    for path, expected in ((aes, AES_BIOS_SHA256), (mvs, MVS_BIOS_SHA256)):
        if not path.is_file() or sha256(path) != expected:
            raise SystemExit(
                f"{path} is not the pinned open nullbios artifact from ngdevkit "
                f"{NGDEVKIT_TAG}; proprietary substitutes are forbidden"
            )

    shutil.rmtree(WORK, ignore_errors=True)
    WORK.mkdir(parents=True)
    with tarfile.open(examples_archive, "r:gz") as archive:
        members = archive.getmembers()
        prefix_name = members[0].name.split("/", 1)[0]
        for member in members:
            if member.name == prefix_name:
                continue
            member.name = member.name.split("/", 1)[1]
            archive.extract(member, WORK)

    env = os.environ.copy()
    python = os.environ.get("NGDEVKIT_PYTHON")
    if python is None:
        candidate = Path(prefix) / "libexec" / "bin" / "python3"
        python = str(candidate if candidate.is_file() else Path(sys.executable))
    run(["autoreconf", "-iv"], WORK, env)
    run(["./configure", f"--with-python={python}"], WORK, env)
    run(["gmake", ".prebuild"], WORK, env)
    example = WORK / "01-helloworld"
    run(["gmake", "build/.generated"], example, env)
    run([
        "gmake", "-j4", "build/rom/01_helloworld.zip",
        "build/rom/neogeo.xml", "bios",
    ], example, env)

    OUTPUT.mkdir(parents=True, exist_ok=True)
    generated = example / "build" / "rom"
    canonical_zip(generated / "01_helloworld.zip", OUTPUT / "01_helloworld.zip")
    shutil.copyfile(generated / "neogeo.xml", OUTPUT / "neogeo.xml")
    shutil.copyfile(aes, OUTPUT / "aes.zip")
    shutil.copyfile(mvs, OUTPUT / "neogeo.zip")
    shutil.copyfile(ngdevkit_archive, OUTPUT / "ngdevkit-source.tar.gz")

    expected_outputs = {
        "01_helloworld.zip": HELLO_ZIP_SHA256,
        "neogeo.xml": HELLO_XML_SHA256,
        "aes.zip": AES_BIOS_SHA256,
        "neogeo.zip": MVS_BIOS_SHA256,
        "ngdevkit-source.tar.gz": NGDEVKIT_ARCHIVE_SHA256,
    }
    for name, expected in expected_outputs.items():
        actual = sha256(OUTPUT / name)
        if actual != expected:
            raise SystemExit(
                f"open Neo Geo fixture checksum mismatch for {name}: {actual}"
            )
    for path in sorted(OUTPUT.iterdir()):
        print(f"{sha256(path)}  {path}")


if __name__ == "__main__":
    main()
