<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash Logo" width="180"/>
</p>

[![current release](https://img.shields.io/badge/release-0.70.0-green)](https://github.com/aalsanie/shamash/releases) | [![install plugin](https://img.shields.io/badge/jetbrains-plugin-red)](https://plugins.jetbrains.com/plugin/29504-shamash) | [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

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

### Under development (not promised yet)
- Dead code detection
- Deprecation / shadow usage detection
- Additional JVM internals visibility and advanced inspections

---

## Documentation & hands-on example

- **Docs + Test Bed applications:** [`./docs/`](./docs)

---

## IntelliJ Plugin: quick start

1. Install from JetBrains Marketplace (or build locally).
2. Open the tool windows:
  - **Shamash PSI**: configure architecture and validate rules against source
  - **Shamash ASM**: scan bytecode and export findings
3. Create a config from reference (or write one manually), then:
  - **Validate config**
  - **Run scan**
  - Review findings and export reports

> Configs typically live under your project (e.g. `psi.yml`, `asm.yml`) and reports go under `.shamash/` by default.
