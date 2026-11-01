# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

# Packages are disjoint; one merged Kover report includes cross-module test execution.
MODULE_PACKAGES = {
    "shamash-artifacts": "io/shamash/artifacts",
    "shamash-export": "io/shamash/export",
    "shamash-asm-core": "io/shamash/asm",
    "shamash-psi-core": "io/shamash/psi",
    "shamash-cli": "io/shamash/cli",
    "shamash-intellij-plugin": "io/shamash/intellij",
}
METRICS = ("LINE", "BRANCH")


def counters(element: ET.Element) -> dict[str, tuple[int, int]]:
    values: dict[str, tuple[int, int]] = {}
    for counter in element.findall("counter"):
        kind = counter.get("type")
        if kind not in METRICS:
            continue
        if kind in values:
            raise ValueError(f"Duplicate {kind} counter in {element.tag}")
        covered = int(counter.attrib["covered"])
        missed = int(counter.attrib["missed"])
        if covered < 0 or missed < 0:
            raise ValueError(f"Negative {kind} counter in {element.tag}")
        values[kind] = (covered, missed)
    if set(values) != set(METRICS):
        raise ValueError(f"Missing coverage counters in {element.tag}: {set(METRICS) - set(values)}")
    return values


def ownership(package: str) -> str:
    owners = [
        module
        for module, prefix in MODULE_PACKAGES.items()
        if package == prefix or package.startswith(prefix + "/")
    ]
    if len(owners) != 1:
        raise ValueError(f"Unmapped or ambiguous production package: {package!r}")
    return owners[0]


def coverage_report(path: Path) -> dict:
    if not path.is_file():
        raise ValueError(f"Missing Kover XML report: {path}")
    root = ET.parse(path).getroot()
    if root.tag != "report":
        raise ValueError(f"Expected JaCoCo report root, got {root.tag!r}")

    expected = counters(root)
    modules = {name: {kind: [0, 0] for kind in METRICS} for name in MODULE_PACKAGES}
    seen_packages: set[str] = set()
    for package_element in root.findall("package"):
        package = package_element.get("name")
        if not package or package in seen_packages:
            raise ValueError(f"Missing or duplicate package name: {package!r}")
        seen_packages.add(package)
        module = ownership(package)
        for kind, (covered, missed) in counters(package_element).items():
            modules[module][kind][0] += covered
            modules[module][kind][1] += missed

    if not seen_packages:
        raise ValueError("Kover report contains no production packages")

    totals = {
        kind: (
            sum(item[kind][0] for item in modules.values()),
            sum(item[kind][1] for item in modules.values()),
        )
        for kind in METRICS
    }
    if totals != expected:
        raise ValueError(f"Package counters do not match merged report totals: {totals} != {expected}")

    def summarize(counts: dict[str, tuple[int, int] | list[int]]) -> dict:
        result = {}
        for kind, (covered, missed) in counts.items():
            total = covered + missed
            if total == 0:
                raise ValueError(f"Missing {kind} coverage units")
            result[kind.lower()] = {
                "covered": covered,
                "missed": missed,
                "total": total,
                "percent": round(100.0 * covered / total, 2),
            }
        return result

    return {
        "schema_version": 1,
        "total": summarize(expected),
        "modules": {name: summarize(values) for name, values in modules.items()},
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate and summarize merged Kover line and branch coverage")
    parser.add_argument("xml", type=Path, help="Merged JaCoCo-compatible Kover XML report")
    parser.add_argument("--output", required=True, type=Path, help="Summary JSON artifact")
    args = parser.parse_args()

    report = coverage_report(args.xml)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print(f"{'Module':30} {'Lines':>8} {'Branches':>10}")
    print("-" * 52)
    for name, item in [*report["modules"].items(), ("TOTAL", report["total"])]:
        print(f"{name:30} {item['line']['percent']:7.2f}% {item['branch']['percent']:9.2f}%")


if __name__ == "__main__":
    main()
