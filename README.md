<p align="center">
  <a href="./README.md">English</a> •
  <a href="./docs/README.zh-Hans.md">简体中文</a>
</p>

<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash Logo" width="180"/>
</p>

[![current release](https://img.shields.io/badge/release-0.90.0-green)](https://github.com/aalsanie/shamash/releases) | [![install plugin](https://img.shields.io/badge/jetbrains-plugin-blue)](https://plugins.jetbrains.com/plugin/29504-shamash) | [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html) | ![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
| ![Plugin Verify](https://github.com/aalsanie/shamash/actions/workflows/plugin.yml/badge.svg)


# Shamash
Shamash is a JVM architecture enforcement tool that helps teams **define, validate, and continuously enforce architectural boundaries**.
It prevents architecture drift in JVM codebases by catching forbidden dependencies and cycles early.

### Use cases

- Stop layer violations (controller → repository, service → web, etc.)
- Detect dependency cycles and show a representative cycle path
- Catch module boundary breaks during refactors/migrations
- Generate SARIF/HTML/JSON/XML reports for CI/PR visibility

### Two engines

- PSI (source): dashboards, suppressions, guided fixes
- ASM (bytecode): deterministic “what ships” verification + CI gates + exports


### Try it in 60 seconds (IntelliJ)

- Tools → Shamash ASM Dashboard
- Create ASM Config (from Reference)
- Build (`./gradlew assemble`)
- Run ASM Scan → results + exports in `.shamash/`

---

## Why two engines?

Use **PSI** when you want source-aware feedback (IDE-native dashboards, suppressions, guided fixes).
Use **ASM** when you need build-artifact truth (bytecode-level reality, JAR visibility, CI-friendly scans).

Run both:
- PSI for day-to-day development feedback
- ASM for “what actually ships” bytecode verification

### Both engines are configurable via
- ASM reads **`asm.yml`**
- PSI reads **`psi.yml`**

These YAML configs define roles, rules, scope, validation behavior, exports, and (when enabled) analysis outputs like graphs/hotspots/scores.

---

<details>
  <summary><b>Demo (GIF)</b></summary>

  <br/>

![Shamash IntelliJ demo](./assets/shamash-demo.gif)
</details>

---

## What it covers

<details>
  <summary><b>Show details</b></summary>
  <br/>

### Architecture enforcement
- Roles (e.g., controller/service/repository) and placement rules
- Forbidden dependencies (role → role, package → package, module → module)
- Dependency cycles (with representative cycle paths)
- Config validation with clear, path-aware errors

### Analysis outputs
- Dependency / call graph analysis (configurable granularity)
- Hotspots and scoring (architecture health indicators)
- Exportable reports (JSON / SARIF / HTML / XML)

### Bytecode Analysis
- Dead code detection
- Deprecation / shadow usage detection
- Additional JVM internals visibility and advanced inspections

</details>

---

## Documentation & Examples

Docs + Test Bed applications: [`./docs/`](./docs)

---

## Gradle kotlin DSL
See: [Quick Start — Gradle Kotlin DSL](./QUICK_START.md#gradle-kotlin-dsl)

```shell
# one-time: generate the starter config in shamash/configs/asm.yml
gradlew shamashInit

# validate config
gradlew shamashValidate

# run scan gate (also runs on ./gradlew check now)
gradlew shamashScan
gradlew check
```

---

## Quick Start (CLI + intellij plugin + gradle DSL)
See [QUICK_START](./QUICK_START.md)

## More
[JVM architecture enforcement in IDE + CI](https://open.substack.com/pub/aalsanie/p/shamash-architecture-enforcement?utm_campaign=post-expanded-share&utm_medium=web)
