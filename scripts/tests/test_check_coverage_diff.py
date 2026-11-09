# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock
import sys
import contextlib
import io
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "check-coverage-diff.py"
spec = importlib.util.spec_from_file_location("check_coverage_diff", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
PATH = "shamash-asm-core/src/main/kotlin/io/shamash/asm/core/Example.kt"


def diff(hunk="@@ -1,4 +1,4 @@", path=PATH):
    return f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n{hunk}\n-old\n+new\n"


class CoverageDiffTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.report = Path(work.name) / "report.xml"

    def report_lines(self, *, path=PATH, entries=None, package=None, duplicate=False, invalid=None):
        root = ET.Element("report")
        package_name = package or path.split("/src/main/kotlin/")[-1].rsplit("/", 1)[0]
        node = ET.SubElement(root, "package", name=package_name)
        source = ET.SubElement(node, "sourcefile", name=path.rsplit("/", 1)[-1])
        for n, values in (entries or {1: (0, 1, 0, 2), 2: (0, 1, 0, 2), 3: (0, 1, 0, 2), 4: (0, 1, 0, 2)}).items():
            attrs = dict(zip(("mi", "ci", "mb", "cb"), values))
            ET.SubElement(source, "line", nr=str(n), **{k: str(v) for k, v in attrs.items()})
        if duplicate:
            ET.SubElement(node, "sourcefile", name=path.rsplit("/", 1)[-1])
        if invalid is not None:
            source.find("line").set("mi", str(invalid))
        ET.ElementTree(root).write(self.report, encoding="unicode")

    def test_change_line_selection_and_deletion_only_hunks(self):
        src = diff("@@ -3,2 +3,4 @@") + diff("@@ -9,1 +11,0 @@")
        self.assertEqual({PATH: {3, 4, 5, 6}}, checker.changed_lines(src))

    def test_ignores_non_production_changes(self):
        src = diff(path="shamash-asm-core/src/test/kotlin/io/shamash/asm/core/Example.kt")
        self.assertEqual({}, checker.changed_lines(src))
        self.assertEqual({}, checker.check_coverage(self.report, src))

    def test_exactly_ninety_percent_rejected(self):
        self.report_lines(entries={n: (int(n == 10), int(n != 10), int(n == 10), int(n != 10)) for n in range(1, 11)})
        with self.assertRaisesRegex(ValueError, "must exceed 90%"):
            checker.check_coverage(self.report, diff("@@ -1,10 +1,10 @@"))

    def test_above_ninety_percent_accepted(self):
        self.report_lines(entries={n: (int(n == 11), int(n != 11), int(n == 11), int(n != 11)) for n in range(1, 12)})
        result = checker.check_coverage(self.report, diff("@@ -1,11 +1,11 @@"))
        self.assertEqual((10, 11), result["line"])
        self.assertEqual((10, 11), result["branch"])

    def test_inadequate_branch_coverage_rejected_even_with_full_lines(self):
        self.report_lines(entries={1: (0, 2, 2, 0), 2: (0, 1, 0, 1)})
        with self.assertRaisesRegex(ValueError, "branch coverage"):
            checker.check_coverage(self.report, diff("@@ -1,2 +1,2 @@"))

    def test_branchless_implementation_requires_only_line_coverage(self):
        self.report_lines(entries={1: (0, 3, 0, 0)})
        result = checker.check_coverage(self.report, diff("@@ -1 +1 @@"))
        self.assertEqual((1, 1), result["line"])
        self.assertEqual((0, 0), result["branch"])

    def test_partial_line_counts_as_covered(self):
        self.report_lines(entries={1: (2, 1, 0, 2)})
        self.assertEqual((1, 1), checker.check_coverage(self.report, diff("@@ -1 +1 @@"))["line"])

    def test_unmapped_or_uninstrumented_source_fails_closed(self):
        self.report_lines(package="another/package")
        with self.assertRaisesRegex(ValueError, "Missing instrumented production source"):
            checker.check_coverage(self.report, diff("@@ -1 +1 @@"))
        self.report_lines(entries={3: (0, 1, 0, 0)})
        with self.assertRaisesRegex(ValueError, "No measurable changed source lines"):
            checker.check_coverage(self.report, diff("@@ -1 +1 @@"))

    def test_invalid_and_duplicate_coverage_fails_closed(self):
        self.report_lines(invalid=-1)
        with self.assertRaisesRegex(ValueError, "Invalid source coverage"):
            checker.check_coverage(self.report, diff("@@ -1 +1 @@"))
        self.report_lines(duplicate=True)
        with self.assertRaisesRegex(ValueError, "Duplicate source coverage"):
            checker.check_coverage(self.report, diff("@@ -1 +1 @@"))

    def test_bad_diff_rejected(self):
        with self.assertRaisesRegex(ValueError, "Invalid unified diff"):
            checker.changed_lines("@@ broken @@")

    def test_missing_or_invalid_report_rejected(self):
        with self.assertRaisesRegex(ValueError, "Missing Kover"):
            checker.check_coverage(self.report, diff("@@ -1 +1 @@"))
        self.report.write_text("<invalid />")
        with self.assertRaisesRegex(ValueError, "Expected a JaCoCo"):
            checker.check_coverage(self.report, diff("@@ -1 +1 @@"))

    def test_real_git_diff_uses_measured_new_line_numbers(self):
        with tempfile.TemporaryDirectory() as path:
            repo = Path(path)
            src = repo / PATH
            src.parent.mkdir(parents=True)
            src.write_text("old\n")
            commands = [
                ["git", "init", "-q"],
                ["git", "config", "user.email", "test@example.invalid"],
                ["git", "config", "user.name", "Test"],
                ["git", "add", "-A"],
                ["git", "commit", "-qm", "baseline"],
            ]
            for cmd in commands:
                subprocess.run(cmd, cwd=repo, capture_output=True, check=True)
            base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
            src.write_text("new\nmore\n")
            subprocess.run(["git", "commit", "-qam", "new"], cwd=repo, check=True)
            out = subprocess.check_output(["git", "diff", "--no-ext-diff", "--no-renames", "--unified=0", f"{base}...HEAD", "--"], cwd=repo, text=True)
            self.assertEqual({PATH: {1, 2}}, checker.changed_lines(out))
            self.report_lines(entries={1: (0, 1, 0, 0), 2: (0, 1, 0, 0)})
            self.assertEqual((2, 2), checker.check_coverage(self.report, out)["line"])

    def test_comment_import_and_whitespace_only_are_not_implementation(self):
        comments = diff("@@ -1 +1,3 @@").replace("+new", "+// annotation\n+/** note */\n+import io.shamash.Bar")
        self.assertEqual({}, checker.changed_lines(comments))
        self.assertEqual({}, checker.changed_lines(diff("@@ -1 +1 @@").replace("+new", "+  ")))

    def test_kotlin_spread_expression_is_implementation(self):
        src = diff("@@ -1 +1 @@").replace("+new", "+*arguments")
        self.assertEqual({PATH: {1}}, checker.changed_lines(src))

    def test_only_deleted_production_lines_are_exempt(self):
        self.assertEqual({}, checker.changed_lines(diff("@@ -1 +1,0 @@").replace("+new\n", "")))

    def test_wrong_package_and_source_root_are_rejected(self):
        self.report_lines()
        wrong = "shamash-asm-core/src/main/kotlin/io/shamash/export/Example.kt"
        with self.assertRaisesRegex(ValueError, "Unexpected production source package"):
            checker.source_coverage(self.report, {wrong})
        with self.assertRaisesRegex(ValueError, "Unsupported production source path"):
            checker.source_coverage(self.report, {"not-a-production-source"})
        bad = "shamash-asm-core/src/main/java/Example.java"
        with self.assertRaisesRegex(ValueError, "no package directory"):
            checker.source_coverage(self.report, {bad})

    def test_same_name_in_both_language_roots_is_ambiguous(self):
        self.report_lines()
        alternate = PATH.replace("/kotlin/", "/java/")
        with self.assertRaisesRegex(ValueError, "Ambiguous production source names"):
            checker.source_coverage(self.report, {PATH, alternate})

    def test_bad_source_line_counters_are_rejected(self):
        self.report_lines(entries={1: (-1, 2, 0, 0)})
        with self.assertRaisesRegex(ValueError, "Invalid source coverage"):
            checker.check_coverage(self.report, diff("@@ -1 +1 @@"))
        self.report_lines(entries={1: (0, 0, 0, 0)})
        with self.assertRaisesRegex(ValueError, "Invalid source coverage"):
            checker.check_coverage(self.report, diff("@@ -1 +1 @@"))

    def test_each_changed_file_must_pass_even_when_global_total_passes(self):
        second = "shamash-asm-core/src/main/kotlin/io/shamash/asm/core/Second.kt"
        self.report_lines(entries={1: (0, 1, 0, 2)})
        tree = ET.parse(self.report)
        node = tree.getroot().find("package")
        extra = ET.SubElement(node, "sourcefile", name="Second.kt")
        ET.SubElement(extra, "line", nr="1", mi="1", ci="0", mb="1", cb="0")
        tree.write(self.report)
        with self.assertRaisesRegex(ValueError, "Second.kt: changed line coverage"):
            checker.check_coverage(self.report, diff("@@ -1 +1 @@") + diff("@@ -1 +1 @@", second))

    def test_command_line_accepts_valid_commit_hash_and_tests_checkout(self):
        self.report_lines(entries={1: (0, 1, 0, 2)})
        result = SimpleNamespace(stdout=diff("@@ -1 +1 @@"))
        with mock.patch.object(sys, "argv", [str(SCRIPT), str(self.report), "a" * 40]), \
                mock.patch.object(checker.subprocess, "run", return_value=result) as git:
            checker.main()
        args = git.call_args.args[0]
        self.assertIn("--no-renames", args)
        self.assertNotIn("--ignore-all-space", args)
        self.assertNotIn("--ignore-blank-lines", args)
        self.assertIn("a" * 40 + "...HEAD", args)

    def test_bad_commit_hash_fails_before_git_diff(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT), str(self.report), "bad"]), \
                mock.patch.object(checker.subprocess, "run") as git, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as failure:
                checker.main()
        self.assertEqual(2, failure.exception.code)
        git.assert_not_called()


if __name__ == "__main__":
    unittest.main()
