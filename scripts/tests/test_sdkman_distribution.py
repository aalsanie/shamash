import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DistributionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "shamash-cli-0.92.0.zip"
        self.sdkman = self.root / "shamash-sdkman-0.92.0.zip"
        self.package = load_script("package_sdkman")
        self.prepare = load_script("prepare_distroplane_sdkman")

    def fixture(self, extra=None):
        entries = {
            "shamash-cli-0.92.0/bin/shamash": b"#!/bin/sh\n",
            "shamash-cli-0.92.0/bin/shamash.bat": b"@echo off\r\n",
            "shamash-cli-0.92.0/lib/shamash.jar": b"jar",
        }
        if extra:
            entries.update(extra)
        with ZipFile(self.source, "w") as archive:
            for path, data in entries.items():
                archive.writestr(path, data)

    def test_preserves_launchers_and_dependencies_at_sdkman_root(self):
        self.fixture()
        self.package.package_sdkman(self.source, self.sdkman, "0.92.0")
        with ZipFile(self.sdkman) as archive:
            self.assertEqual(
                sorted(archive.namelist()),
                ["bin/shamash", "bin/shamash.bat", "lib/shamash.jar"],
            )
            self.assertEqual(archive.read("lib/shamash.jar"), b"jar")
            self.assertEqual(
                (archive.getinfo("bin/shamash").external_attr >> 16) & 0o777, 0o755
            )
        config_path = self.root / "distroplane.json"
        self.prepare.prepare("0.92.0", self.sdkman, config_path)
        config = json.loads(config_path.read_text())
        self.assertEqual(config["schemaVersion"], "1")
        self.assertEqual(config["targets"][0]["configuration"]["candidate"], "shamash")
        self.assertTrue(
            config["targets"][0]["configuration"]["url"].endswith(
                "/v0.92.0/shamash-sdkman-0.92.0.zip"
            )
        )

    def test_rejects_unexpected_archive_roots(self):
        self.fixture({"unrelated/payload": b"bad"})
        with self.assertRaises(ValueError):
            self.package.package_sdkman(self.source, self.sdkman, "0.92.0")
        self.assertFalse(self.sdkman.exists())

    def test_rejects_missing_runtime_dependencies(self):
        with ZipFile(self.source, "w") as archive:
            archive.writestr("shamash-cli-0.92.0/bin/shamash", b"#!/bin/sh")
            archive.writestr("shamash-cli-0.92.0/bin/shamash.bat", b"@echo off")
        with self.assertRaises(ValueError):
            self.package.package_sdkman(self.source, self.sdkman, "0.92.0")

    def test_rejects_version_mismatch(self):
        self.fixture()
        self.package.package_sdkman(self.source, self.sdkman, "0.92.0")
        with self.assertRaises(ValueError):
            self.prepare.prepare("0.93.0", self.sdkman, self.root / "config.json")

    def test_rejects_path_traversal(self):
        self.fixture({"shamash-cli-0.92.0/../payload": b"bad"})
        with self.assertRaises(ValueError):
            self.package.package_sdkman(self.source, self.sdkman, "0.92.0")


if __name__ == "__main__":
    unittest.main()
