# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile

from verification_support import MODULES, ROOT, gradle, run


def extract_source(archive: Path, destination: Path) -> None:
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            name = PurePosixPath(entry.filename)
            mode = entry.external_attr >> 16
            if name.is_absolute() or ".." in name.parts or stat.S_ISLNK(mode):
                raise ValueError(f"Unsupported archive entry: {entry.filename}")
            path = destination.joinpath(*name.parts)
            if entry.is_dir():
                path.mkdir(parents=True, exist_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(source.read(entry))


def initialize(destination: Path) -> None:
    if destination.exists():
        raise ValueError("API baseline already exists; review intentional API changes before replacing it")
    baseline = (ROOT / "verification/api-baseline.txt").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", baseline):
        raise ValueError("API baseline must be a full immutable Git commit hash")
    kind = subprocess.check_output(["git", "cat-file", "-t", baseline], cwd=ROOT, text=True).strip()
    if kind != "commit":
        raise ValueError("API baseline does not identify a Git commit")
    run(["git", "merge-base", "--is-ancestor", baseline, "HEAD"])
    with tempfile.TemporaryDirectory(prefix="shamash-abi-") as directory:
        work = Path(directory)
        archive = work / "source.zip"
        source = work / "source"
        reference = source / "verification" / "api"
        run(["git", "archive", "--format=zip", f"--output={archive}", baseline])
        extract_source(archive, source)
        build_file = source / "build.gradle.kts"
        build = build_file.read_text(encoding="utf-8")
        start_marker = "// BEGIN LIBRARY ABI: reused when generating the first reviewed API snapshot."
        end_marker = "// END LIBRARY ABI"
        current_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        if current_build.count(start_marker) != 1 or current_build.count(end_marker) != 1:
            raise ValueError("Cannot locate the shared library ABI configuration block")
        block = current_build.split(start_marker)[1].split(end_marker)[0]
        if "shamashAbiReferenceRoot" not in build:
            build_file.write_text(build + "\n" + block, encoding="utf-8")
        gradle(
            f"-PshamashAbiReferenceRoot={reference}",
            *(f":{module}:updateKotlinAbi" for module in MODULES),
            project=source,
        )
        hashes = {}
        for module in MODULES:
            dumps = [path for path in (reference / module).rglob("*") if path.is_file() and path.stat().st_size > 0]
            if not dumps:
                raise ValueError(f"Missing reference ABI dump: {module}")
            for path in dumps:
                relative = path.relative_to(reference)
                path.write_bytes(path.read_bytes().replace(b"\r\n", b"\n"))
                hashes[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
        (reference / "manifest.json").write_text(
            json.dumps({"baseline": baseline, "reference_sha256": hashes}, indent=2) + "\n",
            encoding="utf-8",
        )
        shutil.copytree(reference, destination)
    print(f"Initialized API snapshots from reviewed commit {baseline}. Include verification/api in this PR.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--initialize", action="store_true", help="Create the first checked-in baseline from api-baseline.txt")
    args = parser.parse_args()
    reference = ROOT / "verification/api"
    if args.initialize:
        initialize(reference)
    manifest_path = reference / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("API snapshots are missing. Run verify-library-abi.py --initialize locally and commit verification/api.")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected = manifest["reference_sha256"]
    if manifest["baseline"] != (ROOT / "verification/api-baseline.txt").read_text().strip():
        raise ValueError("API baseline commit does not match its manifest")
    actual = {}
    for module in MODULES:
        files = [path for path in (reference / module).rglob("*") if path.is_file()]
        if not files:
            raise ValueError(f"API snapshots missing for {module}")
        for path in files:
            actual[path.relative_to(reference).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError("API snapshots do not match the reviewed manifest")
    report_dir = ROOT / "build/reports/library-abi"
    report_dir.mkdir(parents=True, exist_ok=True)
    report = report_dir / "result.json"
    report.unlink(missing_ok=True)
    gradle(f"-PshamashAbiReferenceRoot={reference}", *(f":{module}:checkKotlinAbi" for module in MODULES))
    report.write_text(json.dumps({"status": "passed", **manifest}, indent=2) + "\n", encoding="utf-8")
    print("Library ABI matches the checked-in reviewed snapshots.")


if __name__ == "__main__":
    main()
