#!/usr/bin/env python3
"""Build a flat SDKMAN distribution from the verified Gradle CLI ZIP."""

import argparse
from pathlib import Path, PurePosixPath
from shutil import copyfileobj
from zipfile import ZIP_DEFLATED, BadZipFile, ZipFile, ZipInfo


def package_sdkman(source: Path, destination: Path, version: str) -> None:
    prefix = f"shamash-{version}/"
    contents = {}
    with ZipFile(source) as archive:
        for member in archive.infolist():
            name = member.filename
            if (
                name.startswith("/")
                or "\\" in name
                or ".." in PurePosixPath(name).parts
                or not name.startswith(prefix)
            ):
                raise ValueError(f"Unsafe or unexpected archive member: {name}")
            relative = name[len(prefix) :]
            if member.is_dir():
                continue
            if not relative or relative in contents:
                raise ValueError(f"Duplicate or empty archive member: {name}")
            mode = (member.external_attr >> 16) & 0o170000
            if mode == 0o120000:
                raise ValueError(f"Symbolic links are not supported: {name}")
            contents[relative] = member

    required = {"bin/shamash", "bin/shamash.bat"}
    if not required.issubset(contents) or not any(
        name.startswith("lib/") and name.endswith(".jar") for name in contents
    ):
        raise ValueError("Gradle distribution must contain both launchers and runtime JARs")

    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    try:
        with ZipFile(source) as original, ZipFile(temporary, "w", compression=ZIP_DEFLATED) as output:
            for name, member in sorted(contents.items()):
                info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = ZIP_DEFLATED
                info.create_system = 3
                permissions = 0o755 if name == "bin/shamash" else 0o644
                info.external_attr = (0o100000 | permissions) << 16
                with original.open(member) as reader, output.open(info, "w") as writer:
                    copyfileobj(reader, writer, length=1024 * 1024)
        with ZipFile(temporary) as verify:
            if verify.testzip() is not None:
                raise BadZipFile("Packaged SDKMAN archive failed CRC verification")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    package_sdkman(args.source, args.destination, args.version)


if __name__ == "__main__":
    main()
