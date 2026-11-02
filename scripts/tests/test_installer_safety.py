"""Offline integrity, ownership and rollback tests for both platform installers."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from io import BytesIO

ROOT = Path(__file__).resolve().parents[2]
VERSION = "0.92.0"
DIST = f"shamash-cli-{VERSION}"


def archive(*, missing=False, traversal=False, symlink=False, reserved=False):
    stream = BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr(f"{DIST}/bin/shamash", "#!/bin/sh\n")
        if not missing:
            z.writestr(f"{DIST}/bin/shamash.bat", "@echo off\r\n")
            z.writestr(f"{DIST}/lib/app.jar", "jar")
        if traversal:
            z.writestr(f"{DIST}/../../escape.txt", "bad")
        if reserved:
            z.writestr(f"{DIST}/lib/CON.jar", "bad")
            z.writestr(f"{DIST}/lib/stream:payload.jar", "bad")
        if symlink:
            entry = zipfile.ZipInfo(f"{DIST}/lib/link.jar")
            entry.create_system = 3
            entry.external_attr = 0o120777 << 16
            z.writestr(entry, "../../escape")
    return stream.getvalue()


class InstallerTests:
    windows = False

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="shamash installer test ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.bin = self.root / "fakebin"
        self.bin.mkdir()
        curl = self.bin / "curl"
        curl.write_text("""#!/bin/sh
set -eu
out=''
url=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output) out=$2; shift 2 ;;
    https://*) url=$1; shift ;;
    *) shift ;;
  esac
done
name=$(basename "$url")
[ -f "$SHAMASH_TEST_ASSETS/$name" ] || exit 22
cp "$SHAMASH_TEST_ASSETS/$name" "$out"
""")
        curl.chmod(0o755)
        self.release()
        self.dest = self.root / "destination with spaces"
        self.env = os.environ.copy()
        self.env["SHAMASH_TEST_ASSETS"] = str(self.assets)
        self.env["PATH"] = str(self.bin) + os.pathsep + self.env.get("PATH", "")

    def release(self, data=None, checksum=True):
        data = archive() if data is None else data
        (self.assets / f"{DIST}.zip").write_bytes(data)
        digest = hashlib.sha256(data).hexdigest() if checksum else "0" * 64
        (self.assets / "SHA256SUMS.txt").write_text(f"{digest}  {DIST}.zip\n")

    def invoke(self, version=VERSION, dest=None):
        dest = self.dest if dest is None else Path(dest)
        if not self.windows:
            cmd = ["bash", str(ROOT / "scripts/install-shamash.sh"), version, str(dest)]
            env = self.env
        else:
            shell = shutil.which("pwsh") or shutil.which("powershell")
            if shell is None:
                self.skipTest("PowerShell unavailable")
            wrapper = self.root / "run.ps1"
            installer = str(ROOT / "scripts/install-shamash.ps1").replace("'", "''")
            wrapper.write_text("""
$ErrorActionPreference = 'Stop'
function global:Invoke-WebRequest {
  param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing)
  $name = [IO.Path]::GetFileName(([uri]$Uri).AbsolutePath)
  $file = Join-Path $env:SHAMASH_TEST_ASSETS $name
  if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Offline file missing' }
  Copy-Item -LiteralPath $file -Destination $OutFile
}
& '""" + installer + """' -Version $env:SHAMASH_TEST_VERSION -Destination $env:SHAMASH_TEST_DEST
""")
            cmd = [shell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
                   "Bypass", "-File", str(wrapper)]
            env = self.env.copy()
            env["SHAMASH_TEST_VERSION"] = version
            env["SHAMASH_TEST_DEST"] = str(dest)
        return subprocess.run(cmd, cwd=self.root, env=env, capture_output=True,
                              text=True, timeout=30)

    def owned(self):
        self.dest.mkdir(parents=True, exist_ok=True)
        (self.dest / ".shamash-install").write_text(f"shamash-cli:{VERSION}\n")
        (self.dest / "sentinel").write_text("old")

    def unchanged(self):
        self.assertEqual("old", (self.dest / "sentinel").read_text())
        self.assertFalse(list(self.root.glob(".shamash-previous-*")))
        self.assertFalse(list(self.root.glob(".shamash-stage-*")))
        self.assertFalse((self.root / "escape.txt").exists())

    def test_new_and_owned_replacement(self):
        first = self.invoke()
        self.assertEqual(0, first.returncode, first.stderr)
        launcher = "shamash.bat" if self.windows else "shamash"
        self.assertTrue((self.dest / DIST / "bin" / launcher).is_file())
        self.assertEqual(f"shamash-cli:{VERSION}\n",
                         (self.dest / ".shamash-install").read_text())
        (self.dest / "sentinel").write_text("old")
        second = self.invoke()
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertFalse((self.dest / "sentinel").exists())

    def test_hostile_versions(self):
        self.owned()
        for version in ["", "1", "v1.2.3", "../1.2.3", "1.2.3/..",
                        "1.2.3'\"; touch pwned", "1.2.3$(touch pwned)",
                        "1.2.3\ncommand", "01.2.3", "1.2.3 ", " 1.2.3"]:
            with self.subTest(version=version):
                self.assertNotEqual(0, self.invoke(version).returncode)
                self.unchanged()
        self.assertFalse((self.root / "pwned").exists())

    def test_unowned_destination(self):
        self.dest.mkdir()
        (self.dest / "user-file").write_text("user")
        self.assertNotEqual(0, self.invoke().returncode)
        self.assertEqual("user", (self.dest / "user-file").read_text())

    def test_bad_ownership_marker(self):
        self.owned()
        (self.dest / ".shamash-install").write_text("unknown")
        self.assertNotEqual(0, self.invoke().returncode)
        self.unchanged()

    def test_download_and_checksum_failures(self):
        for kind in ("download", "checksum", "missing_checksum"):
            with self.subTest(kind=kind):
                self.owned() if not self.dest.exists() else None
                self.release()
                if kind == "download":
                    (self.assets / f"{DIST}.zip").unlink()
                elif kind == "checksum":
                    self.release(checksum=False)
                else:
                    (self.assets / "SHA256SUMS.txt").write_text("0" * 64 + "  other.zip\n")
                self.assertNotEqual(0, self.invoke().returncode)
                self.unchanged()

    def test_archive_validation_failures(self):
        for name, payload in (("corrupt", b"not zip"),
                              ("layout", archive(missing=True)),
                              ("traversal", archive(traversal=True)),
                              ("reserved", archive(reserved=True)),
                              ("symlink", archive(symlink=True))):
            with self.subTest(name=name):
                self.owned() if not self.dest.exists() else None
                self.release(data=payload)
                self.assertNotEqual(0, self.invoke().returncode)
                self.unchanged()

    def test_refuses_home_project_and_root(self):
        for path in (self.root, self.root.parent, Path.home(),
                     Path.home().parent, Path.home().anchor):
            with self.subTest(path=str(path)):
                self.assertNotEqual(0, self.invoke(dest=path).returncode)


@unittest.skipIf(os.name == "nt", "Unix tests run on Linux and macOS")
class UnixInstallerTest(InstallerTests, unittest.TestCase):
    def test_symlink_destination(self):
        real = self.root / "real"
        real.mkdir()
        (real / "user").write_text("keep")
        link = self.root / "link"
        link.symlink_to(real, target_is_directory=True)
        self.assertNotEqual(0, self.invoke(dest=link).returncode)
        self.assertEqual("keep", (real / "user").read_text())

    def test_activation_failure_restores_old_install(self):
        self.owned()
        move = self.bin / "mv"
        move.write_text("""#!/bin/sh
case "$2" in */.shamash-stage-*) exit 23 ;; esac
exec /bin/mv "$@"
""")
        move.chmod(0o755)
        self.assertNotEqual(0, self.invoke().returncode)
        self.unchanged()


@unittest.skipUnless(os.name == "nt", "Windows installer runs on Windows CI")
class WindowsInstallerTest(InstallerTests, unittest.TestCase):
    windows = True


class ActionInputTest(unittest.TestCase):
    def test_version_is_data_not_executable_shell_content(self):
        action = (ROOT / "action.yml").read_text()
        injection = "$" + "{{ inputs.version }}"
        self.assertIn("SHAMASH_VERSION: " + injection, action)
        for script in action.split("run: |")[1:]:
            self.assertNotIn(injection, script.split("\n    - name:")[0])


if __name__ == "__main__":
    unittest.main()
