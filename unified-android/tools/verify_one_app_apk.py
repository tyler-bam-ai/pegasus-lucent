#!/usr/bin/env python3
"""Fail closed unless an APK preserves Lucent's one-app emulation boundary."""

from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
import re
import subprocess
import sys
import zipfile


PACKAGE = "com.thorium.preview"
MAIN_ACTIVITY = "org.pegasus_frontend.android.MainActivity"
ALLOWED_ACTIVITIES = {
    MAIN_ACTIVITY,
    "com.thorium.preview.PreviewActivity",
    "com.thorium.preview.BrowserActivity",
}

# These identifiers belong to standalone emulator applications.  Engine names
# and upstream notices are allowed in assets/native libraries, but no compiled
# Java/Kotlin route in Lucent may know how to target another emulator package.
FORBIDDEN_DEX_IDENTITIES = (
    "com.retroarch",
    "org.retroarch",
    "org.ppsspp.ppsspp",
    "org.dolphinemu",
    "org.azahar_emu",
    "org.citra.emu",
    "com.flycast.emulator",
    "com.reicast.emulator",
    "com.armsx2",
    "xyz.aethersx2.android",
    "dev.legacy.eden_emulator",
    "org.yuzu.yuzu_emu",
    "info.cemu.Cemu",
    "org.vita3k.emulator",
)

FORBIDDEN_DEX_CLASSES = (
    "RomLaunchActivity",
    "InternalGameLaunchActivity",
    "LucentGameActivity",
    "QualificationLaunchActivity",
    "SessionReturnRouter",
    "com/thorium/launchbridge/LaunchActivity",
    "com/thorium/launchbridge/StopButtonService",
)

# Product-facing failure messages must use Lucent.  Internal compatibility
# names (the upstream JNI Activity, metadata suffixes, and storage migration
# keys) deliberately remain allowed.
FORBIDDEN_VISIBLE_DEX_TEXT = (
    "Unable to commit Pegasus settings",
    "Unable to replace Pegasus settings",
)

FRONTEND_LAUNCH_PATCH_OFFSET = 0x75FD8
FRONTEND_LAUNCH_PATCH = bytes.fromhex(
    "600640f9" "3b3f0094" "601240f9" "79170094"
    "fd7b41a9" "f30742f8" "c0035fd6"
)


def _blocks(xmltree: str, element: str) -> list[str]:
    lines = xmltree.splitlines()
    result: list[str] = []
    marker = f"E: {element} "
    for index, line in enumerate(lines):
        stripped = line.lstrip()
        if not (stripped == f"E: {element}" or stripped.startswith(marker)):
            continue
        indent = len(line) - len(stripped)
        block = [line]
        for following in lines[index + 1:]:
            following_stripped = following.lstrip()
            following_indent = len(following) - len(following_stripped)
            if following_stripped.startswith("E: ") and following_indent <= indent:
                break
            block.append(following)
        result.append("\n".join(block))
    return result


def _raw_attribute(block: str, name: str) -> str | None:
    match = re.search(
        rf'^\s*A: (?:android:)?{re.escape(name)}(?:\([^\n]*?\))?="([^"]*)"',
        block,
        re.MULTILINE,
    )
    return None if match is None else match.group(1)


def verify_manifest(xmltree: str) -> list[str]:
    errors: list[str] = []
    for authority in (
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.KILL_BACKGROUND_PROCESSES"):
        if authority in xmltree:
            errors.append(
                f"one-app Lucent must not request legacy authority {authority}"
            )
    manifests = _blocks(xmltree, "manifest")
    applications = _blocks(xmltree, "application")
    activities = _blocks(xmltree, "activity")
    receivers = _blocks(xmltree, "receiver")
    if len(manifests) != 1:
        return [f"expected one manifest, found {len(manifests)}"]
    if _raw_attribute(manifests[0], "package") != PACKAGE:
        errors.append("APK package is not Lucent's com.thorium.preview identity")
    if len(applications) != 1:
        errors.append(f"expected one application, found {len(applications)}")
    else:
        if _raw_attribute(applications[0], "name") != \
                "com.thorium.preview.LucentApplication":
            errors.append("application class is not LucentApplication")
        if _raw_attribute(applications[0], "label") != "Lucent":
            errors.append("application label is not Lucent")

    by_name: dict[str, str] = {}
    for block in activities:
        name = _raw_attribute(block, "name")
        if not name:
            errors.append("manifest contains an unnamed Activity")
            continue
        if name in by_name:
            errors.append(f"manifest repeats Activity {name}")
        by_name[name] = block
    if set(by_name) != ALLOWED_ACTIVITIES:
        errors.append(
            "manifest Activity set differs from Lucent main/browser/preview boundary: "
            + repr(sorted(by_name))
        )

    main = by_name.get(MAIN_ACTIVITY, "")
    if 'android:launchMode' not in main or \
            not re.search(r'android:launchMode[^\n]*\(type 0x10\)0x2', main):
        errors.append("Lucent MainActivity is not singleTask")
    if '"android.intent.action.MAIN"' not in main or \
            '"android.intent.category.LAUNCHER"' not in main:
        errors.append("Lucent MainActivity is not the sole launcher")
    launcher_count = sum(
        '"android.intent.category.LAUNCHER"' in block for block in by_name.values())
    if launcher_count != 1:
        errors.append(f"expected one launcher Activity, found {launcher_count}")

    preview = by_name.get("com.thorium.preview.PreviewActivity", "")
    if not re.search(r'android:exported[^\n]*\(type 0x12\)0x0', preview):
        errors.append("Thor PreviewActivity must be non-exported")
    if not re.search(r'android:excludeFromRecents[^\n]*0xffffffff', preview):
        errors.append("Thor PreviewActivity must be excluded from recents")
    if _raw_attribute(preview, "taskAffinity") != "com.thorium.preview.preview":
        errors.append("Thor PreviewActivity must use its private display task affinity")

    browser = by_name.get("com.thorium.preview.BrowserActivity", "")
    if not re.search(r'android:exported[^\n]*\(type 0x12\)0x0', browser):
        errors.append("Lucent BrowserActivity must be non-exported")
    # Internal game launches are intercepted inside MainActivity.launchAmCommand;
    # no exported receiver or second Activity is part of the product boundary.
    if any(_raw_attribute(block, "name") ==
           "com.thorium.preview.GameLaunchReceiver" for block in receivers):
        errors.append("manifest retains obsolete exported GameLaunchReceiver")
    return errors


def verify_dex(apk: Path) -> list[str]:
    errors: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        dex_names = sorted(
            name for name in archive.namelist()
            if re.fullmatch(r"classes(?:\d+)?\.dex", name)
        )
        if not dex_names:
            return ["APK contains no DEX payload"]
        dex = b"\n".join(archive.read(name) for name in dex_names)
    for identity in FORBIDDEN_DEX_IDENTITIES:
        forms = {identity.encode(), identity.replace(".", "/").encode()}
        if any(form in dex for form in forms):
            errors.append(f"DEX contains standalone emulator route {identity}")
    for class_name in FORBIDDEN_DEX_CLASSES:
        if class_name.encode() in dex:
            errors.append(f"DEX contains legacy game-launch component {class_name}")
    for phrase in FORBIDDEN_VISIBLE_DEX_TEXT:
        if phrase.encode() in dex:
            errors.append(f"DEX retains visible upstream phrase {phrase!r}")
    return errors


def _branding_replacements() -> dict[str, str]:
    module_path = Path(__file__).with_name("patch_lucent_branding.py")
    spec = importlib.util.spec_from_file_location("lucent_branding_patch", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load branding policy: {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return dict(module.VISIBLE_REPLACEMENTS)


def verify_branding(apk: Path) -> list[str]:
    errors: list[str] = []
    frontend = "lib/arm64-v8a/libpegasus-fe_arm64-v8a.so"
    with zipfile.ZipFile(apk) as archive:
        if frontend not in archive.namelist():
            return [f"APK is missing pinned frontend library {frontend}"]
        data = archive.read(frontend)
    for before, after in _branding_replacements().items():
        for encoding in ("utf-8", "utf-16le", "utf-16be"):
            if before.encode(encoding) in data:
                errors.append(f"frontend retains visible upstream phrase {before!r}")
            # The pinned binary contains multiple language/resource copies, but
            # not necessarily every encoding for every string.  Requiring the
            # replacement in at least one encoding below proves the patch ran.
        if not any(after.encode(encoding) in data
                   for encoding in ("utf-8", "utf-16le", "utf-16be")):
            errors.append(f"frontend lacks Lucent replacement {after!r}")
    for malformed in (
            "Lucent  ", "Lucent have permission", "Lucent yet?",
            "games. on your device", "Lucent, can use"):
        if malformed.encode() in data:
            errors.append(f"frontend contains malformed Lucent text {malformed!r}")
    return errors


def verify_frontend_launch_lifecycle(apk: Path) -> list[str]:
    frontend = "lib/arm64-v8a/libpegasus-fe_arm64-v8a.so"
    with zipfile.ZipFile(apk) as archive:
        if frontend not in archive.namelist():
            return [f"APK is missing pinned frontend library {frontend}"]
        data = archive.read(frontend)
    found = data[
        FRONTEND_LAUNCH_PATCH_OFFSET:
        FRONTEND_LAUNCH_PATCH_OFFSET + len(FRONTEND_LAUNCH_PATCH)
    ]
    if found != FRONTEND_LAUNCH_PATCH:
        return [
            "frontend does not preserve QML across an in-process game launch"
        ]
    return []


def verify(apk: Path, aapt: Path) -> list[str]:
    if not apk.is_file():
        return [f"missing APK: {apk}"]
    if not aapt.is_file():
        return [f"missing aapt: {aapt}"]
    result = subprocess.run(
        [str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode:
        return [f"aapt could not inspect AndroidManifest.xml: {result.stderr.strip()}"]
    return (verify_manifest(result.stdout) + verify_dex(apk) +
            verify_branding(apk) + verify_frontend_launch_lifecycle(apk))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt", required=True, type=Path)
    args = parser.parse_args()
    errors = verify(args.apk, args.aapt)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
    print("Lucent one-app APK boundary: PASS")


if __name__ == "__main__":
    main()
