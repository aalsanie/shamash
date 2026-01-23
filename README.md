<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash Logo" width="180"/>
</p>

[![current release](https://img.shields.io/badge/release-0.90.0-green)](https://github.com/aalsanie/shamash/releases) | [![install plugin](https://img.shields.io/badge/jetbrains-plugin-blue)](https://plugins.jetbrains.com/plugin/29504-shamash) | [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html) | ![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
| ![Plugin Verify](https://github.com/aalsanie/shamash/actions/workflows/plugin.yml/badge.svg)


# Shamash

Shamash is a JVM architecture enforcement tool that helps teams **define, validate, and continuously enforce architectural boundaries**

It ships two complementary engines:

- **ASM (Bytecode engine):** analyzes compiled `.class` files and (optionally) dependency JARs to detect forbidden dependencies, cycles, coupling hotspots, and architectural drift **without requiring source code**.
- **PSI (Source engine):** analyzes source code via IntelliJ PSI using a strict YAML schema, providing dashboards, rule validation, suppressions, guided fixes, and exportable reports.
---

## Why two engines?

Use **PSI** when you want source-aware feedback (IDE-native dashboards, suppressions, guided fixes).
Use **ASM** when you need build-artifact truth (bytecode-level reality, JAR visibility, CI-friendly scans).

Most teams run both:
- PSI for day-to-day development feedback
- ASM for “what actually ships” bytecode verification

### Both engines are configurable via
- ASM reads **`asm.yml`**
- PSI reads **`psi.yml`**

These YAML configs define roles, rules, scope, validation behavior, exports, and (when enabled) analysis outputs like graphs/hotspots/scores.

---

## What it covers

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

---

<details>
  <summary><b>Demo (GIF)</b></summary>

  <br/>

![Shamash IntelliJ demo](./assets/shamash-demo.gif)

</details>

---

## Documentation & Examples

Docs + Test Bed applications: [`./docs/`](./docs)

---

## Quick Start
See [QUICK_START](./QUICK_START.md)

## More
[JVM architecture enforcement in IDE + CI](https://open.substack.com/pub/aalsanie/p/shamash-architecture-enforcement?utm_campaign=post-expanded-share&utm_medium=web)