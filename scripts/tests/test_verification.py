# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
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

    def test_rejects_missing_method(self):
        self.report(method="testSomethingElse")
        with self.assertRaisesRegex(ValueError, "discovery mismatch"):
            plugin_tests.verify(self.source, self.results)

    def test_rejects_skipped_or_failed_case_even_with_zero_suite_count(self):
        for status in ("<skipped/>", "<failure/>", "<error/>"):
            self.report(status=status)
            with self.subTest(status=status), self.assertRaises(ValueError):
                plugin_tests.verify(self.source, self.results)


if __name__ == "__main__":
    unittest.main()
