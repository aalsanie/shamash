# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

SOURCE = re.compile(r"^(shamash-[a-z-]+)/src/main/(?:java|kotlin)/(.+)\.(?:java|kt)$")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
MINIMUM = 90
PACKAGE_ROOTS = {
    "shamash-artifacts": "io/shamash/artifacts",
    "shamash-export": "io/shamash/export",
    "shamash-asm-core": "io/shamash/asm",
    "shamash-psi-core": "io/shamash/psi",
    "shamash-cli": "io/shamash/cli",
    "shamash-intellij-plugin": "io/shamash/intellij",
}


def changed_lines(diff: str) -> dict[str, set[int]]:
    files: dict[str, set[int]] = defaultdict(set)
    path: str | None = None
    hunk: tuple[str | None, int, int] | None = None
    added: list[str] = []

    def flush() -> None:
        if hunk is None:
            return
        filename, first, count = hunk
        if filename is not None and count and any(implementation_line(value) for value in added):
            files[filename].update(range(first, first + count))

    for line in diff.splitlines():
        if line.startswith("diff --git "):
            flush()
            hunk = None
            path = None
            added = []
        elif line.startswith("+++ "):
            candidate = line[4:]
            path = candidate[2:] if candidate.startswith("b/") and SOURCE.fullmatch(candidate[2:]) else None
        elif line.startswith("@@ "):
            flush()
            match = HUNK.match(line)
            if match is None:
                raise ValueError(f"Invalid unified diff hunk: {line}")
            hunk = (path, int(match[1]), int(match[2]) if match[2] is not None else 1)
            added = []
        elif hunk is not None and line.startswith("+"):
            added.append(line[1:])
    flush()
    return dict(files)


def implementation_line(value: str) -> bool:
    stripped = value.strip()
    return bool(stripped) and not (
        stripped.startswith(("//", "/*", "*/", "package ", "import "))
        or stripped == "*"
        or stripped.startswith("* ")
    )


def source_coverage(report: Path, paths: set[str]) -> dict[str, dict[int, tuple[int, int, int, int]]]:
    if not report.is_file():
        raise ValueError(f"Missing Kover XML report: {report}")
    root = ET.parse(report).getroot()
    if root.tag != "report":
        raise ValueError("Expected a JaCoCo-compatible report")
    expected = {}
    for path in paths:
        matched = SOURCE.fullmatch(path)
        if matched is None:
            raise ValueError(f"Unsupported production source path: {path}")
        module, relative = matched.groups()
        package, separator, name = relative.rpartition("/")
        if not separator:
            raise ValueError(f"Production source has no package directory: {path}")
        prefix = PACKAGE_ROOTS.get(module)
        if prefix is None or not (package == prefix or package.startswith(prefix + "/")):
            raise ValueError(f"Unexpected production source package: {path}")
        key = (package, name + Path(path).suffix)
        if key in expected:
            raise ValueError(f"Ambiguous production source names: {path} and {expected[key]}")
        expected[key] = path
    result: dict[str, dict[int, tuple[int, int, int, int]]] = {}
    for package in root.findall("package"):
        package_name = package.get("name")
        for source in package.findall("sourcefile"):
            path = expected.get((package_name, source.get("name")))
            if path is None:
                continue
            if path in result:
                raise ValueError(f"Duplicate source coverage: {path}")
            line_values = {}
            for line in source.findall("line"):
                nr = int(line.attrib["nr"])
                values = tuple(int(line.attrib[key]) for key in ("mi", "ci", "mb", "cb"))
                if nr < 1 or any(v < 0 for v in values) or sum(values[:2]) == 0 or nr in line_values:
                    raise ValueError(f"Invalid source coverage at {path}:{nr}")
                line_values[nr] = values
            result[path] = line_values
    return result


def check_coverage(report: Path, diff: str) -> dict[str, tuple[int, int]]:
    changes = changed_lines(diff)
    if not changes:
        print("No changed production Java/Kotlin lines")
        return {}
    covered = source_coverage(report, set(changes))
    totals = {"line": [0, 0], "branch": [0, 0]}
    failures = []
    for path, lines in sorted(changes.items()):
        if path not in covered:
            failures.append(f"Missing instrumented production source: {path}")
            continue
        per_file = {"line": [0, 0], "branch": [0, 0]}
        for nr in sorted(lines):
            counters = covered[path].get(nr)
            if counters is None:
                continue
            mi, ci, mb, cb = counters
            per_file["line"][0] += int(ci > 0)
            per_file["line"][1] += 1
            per_file["branch"][0] += cb
            per_file["branch"][1] += cb + mb
        if per_file["line"][1] == 0:
            failures.append(f"No measurable changed source lines: {path}")
        for metric, (found, count) in per_file.items():
            totals[metric][0] += found
            totals[metric][1] += count
            if count and 100 * found <= MINIMUM * count:
                failures.append(f"{path}: changed {metric} coverage must exceed {MINIMUM}% ({found}/{count})")
    for metric, (found, count) in totals.items():
        if count:
            percent = 100 * found / count
            print(f"Changed {metric} coverage: {found}/{count} ({percent:.2f}%)")
            if 100 * found <= MINIMUM * count:
                failures.append(f"Changed {metric} coverage must exceed {MINIMUM}%")
        else:
            print(f"Changed {metric} coverage: not measurable")
    if failures:
        raise ValueError("; ".join(failures))
    return {key: tuple(value) for key, value in totals.items()}


def main() -> None:
    parser = argparse.ArgumentParser(description="Reject insufficient coverage of changed Java/Kotlin implementation")
    parser.add_argument("report", type=Path)
    parser.add_argument("base", help="Pull request base commit")
    parser.add_argument("head", nargs="?", default="HEAD", help="Commit actually checked out and tested")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.base):
        parser.error("base must be a full commit SHA")
    diff = subprocess.run(
        ["git", "diff", "--no-ext-diff", "--no-renames", "--unified=0", f"{args.base}...{args.head}", "--"],
        text=True,
        capture_output=True,
        check=True,
    ).stdout
    check_coverage(args.report, diff)


if __name__ == "__main__":
    main()
