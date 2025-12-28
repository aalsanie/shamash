<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash Logo" width="180"/>
</p>

[![current release](https://img.shields.io/badge/release-0.3.0-green)](https://github.com/aalsanie/shamash/releases) | [![install plugin](https://img.shields.io/badge/jetbrains-plugin-red)](https://plugins.jetbrains.com/plugin/29504-shamash) | [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

# Shamash

A bytecode aware codebase scanner, architectural dashboard for JVM projects.

### What It Offers?
- Bytecode inspection
- Dependency / call graph analysis
- Dead code / deprecation / shadow usage detection
- JVM internals visibility
- Architectural Dashboard
  - Hierarchy: search panel of project's hierarchical structure
  - Hotspots: dynamic analysis that shows architectural violations
  - Findings: displays severity/violations
  - violations of clean code - strict rules/strategy/scoring to avoid false positive
  - violations of clean architecture - strict rules/strategy/scoring to avoid false positive

### How to use
- Download the plugin from intellij marketplace or build locally
- Run shamash scan from your left hand side or using 
  - Tools → Shamash: run scan
  - Tools → Shamash dashboard
- Open the dashboard and see your codebase reports including
  - overall architecture score 
  - graphs and reports of current hierarchy, issues and fixes

### Local Setup
To verify
```shell
./gradlew runPluginVerifier
```

To build
```shell

./gradlew clean buildplugin
```
To run
```shell
gradlew.bat spotlessApply --stacktrace
gradlew clean runIde
```

All inspections are deterministic, reversible, and framework-aware where necessary.

<p align="left">
  <img src="assets/demo-final-four.png" alt="Shamash demo" width="180"/>
</p>


## License
[LICENSE](LICENSE.md)

## Changelog
[CHANGELOG](./CHANGELOG.md)
