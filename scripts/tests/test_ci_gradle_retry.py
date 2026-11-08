# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: AGPL-3.0-only

import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "ci-gradle-retry.py"
spec = importlib.util.spec_from_file_location("ci_gradle_retry", SCRIPT)
retry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retry)


class FakeProcess:
    def __init__(self, code, log):
        self.returncode = code
        self.stdout = io.StringIO(log)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.stdout.close()

    def wait(self):
        return self.returncode


class CiGradleRetryTest(unittest.TestCase):
    WRAPPER_504 = (
        "java.io.IOException: Server returned HTTP response code: 504 for URL: "
        "https://github.com/gradle/gradle-distributions/releases/download/v8.14.5/gradle-8.14.5-bin.zip"
    )
    KOTLIN_504 = (
        "Could not GET 'https://github.com/JetBrains/kotlin/releases/download/v2.2.21/"
        "kotlin-compiler-embeddable-2.2.21.jar'. Received status code 504 from server"
    )

    def test_gradle_wrapper_and_kotlin_release_504_are_retryable(self):
        self.assertTrue(retry.retryable_artifact_failure(self.WRAPPER_504))
        self.assertTrue(retry.retryable_artifact_failure(self.KOTLIN_504))

    def test_distributions_timeout_and_http_503_are_retryable(self):
        self.assertTrue(retry.retryable_artifact_failure(
            "Downloading from https://services.gradle.org/distributions/gradle-8.14.5-bin.zip failed: timeout (10000ms)"
        ))
        self.assertTrue(retry.retryable_artifact_failure(
            "HTTP response code: 503 for URL: https://downloads.gradle.org/distributions/gradle-8.14.5-bin.zip"
        ))

    def test_compiler_or_test_failures_never_retry(self):
        for log in (
            "e: Unresolved reference: Thing\nCompilation error",
            "42 tests completed, 2 failed\n" + self.KOTLIN_504,
            "Execution failed for task ':foo:spotlessKotlinCheck'",
            "HTTP response code: 500 for URL: https://github.com/gradle/gradle-distributions/releases/download/x",
            "HTTP response code: 504 for URL: https://example.com/file",
            "Downloading https://downloads.gradle.org/distributions/gradle.zip\nHTTP response code: 504 for URL: https://example.com/file",
        ):
            with self.subTest(log=log):
                self.assertFalse(retry.retryable_artifact_failure(log))

    def test_recovers_from_transient_network_failure(self):
        procs = [FakeProcess(1, self.WRAPPER_504), FakeProcess(0, "BUILD SUCCESSFUL\n")]
        with patch.object(retry.subprocess, "Popen", side_effect=procs) as popen, patch.object(retry.time, "sleep") as sleep, patch("builtins.print"):
            self.assertEqual(0, retry.run(["./gradlew", "test"]))
        self.assertEqual(2, popen.call_count)
        sleep.assert_called_once_with(5)

    def test_never_retries_real_build_failure(self):
        with patch.object(retry.subprocess, "Popen", return_value=FakeProcess(1, "1 tests completed, 1 failed")) as popen, patch.object(retry.time, "sleep") as sleep, patch("builtins.print"):
            self.assertEqual(1, retry.run(["./gradlew", "test"]))
        popen.assert_called_once()
        sleep.assert_not_called()

    def test_fails_if_every_download_attempt_fails(self):
        with patch.object(retry.subprocess, "Popen", side_effect=[FakeProcess(1, self.KOTLIN_504) for _ in range(4)]) as popen, patch.object(retry.time, "sleep") as sleep, patch("builtins.print"):
            self.assertEqual(1, retry.run(["./gradlew", "test"]))
        self.assertEqual(4, popen.call_count)
        self.assertEqual([5, 15, 30], [x.args[0] for x in sleep.call_args_list])


if __name__ == "__main__":
    unittest.main()
