#!/usr/bin/env python3
"""Black-box Lucent acceptance on the exact signed AYN Thor artifact.

Unlike the engine qualification runners, this gate never starts a game intent.
It drives the same Odin Controller event node a person uses: right-stick view
selection, D-pad menu movement, physical A, and the one-second physical Stop
hold.  Every game must remain in Lucent's original MainActivity/task/window/PID.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shlex
import stat
import subprocess
import sys
import threading
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from PIL import Image, ImageChops

import run_phase1a_activity_qa as qa
import verify_n64_hw_runtime_evidence as n64_hw
import verify_menu_route_closure as route_closure


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.thorium.preview"
MAIN_ACTIVITY = "com.thorium.preview/org.pegasus_frontend.android.MainActivity"
FROZEN_THEME_QML = "8578d2c16f750913c2af0edc7bc81f9bd7885a6a9222b178821a2ca8a365538a"
FROZEN_THEME_CFG = "555b32df5f07153d34e0e40addd3d70068768cb684aaccf1793e5f7a75f4350b"
ROUTE = re.compile(r"In-window route accepted engine=([^ ]+) system=([^\s]+)")
RETURN = re.compile(
    r"Returned to Lucent immediately in same window engine=([^ ]+) "
    r"system=([^ ]+) latencyMs=(\d+)"
)
ACTIVITY_START = re.compile(
    r"ActivityTaskManager.*START\s+u\d+.*com\.thorium\.preview",
    re.I,
)
PROHIBITED_TEXT = re.compile(
    r"PREPARING|SAVING\s+AND\s+RETURNING|POWERED\s+BY\s+PEGASUS|"
    r"\bLUCENT\b|\bPEGASUS\b",
    re.I,
)


@dataclass(frozen=True)
class SystemCase:
    folder: str
    aliases: tuple[str, ...]
    engines: tuple[str, ...]
    phase: int
    dual_screen: bool = False
    flicker_burst: bool = False
    lower_touch: bool = False
    required_titles: tuple[str, ...] = ()


@dataclass
class InputTrace:
    action: str
    monotonic_ms: int


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize(value: object) -> str:
    return re.sub(r"[^a-z0-9]+", "", str(value or "").lower())


def load_matrix(path: Path) -> list[SystemCase]:
    root = json.loads(path.read_text(encoding="utf-8"))
    if root.get("schemaVersion") != 1:
        raise RuntimeError("unsupported runtime acceptance matrix")
    result = []
    for row in root.get("systems", []):
        result.append(SystemCase(
            folder=normalize(row["folder"]),
            aliases=tuple(normalize(value) for value in row.get("aliases", [])),
            engines=tuple(normalize(value) for value in row.get("engines", [])),
            phase=int(row["phase"]),
            dual_screen=bool(row.get("dualScreen", False)),
            flicker_burst=bool(row.get("flickerBurst", False)),
            lower_touch=bool(row.get("lowerTouch", False)),
            required_titles=tuple(str(value) for value in
                                  row.get("requiredTitles", [])),
        ))
    return result


def embedded_theme(apk: Path) -> tuple[bytes, bytes, list[str]]:
    with zipfile.ZipFile(apk) as archive:
        payload = archive.read("assets/pegasus-lucent-theme.zip")
    with zipfile.ZipFile(io.BytesIO(payload)) as theme:
        qml_names = [name for name in theme.namelist()
                     if name == "theme.qml" or name.endswith("/theme.qml")]
        cfg_names = [name for name in theme.namelist()
                     if name == "theme.cfg" or name.endswith("/theme.cfg")]
        if len(qml_names) != 1 or len(cfg_names) != 1:
            raise RuntimeError("bundled theme has ambiguous qml/cfg roots")
        qml = theme.read(qml_names[0])
        cfg = theme.read(cfg_names[0])
    text = qml.decode("utf-8")
    catalog = text.split("id: systemCatalog", 1)[1].split(
        "// Predecode official platform logotypes", 1)[0]
    order = [normalize(value) for value in re.findall(
        r'ListElement\s*\{[^{}]*?folder:\s*"([^"]+)"[^{}]*\}',
        catalog, re.S,
    )]
    if not order or order[0] != "all":
        raise RuntimeError("cannot derive system order from exact bundled theme")
    return qml, cfg, order


def verify_frozen_menu(apk: Path) -> list[str]:
    qml, cfg, order = embedded_theme(apk)
    errors = []
    if hashlib.sha256(qml).hexdigest() != FROZEN_THEME_QML:
        errors.append("exact APK does not contain the frozen menu theme.qml")
    if hashlib.sha256(cfg).hexdigest() != FROZEN_THEME_CFG:
        errors.append("exact APK does not contain the frozen menu theme.cfg")
    if len(order) < 2:
        errors.append("exact APK has no usable system catalog")
    return errors


def latest_tool(root: Path, name: str) -> Path:
    candidates = sorted(root.glob(f"*/{name}"))
    if not candidates:
        raise RuntimeError(f"Android SDK tool is missing: {name}")
    return candidates[-1]


def exact_install(adb: Path, serial: str, apk: Path, expected_sha: str,
                  output: Path, perform_install: bool = True) -> dict[str, object]:
    actual = sha256_file(apk)
    if actual != expected_sha.lower():
        raise RuntimeError(f"candidate SHA mismatch: expected {expected_sha}, got {actual}")
    if perform_install:
        # Preserve the owner's app data and accept only a normal
        # same/newer-version replacement. The release gate explicitly forbids
        # downgrade or uninstall.
        qa.adb(adb, serial, "install", "-r", str(apk))
    package_path = qa.adb(adb, serial, "shell", "pm", "path", PACKAGE).stdout.strip()
    base = next((line.split(":", 1)[1] for line in package_path.splitlines()
                 if line.startswith("package:") and line.endswith("base.apk")), "")
    if not base:
        raise RuntimeError("installed Lucent base.apk path was not found")
    installed = output / "installed-base.apk"
    with installed.open("wb") as handle:
        completed = subprocess.run(
            [str(adb), "-s", serial, "exec-out", "cat", base],
            stdout=handle, stderr=subprocess.PIPE,
        )
    if completed.returncode != 0:
        raise RuntimeError("cannot read installed Lucent base.apk")
    installed_sha = sha256_file(installed)
    if installed_sha != actual:
        raise RuntimeError(
            f"installed APK differs from candidate: {installed_sha} != {actual}"
        )
    return {"candidateSha256": actual, "installedSha256": installed_sha,
            "installedBaseApk": base,
            "replacementInstallPerformed": perform_install}


def assert_installed_hash(adb: Path, serial: str, expected_sha: str) -> dict[str, object]:
    """Fail closed if another installer/updater replaces the QA artifact."""
    package_path = qa.adb(adb, serial, "shell", "pm", "path", PACKAGE).stdout.strip()
    base = next((line.split(":", 1)[1] for line in package_path.splitlines()
                 if line.startswith("package:") and line.endswith("base.apk")), "")
    if not base:
        raise RuntimeError("installed Lucent base.apk path was not found by hash guard")
    completed = qa.adb(adb, serial, "shell", "sha256sum", base)
    actual = completed.stdout.strip().split()[0].lower()
    expected = expected_sha.lower()
    if actual != expected:
        raise RuntimeError(
            f"installed APK hash changed during QA: {actual} != {expected}"
        )
    return {"monotonicMs": int(time.monotonic() * 1000), "sha256": actual}


def library_index(adb: Path, serial: str) -> dict:
    forwarded = qa.adb(adb, serial, "forward", "tcp:0", "tcp:43821").stdout.strip()
    try:
        import urllib.request
        with urllib.request.urlopen(
                f"http://127.0.0.1:{forwarded}/library/index", timeout=8) as response:
            return json.loads(response.read().decode("utf-8"))
    finally:
        qa.adb(adb, serial, "forward", "--remove", f"tcp:{forwarded}", check=False)


def visible_system_order(apk: Path, index: dict) -> list[str]:
    _qml, _cfg, catalog = embedded_theme(apk)
    active = {normalize(value) for value in (index.get("systems") or {}).keys()}
    return ["all"] + [folder for folder in catalog
                      if folder != "all" and folder in active]


class PhysicalController:
    EV_SYN = 0
    EV_KEY = 1
    EV_ABS = 3
    SYN_REPORT = 0
    A = 304
    B = 305
    Y = 308
    L1 = 310
    R1 = 311
    STOP = 314
    UP = 544
    DOWN = 545
    LEFT = 546
    RIGHT = 547
    AXIS_CODES = {"ABS_Z": 2, "ABS_RX": 3, "ABS_RY": 4, "ABS_RZ": 5}
    HAT_X = 16
    HAT_Y = 17

    def __init__(self, adb: Path, serial: str, node: str):
        self.adb = adb
        self.serial = serial
        self.node = node
        self.trace: list[InputTrace] = []
        description = qa.adb(adb, serial, "shell", "getevent", "-lp", node).stdout
        self.axes = self.parse_axes(description)
        horizontal = "ABS_Z" if "ABS_Z" in self.axes else "ABS_RX"
        vertical = "ABS_RZ" if "ABS_RZ" in self.axes else "ABS_RY"
        if horizontal not in self.axes or vertical not in self.axes:
            raise RuntimeError("Thor right-stick axes were not found on controller node")
        self.horizontal = horizontal
        self.vertical = vertical

    @staticmethod
    def parse_axes(value: str) -> dict[str, tuple[int, int, int]]:
        result = {}
        pattern = re.compile(
            r"\b(ABS_(?:Z|RZ|RX|RY))\b\s*:.*?min\s+(-?\d+),\s*max\s+(-?\d+)",
            re.I,
        )
        for name, minimum, maximum in pattern.findall(value):
            low, high = int(minimum), int(maximum)
            result[name.upper()] = (low, high, round((low + high) / 2))
        return result

    def event(self, event_type: int, code: int, value: int) -> None:
        qa.adb(self.adb, self.serial, "shell", "sendevent", self.node,
               str(event_type), str(code), str(value))

    def sync(self) -> None:
        self.event(self.EV_SYN, self.SYN_REPORT, 0)

    def key_down(self, code: int, label: str) -> int:
        now = time.monotonic_ns() // 1_000_000
        self.trace.append(InputTrace(label + ":down", now))
        self.event(self.EV_KEY, code, 1)
        self.sync()
        return now

    def key_up(self, code: int, label: str) -> int:
        now = time.monotonic_ns() // 1_000_000
        self.trace.append(InputTrace(label + ":up", now))
        self.event(self.EV_KEY, code, 0)
        self.sync()
        return now

    def key(self, code: int, label: str, hold: float = 0.07) -> None:
        if code in {self.UP, self.DOWN, self.LEFT, self.RIGHT}:
            axis = self.HAT_Y if code in {self.UP, self.DOWN} else self.HAT_X
            value = -1 if code in {self.UP, self.LEFT} else 1
            self.trace.append(InputTrace(label + ":hat", time.monotonic_ns() // 1_000_000))
            self.event(self.EV_ABS, axis, value)
            self.sync()
            # A real human hat press remains asserted for roughly a tenth of a
            # second. Ultra-short synthetic pulses can reach evdev but be
            # missed by Qt between render/input polls, especially immediately
            # after an emulator returns to the library.
            time.sleep(max(hold, 0.12))
            self.event(self.EV_ABS, axis, 0)
            self.sync()
            # The Thor/Qt gamepad stack coalesces repeated hat taps while the
            # system-card transition is still settling. A human-paced release
            # interval is required; faster synthetic taps visibly skip input.
            time.sleep(0.65)
            return
        self.key_down(code, label)
        time.sleep(hold)
        self.key_up(code, label)
        time.sleep(0.10)

    def stick(self, direction: str, hold: float = 0.16) -> None:
        axis_name = self.vertical if direction in {"up", "down"} else self.horizontal
        low, high, center = self.axes[axis_name]
        value = low if direction in {"up", "left"} else high
        code = self.AXIS_CODES[axis_name]
        self.trace.append(InputTrace("right-stick-" + direction,
                                     time.monotonic_ns() // 1_000_000))
        self.event(self.EV_ABS, code, value)
        self.sync()
        time.sleep(hold)
        self.event(self.EV_ABS, code, center)
        self.sync()
        time.sleep(0.16)


def screenshot(adb: Path, serial: str, output: Path,
               display_token: Optional[str] = None) -> dict[str, object]:
    arguments = ["exec-out", "screencap"]
    if display_token is not None:
        arguments += ["-d", display_token]
    arguments += ["-p"]
    payload = qa.adb(adb, serial, *arguments, binary=True).stdout
    if not payload.startswith(b"\x89PNG"):
        raise RuntimeError("Android screenshot is not a PNG")
    output.write_bytes(payload)
    image = Image.open(io.BytesIO(payload)).convert("RGB")
    sampled = list(image.resize((240, 135)).getdata())
    visible = sum(max(pixel) >= 18 for pixel in sampled)
    return {
        "path": str(output), "width": image.width, "height": image.height,
        "visibleFraction": round(visible / max(1, len(sampled)), 5),
        "distinctColors": len(set(sampled)),
        "visible": visible >= len(sampled) * 0.08 and len(set(sampled)) >= 48,
    }


def wait_visible_menu(adb: Path, serial: str, output: Path,
                      timeout: float = 60.0) -> Path:
    """Wait until the actual interactive library replaces the startup splash.

    The library HTTP index can be ready several seconds before QML has replaced
    the splash. Controller events during that interval are correctly ignored by
    Lucent, so acceptance must not mistake them for failed physical controls.
    """
    deadline = time.monotonic() + timeout
    latest_text = ""
    attempt = 0
    while time.monotonic() < deadline:
        path = output / f"startup-menu-ready-{attempt:02d}.png"
        screenshot(adb, serial, path)
        latest_text = " ".join(ocr(path).upper().split())
        splash = "POWERED BY PEGASUS" in latest_text
        interactive = (
            any(label in latest_text for label in
                ("COVER VIEW", "LIST VIEW", "SYSTEM VIEW"))
            and any(label in latest_text for label in
                    ("SYSTEM", "TITLES", "CONTINUE", "RECENTLY"))
        )
        if interactive and not splash:
            return path
        attempt += 1
        time.sleep(0.45)
    raise RuntimeError(
        "Lucent did not expose an interactive menu after startup; "
        f"last OCR={latest_text}"
    )


def changed_pixels(left: Path, right: Path, threshold: int = 18) -> int:
    before = Image.open(left).convert("RGB").resize((320, 180))
    after = Image.open(right).convert("RGB").resize((320, 180))
    difference = ImageChops.difference(before, after)
    return sum(max(pixel) >= threshold for pixel in difference.getdata())


def mean_absolute_difference(left: Path, right: Path) -> float:
    before = Image.open(left).convert("RGB").resize((192, 108))
    after = Image.open(right).convert("RGB").resize((192, 108))
    pixels = list(ImageChops.difference(before, after).getdata())
    return sum(sum(pixel) / 3.0 for pixel in pixels) / max(1, len(pixels))


def ocr(path: Path) -> str:
    tesseract = Path("/opt/homebrew/bin/tesseract")
    if not tesseract.is_file():
        return ""
    completed = subprocess.run(
        [str(tesseract), str(path), "stdout", "--psm", "6"],
        check=False, capture_output=True, text=True,
    )
    return completed.stdout


def ocr_region(path: Path, box: tuple[int, int, int, int], psm: int = 6) -> str:
    """OCR a logical 1920x1080 region without leaking a user's ROM path."""
    tesseract = Path("/opt/homebrew/bin/tesseract")
    if not tesseract.is_file():
        return ""
    image = Image.open(path).convert("RGB")
    sx, sy = image.width / 1920.0, image.height / 1080.0
    scaled = (round(box[0] * sx), round(box[1] * sy),
              round(box[2] * sx), round(box[3] * sy))
    with io.BytesIO() as buffer:
        image.crop(scaled).save(buffer, format="PNG")
        completed = subprocess.run(
            [str(tesseract), "stdin", "stdout", "--psm", str(psm)],
            input=buffer.getvalue(), check=False, capture_output=True,
        )
    return completed.stdout.decode("utf-8", errors="replace")


def selected_title_matches(expected: str, observed: str) -> bool:
    expected_normalized = normalize(expected)
    observed_normalized = normalize(observed)
    if expected_normalized and expected_normalized in observed_normalized:
        return True
    # The frozen header can crop the first/last glyph of a long title. Match
    # alphabetic words by a four-character prefix, but preserve a trailing
    # numeric sequel discriminator (Galaxy 2 must never pass as Galaxy).
    words = [normalize(word) for word in re.findall(r"[A-Za-z0-9]+", expected)
             if len(normalize(word)) >= 3 or normalize(word).isdigit()]
    if len(words) < 2:
        return False
    if words[-1].isdigit() and words[-1] not in observed_normalized:
        return False
    alphabetic = [word for word in words if not word.isdigit()]
    matched = sum(word[:min(4, len(word))] in observed_normalized
                  for word in alphabetic)
    return bool(alphabetic) and matched >= max(2, (len(alphabetic) * 2 + 2) // 3)


def selected_header_ocr(path: Path) -> str:
    """Read the selected-title header without letting wallpaper defeat OCR."""
    boxes = (
        (45, 120, 1810, 315),  # long/two-line title
        (45, 130, 900, 260),   # normal title, less wallpaper
        (45, 165, 650, 245),   # short/numeric title only
    )
    values = [" ".join(ocr_region(path, box, 6).split()) for box in boxes]
    return " | ".join(value for value in values if value)


def sort_tab_scores(path: Path) -> list[float]:
    """Score the four exact frozen sort buttons by their non-text interiors."""
    image = Image.open(path).convert("RGB")
    sx, sy = image.width / 1920.0, image.height / 1080.0
    scores = []
    for index in range(4):
        left = 48 + index * 150
        # Sample above and below the label. The selected button has an opaque
        # accent fill; unselected buttons remain the same nearly-black glass.
        regions = ((left + 8, 355, left + 134, 365),
                   (left + 8, 383, left + 134, 391))
        pixels = []
        for x1, y1, x2, y2 in regions:
            crop = image.crop((round(x1 * sx), round(y1 * sy),
                               round(x2 * sx), round(y2 * sy)))
            pixels.extend(crop.getdata())
        count = max(1, len(pixels))
        mean = tuple(sum(pixel[channel] for pixel in pixels) / count
                     for channel in range(3))
        luminance = 0.2126 * mean[0] + 0.7152 * mean[1] + 0.0722 * mean[2]
        chroma = max(mean) - min(mean)
        scores.append(luminance + chroma * 0.65)
    return scores


def active_sort_index(path: Path) -> int:
    scores = sort_tab_scores(path)
    ranked = sorted(range(4), key=lambda index: scores[index], reverse=True)
    if scores[ranked[0]] - scores[ranked[1]] < 8.0:
        raise RuntimeError(f"cannot identify active sort button: {scores}")
    return ranked[0]


def force_alpha_list(adb: Path, serial: str, controller: PhysicalController,
                     output: Path, prefix: str) -> Path:
    """Use only shoulder/Y events to force Alpha + vertical List game view."""
    frame = output / f"{prefix}-sort-probe-00.png"
    screenshot(adb, serial, frame)
    # Always cycle at least once. If Alpha was already persisted, returning to
    # it through the shoulder cycle resets selection to row zero just like a
    # real user changing sort, keeping index-based physical navigation exact.
    controller.key(controller.R1, "physical-r1-next-sort", hold=0.04)
    frame = output / f"{prefix}-sort-probe-01.png"
    screenshot(adb, serial, frame)
    for attempt in range(1, 5):
        if active_sort_index(frame) == 2:
            break
        controller.key(controller.R1, "physical-r1-next-sort", hold=0.04)
        frame = output / f"{prefix}-sort-probe-{attempt + 1:02d}.png"
        screenshot(adb, serial, frame)
    else:
        raise RuntimeError("physical R1 could not select Alpha sort")

    footer = " ".join(ocr_region(frame, (900, 950, 1890, 1080), 6).split()).upper()
    if "VIEW: LIST" not in footer:
        controller.key(controller.Y, "physical-y-toggle-game-view", hold=0.05)
        frame = output / f"{prefix}-list-view.png"
        screenshot(adb, serial, frame)
        footer = " ".join(ocr_region(frame, (900, 950, 1890, 1080), 6).split()).upper()
    if "VIEW: LIST" not in footer:
        raise RuntimeError(f"physical Y could not prove List game view: {footer}")
    return frame


def alpha_keys(index: dict, case: SystemCase) -> list[str]:
    systems = index.get("systems") or {}
    for candidate in (case.folder,) + case.aliases:
        modes = systems.get(candidate)
        if isinstance(modes, dict) and isinstance(modes.get("alpha"), list):
            return [str(value) for value in modes["alpha"]]
    return []


def title_position(keys: list[str], title: str) -> int:
    target = normalize(title)
    exact = [index for index, key in enumerate(keys)
             if normalize(key.split("|", 1)[-1]) == target]
    if len(exact) != 1:
        raise RuntimeError(
            f"required title has {len(exact)} exact Alpha-index matches: {title}"
        )
    return exact[0]


def title_from_key(key: str) -> str:
    return key.split("|", 1)[-1].strip()


def metadata_title_files(adb: Path, serial: str,
                         case: SystemCase) -> dict[str, set[str]]:
    """Read canonical Lucent metadata and retain every ROM path per title."""
    root = ("/storage/emulated/0/Android/data/com.thorium.preview/files/"
            "pegasus-frontend/metadata")
    listing = qa.adb(adb, serial, "shell", "find", root, "-maxdepth", "1",
                     "-type", "f", "-name", "*.pegasus.txt").stdout.splitlines()
    tokens = {normalize(case.folder), *(normalize(value) for value in case.aliases)}
    paths = []
    for raw in listing:
        name = Path(raw.strip()).name.lower()
        if any(re.search(rf"-{re.escape(token)}\.metadata\.pegasus\.txt$", name)
               for token in tokens):
            paths.append(raw.strip())
    values: dict[str, set[str]] = {}
    for path in paths:
        text = qa.adb(adb, serial, "exec-out", "cat", path).stdout
        current = ""
        for line in text.splitlines():
            if line.startswith("game:"):
                current = normalize(line.split(":", 1)[1])
            elif current and line.startswith("file:"):
                value = line.split(":", 1)[1].strip()
                if value:
                    values.setdefault(current, set()).add(value)
    return values


def title_has_live_rom(adb: Path, serial: str,
                       title_files: dict[str, set[str]], title: str) -> bool:
    for path in sorted(title_files.get(normalize(title), set())):
        remote = "test -f " + shlex.quote(path)
        if qa.adb(adb, serial, "shell", remote, check=False).returncode == 0:
            return True
    return False


def acceptance_titles(case: SystemCase, keys: list[str], count: int) -> list[str]:
    if len(keys) < count:
        raise RuntimeError(
            f"{case.folder} has only {len(keys)} indexed titles; {count} are required"
        )
    selected = list(case.required_titles)
    # Exact named regressions come first. Fill the remaining independent
    # samples from the signed live Alpha index, never from a handcrafted path.
    existing = {normalize(value) for value in selected}
    for key in keys:
        title = title_from_key(key)
        if normalize(title) in existing:
            continue
        selected.append(title)
        existing.add(normalize(title))
        if len(selected) >= max(count, len(case.required_titles)):
            break
    return selected


def playable_alpha_keys(adb: Path, serial: str, case: SystemCase,
                        keys: list[str], count: int) -> list[str]:
    """Filter stale indexed rows whose referenced ROM no longer exists."""
    title_files = metadata_title_files(adb, serial, case)
    required = {normalize(title) for title in case.required_titles}
    for title in case.required_titles:
        if not title_has_live_rom(adb, serial, title_files, title):
            raise RuntimeError(f"required title ROM is absent: {title}")
    playable = []
    playable_titles = set()
    for key in keys:
        title = title_from_key(key)
        normalized_title = normalize(title)
        if normalized_title in playable_titles:
            continue
        if not title_has_live_rom(adb, serial, title_files, title):
            continue
        playable.append(key)
        playable_titles.add(normalized_title)
        if (len(playable_titles) >= max(count, len(required)) and
                required.issubset(playable_titles)):
            break
    return playable


def move_to_title(adb: Path, serial: str, controller: PhysicalController,
                  current: int, target: int, title: str,
                  output: Path, prefix: str) -> int:
    code = controller.DOWN if target > current else controller.UP
    label = "dpad-down-exact-title" if target > current else "dpad-up-exact-title"
    for _ in range(abs(target - current)):
        controller.key(code, label, hold=0.025)
    selected = output / f"{prefix}-selected-title.png"
    screenshot(adb, serial, selected)
    observed = selected_header_ocr(selected)
    if not selected_title_matches(title, observed):
        raise RuntimeError(
            f"physical selection did not prove required title {title!r}; OCR={observed!r}"
        )
    return target


def assert_no_interstitial(frames: list[Path]) -> list[dict[str, str]]:
    evidence = []
    for path in frames:
        text = " ".join(ocr(path).split())
        evidence.append({"path": str(path), "ocr": text})
        if PROHIBITED_TEXT.search(text):
            raise RuntimeError(f"visible launch/return interstitial in {path.name}: {text}")
    return evidence


def secondary_token(adb: Path, serial: str) -> str:
    state = qa.adb(adb, serial, "shell", "dumpsys", "display").stdout
    match = re.search(
        r"DisplayViewport\{[^\n]*displayId=4,\s+uniqueId='local:(\d+)'", state
    )
    if match is None:
        raise RuntimeError("Thor lower physical display token was not found")
    return match.group(1)


def wait_new_route(adb: Path, serial: str, baseline: int,
                   case: Optional[SystemCase]) -> tuple[str, str]:
    deadline = time.monotonic() + 30.0
    while time.monotonic() < deadline:
        matches = ROUTE.findall(qa.logs(adb, serial))
        if len(matches) > baseline:
            engine, system = matches[-1]
            normalized_engine, normalized_system = normalize(engine), normalize(system)
            if case is not None:
                if normalized_system not in set(case.aliases) | {case.folder}:
                    raise RuntimeError(
                        f"menu launched {system}/{engine}; expected {case.folder}"
                    )
                if normalized_engine not in case.engines:
                    raise RuntimeError(
                        f"menu launched unexpected engine {engine} for {case.folder}"
                    )
            return engine, system
        time.sleep(0.10)
    raise RuntimeError("physical A did not reach an in-window route")


def wait_presented_frame(adb: Path, serial: str, engine: str, system: str,
                         menu: Path, output: Path) -> dict[str, object]:
    deadline = time.monotonic() + 60.0
    latest = None
    while time.monotonic() < deadline:
        log = qa.logs(adb, serial)
        marker = (f"Core frame presented engine={engine} system={system}")
        telemetry = (f"Runtime telemetry engine={engine} system={system}")
        latest = screenshot(adb, serial, output)
        if latest["visible"] and changed_pixels(menu, output) >= 5_000 and (
                marker in log or telemetry in log or f"Health engine={engine}" in log):
            return latest
        time.sleep(0.20)
    raise RuntimeError(f"no proven visible frame for {system}/{engine}; last={latest}")


def n64_gameplay_evidence(adb: Path, serial: str,
                          controller: PhysicalController, output: Path,
                          prefix: str, reference: Path) -> dict[str, object]:
    """Prove Mupen is visibly advancing and producing audio after input."""
    frames = []
    first = reference
    maximum_delta = 0
    for index in range(8):
        path = output / f"{prefix}-n64-motion-{index + 1:02d}.png"
        metrics = screenshot(adb, serial, path)
        delta = changed_pixels(first, path, threshold=10)
        maximum_delta = max(maximum_delta, delta)
        frames.append({"frame": metrics, "changedPixels": delta})
        time.sleep(0.14)
    if maximum_delta < 8_000:
        raise RuntimeError("N64 presented no changing visible gameplay frames")

    controller.key(controller.A, "physical-a-n64-gameplay-input", hold=0.055)
    post_input = output / f"{prefix}-n64-post-input.png"
    post_metrics = screenshot(adb, serial, post_input)
    post_delta = changed_pixels(first, post_input, threshold=10)

    deadline = time.monotonic() + 20.0
    telemetry = None
    health_pattern = re.compile(
        r"Health engine=mupen64plus-next fps=([0-9.]+) frames=(\d+) "
        r"audioUnderruns=(-?\d+) audioReceived=(\d+) audioWritten=(\d+) "
        r"audioRate=(\d+)"
    )
    while time.monotonic() < deadline:
        matches = health_pattern.findall(qa.logs(adb, serial))
        if matches:
            fps, frame_count, underruns, received, written, rate = matches[-1]
            telemetry = {
                "fps": float(fps), "frames": int(frame_count),
                "audioUnderruns": int(underruns),
                "audioReceived": int(received), "audioWritten": int(written),
                "audioRate": int(rate),
            }
        if (telemetry and float(telemetry["fps"]) >= 55.0 and
                int(telemetry["frames"]) > 0 and
                int(telemetry["audioReceived"]) > 0 and
                int(telemetry["audioWritten"]) > 0 and
                int(telemetry["audioRate"]) > 0):
            break
        time.sleep(0.20)
    else:
        raise RuntimeError(f"N64 produced no sustained video/audio telemetry: {telemetry}")
    return {"motionFrames": frames, "maximumChangedPixels": maximum_delta,
            "physicalInputSent": True, "postInputFrame": post_metrics,
            "postInputChangedPixels": post_delta, "telemetry": telemetry}


def same_identity(expected: dict[str, object], observed: dict[str, object]) -> bool:
    keys = ("activityToken", "taskId", "packagePids")
    return all(expected.get(key) == observed.get(key) for key in keys)


def identity(adb: Path, serial: str) -> dict[str, object]:
    result = qa.strict_gameplay_identity(adb, serial)
    if not result:
        raise RuntimeError("Lucent is not the sole foreground app/task/window/process")
    return result


def go_to_cover_system(controller: PhysicalController, visible_order: list[str],
                       folder: str) -> None:
    if folder not in visible_order:
        raise RuntimeError(f"{folder} is not a visible Lucent system")
    # Force Cover, then use the mapped right-stick shortcut to enter All Systems
    # and physical B to return to Cover with All Systems selected. This is a
    # deterministic origin even though the cover rail intentionally wraps.
    controller.stick("up")
    controller.stick("left")
    controller.key(controller.B, "physical-b-return-cover", hold=0.055)
    time.sleep(0.35)
    for _ in range(visible_order.index(folder)):
        controller.key(controller.RIGHT, "dpad-right", hold=0.07)


def inject_lower_touch(adb: Path, serial: str) -> None:
    # Touch is the one intentionally non-controller input in this black-box
    # suite: it targets the actual physical lower display and proves that
    # Lucent routes lower-panel interaction into the in-process emulator.
    qa.adb(adb, serial, "shell", "input", "touchscreen", "-d", "4",
           "swipe", "960", "270", "960", "270", "220")


def dual_screen_evidence(adb: Path, serial: str, case: SystemCase,
                         top_frame: Path, output: Path,
                         prefix: str) -> dict[str, object]:
    token = secondary_token(adb, serial)
    first = output / f"{prefix}-lower-1.png"
    second = output / f"{prefix}-lower-2.png"
    first_metrics = screenshot(adb, serial, first, token)
    time.sleep(0.45)
    second_metrics = screenshot(adb, serial, second, token)
    delta = changed_pixels(first, second, threshold=10)
    top_lower_delta = changed_pixels(top_frame, first, threshold=10)
    touch_delta = 0
    touch_frames: list[dict[str, object]] = []
    if case.dual_screen:
        if not first_metrics["visible"] or not second_metrics["visible"]:
            raise RuntimeError(f"{case.folder} did not render on the Thor lower display")
        # A static pause/title frame is legitimate, so motion is evidence but
        # not the sole acceptance criterion. The private display-4 Lucent window
        # and nontrivial lower pixels are mandatory.
        state = qa.strict_gameplay_identity(adb, serial)
        if not state or state.get("previewActivityDisplay") != 4:
            raise RuntimeError(f"{case.folder} has no Lucent secondary gameplay window")
        if top_lower_delta < 3_000:
            raise RuntimeError(
                f"{case.folder} duplicated the same gameplay on both Thor displays"
            )
        if case.lower_touch:
            inject_lower_touch(adb, serial)
            for index in range(10):
                touched = output / f"{prefix}-lower-touch-{index + 1:02d}.png"
                metrics = screenshot(adb, serial, touched, token)
                candidate_delta = changed_pixels(second, touched, threshold=10)
                touch_delta = max(touch_delta, candidate_delta)
                touch_frames.append({"frame": metrics,
                                     "changedPixels": candidate_delta})
                time.sleep(0.08)
            if touch_delta < 800:
                raise RuntimeError(
                    f"{case.folder} lower touch produced no visible lower-screen response"
                )
    else:
        if float(first_metrics["visibleFraction"]) > 0.025:
            raise RuntimeError(
                f"single-screen {case.folder} left content on Thor's lower display"
            )
    return {"displayToken": token, "first": first_metrics,
            "second": second_metrics, "changedPixels": delta,
            "topLowerChangedPixels": top_lower_delta,
            "lowerTouchRequired": case.lower_touch,
            "lowerTouchChangedPixels": touch_delta,
            "lowerTouchFrames": touch_frames,
            "samePackageProcessAcrossDisplays": case.dual_screen,
            "requiredGameplay": case.dual_screen}


def flicker_evidence(adb: Path, serial: str, output: Path,
                     prefix: str) -> dict[str, object]:
    frames = []
    complete = 0
    for index in range(24):
        path = output / f"{prefix}-flicker-{index + 1:02d}.png"
        metrics = screenshot(adb, serial, path)
        frames.append(metrics)
        if metrics["visible"] and float(metrics["visibleFraction"]) >= 0.20:
            complete += 1
        time.sleep(0.09 if index % 3 else 0.14)
    if complete != len(frames):
        raise RuntimeError(
            f"incomplete/flickering gameplay frames: {complete}/{len(frames)}"
        )
    return {"captured": len(frames), "complete": complete, "frames": frames}


def stop_and_return(adb: Path, serial: str, controller: PhysicalController,
                    case: SystemCase, engine: str, system: str,
                    menu_reference: Path, before_identity: dict[str, object],
                    output: Path, prefix: str,
                    expected_title: Optional[str] = None) -> dict[str, object]:
    log_before = qa.logs(adb, serial)
    commit_marker = f"Quick Resume committed engine={engine} system={system}"
    commit_baseline = log_before.count(commit_marker)
    return_baseline = len(RETURN.findall(log_before))
    down_ms = controller.key_down(controller.STOP, "physical-stop")

    def release() -> None:
        target = down_ms + 1_150
        while time.monotonic_ns() // 1_000_000 < target:
            time.sleep(0.005)
        controller.key_up(controller.STOP, "physical-stop")

    release_thread = threading.Thread(target=release, daemon=True)
    release_thread.start()
    target = down_ms + 920
    while time.monotonic_ns() // 1_000_000 < target:
        time.sleep(0.005)

    frames: list[tuple[int, Path]] = []
    while time.monotonic_ns() // 1_000_000 <= down_ms + 1_500:
        timestamp = time.monotonic_ns() // 1_000_000
        path = output / f"{prefix}-return-{len(frames) + 1:02d}.png"
        screenshot(adb, serial, path)
        frames.append((timestamp, path))
    release_thread.join(timeout=1.0)

    # A same-process QML reload can log an immediate return while visibly
    # showing Lucent's startup branding/progress UI. Reject that frame before
    # similarity heuristics can mistake two mostly-dark screens for each other.
    interstitial = assert_no_interstitial([path for _, path in frames])

    after_log = qa.logs(adb, serial)
    returned = RETURN.findall(after_log)
    if len(returned) <= return_baseline:
        raise RuntimeError("physical Stop did not log an immediate in-window return")
    returned_engine, returned_system, internal_latency = returned[-1]
    if normalize(returned_engine) != normalize(engine) or normalize(returned_system) != normalize(system):
        raise RuntimeError("physical Stop returned a different engine/system session")

    threshold_ms = down_ms + 1_000
    similarities = []
    first_menu_ms: Optional[int] = None
    first_menu_path: Optional[Path] = None
    for timestamp, path in frames:
        difference = mean_absolute_difference(menu_reference, path)
        frame_text = " ".join(ocr(path).upper().split())
        library_view = any(label in frame_text for label in
                           ("SYSTEM VIEW", "LIST VIEW", "COVER VIEW"))
        similarities.append({"path": str(path), "monotonicMs": timestamp,
                             "meanAbsoluteDifference": round(difference, 4),
                             "libraryViewVisible": library_view})
        if timestamp >= threshold_ms and difference < 65.0 and library_view and \
                first_menu_ms is None:
            first_menu_ms, first_menu_path = timestamp, path
    if first_menu_ms is None or first_menu_path is None:
        raise RuntimeError("prior Lucent menu was not visibly restored after Stop")
    visible_latency = first_menu_ms - threshold_ms
    if visible_latency > 500 or int(internal_latency) > 500:
        raise RuntimeError(
            f"Stop return exceeded 500 ms: visible={visible_latency}, internal={internal_latency}"
        )
    returned_identity = identity(adb, serial)
    if not same_identity(before_identity, returned_identity):
        raise RuntimeError("Stop return replaced Lucent's Activity/task/PID")

    # A physical menu move immediately after the first restored frame proves an
    # input-blocking splash is not merely hidden behind a similar screenshot.
    controller.key(controller.DOWN, "dpad-down-after-stop", hold=0.04)
    moved = output / f"{prefix}-return-interactive.png"
    screenshot(adb, serial, moved)
    input_delta = changed_pixels(first_menu_path, moved, threshold=10)
    if input_delta < 750:
        raise RuntimeError("restored menu did not visibly accept immediate physical input")
    controller.key(controller.UP, "dpad-up-restore", hold=0.04)
    selection_frame = output / f"{prefix}-return-selection.png"
    screenshot(adb, serial, selection_frame)
    selection_ocr = selected_header_ocr(selection_frame)
    if expected_title is not None and not selected_title_matches(
            expected_title, selection_ocr):
        raise RuntimeError(
            "Stop returned to a different library selection; "
            f"expected={expected_title!r}, OCR={selection_ocr!r}"
        )

    deadline = time.monotonic() + 30.0
    while time.monotonic() < deadline:
        if qa.logs(adb, serial).count(commit_marker) > commit_baseline:
            break
        time.sleep(0.10)
    else:
        raise RuntimeError("background Quick Resume commit did not complete")
    final_log = qa.logs(adb, serial)
    # The count comparison above is the stable chronology proof. Independent
    # `logcat -d` snapshots can differ in prefix formatting and buffer length,
    # so a character offset from `log_before` is not meaningful in final_log.
    qa.assert_no_crash(final_log)
    return {
        "downMonotonicMs": down_ms,
        "visibleReturnLatencyMs": visible_latency,
        "hostReturnLatencyMs": int(internal_latency),
        "sameActivityTaskPid": True,
        "immediateInputChangedPixels": input_delta,
        "backgroundAutosaveCommitted": True,
        "exactSelectionRestored": expected_title is None or
            selected_title_matches(expected_title, selection_ocr),
        "selectionOcr": selection_ocr,
        "frameSimilarities": similarities,
        "interstitialOcr": interstitial,
    }


def run_game_from_system_menu(adb: Path, serial: str,
                              controller: PhysicalController, case: SystemCase,
                              output: Path, prefix: str,
                              expected_title: Optional[str] = None) -> dict[str, object]:
    menu = output / f"{prefix}-system-menu.png"
    screenshot(adb, serial, menu)
    library_identity = identity(adb, serial)
    # Bound all runtime/log assertions to this one physical menu launch. This
    # also makes the N64 hardware-render verifier's exactly-one-route contract
    # unambiguous.
    qa.adb(adb, serial, "logcat", "-c")
    launch_log = qa.logs(adb, serial)
    route_baseline = len(ROUTE.findall(launch_log))
    interceptor_baseline = launch_log.count(
        "Intercepted lifecycle-neutral menu launch"
    )
    start_baseline = len(ACTIVITY_START.findall(launch_log))
    resume_baseline = launch_log.count(
        "performResumeActivity com.thorium.preview displayId 0"
    )
    qt_surface_baseline = launch_log.count("QtActivityDelegate.createSurface")
    controller.key(controller.A, "physical-a-launch", hold=0.055)
    launch_frames = []
    launch_deadline = time.monotonic() + 1.2
    while time.monotonic() < launch_deadline:
        path = output / f"{prefix}-launch-{len(launch_frames) + 1:02d}.png"
        screenshot(adb, serial, path)
        launch_frames.append(path)
    launch_ocr = assert_no_interstitial(launch_frames)
    try:
        engine, system = wait_new_route(adb, serial, route_baseline, case)
    except RuntimeError as failure:
        failed_log = qa.logs(adb, serial)
        if failed_log.count("Intercepted lifecycle-neutral menu launch") <= interceptor_baseline:
            raise RuntimeError(
                "physical A did not reach the in-process launch interceptor"
            ) from failure
        raise
    routed_log = qa.logs(adb, serial)
    lifecycle_delta = {
        "activityStart": len(ACTIVITY_START.findall(routed_log)) - start_baseline,
        "performResume": routed_log.count(
            "performResumeActivity com.thorium.preview displayId 0"
        ) - resume_baseline,
        "qtCreateSurface": routed_log.count(
            "QtActivityDelegate.createSurface"
        ) - qt_surface_baseline,
    }
    if lifecycle_delta["activityStart"] != 0:
        raise RuntimeError("menu A launch started an Activity instead of staying in-process")
    if lifecycle_delta["performResume"] != 0:
        raise RuntimeError("menu A launch resumed MainActivity instead of using the live window")
    if lifecycle_delta["qtCreateSurface"] != 0:
        raise RuntimeError("menu A launch recreated Qt's library surface")
    gameplay_identity = identity(adb, serial)
    if not same_identity(library_identity, gameplay_identity):
        raise RuntimeError("physical A created another Activity/task/PID")
    presented_path = output / f"{prefix}-gameplay.png"
    presented = wait_presented_frame(
        adb, serial, engine, system, menu, presented_path
    )
    n64_runtime = n64_gameplay_evidence(
        adb, serial, controller, output, prefix, presented_path
    ) if case.folder == "n64" else None
    displays = dual_screen_evidence(
        adb, serial, case, presented_path, output, prefix
    )
    flicker = flicker_evidence(adb, serial, output, prefix) \
        if case.flicker_burst else None
    returned = stop_and_return(
        adb, serial, controller, case, engine, system, menu,
        library_identity, output, prefix, expected_title,
    )
    bounded_log = qa.logs(adb, serial)
    log_path = output / f"{prefix}-logcat.txt"
    log_path.write_text(bounded_log, encoding="utf-8")
    n64_report = None
    if case.folder == "n64":
        audit = n64_hw.audit(bounded_log)
        n64_report = {
            "pass": audit.passed,
            "errors": list(audit.errors),
            "routeCount": audit.route_count,
            "hardwareContexts": list(audit.hardware_contexts),
            "contextResetCount": audit.context_reset_count,
            "presentedFrameCount": audit.presented_frame_count,
            "negotiationRejected": audit.negotiation_rejected,
            "evidenceLog": str(log_path),
        }
        if not audit.passed:
            raise RuntimeError("N64 hardware-render evidence failed: " +
                               "; ".join(audit.errors))
    return {
        "system": case.folder, "engine": engine,
        "routeSystem": system, "sameMainActivityWindowPid": True,
        "lifecycleNeutralBroadcast": True,
        "lifecycleDelta": lifecycle_delta,
        "launchInterstitialOcr": launch_ocr,
        "presentedFrame": presented,
        "n64Gameplay": n64_runtime,
        "n64HardwareAudit": n64_report,
        "displays": displays,
        "flickerBurst": flicker,
        "heldStop": returned,
    }


def run_system(adb: Path, serial: str, controller: PhysicalController,
               case: SystemCase, visible_order: list[str], index: dict,
               output: Path, titles: int, expected_sha: str,
               hash_guards: list[dict[str, object]]) -> dict[str, object]:
    hash_guards.append({"system": case.folder, "point": "before-system",
                        **assert_installed_hash(adb, serial, expected_sha)})
    go_to_cover_system(controller, visible_order, case.folder)
    cover = output / f"{case.folder}-cover.png"
    screenshot(adb, serial, cover)
    controller.key(controller.A, "physical-a-open-system", hold=0.055)
    time.sleep(0.35)
    force_alpha_list(adb, serial, controller, output, case.folder)
    keys = alpha_keys(index, case)
    playable_keys = playable_alpha_keys(adb, serial, case, keys, titles)
    planned_titles = acceptance_titles(case, playable_keys, titles)
    results = []
    current_position = 0
    for title_index, title in enumerate(planned_titles):
        position = title_position(keys, title)
        current_position = move_to_title(
            adb, serial, controller, current_position, position, title,
            output, f"{case.folder}-title-{title_index + 1:02d}",
        )
        results.append(run_game_from_system_menu(
            adb, serial, controller, case, output,
            f"{case.folder}-title-{title_index + 1:02d}",
            title,
        ))
        hash_guards.append({"system": case.folder, "title": title,
                            "point": "after-game",
                            **assert_installed_hash(adb, serial, expected_sha)})
    return {"status": "PASS", "system": case.folder, "phase": case.phase,
            "coverEntryPhysical": True, "systemMenuPhysical": True,
            "alphaIndexPhysical": True,
            "requiredTitles": list(case.required_titles),
            "testedTitles": planned_titles,
            "titles": results}


def run_list_view_smoke(adb: Path, serial: str,
                        controller: PhysicalController, case: SystemCase,
                        visible_order: list[str], output: Path) -> dict[str, object]:
    controller.stick("down")
    # 25 ms pulses are documented as loseable on the Thor (handover QA
    # lessons); a dropped UP leaves the cursor short of the top and every
    # subsequent DOWN lands on the wrong system. Use the proven >=40 ms
    # duration the rest of this harness uses.
    for _ in range(len(visible_order) + 3):
        controller.key(controller.UP, "dpad-up-list-system", hold=0.045)
    for _ in range(visible_order.index(case.folder)):
        controller.key(controller.DOWN, "dpad-down-list-system", hold=0.045)
    list_frame = output / f"list-view-{case.folder}.png"
    screenshot(adb, serial, list_frame)
    controller.key(controller.A, "physical-a-lock-list-system", hold=0.055)
    time.sleep(0.25)
    result = run_game_from_system_menu(
        adb, serial, controller, case, output, f"list-view-{case.folder}-title-01"
    )
    return {"status": "PASS", "system": case.folder,
            "listEntryPhysical": True, "game": result}


def persist(path: Path, report: dict) -> None:
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                    encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--matrix", type=Path,
                        default=Path(__file__).with_name("runtime-acceptance-matrix.json"))
    parser.add_argument("--system", action="append")
    parser.add_argument("--titles-per-system", type=int, default=3,
                        choices=(1, 2, 3))
    parser.add_argument("--skip-list-view-smoke", action="store_true")
    parser.add_argument("--stop-on-first-failure", action="store_true")
    parser.add_argument("--allow-physical-thor", action="store_true")
    parser.add_argument("--already-installed", action="store_true",
                        help="verify the installed base hash without replacing it again")
    parser.add_argument("--adb", type=Path,
                        default=Path.home() / ".codex/tools/android-platform-tools/adb")
    args = parser.parse_args()
    if not args.allow_physical_thor:
        raise SystemExit("runtime acceptance requires --allow-physical-thor")
    apk = args.apk.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    os.chmod(output, stat.S_IRWXU)
    manufacturer = qa.adb(args.adb, args.serial, "shell", "getprop",
                          "ro.product.manufacturer").stdout.strip()
    model = qa.adb(args.adb, args.serial, "shell", "getprop",
                   "ro.product.model").stdout.strip()
    if "ayn" not in manufacturer.lower() or "thor" not in model.lower():
        raise SystemExit(f"target is not an AYN Thor: {manufacturer=} {model=}")

    report: dict[str, object] = {
        "schemaVersion": 1,
        "target": {"serial": args.serial, "manufacturer": manufacturer,
                   "model": model},
        "apk": str(apk), "expectedSha256": args.expected_sha256.lower(),
        "menuThemeFrozen": False, "routeClosure": {}, "systems": [],
        "installedHashGuards": [],
        "listViewSmoke": None, "counts": {"PASS": 0, "FAIL": 0},
    }
    result_path = output / "results.json"
    persist(result_path, report)

    frozen_errors = verify_frozen_menu(apk)
    if frozen_errors:
        report["preflightFailure"] = frozen_errors
        persist(result_path, report)
        raise RuntimeError("; ".join(frozen_errors))
    report["menuThemeFrozen"] = True

    sdk_build_tools = Path.home() / "Library/Android/sdk/build-tools"
    dexdump = latest_tool(sdk_build_tools, "dexdump")
    # Crucially, collection shortnames come from the owner's installed metadata.
    # A host-only predecessor may intentionally have no launch lines, so model
    # exactly what this candidate's GameLaunchRouter will write at startup.
    metadata_text = route_closure.device_route_text(args.adb, args.serial)
    metadata_systems = route_closure.collection_shortnames(metadata_text)
    routes, simulation_report = route_closure.simulate_candidate_routes(
        apk, metadata_systems, dexdump
    )
    closure_errors, closure_report = route_closure.verify(apk, routes, dexdump)
    closure_report["preinstallSimulation"] = simulation_report
    preinstall_launchers = route_closure.launcher_audit(metadata_text)
    closure_report["installedLauncherStateBeforeReplacement"] = {
        "total": len(preinstall_launchers),
        "interceptedSameActivityStart": sum(
            row["kind"] == "intercepted-same-activity-start"
            for row in preinstall_launchers
        ),
        "staleAmStart": sum(row["kind"] == "stale-am-start"
                            for row in preinstall_launchers),
        "staleAmBroadcast": sum(row["kind"] == "stale-am-broadcast"
                                for row in preinstall_launchers),
        "externalOrInvalid": sum(row["kind"] == "external-or-invalid"
                                 for row in preinstall_launchers),
        "commands": preinstall_launchers,
    }
    if not simulation_report["candidateDexAliasContract"]["pass"]:
        closure_errors.append(
            simulation_report["candidateDexAliasContract"]["detail"]
        )
    closure_errors.extend(simulation_report["aliasContractFailures"])
    closure_report["existingLaunchLineCount"] = len(
        re.findall(r"^launch:", metadata_text, re.M)
    )
    report["routeClosure"] = closure_report
    persist(result_path, report)
    if closure_errors:
        raise RuntimeError("menu route/core closure failed before install")

    verifier = ROOT / "unified-android/tools/verify_one_app_apk.py"
    aapt = latest_tool(sdk_build_tools, "aapt")
    subprocess.run([sys.executable, str(verifier), "--aapt", str(aapt), str(apk)],
                   cwd=ROOT, check=True)
    report["exactInstall"] = exact_install(
        args.adb, args.serial, apk, args.expected_sha256, output,
        perform_install=not args.already_installed,
    )
    # Keep the digest evidence, not a redundant hundreds-of-megabytes copy.
    (output / "installed-base.apk").unlink(missing_ok=True)

    qa.adb(args.adb, args.serial, "shell", "am", "force-stop", PACKAGE)
    qa.adb(args.adb, args.serial, "logcat", "-c")
    qa.ensure_library(args.adb, args.serial)
    deadline = time.monotonic() + 30.0
    index = None
    while time.monotonic() < deadline:
        try:
            index = library_index(args.adb, args.serial)
            if isinstance(index.get("systems"), dict):
                break
        except Exception:
            pass
        time.sleep(0.5)
    if index is None:
        raise RuntimeError("Lucent library index did not become available")

    # Recheck after installation/startup in case the candidate regenerated any
    # launch metadata. A newly exposed route cannot bypass exact core closure.
    installed_route_text = route_closure.device_route_text(args.adb, args.serial)
    installed_routes = route_closure.routes_from_text(installed_route_text)
    post_errors, post_report = route_closure.verify(apk, installed_routes, dexdump)
    post_launchers = route_closure.launcher_audit(installed_route_text)
    post_report["startupLauncherState"] = {
        "total": len(post_launchers),
        "interceptedSameActivityStart": sum(
            row["kind"] == "intercepted-same-activity-start"
            for row in post_launchers
        ),
        "staleAmStart": sum(row["kind"] == "stale-am-start"
                            for row in post_launchers),
        "staleAmBroadcast": sum(row["kind"] == "stale-am-broadcast"
                                for row in post_launchers),
        "externalOrInvalid": sum(row["kind"] == "external-or-invalid"
                                 for row in post_launchers),
        "commands": post_launchers,
    }
    post_invalid = route_closure.invalid_launch_lines(installed_route_text)
    if post_invalid:
        post_report["invalidMetadataLaunchers"] = post_invalid
        post_errors.extend(
            "startup metadata has a non-Lucent launcher: " + row["commandSha256"]
            for row in post_invalid
        )
    if installed_routes != routes:
        post_report["expectedCandidateRoutes"] = [
            {"system": route.system, "engine": route.engine}
            for route in sorted(routes)
        ]
        post_report["actualStartupRoutes"] = [
            {"system": route.system, "engine": route.engine}
            for route in sorted(installed_routes)
        ]
        post_errors.append("startup routes differ from preinstall GameLaunchRouter simulation")
    report["routeClosureAfterStartup"] = post_report
    persist(result_path, report)
    if post_errors:
        raise RuntimeError("startup exposed a menu route without an exact packaged engine")

    visible_order = visible_system_order(apk, index)
    report["visibleSystemOrder"] = visible_order
    matrix = load_matrix(args.matrix)
    selected = {normalize(value) for value in (args.system or [])}
    if args.system:
        cases = []
        for requested in args.system:
            key = normalize(requested)
            match = next((case for case in matrix
                          if case.folder == key or key in case.aliases), None)
            if match is None:
                raise RuntimeError(f"unknown requested runtime system: {requested}")
            if match not in cases:
                cases.append(match)
    else:
        cases = list(matrix)
    missing = [case.folder for case in cases if case.folder not in visible_order]
    if missing:
        raise RuntimeError("required Thor library systems are not visible: " +
                           ", ".join(missing))

    event_node = qa.thor_controller_events(args.adb, args.serial)[0]
    controller = PhysicalController(args.adb, args.serial, event_node)
    report["controller"] = {"node": event_node, "physicalOnly": True,
                            "rightStickAxes": controller.axes}
    report["visibleMenuReadyFrame"] = str(
        wait_visible_menu(args.adb, args.serial, output)
    )
    persist(result_path, report)
    records = report["systems"]
    for case in cases:
        try:
            record = run_system(args.adb, args.serial, controller, case,
                                visible_order, index, output,
                                args.titles_per_system, args.expected_sha256,
                                report["installedHashGuards"])
        except Exception as error:
            record = {"status": "FAIL", "system": case.folder,
                      "phase": case.phase, "reason": str(error)}
            (output / f"{case.folder}-failure-logcat.txt").write_text(
                qa.logs(args.adb, args.serial), encoding="utf-8")
            (output / f"{case.folder}-failure-activities.txt").write_text(
                qa.activity_dump(args.adb, args.serial), encoding="utf-8")
            (output / f"{case.folder}-failure-windows.txt").write_text(
                qa.window_dump(args.adb, args.serial), encoding="utf-8")
        records.append(record)
        report["counts"] = {
            "PASS": sum(row["status"] == "PASS" for row in records),
            "FAIL": sum(row["status"] == "FAIL" for row in records),
        }
        persist(result_path, report)
        print(f"{record['status']:4} {case.folder}", flush=True)
        if args.stop_on_first_failure and record["status"] == "FAIL":
            break

    if not args.skip_list_view_smoke and cases and report["counts"]["FAIL"] == 0:
        try:
            report["listViewSmoke"] = run_list_view_smoke(
                args.adb, args.serial, controller, cases[0], visible_order, output
            )
        except Exception as error:
            report["listViewSmoke"] = {"status": "FAIL", "reason": str(error)}
            report["counts"]["FAIL"] += 1

    report["inputTrace"] = [trace.__dict__ for trace in controller.trace]
    report["complete"] = report["counts"]["FAIL"] == 0
    persist(result_path, report)
    return 0 if report["complete"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
