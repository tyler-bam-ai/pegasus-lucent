#!/usr/bin/env python3
"""Fail-closed audit for Lucent's N64 hardware-render runtime evidence.

Loading the Mupen shared object is not proof that the frontend accepted the
libretro hardware-render contract.  This verifier requires every observable
stage of that contract and rejects the exact misleading failure seen in the
00616f89 qualification run.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path


ROUTE = re.compile(
    r"In-window route accepted engine=mupen64plus-next system=n64"
)
LOAD_COMPLETE = re.compile(r"retro_load_game complete")
HW_ACCEPTED = re.compile(
    r"hardware render (?:request )?accepted.*?\bcontext=([A-Za-z0-9_.-]+)",
    re.I,
)
CONTEXT_RESET = re.compile(
    r"(?:hardware context reset complete|mupen64plus: context_reset\(\))",
    re.I,
)
FRAME_PRESENTED = re.compile(
    r"(?:hardware frame presented|pre-swap pixels sequence=([1-9][0-9]*))",
    re.I,
)
GL_REJECTED = re.compile(
    r"libretro frontend doesn't have OpenGL support", re.I
)
CORE_REJECTED = re.compile(r"core rejected game content", re.I)
SESSION_ERROR = re.compile(
    r"Renderer stopped engine=mupen64plus-next system=n64|"
    r"Engine session error engine=mupen64plus-next system=n64",
    re.I,
)


@dataclass(frozen=True)
class Audit:
    errors: tuple[str, ...]
    route_count: int
    hardware_contexts: tuple[str, ...]
    context_reset_count: int
    presented_frame_count: int
    negotiation_rejected: bool

    @property
    def passed(self) -> bool:
        return not self.errors


def audit(log_text: str) -> Audit:
    """Audit a bounded log captured from one real-menu N64 launch."""
    errors: list[str] = []
    route_count = len(ROUTE.findall(log_text))
    contexts = tuple(HW_ACCEPTED.findall(log_text))
    reset_count = len(CONTEXT_RESET.findall(log_text))
    frame_count = len(FRAME_PRESENTED.findall(log_text))
    negotiation_rejected = bool(GL_REJECTED.search(log_text))

    if route_count != 1:
        errors.append(
            f"expected exactly one real-menu N64 route, found {route_count}"
        )
    if negotiation_rejected:
        errors.append(
            "Mupen rejected RETRO_ENVIRONMENT_SET_HW_RENDER; a loaded core "
            "library is not an operable N64 session"
        )
    if CORE_REJECTED.search(log_text):
        errors.append("Mupen rejected game content after frontend negotiation")
    if SESSION_ERROR.search(log_text):
        errors.append("the N64 engine surfaced a renderer/session error")
    if not LOAD_COMPLETE.search(log_text):
        errors.append("retro_load_game never completed")
    if not contexts:
        errors.append(
            "no explicit hardware-render acceptance marker was captured"
        )
    if reset_count < 1:
        errors.append(
            "no hardware context_reset completion was captured with EGL current"
        )
    if frame_count < 1:
        errors.append("no nonzero hardware frame presentation was captured")

    return Audit(
        errors=tuple(errors),
        route_count=route_count,
        hardware_contexts=contexts,
        context_reset_count=reset_count,
        presented_frame_count=frame_count,
        negotiation_rejected=negotiation_rejected,
    )


def read_logs(evidence: Path) -> str:
    preferred = evidence / "n64-failure-logcat.txt"
    logs = [preferred] if preferred.is_file() else sorted(
        evidence.glob("*n64*logcat*.txt")
    )
    if not logs:
        raise RuntimeError(f"no bounded N64 logcat evidence in {evidence}")
    return "\n".join(
        path.read_text(encoding="utf-8", errors="replace") for path in logs
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = audit(read_logs(args.evidence.resolve()))
    report = {
        "pass": result.passed,
        "errors": list(result.errors),
        "routeCount": result.route_count,
        "hardwareContexts": list(result.hardware_contexts),
        "contextResetCount": result.context_reset_count,
        "presentedFrameCount": result.presented_frame_count,
        "negotiationRejected": result.negotiation_rejected,
    }
    payload = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    print(payload, end="")
    return 0 if result.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
