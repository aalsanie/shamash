<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash Logo" width="180"/>
</p>

[![current release](https://img.shields.io/badge/release-0.60.1-green)](https://github.com/aalsanie/shamash/releases) | [![install plugin](https://img.shields.io/badge/jetbrains-plugin-red)](https://plugins.jetbrains.com/plugin/29504-shamash) | [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

# Shamash

Shamash is a JVM architecture enforcement tool for teams that want to **define, validate, and maintain architectural boundaries**.

It provides two complementary engines:

- **ASM:** analyzes compiled bytecode and JARs to detect architectural drift, forbidden dependencies, and dependency graph violations, without requiring source code.

- **PSI:** analyzes source code using IntelliJ PSI and a configurable YAML schema, providing architectural dashboards, inline suppressions, guided fixes, and exportable reports.

### What It Offers?
- Configurable architecture rules (roles, placement, dependencies)
- Source-level architecture validation via PSI
- Bytecode-level architecture validation via ASM
- Bytecode aware codebase scanner
- Bytecode inspection
- Dependency / call graph analysis
- Dead code / deprecation / shadow usage detection - under development
- JVM internals visibility - under development
- Architectural Dashboard
- Instant IDE feedback and exportable reports

### Documentation & Hands-on Example
[Documentation & Test Bed application](./docs/psi/README.md)

### How to use as an intellij plugin
- Refer to [documentation](./docs/psi/README.md) for more details around configurability
- Download the plugin from intellij marketplace or build locally (Future milestone: CLI: under development)
- Open Shamash ASM - left hand panel: for bytecode analysis and export findings
  - Press Shamash logo to analyze and view finding, hierarchy, analysis, and more.
- Open Shamash PSI - left hand panel: to configure your architecture and export findings
  - create your psi.yml using ui or manually
  - validate your rules
  - run a scan and analyze your architecture
- Open either dashboards and export your codebase reports including
  - overall architecture score 
  - graphs and reports of current hierarchy, issues and analysis
  - exports of all PSI findings, violations and fixes

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
gradlew.bat spotlessApply
gradlew clean runIde
```

All inspections are deterministic, reversible, and framework-aware where necessary.


## License
[LICENSE](LICENSE.md)

## Changelog
[CHANGELOG](./CHANGELOG.md)
