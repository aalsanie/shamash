<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash Logo" width="180"/>
</p>

[![current release](https://img.shields.io/badge/release-0.2.0-green)](https://github.com/aalsanie/shamash/releases) | [![install plugin](https://img.shields.io/badge/intellij-plugin-red)](https://plugins.jetbrains.com/plugin/29504-shamash) | [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)



# Shamash

Shamash is an architectural refactoring tool (currently: intellij plugin) that enforces clean architecture.


### What It Offers?
- Architectural Dashboard - Tools → Samash Dashboard (All reports are exportable)
  - Hierarchy tab: search panel of project's hierarchical structure
  - Tree tab: view of project's hierarchical structure
  - Hotspots tab: dynamic analysis that shows architectural violations
  - Findings: displays severity/violations
- PSI inspections - displayed as warning in problems tab with fixes
  - violations of clean code
  - violations of clean architecture
- ASM bytecode scan - Tools → Shamash: run scan


### Levels of Operations
- ASM bytecode analysis
- PSI for static analysis
- Logging-based cleanup (hybrid runtime signal)

All inspections are deterministic, reversible, and framework-aware where necessary.
The plugin offers dashboards, fixes, inspection etc...

## License

[LICENSE.md](LICENSE.md)

## Changelog

[Change-log](./CHANGELOG.md)
