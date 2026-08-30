# Shamash quick start

## 1. Build the JVM project

```bash
./gradlew classes
# or
./mvnw package
```

## 2. Get immediate value without configuration

```bash
shamash scan
```

The first scan uses a built-in report-only discovery profile. It does not write project files and findings do not fail the process.

## 3. Turn discovery into enforcement

```bash
shamash init
shamash validate
```

For Spring-specific role/boundary defaults:

```bash
shamash init --preset spring
```

The full reference remains available for advanced users:

```bash
shamash init --preset reference
```

## 4. Existing codebase? Baseline current debt once

```bash
shamash baseline create
```

Commit the generated config and baseline. Then:

```bash
shamash scan
```

will expose new violations according to the configured threshold/baseline policy.

## 5. CI

```yaml
- run: ./gradlew classes
- uses: aalsanie/shamash@v0.91.0
  with:
    config: shamash/configs/asm.yml
    fail-on: ERROR
```

The action downloads `shamash-cli-0.91.0.zip`, verifies it against `SHA256SUMS.txt`, and propagates Shamash's exit code.

## Useful CLI options

```bash
shamash scan --all-findings
shamash scan --verbose
shamash scan --print-analysis-summary
shamash scan --fail-on WARNING
shamash scan --fail-on NONE
```

`--print-findings` remains accepted for 0.x compatibility but is no longer necessary because findings are shown by default.

## IntelliJ

Install Shamash and open:

```text
Tools → Shamash
```

Use **Build Analysis** for compiled-code enforcement and **Source Analysis** for IDE/source-aware feedback.

## Checksum

Releases publish one canonical checksum file:

```text
SHA256SUMS.txt
```

Linux:

```bash
grep 'shamash-cli-0.91.0.zip' SHA256SUMS.txt | sha256sum -c -
```

macOS:

```bash
EXPECTED=$(awk '/shamash-cli-0.91.0.zip/ {print $1}' SHA256SUMS.txt)
ACTUAL=$(shasum -a 256 shamash-cli-0.91.0.zip | awk '{print $1}')
test "$EXPECTED" = "$ACTUAL"
```

Windows PowerShell:

```powershell
$line = Get-Content .\SHA256SUMS.txt |
  Where-Object { $_ -match '\s+shamash-cli-0\.91\.0\.zip$' } |
  Select-Object -First 1
if (-not $line) { throw "Checksum entry not found" }
$expected = ($line -split '\s+')[0].ToLowerInvariant()
$actual = (Get-FileHash .\shamash-cli-0.91.0.zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expected -ne $actual) { throw "Checksum mismatch" }
"Checksum OK"
```
