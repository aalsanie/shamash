
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

`shamash-cli` runs the ASM engine: validate config, scan bytecode, produce findings, facts, graphs, analysis and export reports.

### Install

Download the latest CLI from the GitHub Releases assets:

- `shamash-cli-<version>.zip`
- `shamash-cli-<version>.zip.sha256` (optional checksum verification)

Unzip it, then run the binary:

- **macOS/Linux:** `bin/shamash`
- **Windows:** `bin/shamash.bat`

(Optional) add the `bin/` directory to your `PATH`.

### Schema Examples

```psi.yml:```[](shamash-psi-core/src/main/resources/shamash/psi/schema/v1/shamash-psi.reference.yml)

```asm.yml:``` [asm reference](shamash-asm-core/src/main/resources/shamash/asm/schema/v1/shamash-asm.reference.yml)

> See [pit-violation yaml configurations if you want to see all rules violator](/docs/asm/pit-violation/src/main/resources/shamash/configs/asm.yml)

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

#### `shamash scan --export-facts --facts-format JSONL_GZ`
Facts are a streamable snapshot of the dependency graph (classes + edges). 
They’re exported as JSONL (one record per line) and typically compressed as facts.jsonl.gz so large graphs stay manageable.

Enable facts in configuration `asm.yml`:
```yaml
export:
  enabled: true
  artifacts:
    facts:
      enabled: true
      format: JSONL_GZ #can be json but not advised in large codebases
```

```shell
# Build first so bytecode exists
./gradlew assemble

# Run scan and force facts export (writes under .shamash/ by default)
shamash scan --export-facts

# Optionally choose output format explicitly
shamash scan --export-facts --facts-format JSONL_GZ
shamash scan --export-facts --facts-format JSON

# Use facts command to read exported file and print summaries
# Summaries: totals + top packages + top fan-in/out
shamash facts .shamash/facts.jsonl.gz

# JSON is also supported
shamash facts .shamash/facts.json

# Cap the number of unique keys tracked for fan-in/out and package stats (default: 200000)
shamash facts .shamash/facts.jsonl.gz --max-keys 100000

# Limit to one class
shamash facts .shamash/facts.jsonl.gz --class com.acme.app.service.UserService

# Limit to a package prefix
shamash facts .shamash/facts.jsonl.gz --package com.acme.app.service

```

### CLI overrides

```shell
shamash scan --project . \
  --scope PROJECT_ONLY \      # PROJECT_ONLY | ALL_SOURCES | PROJECT_WITH_EXTERNAL_BUCKETS
  --follow-symlinks false \   # true | false
  --max-classes 50000 \
  --max-jar-bytes 50000000 \
  --max-class-bytes 2000000
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