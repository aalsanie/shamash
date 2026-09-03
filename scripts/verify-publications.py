#!/usr/bin/env python3
# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0

import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

MODULES = ("shamash-artifacts", "shamash-export", "shamash-asm-core")
GROUP = "io.github.aalsanie"
GROUP_PATH = "io/github/aalsanie"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(repository, version, signed=False):
    group_dir = repository / GROUP_PATH
    require(group_dir.is_dir(), f"Publication directory not found: {group_dir}")
    require({p.name for p in group_dir.iterdir() if p.is_dir()} == set(MODULES), "Unexpected published modules")
    for module in MODULES:
        directory = group_dir / module / version
        stem = f"{module}-{version}"
        primary = [directory / f"{stem}{suffix}" for suffix in (".pom", ".module", ".jar", "-sources.jar", "-javadoc.jar")]
        for artifact in primary:
            require(artifact.is_file() and artifact.stat().st_size > 0, f"Missing/empty artifact: {artifact}")
            for algorithm in ("md5", "sha1"):
                checksum = artifact.with_name(artifact.name + "." + algorithm)
                expected = hashlib.new(algorithm, artifact.read_bytes()).hexdigest()
                require(checksum.is_file() and checksum.read_text().strip() == expected, f"Invalid {algorithm}: {artifact}")
            if signed:
                signature = artifact.with_name(artifact.name + ".asc")
                require(signature.is_file(), f"Missing signature: {artifact}")
                subprocess.run(["gpg", "--batch", "--verify", str(signature), str(artifact)], check=True, capture_output=True)

        pom = ET.parse(primary[0]).getroot()
        require(pom.findtext("m:groupId", namespaces=NS) == GROUP, f"Wrong group: {module}")
        require(pom.findtext("m:version", namespaces=NS) == version, f"Wrong version: {module}")
        require(pom.findtext("m:artifactId", namespaces=NS) == module, f"Wrong artifact: {module}")
        required_metadata = (
            "name", "description", "url", "licenses/license/name", "licenses/license/url",
            "developers/developer/id", "developers/developer/name", "scm/connection", "scm/developerConnection", "scm/tag",
        )
        for path in required_metadata:
            require(pom.findtext("/".join("m:" + part for part in path.split("/")), namespaces=NS), f"Missing POM {path}: {module}")
        require(pom.findtext("m:scm/m:tag", namespaces=NS) == f"v{version}", f"Wrong SCM tag: {module}")
        dependencies = {}
        for dep in pom.findall("m:dependencies/m:dependency", NS):
            group = dep.findtext("m:groupId", namespaces=NS) or ""
            artifact = dep.findtext("m:artifactId", namespaces=NS)
            dependency_version = dep.findtext("m:version", namespaces=NS) or ""
            require(not dependency_version.endswith("-SNAPSHOT"), f"Snapshot dependency: {artifact}")
            if group == GROUP:
                require(dependency_version == version and artifact in MODULES, f"Unaligned dependency: {module} -> {artifact}")
            require("intellij" not in group.lower(), f"IntelliJ dependency: {module} -> {group}")
            dependencies[(group, artifact)] = dep.findtext("m:scope", namespaces=NS) or "compile"
        if module != "shamash-artifacts":
            require(dependencies.get((GROUP, "shamash-artifacts")) == "compile", f"Missing API contracts: {module}")
        if module == "shamash-asm-core":
            require(dependencies.get((GROUP, "shamash-export")) == "runtime", "Exporter implementation scope changed")
            for api in (("org.ow2.asm", "asm"), ("com.fasterxml.jackson.core", "jackson-databind")):
                require(dependencies.get(api) == "compile", f"Missing API dependency: {api}")

        metadata = json.loads(primary[1].read_text(encoding="utf-8"))
        component = metadata["component"]
        require((component["group"], component["module"], component["version"]) == (GROUP, module, version), f"Wrong Gradle coordinates: {module}")
        variants = {v["name"]: v for v in metadata["variants"]}
        api_deps = {(d["group"], d["module"]) for d in variants["apiElements"].get("dependencies", [])}
        if module != "shamash-artifacts":
            require((GROUP, "shamash-artifacts") in api_deps, f"Missing Gradle API contract: {module}")
        if module == "shamash-asm-core":
            require({("org.ow2.asm", "asm"), ("com.fasterxml.jackson.core", "jackson-databind")} <= api_deps, "Missing Gradle API dependencies")
        require(variants["apiElements"]["attributes"]["org.gradle.jvm.version"] == 17, f"Wrong target JVM: {module}")
        for variant in variants.values():
            for dependency in variant.get("dependencies", []):
                if dependency["group"] == GROUP:
                    require(dependency["module"] in MODULES and dependency["version"].get("requires") == version, f"Unaligned Gradle dependency: {module}")
                require("intellij" not in dependency["group"].lower(), f"IntelliJ Gradle dependency: {module}")

        with zipfile.ZipFile(primary[2]) as jar:
            names = jar.namelist()
            manifest = jar.read("META-INF/MANIFEST.MF").decode()
            require(f"Implementation-Version: {version}" in manifest, f"Missing runtime version: {module}")
            require("META-INF/LICENSE" in names, f"Missing license: {module}")
            classes = [name for name in names if name.endswith(".class")]
            require(classes, f"No classes: {module}")
            for name in classes:
                magic, _, major = struct.unpack(">IHH", jar.read(name)[:8])
                require(magic == 0xCAFEBABE and major <= 61, f"Non-Java-17 bytecode: {module}/{name}")
                require(not name.startswith(("com/intellij/", "org/jetbrains/kotlin/", "org/objectweb/asm/")), f"Bundled dependency: {name}")
            if module == "shamash-asm-core":
                resources = (
                    "META-INF/services/io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider",
                    "shamash/asm/schema/v1/shamash-asm.schema.json",
                    "shamash/asm/schema/v1/shamash-asm.discovery.yml",
                )
                for resource in resources:
                    require(resource in names, f"Missing runtime resource: {resource}")
        with zipfile.ZipFile(primary[3]) as jar:
            require(any(n.endswith(".kt") for n in jar.namelist()), f"Empty sources: {module}")
        with zipfile.ZipFile(primary[4]) as jar:
            require("index.html" in jar.namelist() and len([n for n in jar.namelist() if n.endswith(".html")]) > 5, f"Missing generated API reference: {module}")
        print(f"Verified {GROUP}:{module}:{version}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("version")
    parser.add_argument("--signed", action="store_true", help="Also verify OpenPGP signatures using the local GnuPG keyring")
    args = parser.parse_args()
    verify(args.repository.resolve(), args.version, args.signed)
