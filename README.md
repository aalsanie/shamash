<p align="center">
  <a href="./README.md">English</a> •
  <a href="./docs/README.zh-CN.md">简体中文</a> •
  <a href="./docs/README.es.md">Español</a>
</p>

<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash" width="180"/>
</p>

# Shamash

Shamash scans compiled Java and Kotlin applications for dependency cycles and architecture violations without requiring architecture-test code.

- Scan without configuration.
- Baseline existing violations and enforce new ones in CI.
- Use the CLI, IntelliJ plugin or Java/Kotlin library.
- Extend checks with custom rules and registries.

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![License](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](./LICENSE)

## Quick start

Requires Java 17 or newer.

### 1. Install the CLI

Download `shamash-cli-<version>.zip` and `SHA256SUMS.txt` from [GitHub Releases](https://github.com/aalsanie/shamash/releases), verify the checksum and extract the archive.

```text
bin/shamash      # Linux/macOS
bin/shamash.bat  # Windows
```

The following examples assume the launcher is on your `PATH`.

### 2. Build your project

```bash
./gradlew classes
# or: ./mvnw package
```

If compiled classes are missing, Shamash detects common Gradle/Maven projects and suggests a build command.

### 3. Run your first scan

```bash
shamash scan
```

Without a configuration, Shamash runs in discovery mode. It reports findings without creating configuration, reports or baselines.

Example output:

```text
Shamash - discovery scan
Report-only mode. No project files were changed.

Shamash found 3 architecture issues

ERROR   graph.noCycles
        Dependency cycle detected ...

WARN    metrics.maxFanOut
        ...

642 classes scanned
1 errors, 2 warnings, 0 info

Ready to enforce architecture? Run: shamash init
```

## Turn discovery into enforcement

Create the default configuration:

```bash
shamash init
```

This writes `shamash/configs/asm.yml` with a dependency-cycle rule.

For Spring-specific rules:

```bash
shamash init --preset spring
```

For the full reference configuration:

```bash
shamash init --preset reference
```

Validate and scan:

```bash
shamash validate
shamash scan
```

Use `--all-findings` for the complete findings list and `--verbose` for diagnostics.

## Existing projects: accept current debt once

After `shamash init`, run:

```bash
shamash baseline create
```

After a complete, successful scan, this writes the configured baseline and sets `baseline.mode` to `VERIFY`. Replacing an existing baseline requires `--force`.

Commit the configuration and baseline:

```text
shamash/configs/asm.yml
.shamash/baseline/asm-baseline.json
```

Later scans suppress accepted violations and report new ones.

## GitHub Actions

Build the application before running Shamash:

```yaml
name: Architecture

on:
  pull_request:
  push:
    branches: [main]

jobs:
  shamash:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"
      - run: ./gradlew classes
      - uses: aalsanie/shamash@v0.92.0
```

For configured enforcement:

```yaml
      - uses: aalsanie/shamash@v0.92.0
        with:
          config: shamash/configs/asm.yml
          fail-on: ERROR
```

The action verifies the release checksum before execution.

## Library

Embed the bytecode engine in Java or Kotlin applications. Requires Java 17 or newer.

### Gradle

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.aalsanie:shamash-asm-core:0.92.0")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.aalsanie</groupId>
    <artifactId>shamash-asm-core</artifactId>
    <version>0.92.0</version>
</dependency>
```

Shared contracts and report exporters are included as transitive dependencies.

### Usage

Scan a compiled project:

```kotlin
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import java.nio.file.Path

val result = ShamashAsmScanRunner().run(
    ScanOptions(projectBasePath = Path.of("."))
)
check(result.isSuccess) { result.toString() }
val findings = requireNotNull(result.engine).findings
```

A successful scan can still contain architecture violations. `isSuccess` indicates that validation and analysis completed without execution errors or truncation.

## IntelliJ

Install from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29504-shamash), then open:

```text
Tools → Shamash
```

The tool window contains:

- **Build Analysis** — compiled-bytecode checks, findings, roles, graphs and reports.
- **Source Analysis** — source-aware checks, suppressions and fixes.

## CI behavior and exit codes

Configured scans use these exit codes:

- `0` successful scan and findings below threshold
- `2` configuration/input problem, including missing compiled bytecode
- `3` runtime failure or incomplete scan
- `4` findings reached the selected `--fail-on` threshold

Discovery scans return `0` after successful analysis, regardless of findings.

## Advanced capabilities

- Architecture role dependencies and package rules
- Dependency graph rules and cycle limits
- Coupling and class-size metrics
- API and annotation restrictions
- JAR-origin restrictions
- Facts export and `shamash facts`
- Graphs, hotspots, scoring and `shamash analysis`
- JSON, SARIF, HTML and XML reports
- Custom rule registries
- Exceptions and baselines

See [`docs/asm/`](./docs/asm/), [`REGISTRY_GUIDE.md`](./REGISTRY_GUIDE.md) and [`benchmarks/`](./benchmarks/).

## Security

Report vulnerabilities through [`SECURITY.md`](./SECURITY.md).

## License

Apache License 2.0. See [`LICENSE`](./LICENSE).
