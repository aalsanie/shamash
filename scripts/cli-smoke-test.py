from __future__ import annotations

import glob
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def fail(message: str, output: str = "") -> None:
    if output:
        print(output, file=sys.stderr)
    raise SystemExit(message)


def run_cli(executable: Path, *args: str, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    if os.name == "nt":
        command_line = subprocess.list2cmdline([str(executable), *args])
        command = ["cmd.exe", "/d", "/s", "/c", command_line]
    else:
        command = [str(executable), *args]

    return subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: cli-smoke-test.py <shamash-cli.zip-glob>")

    matches = sorted(Path(path).resolve() for path in glob.glob(sys.argv[1]))
    if not matches:
        fail(f"CLI distribution not found: {sys.argv[1]}")

    zip_path = matches[0]
    if not zipfile.is_zipfile(zip_path):
        fail(f"Invalid CLI distribution ZIP: {zip_path}")

    with tempfile.TemporaryDirectory(prefix="shamash-smoke-") as temp_dir:
        temp = Path(temp_dir)
        with zipfile.ZipFile(zip_path) as archive:
            archive.extractall(temp)

        roots = sorted(path for path in temp.iterdir() if path.is_dir() and path.name.startswith("shamash-"))
        if len(roots) != 1:
            fail(f"Expected one extracted Shamash distribution, found {len(roots)}")

        root = roots[0]
        executable = root / "bin" / ("shamash.bat" if os.name == "nt" else "shamash")
        if not executable.is_file():
            fail(f"CLI launcher not found: {executable}")

        if os.name != "nt":
            executable.chmod(executable.stat().st_mode | stat.S_IXUSR)

        version = run_cli(executable, "version")
        if version.returncode != 0 or "shamash-cli" not in version.stdout:
            fail("Packaged CLI version command failed.", version.stdout)

        javac = shutil.which("javac")
        if javac is None:
            fail("javac not found")

        fixture = temp / "fixture"
        classes = fixture / "build" / "classes" / "java" / "main"
        classes.mkdir(parents=True)
        source = fixture / "App.java"
        source.write_text(
            "package com.example; public class App { public static void main(String[] args) {} }\n",
            encoding="utf-8",
        )

        compile_result = subprocess.run(
            [javac, "-d", str(classes), str(source)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        if compile_result.returncode != 0:
            fail("Smoke fixture compilation failed.", compile_result.stdout)

        scan = run_cli(executable, "scan", cwd=fixture)
        if scan.returncode != 0:
            fail("Packaged CLI discovery scan failed.", scan.stdout)

        output = scan.stdout.lower()
        if "discovery scan" not in output:
            fail("Packaged CLI output did not identify a discovery scan.", scan.stdout)
        if "classes scanned" not in output:
            fail("Packaged CLI output did not report scanned classes.", scan.stdout)
        if (fixture / "shamash").exists() or (fixture / ".shamash").exists():
            fail("Configless discovery scan mutated the fixture project.")

    print("Packaged CLI smoke test passed.")


if __name__ == "__main__":
    main()
