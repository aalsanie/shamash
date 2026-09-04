#!/usr/bin/env python3
# Copyright 2025-2026 @aalsanie. SPDX-License-Identifier: Apache-2.0
"""Benchmark Shamash discovery on Jenkins 2.479.3 core; Python 3.10+, Java 17+."""

import argparse
import csv
import datetime as dt
import glob
import hashlib
import io
import json
import os
import platform
import re
import shutil
import statistics
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile
from pathlib import Path, PurePosixPath


VERSION = "2.479.3"
WAR_URL = f"https://updates.jenkins.io/download/war/{VERSION}/jenkins.war"
# Published at https://updates.jenkins.io/download/war/ under 2.479.3.
WAR_SHA256 = "304c8592860d5b03dec27c96b5e89ec58fc744f78161c53f7a344a0bf7ce9203"
DISCOVERY = "shamash/asm/schema/v1/shamash-asm.discovery.yml"
MAIN = "io.shamash.cli.MainKt"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def unpack_cli(archive, destination):
    destination.mkdir()
    with zipfile.ZipFile(archive) as bundle:
        entries = sorted(
            (entry for entry in bundle.infolist()
             if PurePosixPath(entry.filename).parent.name == "lib" and entry.filename.endswith(".jar")),
            key=lambda entry: entry.filename,
        )
        for entry in entries:
            target = destination / PurePosixPath(entry.filename).name
            with target.open("xb") as output, bundle.open(entry) as source:
                shutil.copyfileobj(source, output)
    jars = sorted(destination.glob("*.jar"))
    cli_versions = []
    discovery = []
    for jar in jars:
        with zipfile.ZipFile(jar) as contents:
            names = set(contents.namelist())
            if "io/shamash/cli/MainKt.class" in names:
                manifest = contents.read("META-INF/MANIFEST.MF").decode("utf-8")
                match = re.search(r"^Implementation-Version: (\S+)", manifest, re.MULTILINE)
                if not match:
                    raise ValueError("CLI JAR has no Implementation-Version")
                cli_versions.append(match.group(1))
            if DISCOVERY in names:
                discovery.append(contents.read(DISCOVERY))
    if len(cli_versions) != 1 or len(discovery) != 1:
        raise ValueError("Expected one CLI main class and one built-in discovery configuration")
    return jars, cli_versions[0], discovery[0]


def prepare_target(war, classes):
    actual = sha256(war)
    if actual != WAR_SHA256:
        raise ValueError(f"Jenkins WAR checksum mismatch: {actual}; expected {WAR_SHA256}")
    with zipfile.ZipFile(war) as bundle:
        core_bytes = bundle.read(f"WEB-INF/lib/jenkins-core-{VERSION}.jar")
    inventory = []
    with zipfile.ZipFile(io.BytesIO(core_bytes)) as core:
        for entry in sorted(core.infolist(), key=lambda entry: entry.filename):
            if entry.is_dir() or not entry.filename.endswith(".class"):
                continue
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or any(part in ("..", ".") for part in name.parts) or "\\" in entry.filename:
                raise ValueError(f"Unexpected class entry: {entry.filename}")
            if entry.filename.startswith("META-INF/versions/"):
                raise ValueError("Multi-release classes require an explicit version-selection policy")
            target = classes.joinpath(*name.parts).resolve()
            if not target.is_relative_to(classes.resolve()):
                raise ValueError(f"Class entry escapes output directory: {entry.filename}")
            target.parent.mkdir(parents=True, exist_ok=True)
            data = core.read(entry)
            with target.open("xb") as output:
                output.write(data)
            inventory.append({"path": entry.filename, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    if not inventory:
        raise ValueError("Jenkins core contains no classes")
    return inventory, hashlib.sha256(core_bytes).hexdigest()


def parse_scan(log, expected_classes):
    if "Shamash - discovery scan" not in log:
        raise ValueError("Expected a discovery scan without a project configuration")
    classes = re.search(r"^(\d+)(\+?) classes scanned\s*$", log, re.MULTILINE)
    counts = re.search(r"^(\d+) errors, (\d+) warnings, (\d+) info\s*$", log, re.MULTILINE)
    rules = re.search(r"^Rules\s*: configured=(\d+) executed=(\d+) skipped=(\d+)\s*$", log, re.MULTILINE)
    if not classes or classes.group(2) or int(classes.group(1)) != expected_classes:
        raise ValueError("Incomplete scan or class count differs from the extracted class inventory")
    if not counts or not rules or int(rules.group(2)) == 0 or int(rules.group(3)) != 0:
        raise ValueError("Missing diagnostics, no executed rules, or skipped rules")
    if int(rules.group(1)) != int(rules.group(2)):
        raise ValueError("Not all configured rules executed")
    findings = []
    for match in re.finditer(
        r"^(ERROR|WARNING|INFO) +([^\r\n]+)\r?\n(.*?)(?=\r?\n\r?\n|\Z)",
        log, re.MULTILINE | re.DOTALL,
    ):
        findings.append({"severity": match.group(1), "rule": match.group(2).strip(),
                         "detail": match.group(3).strip()})
    errors, warnings, infos = map(int, counts.groups())
    if len(findings) != errors + warnings + infos:
        raise ValueError("Could not account for every printed finding; inspect the raw scan log")
    return {"classes": expected_classes, "errors": errors, "warnings": warnings,
            "info": infos, "rules_executed": int(rules.group(2)), "findings": findings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli-zip", required=True, help="Built CLI distribution ZIP; a quoted glob is accepted")
    parser.add_argument("--war", type=Path, help="Existing Jenkins 2.479.3 WAR; otherwise download the pinned artifact")
    parser.add_argument("--java", help="Java executable; defaults to JAVA_HOME/bin/java, then PATH")
    parser.add_argument("--runs", type=int, default=5, help="Measured runs after one priming run (minimum 3)")
    parser.add_argument("--timeout", type=int, default=900, help="Seconds allowed per scan")
    parser.add_argument("--out", type=Path, default=Path("benchmark-results"), help="Parent directory for a new result folder")
    parser.add_argument("--machine", default="", help="Optional hardware description, e.g. CPU model and installed RAM")
    args = parser.parse_args()
    if args.runs < 3 or args.timeout <= 0:
        parser.error("Use at least three measured runs and a positive timeout")
    matches = sorted(Path(name).resolve() for name in glob.glob(args.cli_zip) if Path(name).is_file())
    if len(matches) != 1:
        parser.error(f"--cli-zip must match exactly one file; matched {len(matches)}. Specify the exact ZIP if necessary.")
    archive = matches[0]
    java_name = "java.exe" if os.name == "nt" else "java"
    java_candidate = args.java or (str(Path(os.environ["JAVA_HOME"]) / "bin" / java_name)
                                   if os.environ.get("JAVA_HOME") else java_name)
    java = shutil.which(java_candidate)
    if not java:
        parser.error(f"Java executable not found: {java_candidate}")
    java = str(Path(java).resolve())
    java_version = subprocess.run([java, "-version"], capture_output=True, text=True,
                                  encoding="utf-8", errors="replace", timeout=30, check=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    result_dir = args.out.resolve() / f"jenkins-{VERSION}-{stamp}-{uuid.uuid4().hex[:8]}"
    result_dir.mkdir(parents=True)
    print(f"Results: {result_dir}", flush=True)
    try:
        jars, cli_version, discovery = unpack_cli(archive, result_dir / "cli-lib")
        (result_dir / "discovery.yml").write_bytes(discovery)
        war = args.war.resolve() if args.war else result_dir / "jenkins.war"
        if not args.war:
            print(f"Downloading pinned Jenkins {VERSION} WAR (outside timing)...", flush=True)
            request = urllib.request.Request(WAR_URL, headers={"User-Agent": "Shamash-benchmark/1"})
            with urllib.request.urlopen(request, timeout=120) as source, war.open("xb") as output:
                shutil.copyfileobj(source, output)
        project = result_dir / "project"
        inventory, core_hash = prepare_target(war, project / "target" / "classes")
        write_json(result_dir / "class-inventory.json", inventory)
        # Start Java directly so timing includes one JVM, with no launcher shell.
        command = [java, "-Xms256m", "-Xmx2g", "-Dfile.encoding=UTF-8", "-cp",
                   os.pathsep.join(str(jar) for jar in jars), MAIN,
                   "scan", "--project", str(project), "--max-classes", str(len(inventory) + 1),
                   "--fail-on", "NONE", "--all-findings", "--verbose"]
        metadata = {
            "target": f"Jenkins {VERSION} core", "scope": "Core .class files only; no plugins or bundled dependency JARs",
            "target_url": WAR_URL, "war_sha256": WAR_SHA256, "core_jar_sha256": core_hash,
            "class_count": len(inventory), "class_bytes": sum(row["bytes"] for row in inventory),
            "shamash_version": cli_version, "cli_zip_sha256": sha256(archive),
            "benchmark_sha256": sha256(Path(__file__)), "discovery_sha256": hashlib.sha256(discovery).hexdigest(),
            "java_version": (java_version.stdout + java_version.stderr).strip(),
            "os": platform.platform(), "machine": args.machine, "cpu": platform.processor(),
            "logical_cpus": os.cpu_count(), "timestamp_utc": stamp,
            "command": [
                Path(java).name, "-Xms256m", "-Xmx2g", "-Dfile.encoding=UTF-8", "-cp", "<CLI_CLASSPATH>", MAIN,
                "scan", "--project", "<PROJECT>", "--max-classes", str(len(inventory) + 1),
                "--fail-on", "NONE", "--all-findings", "--verbose",
            ],
            "additional_java_environment_present": [name for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
                                                    if os.environ.get(name)],
            "method": "One untimed priming run, then fresh-JVM CLI wall times; OS caches uncontrolled. Includes all findings written to log.",
            "excluded_from_timing": "Download, checksum verification, extraction, builds, log parsing; no JSON/HTML export",
            "memory": "Not measured; -Xmx2g is a heap limit, not memory consumption",
        }
        write_json(result_dir / "environment.json", metadata)
        expected = None
        rows = []
        for index in range(args.runs + 1):
            name = "priming" if index == 0 else f"run-{index:02d}"
            print(f"{name}: scanning {len(inventory)} classes...", flush=True)
            log_path = result_dir / f"{name}.log"
            with log_path.open("wb") as output:
                started = time.perf_counter()
                completed = subprocess.run(command, cwd=result_dir, stdout=output, stderr=subprocess.STDOUT,
                                           timeout=args.timeout, check=False)
                elapsed = time.perf_counter() - started
            if completed.returncode != 0:
                raise ValueError(f"{name} failed with exit {completed.returncode}; see {log_path}")
            scan = parse_scan(log_path.read_text(encoding="utf-8", errors="replace"), len(inventory))
            signature = {**scan, "findings": sorted(json.dumps(item, sort_keys=True) for item in scan["findings"])}
            if expected is None:
                expected = signature
                write_json(result_dir / "findings.json", scan["findings"])
            elif signature != expected:
                raise ValueError(f"Findings or counts changed between identical scans; inspect {log_path}")
            row = {"run": name, "measured": index > 0, "seconds": elapsed, "exit_code": completed.returncode,
                   "classes": scan["classes"], "errors": scan["errors"], "warnings": scan["warnings"],
                   "info": scan["info"], "rules_executed": scan["rules_executed"]}
            rows.append(row)
            with (result_dir / "runs.csv").open("w", newline="", encoding="utf-8") as output:
                writer = csv.DictWriter(output, fieldnames=list(row))
                writer.writeheader()
                writer.writerows(rows)
            print(f"{name}: {elapsed:.3f}s, {len(scan['findings'])} findings", flush=True)
        times = [row["seconds"] for row in rows if row["measured"]]
        summary = {"status": "complete", "median_seconds": statistics.median(times),
                   "min_seconds": min(times), "max_seconds": max(times), "measured_runs": len(times),
                   "classes": len(inventory), "findings": len(scan["findings"]), "runs": rows}
        write_json(result_dir / "summary.json", summary)
        report = [
            f"Shamash {cli_version} scanned {len(inventory):,} classes from Jenkins {VERSION}'s core",
            f"in a median {summary['median_seconds']:.3f}s across {len(times)} fresh-JVM runs",
            f"(range {min(times):.3f}–{max(times):.3f}s), after one priming run.",
            f"Its discovery rules flagged {len(scan['findings']):,} findings",
            f"({scan['errors']} errors, {scan['warnings']} warnings, {scan['info']} informational).",
            "Plugins and bundled dependencies were excluded. Timing includes JVM startup, scanning, analysis and writing findings to a log;",
            "download, extraction and compilation are excluded. OS caches were not reset. Memory was not measured.",
            "This measures one application core; it does not establish performance on every monolith or compare tools.",
            "", f"Environment: {args.machine or platform.processor() or platform.machine()}; {platform.platform()}.",
            "Java: " + metadata["java_version"].replace("\n", " | "),
            "", "Example finding (scanner output; requires human review):",
        ]
        sample = next((item for item in scan["findings"] if "noCycles" in item["rule"]),
                      next(iter(scan["findings"]), None))
        report.extend(["```text", f"{sample['severity']} {sample['rule']}\n{sample['detail']}", "```"]
                      if sample else ["No findings were reported."])
        (result_dir / "post-benchmark.md").write_text("\n".join(report) + "\n", encoding="utf-8")
        print(f"Complete. Read {result_dir / 'post-benchmark.md'}", flush=True)
    except Exception as error:
        write_json(result_dir / "failure.json", {"status": "failed", "error": str(error)})
        raise


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Benchmark failed: {error}", file=sys.stderr)
        sys.exit(1)
