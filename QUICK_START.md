
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

### CLI analysis
```shell
shamash analysis                        # print summaries from exported analysis (graphs/hotspots/scores)
shamash analysis --dir .shamash/out/asm # export output directory (default: ./.shamash/out/asm)
shamash analysis --top 10               # how many entries to print per section (default: 5)
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

### Custom registries (Advanced)
If you want to provide your own ASM registry (custom rules / contracts), see: **[REGISTRY_GUIDE.md](./REGISTRY_GUIDE.md)**

### Gradle kotlin DSL

`build.gradle.kts:`

```kotlin
import java.net.URI
import java.security.MessageDigest

// ------------------------------
// Shamash CLI (download + JavaExec)
// ------------------------------
val shamashVersion = providers.gradleProperty("shamash.version").orElse("0.90.0")
val shamashCliUrl = providers.gradleProperty("shamash.cliUrl").orElse(
  shamashVersion.map { v ->
    // Default follows GitHub release asset convention.
    // If your asset name/path differs, override with -Pshamash.cliUrl=...
    "https://github.com/aalsanie/shamash/releases/download/$v/shamash-cli-$v.zip"
  }
)
val shamashCliSha256 = providers.gradleProperty("shamash.sha256").orNull // optional

val shamashToolsDir = layout.buildDirectory.dir("tools/shamash")
val shamashZipFile = layout.buildDirectory.file(
  shamashVersion.map { v -> "tools/shamash/shamash-cli-$v.zip" }
)
val shamashUnpackDir = layout.buildDirectory.dir(
  shamashVersion.map { v -> "tools/shamash/unpacked/$v" }
)

fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

val downloadShamashCli = tasks.register("downloadShamashCli") {
  group = "verification"
  description = "Downloads Shamash CLI distribution zip (if missing)."

  outputs.file(shamashZipFile)

  doLast {
    val outFile = shamashZipFile.get().asFile
    outFile.parentFile.mkdirs()

    // Skip download if present + (optional) checksum matches
    if (outFile.exists()) {
      if (shamashCliSha256 != null) {
        val existing = sha256Hex(outFile.readBytes())
        check(existing.equals(shamashCliSha256, ignoreCase = true)) {
          "Shamash CLI zip exists but SHA-256 mismatch.\nExpected: $shamashCliSha256\nActual  : $existing\nDelete ${outFile.absolutePath} and re-run."
        }
      }
      return@doLast
    }

    val url = shamashCliUrl.get()
    val bytes = URI(url).toURL().openStream().use { it.readBytes() }
    outFile.writeBytes(bytes)

    if (shamashCliSha256 != null) {
      val actual = sha256Hex(bytes)
      check(actual.equals(shamashCliSha256, ignoreCase = true)) {
        "Downloaded Shamash CLI zip SHA-256 mismatch.\nExpected: $shamashCliSha256\nActual  : $actual\nURL     : $url"
      }
    }
  }
}

val unpackShamashCli = tasks.register("unpackShamashCli", Copy::class.java) {
  group = "verification"
  description = "Unpacks Shamash CLI distribution zip."

  dependsOn(downloadShamashCli)

  from(zipTree(shamashZipFile))
  into(shamashUnpackDir)

  // Make it cache-friendly
  inputs.file(shamashZipFile)
  outputs.dir(shamashUnpackDir)
}

/**
 * Resolves the "lib" directory inside the unpacked distribution.
 * The application plugin typically produces: <root>/lib/*.jar (and <root>/bin/*).
 * We search robustly to avoid depending on the top-level folder name inside the zip.
 */
fun resolveShamashLibDir(): File {
  val base = shamashUnpackDir.get().asFile
  check(base.exists()) { "Shamash CLI not unpacked at: ${base.absolutePath}" }

  // 1) common: base/<distRoot>/lib
  base.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
    val lib = File(dir, "lib")
    if (lib.isDirectory) return lib
  }

  // 2) fallback: base/lib
  val direct = File(base, "lib")
  if (direct.isDirectory) return direct

  // 3) last resort: walk
  val found = base.walkTopDown().firstOrNull { it.isDirectory && it.name == "lib" && it.listFiles()?.any { f -> f.extension == "jar" } == true }
  check(found != null) { "Could not find Shamash 'lib' directory under: ${base.absolutePath}" }
  return found
}

// ------------------------------
// Public tasks you actually run
// ------------------------------

// 1) Generate config (only if you want a Gradle entrypoint for `shamash init`)
tasks.register<JavaExec>("shamashInit") {
  group = "verification"
  description = "Creates shamash/configs/asm.yml from embedded reference (Shamash CLI: init)."

  dependsOn(unpackShamashCli)

  mainClass.set("io.shamash.cli.MainKt")

  doFirst {
    val libDir = resolveShamashLibDir()
    classpath = fileTree(libDir) { include("*.jar") }
  }

  // Writes to shamash/configs/asm.yml under the root project by default
  args("init", "--project", rootProject.projectDir.absolutePath)
}

// 2) Validate config (Shamash CLI: validate)
tasks.register<JavaExec>("shamashValidate") {
  group = "verification"
  description = "Validates shamash/configs/asm.yml (Shamash CLI: validate)."

  dependsOn(unpackShamashCli)

  mainClass.set("io.shamash.cli.MainKt")

  doFirst {
    val libDir = resolveShamashLibDir()
    classpath = fileTree(libDir) { include("*.jar") }
  }

  // If you want an explicit config path:
  // args("validate", "--project", rootProject.projectDir.absolutePath, "--config", "shamash/configs/asm.yml")
  args("validate", "--project", rootProject.projectDir.absolutePath)
}

// 3) Scan gate (Shamash CLI: scan) — wire this into `check`
tasks.register<JavaExec>("shamashScan") {
  group = "verification"
  description = "Runs Shamash ASM scan as a CI-friendly verification gate (Shamash CLI: scan)."

  dependsOn(unpackShamashCli)
  // Ensure build outputs exist. If your build is multi-module, prefer running from root after `build`.
  dependsOn("classes")

  mainClass.set("io.shamash.cli.MainKt")

  // Good defaults for CI logs; you can tweak via -Pshamash.failOn=... etc.
  val failOn = providers.gradleProperty("shamash.failOn").orElse("ERROR")
  val printFindings = providers.gradleProperty("shamash.printFindings").orElse("false")
  val printAnalysisSummary = providers.gradleProperty("shamash.printAnalysisSummary").orElse("true")

  doFirst {
    val libDir = resolveShamashLibDir()
    classpath = fileTree(libDir) { include("*.jar") }
  }

  args(
    "scan",
    "--project", rootProject.projectDir.absolutePath,
    "--fail-on", failOn.get(),
    "--print-findings", printFindings.get(),
    "--print-analysis-summary", printAnalysisSummary.get(),
  )

  // If you want to force facts export regardless of config:
  // args("--export-facts")
}

tasks.named("check") {
  dependsOn("shamashScan")
}
```

`gradle.properties:`

```properties
# Pin version (matches the GitHub release tag you're consuming)
shamash.version=0.90.0

# Optional: override if your release asset name/path differs
# shamash.cliUrl=https://github.com/aalsanie/shamash/releases/download/0.90.0/shamash-cli-0.90.0.zip

# Optional supply-chain guard (recommended once you have the SHA-256)
# shamash.sha256=<paste-sha256-here>

# CI behavior knobs
shamash.failOn=ERROR
shamash.printFindings=false
shamash.printAnalysisSummary=true
```

### Usage

```shell
# one-time: generate the starter config in shamash/configs/asm.yml
gradlew shamashInit

# validate config
gradlew shamashValidate

# run scan gate (also runs on ./gradlew check now)
gradlew shamashScan
gradlew check
```