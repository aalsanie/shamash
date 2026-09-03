# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("shamash-artifacts", "shamash-export", "shamash-asm-core")


def project_version() -> str:
    matches = re.findall(r'^    version = "([0-9]+\.[0-9]+\.[0-9]+)"$', (ROOT / "build.gradle.kts").read_text(), re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("Expected one release version in build.gradle.kts")
    return matches[0]


def run(command: list[str], *, cwd: Path = ROOT, env: dict[str, str] | None = None) -> None:
    print("Running: " + subprocess.list2cmdline(command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def gradle(*args: str, project: Path = ROOT, wrapper: Path | None = None, env: dict[str, str] | None = None) -> None:
    wrapper = wrapper or project
    command = [str(wrapper / "gradlew.bat")] if os.name == "nt" else ["sh", str(wrapper / "gradlew")]
    run(command + ["--no-daemon", "--no-configuration-cache", "--stacktrace", *args], cwd=project, env=env)
