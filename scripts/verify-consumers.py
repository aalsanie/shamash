# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import sys
import tempfile

from verification_support import ROOT, gradle, project_version, run

KOTLIN_VERSIONS = ("2.3.21", "2.4.10")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=ROOT / "build/test-maven-repository")
    args = parser.parse_args()
    repository = args.repository.resolve()
    version = project_version()
    maven = shutil.which("mvn")
    if maven is None:
        raise RuntimeError("Maven 3.9+ must be available as mvn on PATH")
    report = ROOT / "build/reports/library-consumers/result.json"
    report.unlink(missing_ok=True)
    run([sys.executable, str(ROOT / "scripts/verify-publications.py"), str(repository), version])
    with tempfile.TemporaryDirectory(prefix="shamash consumers ") as directory:
        work = Path(directory)
        consumers = work / "consumers"
        shutil.copytree(ROOT / "verification/consumers", consumers)
        settings = work / "settings.xml"
        settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n', encoding="utf-8")
        run([
            maven, "--batch-mode", "--no-transfer-progress", "--settings", str(settings), "--global-settings", str(settings),
            f"-Dmaven.repo.local={work / 'maven-cache'}", f"-Dshamash.version={version}",
            f"-Dshamash.repository.url={repository.as_uri()}", f"-Dshamash.repository.path={repository}",
            "compile", "exec:java",
        ], cwd=consumers / "maven-java")
        for kotlin_version in KOTLIN_VERSIONS:
            gradle(
                f"-PconsumerKotlinVersion={kotlin_version}", f"-PshamashVersion={version}",
                f"-PshamashRepository={repository.as_uri()}", f"-PshamashRepositoryPath={repository}",
                "--refresh-dependencies", "clean", "run", project=consumers / "kotlin-gradle", wrapper=ROOT,
            )
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(
            json.dumps({"status": "passed", "version": version, "java": 17, "kotlin": KOTLIN_VERSIONS}, indent=2) + "\n",
            encoding="utf-8",
        )
    print("Isolated Java/Maven and Kotlin/Gradle consumers passed.")


if __name__ == "__main__":
    main()
