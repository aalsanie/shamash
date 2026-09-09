import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
USES_RE = re.compile(r"^\s*-\s*uses:\s*([^\s#]+)", re.MULTILINE)
FULL_SHA_RE = re.compile(r"[0-9a-f]{40}")


class WorkflowSecurityTest(unittest.TestCase):
    def test_external_actions_are_pinned_to_full_commit_shas(self):
        files = [ROOT / "action.yml"]
        files.extend(sorted((ROOT / ".github" / "workflows").glob("*.yml")))
        files.extend(sorted((ROOT / ".github" / "workflows").glob("*.yaml")))
        files.extend(sorted((ROOT / ".github" / "actions").glob("**/*.yml")))
        files.extend(sorted((ROOT / ".github" / "actions").glob("**/*.yaml")))

        violations = []
        for path in files:
            if not path.is_file():
                continue
            text = path.read_text(encoding="utf-8")
            for value in USES_RE.findall(text):
                if value.startswith("./"):
                    continue
                if "@" not in value:
                    violations.append(f"{path.relative_to(ROOT)}: missing ref in {value}")
                    continue
                _, ref = value.rsplit("@", 1)
                if FULL_SHA_RE.fullmatch(ref) is None:
                    violations.append(f"{path.relative_to(ROOT)}: {value} is not pinned to a full commit SHA")

        self.assertEqual([], violations, "\n".join(violations))


if __name__ == "__main__":
    unittest.main()
