
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

## CLI (ASM)

`shamash-cli` runs the ASM engine: validate config, scan bytecode, produce findings, and export reports.

### Install

Download the latest CLI from the GitHub Releases assets:

- `shamash-cli-<version>.zip`
- `shamash-cli-<version>.zip.sha256` (optional checksum verification)

Unzip it, then run the binary:

- **macOS/Linux:** `bin/shamash`
- **Windows:** `bin/shamash.bat`

(Optional) add the `bin/` directory to your `PATH`.

### Schema Examples

```psi.yml:```

```yaml
version: 1

project:
  validation:
    unknownRule: ERROR
  rootPackage:
    mode: EXPLICIT
    value: "com.acme.app"
  sourceGlobs:
    include:
      - "src/main/java/**"
      - "src/main/kotlin/**"
    exclude:
      - "**/build/**"
      - "**/.gradle/**"
      - "**/out/**"
      - "**/.idea/**"

roles:
  controller:
    priority: 100
    match:
      anyOf:
        - annotation: "org.springframework.web.bind.annotation.RestController"
        - classNameEndsWith: "Controller"

  service:
    priority: 80
    match:
      anyOf:
        - annotation: "org.springframework.stereotype.Service"
        - classNameEndsWith: "Service"

  repository:
    priority: 60
    match:
      anyOf:
        - annotation: "org.springframework.stereotype.Repository"
        - classNameEndsWithAny: ["Repository", "Dao"]

rules:
  - type: "arch"
    name: "forbiddenRoleDependencies"
    roles: null
    enabled: true
    severity: ERROR
    params:
      kinds: ["methodCall", "fieldType", "parameterType", "returnType", "extends", "implements", "annotationType"]
      forbidden:
        - from: "controller"
          to: ["repository"]
          message: "Controllers must not depend directly on repositories"
        - from: "service"
          to: ["controller"]
```

```asm.yml:```
```yaml
version: 1

project:
  bytecode:
    roots:
      - "."
    outputsGlobs:
      include:
        - "**/build/classes/kotlin/main/**"
        - "**/build/classes/java/main/**"
      exclude: []
    jarGlobs:
      include:
        - "**/*.jar"
      exclude:
        - "**/*-sources.jar"
        - "**/*-javadoc.jar"

  scan:
    scope: PROJECT_ONLY
    followSymlinks: false
    maxClasses: null
    maxJarBytes: null
    maxClassBytes: null

  validation:
    unknownRule: ERROR

roles:
  api:
    priority: 10
    description: "Public API layer"
    match:
      packageContainsSegment: "api"

  service:
    priority: 20
    description: "Application/service layer"
    match:
      packageContainsSegment: "service"

  data:
    priority: 30
    description: "Persistence/infrastructure"
    match:
      packageContainsSegment: "data"

analysis:
  graphs:
    enabled: true
    granularity: PACKAGE
    includeExternalBuckets: false

  hotspots:
    enabled: false
    topN: 25
    includeExternal: false

  scoring:
    enabled: false
    model: V1
    godClass:
      enabled: true
      weights: null
      thresholds: null
    overall:
      enabled: true
      weights: null
      thresholds: null

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
        api:
          - data
        service:
          - data

exceptions: []

baseline:
  mode: NONE

export:
  enabled: true
  formats: [JSON,HTML,SARIF,XML]
  overwrite: false
```

### CLI Quick start

From your project root:

```bash
# 1) Create a starter config (writes to shamash/configs/asm.yml)
shamash init

# 2) Validate the config
shamash validate

# 3) Build your project (so bytecode exists), then run a scan
./gradlew assemble
shamash scan
```

### Commands

#### `shamash init`

Creates `shamash/configs/asm.yml` from the embedded reference.

```bash
shamash init
shamash init --project .
shamash init --force
shamash init --stdout
shamash init --path shamash/configs/asm.yml
```

#### `shamash validate`

Validates `asm.yml` (schema + semantic rules).

```bash
shamash validate
shamash validate --project .
shamash validate --config shamash/configs/asm.yml
```

#### `shamash scan`

Runs ASM scan + analysis and prints a summary.
Exit code is CI-friendly.

```bash
shamash scan
shamash scan --project .
shamash scan --config shamash/configs/asm.yml

# CI threshold: fail if findings include WARNING or higher
shamash scan --fail-on WARNING

# Never fail the process based on findings (report-only mode)
shamash scan --fail-on NONE

# Print all findings to stdout
shamash scan --print-findings
```

### Exit codes (CI)

- `0` success (and findings below fail threshold)
- `2` config error (missing/invalid config)
- `3` runtime error (scan/engine failure)
- `4` findings exceeded `--fail-on` threshold

### Verify checksum (optional)

**macOS/Linux**
```bash
sha256sum -c shamash-cli-<version>.zip.sha256
```

**Windows PowerShell**
```powershell
Get-FileHash .\shamash-cli-<version>.zip -Algorithm SHA256
```