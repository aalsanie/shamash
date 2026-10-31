# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("coverage_summary", SCRIPTS / "coverage-summary.py")
coverage_summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(coverage_summary)


class CoverageSummaryTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.report = Path(work.name) / "report.xml"

    def write_report(self, *, missing=None, duplicate=False, extra=None, mismatch=False):
        root = ET.Element("report", name="Shamash")
        count = 0
        for module, package in coverage_summary.MODULE_PACKAGES.items():
            if module == missing:
                continue
            entry = ET.SubElement(root, "package", name=package)
            ET.SubElement(entry, "counter", type="LINE", covered="8", missed="2")
            ET.SubElement(entry, "counter", type="BRANCH", covered="3", missed="1")
            count += 1
            if duplicate and count == 1:
                second = ET.SubElement(root, "package", name=package)
                ET.SubElement(second, "counter", type="LINE", covered="8", missed="2")
                ET.SubElement(second, "counter", type="BRANCH", covered="3", missed="1")
        if extra is not None:
            entry = ET.SubElement(root, "package", name=extra)
            ET.SubElement(entry, "counter", type="LINE", covered="8", missed="2")
            ET.SubElement(entry, "counter", type="BRANCH", covered="3", missed="1")
        ET.SubElement(root, "counter", type="LINE", covered=str(count * 8 + int(mismatch)), missed=str(count * 2))
        ET.SubElement(root, "counter", type="BRANCH", covered=str(count * 3), missed=str(count))
        ET.ElementTree(root).write(self.report, encoding="unicode")

    def test_reports_all_modules_and_merged_totals(self):
        self.write_report()
        result = coverage_summary.coverage_report(self.report)
        self.assertEqual(set(coverage_summary.MODULE_PACKAGES), set(result["modules"]))
        self.assertEqual(48, result["total"]["line"]["covered"])
        self.assertEqual(60, result["total"]["line"]["total"])
        self.assertEqual(80.0, result["total"]["line"]["percent"])
        self.assertEqual(75.0, result["total"]["branch"]["percent"])

    def test_missing_module_is_not_silently_ignored(self):
        self.write_report(missing="shamash-psi-core")
        with self.assertRaisesRegex(ValueError, "Missing LINE coverage units"):
            coverage_summary.coverage_report(self.report)

    def test_unknown_package_is_rejected(self):
        self.write_report(extra="unexpected/production")
        with self.assertRaisesRegex(ValueError, "Unmapped"):
            coverage_summary.coverage_report(self.report)

    def test_duplicate_package_is_rejected(self):
        self.write_report(duplicate=True)
        with self.assertRaisesRegex(ValueError, "duplicate package"):
            coverage_summary.coverage_report(self.report)

    def test_inconsistent_root_and_package_totals_are_rejected(self):
        self.write_report(mismatch=True)
        with self.assertRaisesRegex(ValueError, "do not match"):
            coverage_summary.coverage_report(self.report)

    def test_missing_or_empty_report_fails(self):
        with self.assertRaisesRegex(ValueError, "Missing Kover XML"):
            coverage_summary.coverage_report(self.report)
        self.report.write_text('<report name="empty"><counter type="LINE" covered="0" missed="0"/>'
                               '<counter type="BRANCH" covered="0" missed="0"/></report>')
        with self.assertRaisesRegex(ValueError, "no production packages"):
            coverage_summary.coverage_report(self.report)

    def test_duplicate_or_negative_counters_are_rejected(self):
        entry = ET.Element("package")
        ET.SubElement(entry, "counter", type="LINE", covered="-1", missed="1")
        ET.SubElement(entry, "counter", type="BRANCH", covered="1", missed="1")
        with self.assertRaisesRegex(ValueError, "Negative LINE"):
            coverage_summary.counters(entry)
        ET.SubElement(entry, "counter", type="BRANCH", covered="1", missed="1")
        with self.assertRaisesRegex(ValueError, "Duplicate BRANCH"):
            coverage_summary.counters(entry)


if __name__ == "__main__":
    unittest.main()
