# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

from pathlib import Path
import re
import xml.etree.ElementTree as ET

from verification_support import ROOT


def expected_tests(source_root: Path) -> dict[str, set[str]]:
    expected = {}
    for path in source_root.rglob("*.kt"):
        source = path.read_text(encoding="utf-8")
        methods = set(re.findall(r"^\s+fun (test\w+)\s*\(", source, re.MULTILINE))
        if not methods:
            continue
        package = re.search(r"^package ([\w.]+)$", source, re.MULTILINE)
        classes = re.findall(r"^class (\w+)", source, re.MULTILINE)
        if package is None or len(classes) != 1:
            raise ValueError(f"Cannot determine test class: {path}")
        expected[package[1] + "." + classes[0]] = methods
    if not expected:
        raise ValueError("No IntelliJ test methods found in source")
    return expected


def verify(source_root: Path, results_root: Path) -> None:
    expected = expected_tests(source_root)
    reports = {}
    for path in results_root.glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        name = suite.get("name")
        if name in reports:
            raise ValueError(f"Duplicate test report: {name}")
        reports[name] = suite
    for name, methods in sorted(expected.items()):
        suite = reports.get(name)
        if suite is None:
            raise ValueError(f"IntelliJ test class did not run: {name}")
        actual = {case.get("name") for case in suite.findall("testcase")}
        if actual != methods or int(suite.get("tests", "0")) != len(methods):
            raise ValueError(f"Test discovery mismatch for {name}: expected {sorted(methods)}, got {sorted(actual)}")
        for status in ("failures", "errors", "skipped"):
            if int(suite.get(status, "0")) != 0:
                raise ValueError(f"IntelliJ test report has {status}: {name}")
        if any(case.find(status) is not None for case in suite.findall("testcase") for status in ("failure", "error", "skipped")):
            raise ValueError(f"IntelliJ test case did not pass: {name}")
    print(f"Verified execution of {sum(map(len, expected.values()))} IntelliJ tests across {len(expected)} classes.")


if __name__ == "__main__":
    module = ROOT / "shamash-intellij-plugin"
    verify(module / "src/test/kotlin", module / "build/test-results/test")
