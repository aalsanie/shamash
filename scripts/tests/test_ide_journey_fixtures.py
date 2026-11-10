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
EXPECTED_FILES = {
    "acyclic": 2,
    "empty": 0,
    "java": 2,
    "kotlin": 2,
    "mixed": 2,
    "multi-module": 3,
    "partial": 1,
}


def project_path(project, value, module_file=None):
    path = value.removeprefix("file://")
    prefix = "$PROJECT_DIR$" if module_file is None else "$MODULE_DIR$"
    base = project if module_file is None else module_file.parent
    if path != prefix and not path.startswith(prefix + "/"):
        raise ValueError(f"Unexpected path macro or location: {value}")
    suffix = path[len(prefix):].lstrip("/")
    if ".." in Path(suffix).parts or "$" in suffix or "\\" in suffix:
        raise ValueError(f"Invalid path: {value}")
    resolved = (base / suffix).resolve()
    if not resolved.is_relative_to(project.resolve()):
        raise ValueError(f"Path escapes the project: {value}")
    return resolved


class IdeJourneyFixtureTest(unittest.TestCase):
    def test_source_roots_are_inside_their_modules(self):
        projects = sorted(folder for folder in FIXTURES.iterdir() if folder.is_dir())
        self.assertEqual(set(EXPECTED_FILES), {folder.name for folder in projects})
        for project in projects:
            with self.subTest(project=project.name):
                metadata = ET.parse(project / ".idea" / "modules.xml").getroot()
                modules = metadata.findall(".//module")
                self.assertEqual(2 if project.name == "multi-module" else 1, len(modules))
                source_files = set()
                for module in modules:
                    module_file = project_path(project, module.attrib["filepath"])
                    self.assertEqual(module_file, project_path(project, module.attrib["fileurl"]))
                    self.assertTrue(module_file.is_file())
                    if project.name == "multi-module":
                        self.assertEqual(project / module_file.stem / module_file.name, module_file)
                    else:
                        self.assertEqual(project / "fixture.iml", module_file)
                    configuration = ET.parse(module_file).getroot()
                    content_roots = configuration.findall(".//content")
                    self.assertEqual(1, len(content_roots))
                    for content in content_roots:
                        content_root = project_path(project, content.attrib["url"], module_file)
                        self.assertEqual(module_file.parent, content_root)
                        self.assertTrue(content_root.is_dir())
                        for folder in content.findall("sourceFolder"):
                            source_root = project_path(project, folder.attrib["url"], module_file)
                            self.assertIn(source_root, (
                                content_root / "src/main/java",
                                content_root / "src/main/kotlin",
                            ))
                            self.assertEqual("false", folder.attrib["isTestSource"])
                            if source_root.is_dir():
                                source_files.update(source_root.rglob("*.java"))
                                source_files.update(source_root.rglob("*.kt"))
                self.assertEqual(EXPECTED_FILES[project.name], len(source_files))

    def test_rejects_wrong_macros_and_parent_directories(self):
        project = FIXTURES / "java"
        module_file = project / "fixture.iml"
        for value, module in (
            ("file://$MODULE_DIR$/..", module_file),
            ("file://$PROJECT_DIR$/src/main/java", module_file),
            ("file://$MODULE_DIR$-other", module_file),
            ("file://$PROJECT_DIR$/../outside", None),
            ("file://$MODULE_DIR$/src/main/java", None),
        ):
            with self.subTest(path=value, module=module), self.assertRaises(ValueError):
                project_path(project, value, module)


if __name__ == "__main__":
    unittest.main()
