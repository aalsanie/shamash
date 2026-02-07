
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
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

plugins {
   base
}

val shamashVersion = providers.gradleProperty("shamash.version").orElse("0.90.0")
val shamashCliUrl = providers.gradleProperty("shamash.cliUrl").orElse(
   shamashVersion.map { v ->
      "https://github.com/aalsanie/shamash/releases/download/$v/shamash-cli-$v.zip"
   }
)
val shamashCliSha256 = providers.gradleProperty("shamash.sha256").orNull // optional

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
   description = "Downloads Shamash CLI distribution zip (cache-correct, checksum optional)."

   inputs.property("shamash.version", shamashVersion)
   inputs.property("shamash.cliUrl", shamashCliUrl)
   inputs.property("shamash.sha256", shamashCliSha256 ?: "")

   outputs.file(shamashZipFile)

   doLast {
      val outFile = shamashZipFile.get().asFile
      outFile.parentFile.mkdirs()

      val url = shamashCliUrl.get()

      if (outFile.exists()) {
         if (shamashCliSha256 != null) {
            val existing = sha256Hex(outFile.readBytes())
            check(existing.equals(shamashCliSha256, ignoreCase = true)) {
               "Shamash CLI zip exists but SHA-256 mismatch.\nExpected: $shamashCliSha256\nActual  : $existing\nFile    : ${outFile.absolutePath}\nDelete the file or fix shamash.sha256."
            }
         }
         return@doLast
      }

      val bytes = URI(url).toURL().openStream().use { it.readBytes() }

      if (shamashCliSha256 != null) {
         val actual = sha256Hex(bytes)
         check(actual.equals(shamashCliSha256, ignoreCase = true)) {
            "Downloaded Shamash CLI zip SHA-256 mismatch.\nExpected: $shamashCliSha256\nActual  : $actual\nURL     : $url"
         }
      }

      outFile.writeBytes(bytes)
   }
}

val unpackShamashCli = tasks.register("unpackShamashCli", Copy::class) {
   group = "verification"
   description = "Unpacks Shamash CLI distribution zip."

   dependsOn(downloadShamashCli)

   from(zipTree(shamashZipFile))
   into(shamashUnpackDir)

   inputs.file(shamashZipFile)
   outputs.dir(shamashUnpackDir)
}

/**
 * Finds the 'lib' directory inside the unpacked distribution without assuming zip root folder name.
 */
fun resolveShamashLibDir(): java.io.File {
   val base = shamashUnpackDir.get().asFile
   check(base.exists()) { "Shamash CLI not unpacked at: ${base.absolutePath}" }

   base.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
      val lib = java.io.File(dir, "lib")
      if (lib.isDirectory && lib.listFiles()?.any { it.extension == "jar" } == true) return lib
   }
   
   val direct = java.io.File(base, "lib")
   if (direct.isDirectory && direct.listFiles()?.any { it.extension == "jar" } == true) return direct

   val found = base.walkTopDown().firstOrNull {
      it.isDirectory && it.name == "lib" && it.listFiles()?.any { f -> f.extension == "jar" } == true
   }
   check(found != null) { "Could not find Shamash 'lib' directory under: ${base.absolutePath}" }
   return found
}

fun org.gradle.api.Task.dependsOnAllJvmClassesTasks() {
   val classTasks = rootProject.allprojects.flatMap { p ->
      p.tasks.matching { it.name == "classes" }.toList()
   }
   if (classTasks.isNotEmpty()) dependsOn(classTasks)
}

// ------------------------------
// Public tasks
// ------------------------------
tasks.register<JavaExec>("shamashInit") {
   group = "verification"
   description = "Runs Shamash CLI: init."

   dependsOn(unpackShamashCli)

   mainClass.set("io.shamash.cli.MainKt")

   doFirst {
      val libDir = resolveShamashLibDir()
      classpath = fileTree(libDir) { include("*.jar") }
   }

   args("init", "--project", rootProject.projectDir.absolutePath)
}

tasks.register<JavaExec>("shamashValidate") {
   group = "verification"
   description = "Runs Shamash CLI: validate."

   dependsOn(unpackShamashCli)

   mainClass.set("io.shamash.cli.MainKt")

   doFirst {
      val libDir = resolveShamashLibDir()
      classpath = fileTree(libDir) { include("*.jar") }
   }

   args("validate", "--project", rootProject.projectDir.absolutePath)
}

val shamashScan = tasks.register<JavaExec>("shamashScan") {
   group = "verification"
   description = "Runs Shamash CLI: scan (CI gate)."

   dependsOn(unpackShamashCli)

   dependsOnAllJvmClassesTasks()

   mainClass.set("io.shamash.cli.MainKt")

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
}

tasks.matching { it.name == "check" }.configureEach {
   dependsOn(shamashScan)
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