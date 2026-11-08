# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: AGPL-3.0-only
"""Run Gradle, retrying only transient downloads from trusted artifact hosts.

The exit code of a failing build is preserved; test, compilation, formatting,
verification and signing failures are never retried or treated as successful.
"""

from __future__ import annotations

import re
import subprocess
import sys
import time

MAX_ATTEMPTS = 4
BACKOFF_SECONDS = (5, 15, 30)

# Only specific transient HTTP responses from the hosts in the observed failure
# chain are retryable. Do not retry failed assertions or other build problems.
TRANSIENT_HTTP = re.compile(r"(?:HTTP response code:|Received status code)\s*(502|503|504)\b", re.I)
TRUSTED_ARTIFACT = re.compile(
    r"https://(?:"
    r"github\.com/(?:gradle/gradle-distributions|JetBrains/kotlin)/releases/download/|"
    r"(?:services|downloads)\.gradle\.org/distributions/|"
    r"repo\.maven\.apache\.org/maven2/"
    r")",
    re.I,
)
WRAPPER_TIMEOUT = re.compile(
    r"Downloading from https://(?:services|downloads)\.gradle\.org/distributions/\S+"
    r" failed: timeout\b",
    re.I,
)
TEST_FAILURE = re.compile(r"\b\d+ tests completed,\s*[1-9]\d* failed\b", re.I)


def retryable_artifact_failure(output: str) -> bool:
    """Require a recognized download error and no actual test failure."""
    if TEST_FAILURE.search(output):
        return False
    return any(
        (TRANSIENT_HTTP.search(line) and TRUSTED_ARTIFACT.search(line))
        or WRAPPER_TIMEOUT.search(line)
        for line in output.splitlines()
    )


def run(command: list[str]) -> int:
    if not command:
        raise ValueError("usage: ci-gradle-retry.py <gradle command and arguments>")

    for attempt in range(1, MAX_ATTEMPTS + 1):
        # Stream diagnostics in real time; keep only a bounded tail for the
        # retry decision. Nothing about a failed build is silently discarded.
        print(f"CI Gradle invocation {attempt}/{MAX_ATTEMPTS}: {' '.join(command)}", flush=True)
        lines: list[str] = []
        with subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        ) as process:
            assert process.stdout is not None
            for line in process.stdout:
                print(line, end="", flush=True)
                lines.append(line)
                if len(lines) > 10_000:
                    del lines[:5_000]
            code = process.wait()

        if code == 0:
            return 0
        if attempt == MAX_ATTEMPTS or not retryable_artifact_failure("".join(lines)):
            return code
        pause = BACKOFF_SECONDS[attempt - 1]
        print(f"Transient artifact download failure (exit {code}); retrying after {pause}s.", flush=True)
        time.sleep(pause)
    raise AssertionError("unreachable")


def main() -> int:
    args = sys.argv[1:]
    if args and args[0] == "--":
        args = args[1:]
    try:
        return run(args)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
