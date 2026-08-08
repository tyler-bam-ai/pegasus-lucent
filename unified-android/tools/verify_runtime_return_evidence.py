#!/usr/bin/env python3
"""Offline verifier for Lucent's same-window launch/return evidence.

This gate is deliberately independent of the in-process host's latency log.
An implementation can remove its gameplay overlay in one millisecond while
still forcing Qt through an Activity resume and visibly exposing the startup
splash.  Runtime acceptance therefore fails if either the Android lifecycle
trace or any captured return frame shows that reset.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


LAUNCH_ACTION = "com.thorium.preview.LAUNCH_INTERNAL_GAME"
MAIN_ACTIVITY = (
    "com.thorium.preview/org.pegasus_frontend.android.MainActivity"
)
HOST_RETURN = re.compile(
    r"Returned to Lucent immediately in same window .*?latencyMs=(\d+)"
)
VISIBLE_RESET = re.compile(
    r"PREPARING|SAVING\s+AND\s+RETURNING|POWERED\s+BY\s+PEGASUS|"
    r"\bLUCENT\b|\bPEGASUS\b",
    re.I,
)


@dataclass(frozen=True)
class Audit:
    errors: tuple[str, ...]
    host_return_latencies_ms: tuple[int, ...]
    activity_launch_count: int
    lifecycle_resume_count: int
    visible_reset_frames: tuple[str, ...]

    @property
    def passed(self) -> bool:
        return not self.errors


def audit(log_text: str, frame_ocr: dict[str, str],
          evidence_errors: tuple[str, ...] = ()) -> Audit:
    """Return deterministic findings from already-collected evidence."""
    activity_launches = []
    lifecycle_resumes = []
    for line in log_text.splitlines():
        if ("ActivityTaskManager" in line and "START " in line and
                LAUNCH_ACTION in line and MAIN_ACTIVITY in line):
            activity_launches.append(line.strip())
        if ("performResumeActivity com.thorium.preview" in line or
                ("QtActivity" in line and "onResume" in line)):
            lifecycle_resumes.append(line.strip())

    latencies = tuple(int(value) for value in HOST_RETURN.findall(log_text))
    visible = tuple(sorted(
        name for name, text in frame_ocr.items() if VISIBLE_RESET.search(text)
    ))
    errors: list[str] = list(evidence_errors)
    if activity_launches:
        errors.append(
            "normal menu launch used ActivityTaskManager START for Lucent "
            "MainActivity; launch must reach the live Activity by the "
            "same-package receiver without an Activity lifecycle transition"
        )
    if activity_launches and lifecycle_resumes:
        errors.append(
            "menu launch resumed/restarted the Lucent Activity after an "
            "Activity START, allowing Qt to recreate its surface"
        )
    if visible:
        suffix = ""
        if latencies:
            suffix = (
                f" despite host latency marker min={min(latencies)}ms"
            )
        errors.append(
            "visible launch/return splash or reset captured in "
            + ", ".join(visible) + suffix
        )
    if not latencies:
        errors.append("no in-window return marker was captured")

    return Audit(
        errors=tuple(errors),
        host_return_latencies_ms=latencies,
        activity_launch_count=len(activity_launches),
        lifecycle_resume_count=len(lifecycle_resumes),
        visible_reset_frames=visible,
    )


def ocr_frame(path: Path, tesseract: Path) -> str:
    completed = subprocess.run(
        [str(tesseract), str(path), "stdout", "--psm", "6"],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"OCR failed for {path.name}: {completed.stderr.strip()}"
        )
    return " ".join(completed.stdout.split())


def stored_result_evidence(path: Path) -> tuple[dict[str, str], tuple[int, ...]]:
    """Extract captured OCR/latencies even from a prematurely marked PASS."""
    if not path.is_file():
        return {}, ()
    root = json.loads(path.read_text(encoding="utf-8"))
    ocr: dict[str, str] = {}
    latencies: list[int] = []

    def visit(value: object) -> None:
        if isinstance(value, dict):
            if "hostReturnLatencyMs" in value:
                try:
                    latencies.append(int(value["hostReturnLatencyMs"]))
                except (TypeError, ValueError):
                    pass
            stored = value.get("interstitialOcr")
            if isinstance(stored, list):
                for row in stored:
                    if not isinstance(row, dict):
                        continue
                    name = Path(str(row.get("path", "unknown"))).name
                    ocr[name] = str(row.get("ocr", ""))
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(root)
    return ocr, tuple(latencies)


def verify_directory(evidence: Path, tesseract: Path) -> Audit:
    logs = sorted(evidence.glob("*logcat*.txt"))
    log_text = "\n".join(path.read_text(
        encoding="utf-8", errors="replace") for path in logs)
    stored_ocr, stored_latencies = stored_result_evidence(
        evidence / "results.json"
    )
    if not logs:
        # Preserve independently recorded latency markers, but fail closed on
        # the missing lifecycle trace: otherwise an Activity START is
        # impossible to rule out.
        log_text = "\n".join(
            "I/LucentInWindow: Returned to Lucent immediately in same window "
            f"latencyMs={latency}" for latency in stored_latencies
        )
    frames = sorted(path for path in evidence.glob("*-return-*.png")
                    if "interactive" not in path.name)
    if not frames:
        raise RuntimeError(f"no captured return frames in {evidence}")
    frame_ocr = dict(stored_ocr)
    frame_ocr.update({path.name: ocr_frame(path, tesseract) for path in frames})
    evidence_errors = () if logs else (
        "no raw logcat lifecycle trace was preserved; Activity restart/resume "
        "cannot be excluded",
    )
    return audit(log_text, frame_ocr, evidence_errors)


def recorded_apk_sha256(evidence: Path) -> str | None:
    """Return the APK identity the evidence bundle was captured from.

    Mirrors what the QA runners write: run_runtime_acceptance_qa.py records
    ``expectedSha256`` plus ``exactInstall.{candidateSha256,installedSha256}``
    in results.json, run_phase1a_activity_qa.py records ``apkSha256``, and a
    bare ``installed-base-sha256.txt`` file is accepted as a manual marker.
    """
    results = evidence / "results.json"
    if results.is_file():
        try:
            root = json.loads(results.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            root = None
        if isinstance(root, dict):
            candidates: list[object] = [
                root.get("apkSha256"), root.get("expectedSha256"),
            ]
            install = root.get("exactInstall")
            if isinstance(install, dict):
                candidates += [
                    install.get("installedSha256"),
                    install.get("candidateSha256"),
                ]
            for value in candidates:
                if isinstance(value, str) and re.fullmatch(
                        r"[0-9a-fA-F]{64}", value):
                    return value.lower()
    marker = evidence / "installed-base-sha256.txt"
    if marker.is_file():
        fields = marker.read_text(encoding="utf-8").split()
        if fields and re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]):
            return fields[0].lower()
    return None


def require_apk_identity(evidence: Path, expected_sha256: str) -> None:
    """Evidence never transfers across SHAs; fail closed on any mismatch."""
    expected = expected_sha256.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise SystemExit(
            f"--expected-apk-sha256 must be a 64-digit hex SHA-256, got "
            f"{expected_sha256!r}"
        )
    recorded = recorded_apk_sha256(evidence)
    if recorded is None:
        raise SystemExit(
            f"evidence bundle {evidence} records no APK identity "
            "(results.json apkSha256/expectedSha256/exactInstall or "
            "installed-base-sha256.txt); evidence never transfers across SHAs"
        )
    if recorded != expected:
        raise SystemExit(
            f"evidence bundle {evidence} was captured from APK {recorded}, "
            f"not the expected {expected}; evidence never transfers across "
            "SHAs"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=Path)
    parser.add_argument(
        "--expected-apk-sha256", required=True,
        help="SHA-256 of the exact APK this evidence must have been captured "
             "from; the bundle must record the same hash",
    )
    parser.add_argument(
        "--tesseract", type=Path,
        default=Path("/opt/homebrew/bin/tesseract"),
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.tesseract.is_file():
        raise SystemExit(f"tesseract not found: {args.tesseract}")
    evidence = args.evidence.resolve()
    require_apk_identity(evidence, args.expected_apk_sha256)
    result = verify_directory(evidence, args.tesseract)
    report = {
        "pass": result.passed,
        "errors": list(result.errors),
        "hostReturnLatenciesMs": list(result.host_return_latencies_ms),
        "activityLaunchCount": result.activity_launch_count,
        "lifecycleResumeCount": result.lifecycle_resume_count,
        "visibleResetFrames": list(result.visible_reset_frames),
    }
    payload = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    print(payload, end="")
    return 0 if result.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
