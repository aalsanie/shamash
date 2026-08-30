<p align="center">
  <a href="./README.md">English</a> •
  <a href="./docs/README.zh-CN.md">简体中文</a> •
  <a href="./docs/README.es.md">Español</a>
</p>

<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash" width="180"/>
</p>

# Shamash

Stops JVM architecture drift before it reaches main by scanning compiled Java/Kotlin applications, finds dependency cycles and architecture violations, and can prevent new violations in CI without requiring architecture-test code.

- **CLI-first:** standalone Java 17+ tool for local use and CI.
- **Configless first scan:** see useful architecture risks before learning the configuration model.
- **Brownfield-friendly:** baseline existing debt once, then fail only on new violations.
- **IntelliJ:** one workspace with Build Analysis and Source Analysis.
- **Advanced when needed:** custom roles/rules, facts, graphs, hotspots, registries and multiple report formats remain available.

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![License](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](./LICENSE)

## Usage

Shamash analyzes compiled bytecode. Build the project first:

```bash
./gradlew classes
# or: ./mvnw package
```

Then run:

```bash
shamash scan
```

No configuration is required for this first scan. Discovery mode is report-only: it does not create config, reports or baselines in your project, and it never fails because of findings.

Example shape of the output:

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

If Shamash cannot find compiled classes, it detects common Gradle/Maven projects and prints the exact build command to run first.

## Install the CLI

Requires Java 17 or newer.

Download `shamash-cli-<version>.zip` and `SHA256SUMS.txt` from GitHub Releases, verify the checksum, extract it, then use:

```text
bin/shamash      # Linux/macOS
bin/shamash.bat  # Windows
```

The launcher name is part of the packaged-product contract and is smoke-tested on Linux, Windows and macOS before release.

## Enforce architecture in a project

Create the small default config:

```bash
shamash init
```

It writes:

```text
shamash/configs/asm.yml
```

The default starter is intentionally small and starts with a dependency-cycle rule. Framework-specific policy is opt-in:

```bash
shamash init --preset spring
```

The full advanced reference remains available:

```bash
shamash init --preset reference
```

Validate configuration:

```bash
shamash validate
```

Then scan normally:

```bash
shamash scan
```

Findings are printed by default. Use `--all-findings` for the complete list and `--verbose` for engine diagnostics.

## Existing projects: accept current debt once

After `shamash init`, run:

```bash
shamash baseline create
```

This analyzes the current project, writes the configured baseline, and ensures `baseline.mode` is `VERIFY`. Existing baselines are protected; replacement requires `--force`.

Commit both:

```text
shamash/configs/asm.yml
.shamash/baseline/asm-baseline.json
```

With baseline mode `VERIFY`, later scans suppress accepted fingerprints and expose new violations.

## GitHub Actions

Build the application, then use the first-party action:

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
      - uses: aalsanie/shamash@v0.91.0
```

For configured enforcement:

```yaml
      - uses: aalsanie/shamash@v0.91.0
        with:
          config: shamash/configs/asm.yml
          fail-on: ERROR
```

The action verifies the release checksum before execution.

## IntelliJ

Install **Shamash** from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29504-shamas), then open:

```text
Tools → Shamash
```

There is one Shamash tool window. Its first-level areas are:

- **Build Analysis** — compiled-bytecode architecture checks, findings, roles, graphs and reports.
- **Source Analysis** — source-aware checks, suppressions and fixes.

ASM and PSI still exist internally because they solve different technical problems, but users do not need to understand those engine names to get started.

## CI behavior and exit codes

Configured scans use these stable exit codes:

- `0` successful scan and findings below threshold
- `2` configuration/input problem (including missing compiled bytecode)
- `3` runtime/engine failure
- `4` findings reached the selected `--fail-on` threshold

Discovery mode is report-only and returns `0` after a successful scan even if it finds architecture risks.

## Advanced capabilities

Advanced teams can still use:

- architecture role dependencies and package rules
- dependency graph rules and cycle limits
- coupling/class-size metrics
- API/annotation restrictions
- JAR-origin restrictions
- facts export and `shamash facts`
- graph/hotspot/scoring analysis and `shamash analysis`
- JSON, SARIF, HTML and XML report formats
- custom rule registries
- exceptions and baselines

See `docs/asm/` and `REGISTRY_GUIDE.md` for the advanced engine/configuration reference.

## Security

Please do not disclose vulnerabilities in a public issue. Follow [`SECURITY.md`](./SECURITY.md).

## License

Apache License 2.0. See [`LICENSE`](./LICENSE).
