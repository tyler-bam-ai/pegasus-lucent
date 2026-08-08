#!/usr/bin/env python3
"""Fail closed on N64 held-Stop surface/save races.

Returning the library in a few milliseconds is insufficient if removing the
game view abandons its Surface while a queued GPU frame is still swapping.
This verifier requires the background Quick Resume transaction to finish and
rejects every observed signature of that race.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path


EXIT = re.compile(
    r"Exit to Lucent invoked engine=mupen64plus-next system=n64"
)
RETURN = re.compile(
    r"Returned to Lucent immediately in same window "
    r"engine=mupen64plus-next system=n64 latencyMs=(\d+)"
)
COMMIT = re.compile(
    r"Committed Quick Resume before stop engine=mupen64plus-next"
)
CHECKPOINT_FINISHED = re.compile(
    r"Exit checkpoint finished after library return "
    r"engine=mupen64plus-next system=n64"
)
SURFACE_RACE = re.compile(
    r"Android EGL swap failed \(0x300d\)|"
    r"BufferQueue has been abandoned|"
    r"dequeueBuffer: BufferQueue has been abandoned|"
    r"OpenGL ES API with no current context",
    re.I,
)
SESSION_ERROR = re.compile(
    r"Renderer stopped engine=mupen64plus-next system=n64|"
    r"Engine session error engine=mupen64plus-next system=n64",
    re.I,
)
SAVE_FAILURE = re.compile(
    r"engine state vault is not ready|"
    r"Quick Resume was not updated for mupen64plus-next|"
    r"Exit checkpoint failed after library return",
    re.I,
)


@dataclass(frozen=True)
class Audit:
    errors: tuple[str, ...]
    return_latencies_ms: tuple[int, ...]
    surface_race_count: int
    session_error_count: int
    save_failure_count: int

    @property
    def passed(self) -> bool:
        return not self.errors


def _positions(pattern: re.Pattern[str], text: str) -> list[int]:
    return [match.start() for match in pattern.finditer(text)]


def audit(log_text: str, maximum_return_latency_ms: int = 50) -> Audit:
    errors: list[str] = []
    exits = _positions(EXIT, log_text)
    returns = list(RETURN.finditer(log_text))
    commits = _positions(COMMIT, log_text)
    finished = _positions(CHECKPOINT_FINISHED, log_text)
    race_count = len(SURFACE_RACE.findall(log_text))
    session_error_count = len(SESSION_ERROR.findall(log_text))
    save_failure_count = len(SAVE_FAILURE.findall(log_text))
    latencies = tuple(int(match.group(1)) for match in returns)

    if len(exits) != 1:
        errors.append(f"expected exactly one N64 held-Stop exit, found {len(exits)}")
    if len(returns) != 1:
        errors.append(f"expected exactly one same-window N64 return, found {len(returns)}")
    if any(value > maximum_return_latency_ms for value in latencies):
        errors.append(
            f"N64 library return exceeded {maximum_return_latency_ms}ms: "
            f"{list(latencies)}"
        )
    if race_count:
        errors.append(
            "the gameplay Surface/EGL context was torn down while the render "
            f"loop was still active ({race_count} race markers)"
        )
    if session_error_count:
        errors.append(
            f"N64 emitted {session_error_count} renderer/session errors during exit"
        )
    if save_failure_count:
        errors.append(
            f"N64 background Quick Resume emitted {save_failure_count} failure markers"
        )
    if len(commits) != 1:
        errors.append(
            f"expected one verified N64 Quick Resume commit, found {len(commits)}"
        )
    if len(finished) != 1:
        errors.append(
            "background N64 retirement did not finish successfully after library return"
        )

    if len(exits) == len(returns) == len(commits) == len(finished) == 1:
        if not exits[0] < returns[0].start() < commits[0] < finished[0]:
            errors.append(
                "N64 exit ordering must be exit -> visible return -> atomic "
                "Quick Resume commit -> retirement completion"
            )

    return Audit(
        errors=tuple(errors),
        return_latencies_ms=latencies,
        surface_race_count=race_count,
        session_error_count=session_error_count,
        save_failure_count=save_failure_count,
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
    parser.add_argument("--maximum-return-latency-ms", type=int, default=50)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = audit(read_logs(args.evidence.resolve()),
                   args.maximum_return_latency_ms)
    report = {
        "pass": result.passed,
        "errors": list(result.errors),
        "returnLatenciesMs": list(result.return_latencies_ms),
        "surfaceRaceCount": result.surface_race_count,
        "sessionErrorCount": result.session_error_count,
        "saveFailureCount": result.save_failure_count,
    }
    payload = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    print(payload, end="")
    return 0 if result.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
