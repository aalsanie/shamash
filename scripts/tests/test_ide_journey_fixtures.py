# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: AGPL-3.0-only

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

FIXTURES = (
    Path(__file__).resolve().parents[2]
    / "shamash-intellij-plugin"
    / "src"
    / "integrationTest"
    / "fixtures"
)
EXPECTED = {"acyclic", "empty", "java", "kotlin", "mixed", "multi-module", "partial"}


def project_path(project, value):
    path = value.removeprefix("file://")
    prefix = "$PROJECT_DIR$"
    if path != prefix and not path.startswith(prefix + "/"):
        raise ValueError(f"Path must be relative to the project: {value}")
    resolved = (project / path[len(prefix):].lstrip("/")).resolve()
    if not resolved.is_relative_to(project.resolve()):
        raise ValueError(f"Path escapes the project: {value}")
    return resolved


class IdeJourneyFixtureTest(unittest.TestCase):
    def test_source_roots_are_within_their_own_projects(self):
        projects = sorted(folder for folder in FIXTURES.iterdir() if folder.is_dir())
        self.assertEqual(EXPECTED, {folder.name for folder in projects})
        for project in projects:
            with self.subTest(project=project.name):
                metadata = ET.parse(project / ".idea" / "modules.xml").getroot()
                modules = metadata.findall(".//module")
                self.assertEqual(2 if project.name == "multi-module" else 1, len(modules))
                source_files = 0
                for module in modules:
                    module_file = project_path(project, module.attrib["filepath"])
                    self.assertEqual(module_file, project_path(project, module.attrib["fileurl"]))
                    self.assertEqual(project / ".idea", module_file.parent)
                    self.assertTrue(module_file.is_file())
                    configuration = ET.parse(module_file).getroot()
                    content_roots = configuration.findall(".//content")
                    self.assertEqual(1, len(content_roots))
                    for content in content_roots:
                        content_root = project_path(project, content.attrib["url"])
                        self.assertTrue(content_root.is_dir())
                        self.assertTrue(content_root == project or content_root.parent == project)
                        for folder in content.findall("sourceFolder"):
                            source_root = project_path(project, folder.attrib["url"])
                            self.assertTrue(source_root == content_root or content_root in source_root.parents)
                            self.assertEqual("false", folder.attrib["isTestSource"])
                            if source_root.is_dir():
                                source_files += sum(1 for _ in source_root.rglob("*.java"))
                                source_files += sum(1 for _ in source_root.rglob("*.kt"))
                self.assertEqual(project.name != "empty", source_files > 0)

    def test_rejects_parent_and_ambiguous_module_paths(self):
        project = FIXTURES / "java"
        for invalid in (
            "file://$MODULE_DIR$/..",
            "file://$PROJECT_DIR$/../outside",
            "file://$PROJECT_DIR$-other",
        ):
            with self.subTest(path=invalid), self.assertRaises(ValueError):
                project_path(project, invalid)


if __name__ == "__main__":
    unittest.main()
