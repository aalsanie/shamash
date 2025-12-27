<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash Logo" width="180"/>
</p>

[![current release](https://img.shields.io/badge/release-0.2.0-green)](https://github.com/aalsanie/shamash/releases) | [![install plugin](https://img.shields.io/badge/intellij-plugin-red)](https://plugins.jetbrains.com/plugin/29504-shamash) | [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)



# Shamash

Shamash is a refactoring/reporting tool that enforces clean architecture (operates on java codebases).
Deterministic & Fast (relies on bytecode for analysis using ASM).

### What It Offers?
- Architectural Dashboard (reports are exportable)
  - Hierarchy tab: search panel of project's hierarchical structure
  - Tree tab: view of project's hierarchical structure
  - Hotspots tab: dynamic analysis that shows architectural violations
  - Findings: displays severity/violations
- PSI inspections - displayed as warning in problems tab with fixes
  - violations of clean code
  - violations of clean architecture
- ASM bytecode scan, a complete hierarchical tree view

### How to use
- Download the plugin from intellij marketplace or build locally
- Run shamash scan from your left hand side or using 
  - Tools → Shamash: run scan
  - Tools → Shamash dashboard
- Open the dashboard and see your codebase reports including
  - overall architecture score 
  - graphs and reports of current hierarchy, issues and fixes

### Local Setup
To build
```shell
./gradlew clean buildplugin
```
To run
```shell
gradlew.bat spotlessApply --stacktrace
gradlew clean runIde
```

To verify plugin
```shell
./gradlew runPluginVerifier
```

- ASM for bytecode analysis
- PSI for static analysis
- Logging-based for cleanup actions (hybrid runtime signal)

All inspections are deterministic, reversible, and framework-aware where necessary.

### Demo
<p align="center">
  <img src="assets/demo-final-one.png" alt="Shamash Logo" width="180"/>
  <img src="assets/demo-final-five.png" alt="Shamash Logo" width="180"/>
  <img src="assets/demo-final-two.png" alt="Shamash Logo" width="180"/>
  <br>
  <img src="assets/demo-final-three.png" alt="Shamash Logo" width="180"/>
  <img src="assets/demo-final-four.png" alt="Shamash Logo" width="180"/>
  <br>
  <img src="assets/demo-final.png" alt="Shamash Logo" width="180"/>
</p>


## License

[LICENSE.md](LICENSE.md)

## Changelog

[Change-log](./CHANGELOG.md)
