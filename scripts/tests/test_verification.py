# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: AGPL-3.0-only

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), SCRIPTS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


publications = load("verify-publications")
plugin_tests = load("verify-plugin-tests")


class SignatureVerificationTest(unittest.TestCase):
    PRIMARY = "A" * 40
    SUBKEY = "B" * 40

    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.artifact = Path(self.work.name) / "core.jar"
        self.artifact.write_bytes(b"candidate")
        self.signature = self.artifact.with_name("core.jar.asc")
        self.signature.write_text("detached-signature")

    def status(self, signer=None, primary=None):
        return f"[GNUPG:] VALIDSIG {signer or self.PRIMARY} 2026-09-03 1 0 4 0 1 10 00 {primary or self.PRIMARY}\n"

    def verify_status(self, status, code=0):
        response = subprocess.CompletedProcess([], code, stdout=status, stderr="")
        with patch.object(publications.subprocess, "run", return_value=response):
            publications.verify_signature(self.artifact, self.PRIMARY)

    def test_accepts_primary_and_its_signing_subkey(self):
        self.verify_status(self.status())
        self.verify_status(self.status(self.SUBKEY, self.PRIMARY))

    def test_rejects_unrelated_key(self):
        with self.assertRaisesRegex(ValueError, "Unexpected signing key"):
            self.verify_status(self.status(self.SUBKEY, self.SUBKEY))

    def test_rejects_expired_revoked_and_failed_signatures(self):
        for keyword in ("EXPSIG", "EXPKEYSIG", "REVKEYSIG", "BADSIG", "ERRSIG", "NO_PUBKEY"):
            with self.subTest(keyword=keyword), self.assertRaises(ValueError):
                self.verify_status(self.status() + f"[GNUPG:] {keyword} key\n")
        with self.assertRaises(ValueError):
            self.verify_status(self.status(), code=1)

    def test_rejects_missing_or_multiple_valid_signatures(self):
        for status in ("", "[GNUPG:] GOODSIG key name\n", self.status() * 2):
            with self.subTest(status=status), self.assertRaises(ValueError):
                self.verify_status(status)

    def test_requires_full_fingerprint_and_nonempty_signature(self):
        with self.assertRaises(ValueError):
            publications.verify_signature(self.artifact, "12345678")
        self.signature.write_bytes(b"")
        with self.assertRaises(ValueError):
            publications.verify_signature(self.artifact, self.PRIMARY)
        self.signature.unlink()
        with self.assertRaises(ValueError):
            publications.verify_signature(self.artifact, self.PRIMARY)


class PluginDiscoveryVerificationTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.source = self.root / "source"
        self.results = self.root / "results"
        self.source.mkdir()
        self.results.mkdir()
        (self.source / "ExampleTest.kt").write_text(
            "package sample\n\nclass ExampleTest : TestCase() {\n    fun testRuns() {}\n}\n"
        )

    def report(self, method="testRuns", status=""):
        (self.results / "TEST-sample.ExampleTest.xml").write_text(
            '<testsuite name="sample.ExampleTest" tests="1" failures="0" errors="0" skipped="0">'
            f'<testcase name="{method}">{status}</testcase></testsuite>'
        )

    def test_accepts_executed_test(self):
        self.report()
        plugin_tests.verify(self.source, self.results)

    def test_rejects_missing_class(self):
        with self.assertRaisesRegex(ValueError, "did not run"):
            plugin_tests.verify(self.source, self.results)

    def test_junit5_annotation_discovery_requires_executed_methods(self):
        (self.source / "ExampleTest.kt").write_text(
            "package sample\n\nclass ExampleTest {\n"
            "    @Test\n    fun firstRun() {}\n"
            "    fun helper() {}\n}\n",
            encoding="utf-8",
        )
        self.report(method="firstRun()")
        plugin_tests.verify(self.source, self.results, junit5=True)
        self.report(method="helper()")
        with self.assertRaisesRegex(ValueError, "discovery mismatch"):
            plugin_tests.verify(self.source, self.results, junit5=True)

    def test_junit5_missing_or_failed_execution_fails_closed(self):
        (self.source / "ExampleTest.kt").write_text(
            "package sample\nclass ExampleTest {\n    @Test\n    fun firstRun() {}\n}\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "did not run"):
            plugin_tests.verify(self.source, self.results, junit5=True)
        for status in ("<skipped/>", "<failure/>", "<error/>"):
            self.report(method="firstRun()", status=status)
            with self.subTest(status=status), self.assertRaises(ValueError):
                plugin_tests.verify(self.source, self.results, junit5=True)


    def test_junit5_essential_suite_runs_only_tagged_methods(self):
        (self.source / "ExampleTest.kt").write_text(
            "package sample\nclass ExampleTest {\n"
            "    @Tag(\"essential\")\n    @Test\n    fun smoke() {}\n"
            "    @Test\n    fun fullJourney() {}\n}\n",
            encoding="utf-8",
        )
        self.report(method="smoke()")
        plugin_tests.verify(self.source, self.results, junit5=True, essential_only=True)
        self.report(method="fullJourney()")
        with self.assertRaisesRegex(ValueError, "discovery mismatch"):
            plugin_tests.verify(self.source, self.results, junit5=True, essential_only=True)

    def test_rejects_missing_method(self):
        self.report(method="testSomethingElse")
        with self.assertRaisesRegex(ValueError, "discovery mismatch"):
            plugin_tests.verify(self.source, self.results)

    def test_rejects_skipped_or_failed_case_even_with_zero_suite_count(self):
        for status in ("<skipped/>", "<failure/>", "<error/>"):
            self.report(status=status)
            with self.subTest(status=status), self.assertRaises(ValueError):
                plugin_tests.verify(self.source, self.results)


class PublicationLicensingTest(unittest.TestCase):
    def test_source_url_uses_exact_commit_and_fallback(self):
        commit = "a" * 40
        complete = subprocess.CompletedProcess([], 0, stdout=commit + "\n", stderr="")
        with patch.object(publications.subprocess, "run", return_value=complete):
            self.assertEqual(
                f"https://github.com/aalsanie/shamash/tree/{commit}",
                publications.source_url("1.0.0"),
            )
        for result in (
            subprocess.CompletedProcess([], 1, stdout=commit, stderr=""),
            subprocess.CompletedProcess([], 0, stdout="not-a-commit", stderr=""),
        ):
            with patch.object(publications.subprocess, "run", return_value=result):
                self.assertEqual(publications.SOURCE_BASE + "1.0.0", publications.source_url("1.0.0"))
        with patch.object(publications.subprocess, "run", side_effect=OSError()):
            self.assertEqual(publications.SOURCE_BASE + "1.0.0", publications.source_url("1.0.0"))

    def test_pom_license(self):
        def make(name, url):
            return ET.fromstring(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license>'
                f'<name>{name}</name><url>{url}</url></license></licenses></project>'
            )
        publications.verify_license_pom(make(publications.LICENSE_NAME, publications.LICENSE_URL))
        for name, url in (("Apache-2.0", publications.LICENSE_URL), (publications.LICENSE_NAME, "wrong")):
            with self.subTest(name=name, url=url), self.assertRaises(ValueError):
                publications.verify_license_pom(make(name, url))

    def test_archive_license_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.jar"
            version, expected = "1.0.0", b"sample AGPL"
            def check(license_bytes=expected, source=None, notices=b"copyright notice", missing=None):
                entries = {
                    "META-INF/LICENSE": license_bytes,
                    "META-INF/SOURCE": (source or publications.source_url(version)).encode(),
                    "META-INF/THIRD_PARTY_NOTICES.md": notices,
                    "META-INF/LICENSES/ASM-BSD-3-Clause.txt": b"BSD license",
                    "META-INF/LICENSES/SLF4J-MIT.txt": b"MIT license",
                }
                with zipfile.ZipFile(path, "w") as archive:
                    for name, data in entries.items():
                        if name != missing:
                            archive.writestr(name, data)
                with zipfile.ZipFile(path) as archive:
                    publications.verify_license_payload(archive, version, expected)
            check()
            for scenario in (
                {"missing": "META-INF/LICENSE"},
                {"missing": "META-INF/SOURCE"},
                {"missing": "META-INF/THIRD_PARTY_NOTICES.md"},
                {"missing": "META-INF/LICENSES/ASM-BSD-3-Clause.txt"},
                {"missing": "META-INF/LICENSES/SLF4J-MIT.txt"},
                {"license_bytes": b"wrong"},
                {"source": "https://example.invalid"},
                {"notices": b""},
            ):
                with self.subTest(**scenario), self.assertRaises(ValueError):
                    check(**scenario)


class SourceLicenseTest(unittest.TestCase):
    def test_module_source_headers(self):
        root = SCRIPTS.parent
        for module in ("shamash-artifacts", "shamash-export", "shamash-asm-core", "shamash-psi-core", "shamash-cli"):
            for source in (root / module / "src").rglob("*"):
                if source.suffix in (".kt", ".java"):
                    self.assertIn(
                        "SPDX-License-Identifier: AGPL-3.0-only",
                        source.read_text(encoding="utf-8").split("*/", 1)[0],
                        str(source),
                    )
        for source in (root / "shamash-intellij-plugin" / "src").rglob("*.kt"):
            self.assertIn(
                "Licensed under the Apache License",
                source.read_text(encoding="utf-8").split("*/", 1)[0],
                str(source),
            )


if __name__ == "__main__":
    unittest.main()
