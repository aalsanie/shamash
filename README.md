<p align="center">
  <a href="./README.md">English</a> •
  <a href="./docs/README.zh-CN.md">简体中文</a> •
  <a href="./docs/README.es.md">Español</a>
</p>

<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash" width="180"/>
</p>

# Shamash

**Architecture checks for Java and Kotlin, without architecture tests.**

Detect dependency cycles, enforce package and role boundaries, and surface coupling hotspots. Use compiled-code checks in the CLI or CI, with source-aware findings and fixes in IntelliJ.

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![License](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](./LICENSE)

## First scan

Requires **Java 17+** and compiled classes. Download the [CLI archive and checksums](https://github.com/aalsanie/shamash/releases/latest), verify the archive, and add its `bin` directory to your `PATH` (`shamash` on Unix, `shamash.bat` on Windows).

```bash
./gradlew classes  # or ./mvnw package
shamash scan
```

Without a config, the scan reports findings but does not write project files or fail because of architecture violations.

## Enforce rules

```bash
shamash init
shamash validate
shamash scan --fail-on ERROR
```

`init` creates `shamash/configs/asm.yml` with a dependency-cycle rule. Use `shamash init --preset spring` for Spring defaults or `--preset reference` for the full example.

For an existing codebase, run `shamash baseline create` to accept current findings. Commit the updated config and `.shamash/baseline/asm-baseline.json`; subsequent scans report new violations.

## GitHub Actions

```yaml
name: Architecture

on: [pull_request, push]

jobs:
  architecture:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
      - run: ./gradlew classes
      - uses: aalsanie/shamash@v0.92.0
        with:
          config: shamash/configs/asm.yml
          fail-on: ERROR
```

Replace the build step with `./mvnw package` for Maven. The Action verifies the CLI download checksum before scanning.

**Exit codes:** `0` completed below threshold; `2` configuration or input error; `3` failed or incomplete scan; `4` findings at or above the threshold.

## IntelliJ

[Install from JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29504-shamash) and open **Tools → Shamash**. **Build Analysis** checks compiled code; **Source Analysis** provides source-aware findings, suppressions, and fixes.

## Library

Published on Maven Central for Java 17+.

**Gradle**
```kotlin
dependencies {
    implementation("io.github.aalsanie:shamash-asm-core:0.92.0")
}
```

**Maven**
```xml
<dependency>
    <groupId>io.github.aalsanie</groupId>
    <artifactId>shamash-asm-core</artifactId>
    <version>0.92.0</version>
</dependency>
```

```kotlin
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import java.nio.file.Path

val result = ShamashAsmScanRunner().run(ScanOptions(projectBasePath = Path.of(".")))
check(result.isSuccess) { result.toString() }
val findings = requireNotNull(result.engine).findings
```

`isSuccess` confirms scan completion, not the absence of violations.

## Beyond the basics

Configure API and annotation restrictions, JAR-origin rules, dependency metrics, and graph checks. Export JSON, SARIF, HTML, or XML reports; inspect facts, hotspots, and architecture scores. Add custom rule registries where built-in rules aren't enough.

[CLI options and checksums](./QUICK_START.md) · [Configuration examples](./docs/asm/schema/) · [Custom registries](./REGISTRY_GUIDE.md) · [Benchmarks](./benchmarks/)

[Changelog](./CHANGELOG.md) · [Security](./SECURITY.md) · [Contributing](./CONTRIBUTING.md) · [License](./LICENSE)
