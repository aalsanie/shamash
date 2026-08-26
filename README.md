<p align="center">
  <a href="./README.md">English</a> •
  <a href="./docs/README.zh-Hans.md">简体中文</a>
</p>

<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash" width="180"/>
</p>

# Shamash

Shamash is a JVM architecture analysis and enforcement tool.

You define architectural roles and rules in YAML. Shamash analyzes your compiled JVM bytecode, evaluates those rules, and can fail a build when the architecture violates them.

The standalone **CLI + ASM engine** is the primary interface for CI and build-time enforcement.

The optional **IntelliJ plugin** adds an interactive ASM interface and a separate PSI engine for source-aware checks, suppressions, and fixes.

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![License](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](./LICENSE.md)

---

## What you can enforce using the engines

- Can controllers depend directly on repositories?
- Which architectural roles may depend on which other roles?
- Is code depending on forbidden packages?
- Has a dependency cycle appeared?
- Are classes accumulating too many dependencies, fields, or methods?
- Is a package becoming unusually coupled?
- Is code using an annotation that should not appear in a particular part of the system?
- Is one JAR depending on another JAR that it should not?
- Which classes were assigned to each architectural role?
- What dependency graph was derived from the compiled code?
- Which classes and packages are architectural hotspots?
- Did this pull request introduce architecture violations that were not already accepted?

The important part is that these checks are defined as project configuration and can be run in CI rather than existing only as conventions or diagrams.

---

## What engines to use

| Goal                                                  | Module             |
|-------------------------------------------------------|--------------------|
| Enforce architecture in CI                            | **CLI + ASM**      |
| Check the compiled classes/JARs produced by a build   | **CLI + ASM**      |
| Export SARIF, JSON, HTML, or XML reports              | **CLI + ASM**      |
| Inspect extracted bytecode facts                      | **CLI `facts`**    |
| Inspect graphs, hotspots, and architecture scores     | **CLI `analysis`** |
| Explore ASM results interactively                     | **IntelliJ ASM**   |
| Run source-aware architecture/structure rules         | **IntelliJ PSI**   |
| Use source-level suppressions and rule-specific fixes | **IntelliJ PSI**   |

The two engines are related, but they are not interchangeable.

**ASM** is standalone and has no IntelliJ dependency. It operates on compiled `.class` files and JARs and is the engine intended for CI.

**PSI** depends on the IntelliJ Platform. It works from source code and is available through the IntelliJ plugin.

---

# Quick start: CLI and CI

## 1. Install the CLI

Requires **Java 17 or newer**.

Download `shamash-cli-<version>.zip` from the [GitHub Releases](https://github.com/aalsanie/shamash/releases) page and extract it.

Releases also publish `SHA256SUMS.txt` if you want to verify the downloaded ZIP.

On Linux/macOS:

```shell
export SHAMASH_HOME="$PWD/shamash-cli-<version>"
export PATH="$SHAMASH_HOME/bin:$PATH"
shamash version
```

On Windows:

```powershell
$env:SHAMASH_HOME = "$PWD\shamash-cli-<version>"
$env:Path = "$env:SHAMASH_HOME\bin;$env:Path"
shamash version
```

You can add the same `bin` directory to your shell profile or system/user `PATH` if you want a persistent installation.

---

## 2. Create the ASM configuration

From the root of the project you want to analyze:

```shell
shamash init
```

This creates:

```text
shamash/configs/asm.yml
```

Validate it:

```shell
shamash validate
```

`init` copies the reference configuration shipped with Shamash. Treat it as a starting point, not as a recommended architecture for every JVM application.

The generated configuration contains:

- common Gradle, Maven, IntelliJ, and Android bytecode locations
- example architectural roles
- a starter set of architecture and metric rules
- graph, hotspot, and scoring analysis
- report and artifact export
- baseline configuration

Edit the file to describe **your** architecture.

---

## 3. Build your project

ASM analyzes compiled bytecode. It does not compile your application for you.

For Gradle, for example:

```shell
./gradlew assemble
```

For Maven:

```shell
./mvnw package
```

Any build is fine as long as the configured ASM roots/globs can find the resulting classes or JARs.

---

## 4. Run

```shell
shamash scan
```

For more useful CI output:

```shell
shamash scan \
  --print-findings \
  --print-analysis-summary
```

By default, `scan` fails when at least one `ERROR` finding remains after exceptions and baseline processing.

The default failure threshold is equivalent to:

```shell
shamash scan --fail-on ERROR
```

Available thresholds are:

```text
NONE
INFO
WARNING
ERROR
```

With the reference configuration, exported ASM results are written under:

```text
.shamash/out/asm/
```

---

# Using Shamash in CI

A normal CI pipeline is:

```text
compile project
      │
      ▼
compiled classes / JARs
      │
      ▼
Shamash ASM
      │
      ├── extract bytecode facts
      ├── classify architectural roles
      ├── evaluate configured rules
      ├── apply exceptions
      ├── apply baseline
      ├── run graph / hotspot / scoring analysis
      │
      ▼
findings + reports + CI exit code
```

There is no requirement to use GitHub Actions. The CLI can run in any CI system.

A minimal GitHub Actions example looks like this:

```yaml
name: Architecture

on:
  pull_request:
  push:
    branches: [main]

jobs:
  shamash:
    runs-on: ubuntu-latest

    env:
      SHAMASH_VERSION: "0.90.0"

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Build
        run: ./gradlew assemble

      - name: Download Shamash
        run: |
          curl -fsSL \
            -o shamash.zip \
            "https://github.com/aalsanie/shamash/releases/download/${SHAMASH_VERSION}/shamash-cli-${SHAMASH_VERSION}.zip"

          unzip -q shamash.zip -d .shamash-cli

      - name: Check architecture
        run: |
          ./.shamash-cli/shamash-cli-${SHAMASH_VERSION}/bin/shamash \
            scan \
            --fail-on ERROR \
            --print-findings \
            --print-analysis-summary
```

For SARIF integration, enable SARIF in `asm.yml` and upload:

```text
.shamash/out/asm/shamash-report.sarif.json
```

using the code-scanning mechanism provided by your CI platform.

---

# Adoption

A large existing project usually already contains architectural debt.

Requiring every existing violation to be fixed before Shamash can enter CI makes adoption unnecessarily difficult. Therefore, it supports baselines.

The relevant modes are:

```yaml
baseline:
  mode: NONE
```

```yaml
baseline:
  mode: GENERATE
  path: ".shamash/baseline/asm-baseline.json"
```

```yaml
baseline:
  mode: VERIFY
  path: ".shamash/baseline/asm-baseline.json"
```

### `NONE`

No baseline is used.

This is the simplest option for a new project or a project that is already clean enough to enforce immediately.

### `GENERATE`

Fingerprints the current findings and writes them to the baseline file.

The findings are **not hidden from the current scan**. `GENERATE` records the accepted state; it does not pretend the violations do not exist.

For an initial adoption pass you can use:

```shell
shamash scan --fail-on NONE
```

Review the reports and generated baseline before committing it.

### `VERIFY`

Loads the baseline and suppresses findings whose fingerprints already exist in it.

New findings remain visible and can fail CI.

A practical existing-project rollout is:

```text
1. Create and customize asm.yml
2. Build the project
3. Run with baseline GENERATE and --fail-on NONE
4. Review the current findings
5. Commit the accepted baseline
6. Change baseline mode to VERIFY
7. Run Shamash on every pull request
```

This lets a team stop architecture from getting worse without first pretending that years of existing debt can be fixed in one change.

---

# Configuration model

ASM configuration is versioned:

```yaml
version: 1
```

The main sections are:

```yaml
version: 1

project:
  bytecode: ...
  scan: ...
  validation: ...

roles:
  ...

analysis:
  ...

rules:
  ...

exceptions:
  ...

baseline:
  ...

export:
  ...
```

```shell
shamash init
```

Generates a complete valid reference rather than writing the entire schema by hand.

---

## Bytecode inputs

`project.bytecode` controls where Shamash looks for classes and JARs.

Example:

```yaml
project:
  bytecode:
    roots:
      - "."

    outputsGlobs:
      include:
        - "**/build/classes/**"
        - "**/target/classes/**"
      exclude:
        - "**/test/**"

    jarGlobs:
      include:
        - "**/build/libs/*.jar"
        - "**/target/*.jar"
      exclude:
        - "**/*-sources.jar"
        - "**/*-javadoc.jar"
```

The scanner walks the configured roots and includes class-output directories and JARs matching these globs.

It does **not** automatically resolve your Gradle or Maven dependency graph. If you want external JARs analyzed, they must be reachable through the roots and globs you configure.

---

## Scan scope and limits

ASM supports:

```yaml
project:
  scan:
    scope: PROJECT_ONLY
    followSymlinks: false
    maxClasses: 50000
```

Available scopes are:

- `PROJECT_ONLY`
- `PROJECT_WITH_EXTERNAL_BUCKETS`
- `ALL_SOURCES`

Limits can also be overridden for one CLI run without modifying YAML:

```shell
shamash scan --max-classes 100000
```

```shell
shamash scan --max-jar-bytes 50000000
```

```shell
shamash scan --max-class-bytes 5000000
```

```shell
shamash scan --scope PROJECT_WITH_EXTERNAL_BUCKETS
```

```shell
shamash scan --follow-symlinks true
```

The CLI reports which overrides were applied.

---

# Architectural roles

Roles let rules describe architecture in terms such as `controller`, `service`, or `repository` rather than hard-coding every class.

For example:

```yaml
roles:
  controller:
    priority: 100
    description: "HTTP/API entry points"
    match:
      anyOf:
        - packageContainsSegment: "controller"
        - classNameEndsWith: "Controller"
        - annotation: "org.springframework.web.bind.annotation.RestController"

  service:
    priority: 80
    description: "Application services"
    match:
      anyOf:
        - packageContainsSegment: "service"
        - classNameEndsWith: "Service"

  repository:
    priority: 70
    description: "Persistence"
    match:
      anyOf:
        - packageContainsSegment: "repository"
        - classNameEndsWith: "Repository"
```

ASM role matchers support boolean composition:

```yaml
anyOf:
allOf:
not:
```

and bytecode-derived matcher leaves including:

```yaml
packageRegex:
packageContainsSegment:
classNameEndsWith:
annotation:
annotationPrefix:
```

If more than one role matches a class, higher `priority` wins. Ties are resolved deterministically by role ID.

A class therefore has one winning ASM role.

When role export is enabled, the resulting classification can be inspected in:

```text
.shamash/out/asm/roles.json
```

or from the IntelliJ ASM **Roles** tab.

---

# Example architecture rule

Suppose controllers are allowed to call services but must not depend directly on repositories.

A rule can express that directly:

```yaml
rules:
  - type: arch
    name: forbiddenRoleDependencies
    roles: null
    enabled: true
    severity: ERROR
    scope: null
    params:
      direction: transitive
      forbidden:
        controller:
          - repository
```

This is the core model:

```text
bytecode facts
   ↓
roles
   ↓
rules
   ↓
findings
```

Roles describe **what a class is**.

Rules describe **what those classes may do**.

---

# ASM rule catalog

The default ASM registry contains rules across architecture, graph structure, metrics, API usage, and bytecode origin.

## Architecture

| Rule                             | Purpose                                                |
|----------------------------------|--------------------------------------------------------|
| `arch.allowedRoleDependencies`   | Define which role-to-role dependencies are permitted   |
| `arch.forbiddenRoleDependencies` | Forbid selected role-to-role dependencies              |
| `arch.allowedPackages`           | Restrict dependencies to allowed packages              |
| `arch.forbiddenPackages`         | Forbid dependencies on selected packages               |

## Dependency graph

| Rule                         | Purpose                             |
|------------------------------|-------------------------------------|
| `graph.noCycles`             | Reject dependency cycles            |
| `graph.maxCycles`            | Limit the number of detected cycles |
| `graph.maxEdgeCount`         | Limit dependency graph edge count   |
| `graph.maxDependencyDensity` | Limit graph dependency density      |

## Metrics

| Rule                         | Purpose                                      |
|------------------------------|----------------------------------------------|
| `metrics.maxFanIn`           | Limit incoming dependency count              |
| `metrics.maxFanOut`          | Limit outgoing dependency count              |
| `metrics.maxFieldsPerClass`  | Limit fields per class                       |
| `metrics.maxMethodsPerClass` | Limit methods per class                      |
| `metrics.maxPackageSpread`   | Limit the number of packages a class reaches |

## API / bytecode usage

| Rule                                | Purpose                                         |
|-------------------------------------|-------------------------------------------------|
| `api.maxPublicTypes`                | Limit public types in the configured scope      |
| `api.forbiddenInternalNamePatterns` | Reject matching internal JVM type-name patterns |
| `api.forbiddenAnnotationUsage`      | Reject configured annotation usage              |

## Origin

| Rule                              | Purpose                                          |
|-----------------------------------|--------------------------------------------------|
| `origin.forbiddenJarDependencies` | Forbid dependencies between matching JAR origins |

Complete per-rule configurations are available under:

[`docs/asm/schema/v1/examples`](./docs/asm/schema/v1/examples)

The reference `asm.yml` enables only a starter subset of the available rules. A rule being built into Shamash does not mean it is automatically enabled for your project.

---

# What ASM actually analyzes

ASM is not limited to package names.

For each scanned class, Shamash extracts facts from JVM bytecode using ASM.

Dependency evidence includes:

- superclass relationships
- implemented interfaces
- annotations
- field types
- method parameter types
- method return types
- declared exception types
- object creation and other type instructions
- field access
- method calls
- caught exception types
- class/type constants
- method handles
- `invokedynamic` bootstrap metadata

Those facts are aggregated into the dependency model used by architecture rules and analysis.

When bytecode contains source/debug information, findings can also carry source-file and line information. That information is naturally less precise when classes were compiled without the relevant debug metadata.

---

# Rule scope

A rule does not have to apply to the entire project.

ASM rules can be narrowed using role, package, and file/origin scope:

```yaml
scope:
  includeRoles:
    - service

  excludeRoles:
    - repository

  includePackages:
    - "^com\\.acme\\..*"

  excludePackages:
    - ".*\\.generated(\\..*)?$"

  includeGlobs:
    - "**/main/**"

  excludeGlobs:
    - "**/generated/**"
```

This lets one rule implementation enforce different constraints in different parts of a system.

---

# Exceptions

Sometimes a violation is intentional and should be documented rather than added permanently to a global baseline.

ASM exceptions can target findings using fields such as:

- exact rule ID
- rule type/name
- architectural role
- exact internal class name
- class-name regex
- package regex
- origin-path regex
- file/path glob

Example:

```yaml
exceptions:
  - id: "legacy-payment-adapter"
    enabled: true
    reason: "Removed after payment migration"
    match:
      ruleId: "arch.forbiddenRoleDependencies"
      packageRegex: "^com\\.acme\\.legacy\\.payment(\\..*)?$"
```

Use exceptions for deliberate, explainable cases.

Use a baseline when the objective is to freeze a larger body of existing findings and prevent new ones.

---

# Architecture analysis

Rule enforcement is only one part of the ASM pipeline.

you can also derive analysis outputs from the bytecode dependency model.

## Graphs

Graph analysis includes:

- class/package/module granularity
- nodes and adjacency
- edge counts
- strongly connected components
- cyclic strongly connected components
- representative cycle paths

Example configuration:

```yaml
analysis:
  graphs:
    enabled: true
    granularity: PACKAGE
    includeExternalBuckets: false
```

## Hotspots

Hotspots can be calculated for classes and packages using:

- fan-in
- fan-out
- package spread
- method count

## Scoring

The V1 scoring model can calculate:

- class-level god-class scores
- package-level overall architecture scores
- `OK`, `WARN`, and `ERROR` bands

Weights and thresholds are configurable.

These scores are analysis signals, not substitutes for project-specific architecture rules.

---

# Inspect analysis from the CLI

With the reference export configuration:

```shell
shamash analysis --dir .shamash/out/asm
```

Use:

```shell
shamash analysis \
  --dir .shamash/out/asm \
  --top 10
```

to change how many cycles, hotspots, and scores are printed.

Analysis sidecars include:

```text
analysis-graphs.json
analysis-hotspots.json
analysis-scores.json
```

---

# Inspect raw ASM facts

Facts can be exported as JSON or compressed JSON Lines.

The reference configuration uses:

```text
.shamash/out/asm/facts.jsonl.gz
```

Summarize it:

```shell
shamash facts \
  --path .shamash/out/asm/facts.jsonl.gz
```

Filter for a class:

```shell
shamash facts \
  --path .shamash/out/asm/facts.jsonl.gz \
  --class com.acme.payment.PaymentService
```

Filter by package:

```shell
shamash facts \
  --path .shamash/out/asm/facts.jsonl.gz \
  --package com.acme.payment
```

Inspect edges:

```shell
shamash facts \
  --path .shamash/out/asm/facts.jsonl.gz \
  --edge-from com.acme.payment
```

or:

```shell
shamash facts \
  --path .shamash/out/asm/facts.jsonl.gz \
  --edge-to com.acme.repository
```

This is useful when a finding is not enough, and you want to inspect the evidence we derived from the bytecode.

---

# Reports and exported artifacts

ASM can export reports in:

```text
JSON
SARIF
HTML
XML
```

The corresponding default file names are:

```text
shamash-report.json
shamash-report.sarif.json
shamash-report.html
shamash-report.xml
```

Additional ASM artifacts can include:

```text
facts.jsonl.gz
facts.json
roles.json
analysis-graphs.json
analysis-hotspots.json
analysis-scores.json
```

The reference configuration writes them under:

```text
.shamash/out/asm/
```

JSON is useful for programmatic consumers.

SARIF is suitable for code-scanning systems.

HTML is useful for human review.

XML is available for systems that consume XML reports.

---

# CLI reference

## `init`

Create the embedded reference ASM config:

```shell
shamash init
```

Useful options:

```shell
shamash init --stdout
shamash init --force
shamash init --path path/to/asm.yml
shamash init --project path/to/project
```

---

## `validate`

Validate both JSON-schema structure and semantic constraints:

```shell
shamash validate
```

or:

```shell
shamash validate --config path/to/asm.yml
```

Without `--config`, the CLI discovers:

```text
shamash/configs/asm.yml
shamash/configs/asm.yaml
```

under the selected project root.

---

## `scan`

Run the complete ASM pipeline:

```shell
shamash scan
```

Common options:

```text
--project
--config
--registry

--scope
--follow-symlinks
--max-classes
--max-jar-bytes
--max-class-bytes

--export-facts
--facts-format

--fail-on

--print-findings
--print-analysis-summary
```

For example:

```shell
shamash scan \
  --fail-on WARNING \
  --print-findings \
  --print-analysis-summary
```

---

## `facts`

Inspect an exported fact file:

```shell
shamash facts --path <facts.json|facts.jsonl.gz>
```

Filters include:

```text
--class
--package
--edge-from
--edge-to
--top
```

---

## `analysis`

Read exported graph/hotspot/score sidecars:

```shell
shamash analysis --dir .shamash/out/asm
```

---

## `registry`

List ASM rule-registry providers available to the CLI:

```shell
shamash registry list
```

The built-in registry is used when no custom registry is selected.

---

## `version`

```shell
shamash version
```

---

# CLI exit codes

The CLI uses distinct exit codes so CI can distinguish architecture failure from tool failure.

|  Code | Meaning                                               |
|------:|-------------------------------------------------------|
|   `0` | Successful execution                                  |
|   `2` | Configuration error                                   |
|   `3` | Runtime / scan / engine error                         |
|   `4` | Findings reached the configured `--fail-on` threshold |

This means a build rejected because it found an architecture violation is distinguishable from a broken execution.

---

# IntelliJ plugin

The [Shamash IntelliJ plugin](https://plugins.jetbrains.com/plugin/29504-shamash) is an optional developer interface around both analysis engines.

It does not replace the CLI as the CI integration.

The plugin exposes two separate tool windows.

## ASM

The ASM tool window works with the same compiled-bytecode engine used by the CLI.

Current tabs are:

```text
Dashboard
Findings
Facts
Analysis
Roles
Config
Settings
```

From IntelliJ you can:

- create an ASM config from the shipped reference
- validate configuration
- run ASM scans
- inspect findings
- inspect exported/in-memory facts
- inspect role classification
- inspect graphs, hotspots, and scoring
- change run-time scan overrides
- export reports

Build the project before running ASM so compiled classes are available.

---

## PSI

PSI is the source-aware engine.

Its built-in rules currently include:

| Rule                             | Purpose                                                       |
|----------------------------------|---------------------------------------------------------------|
| `arch.forbiddenRoleDependencies` | Source-level role dependency restrictions                     |
| `deadcode.unusedPrivateMembers`  | Detect unused private fields, methods, and optionally classes |
| `metrics.maxMethodsByRole`       | Different method-count limits by architectural role           |
| `naming.bannedSuffixes`          | Reject configured class-name suffixes                         |
| `packages.rolePlacement`         | Require roles to live in matching package locations           |
| `packages.rootPackage`           | Enforce project root-package conventions                      |

The PSI tool window provides:

```text
Config
Dashboard
Findings
```

It scans operate on IntelliJ project/module content roots and run through IntelliJ's smart/read-mode APIs so scans do not race project indexing.

---

# PSI suppressions

PSI supports source-level suppression when an exception belongs with the code rather than in central configuration.

A comment can suppress a specific rule:

```java
// shamash:ignore naming.bannedSuffixes
class LegacyServiceImpl {
}
```

or all Shamash findings for the target:

```java
// shamash:ignore all
```

Kotlin can use:

```kotlin
@Suppress("shamash:naming.bannedSuffixes")
class LegacyServiceImpl
```

and Java can use:

```java
@SuppressWarnings("shamash:naming.bannedSuffixes")
class LegacyServiceImpl {
}
```

The plugin also contains rule-specific fix providers. Some fixes are source changes, while others intentionally create a local suppression; they should not be interpreted as automatic architectural refactoring.

---

# ASM and PSI are intentionally different

A useful mental model is:

```text
                     ┌─────────────────────────┐
source code ────────►│ PSI / IntelliJ          │
                     │ source-aware rules      │
                     │ suppressions / fixes    │
                     └─────────────────────────┘


                      ┌─────────────────────────┐
compiled bytecode ──► │ ASM / standalone        │
.class + JAR          │ CI enforcement          │
                      │ facts / graph / reports │
                      └─────────────────────────┘
```

---

# Custom ASM rule registries

It supports alternate rule registries. The CLI discovers providers using Java `ServiceLoader`.

The IntelliJ plugin exposes an ASM rule-registry extension point.

Select a CLI registry with:

```shell
shamash registry list
```

and:

```shell
shamash scan --registry <id>
```

Registry selection changes the available rule implementation catalog; it does not replace the project's `asm.yml`.

Custom registries are currently an advanced integration surface. In particular, `shamash-asm-core` is not currently published as a Maven dependency for third-party registry development.

See:

[`REGISTRY_GUIDE.md`](./REGISTRY_GUIDE.md)

---

# Examples

ASM rule examples:

[`docs/asm/schema/v1/examples`](./docs/asm/schema/v1/examples)

ASM test-bed application:

[`docs/asm/pit-violation`](./docs/asm/pit-violation)

PSI schema documentation and examples:

[`docs/psi/schema/v1`](./docs/psi/schema/v1)

PSI test-bed application:

[`docs/psi/pit-violation`](./docs/psi/pit-violation)

Example exported reports:

[`docs/reports_samples`](./docs/reports_samples)

---

# Building

```shell
./gradlew spotlessCheck test
```

```shell
./gradlew :shamash-cli:distZip
```

```shell
./gradlew :shamash-intellij-plugin:buildPlugin
```

---

# Contributing

See:

[`CONTRIBUTING.md`](./CONTRIBUTING.md)

Please also read:

[`CODE_OF_CONDUCT.md`](./CODE_OF_CONDUCT.md)

For security issues:

[`SECURITY.md`](./SECURITY.md)

---

# License

Shamash is licensed under the [Apache License 2.0](./LICENSE.md).