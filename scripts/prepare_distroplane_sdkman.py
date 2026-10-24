#!/usr/bin/env python3
"""Prepare an SDKMAN publication plan using an existing GitHub Release archive."""

import argparse
import json
import re
from pathlib import Path
from zipfile import ZipFile


def prepare(version: str, archive: Path, output: Path) -> None:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Expected a stable numeric release version")
    archive = archive.resolve(strict=True)
    if archive.name != f"shamash-sdkman-{version}.zip":
        raise ValueError("Archive name does not match release version")
    with ZipFile(archive) as source:
        names = set(source.namelist())
        if not {"bin/shamash", "bin/shamash.bat"}.issubset(names) or not any(
            name.startswith("lib/") and name.endswith(".jar") for name in names
        ):
            raise ValueError("SDKMAN archive is missing CLI launchers or runtime JARs")
        if source.testzip() is not None:
            raise ValueError("SDKMAN archive is corrupted")
    config = {
        "schemaVersion": "1",
        "release": {
            "id": f"shamash-{version}",
            "artifacts": [
                {
                    "name": "sdkman-cli",
                    "source": str(archive),
                    "mediaType": "application/zip",
                }
            ],
        },
        "targets": [
            {
                "id": "sdkman",
                "provider": {"name": "sdkman"},
                "configuration": {
                    "artifact": "sdkman-cli",
                    "candidate": "shamash",
                    "version": version,
                    "url": f"https://github.com/aalsanie/shamash/releases/download/v{version}/{archive.name}",
                    "platform": "UNIVERSAL",
                    "authentication": {
                        "consumerKeyCredential": "sdkman-key",
                        "consumerTokenCredential": "sdkman-token",
                    },
                },
            }
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    prepare(args.version, args.archive, args.output)


if __name__ == "__main__":
    main()
