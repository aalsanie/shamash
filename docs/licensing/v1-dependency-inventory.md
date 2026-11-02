# Dependency and distribution licensing inventory

**Source baseline:** `0af4caac359877f11612d03e4dfffb920de6b7a0` (`0.92.0`). These are **declared direct versions**, not a resolved dependency graph or an inspected release package. The owner's prospective source policy is `AGPL-3.0-only` for five library/CLI modules and `Apache-2.0` for the IntelliJ plugin module. [Decision record](v1-decision.md).

## Declared runtime dependencies

| Component | Version | Used by | Upstream license (to confirm against actual artifact) |
| --- | --- | --- | --- |
| `org.ow2.asm:asm`, `asm-util` | 9.10.1 | ASM core | BSD-3-Clause |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.1 | artifacts | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.1 | ASM/PSI cores | Apache-2.0 |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.22.1 | ASM core | Apache-2.0 |
| `com.networknt:json-schema-validator` | 1.5.9 | ASM/PSI cores | Apache-2.0 |
| `org.snakeyaml:snakeyaml-engine` | 3.1.1 | ASM core | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-cli` | 0.3.6 | CLI | Apache-2.0 |
| `org.slf4j:slf4j-nop` | 2.0.17 | CLI | MIT |
| Kotlin standard library | Selected version unverified | Kotlin modules | Apache-2.0 |

**Additional sources:** [ASM licensing](https://asm.ow2.io/license.html), [FasterXML](https://github.com/FasterXML/jackson-databind), [NetworkNT](https://github.com/networknt/json-schema-validator), [SnakeYAML Engine](https://github.com/snakeyaml/snakeyaml-engine), [SLF4J](https://www.slf4j.org/license.html), [Kotlin](https://github.com/JetBrains/kotlin). Confirm the original license and NOTICE files in the *exact resolved artifacts*. The table does not assert that any transitive component has been audited.

**Platform and build tooling:** IntelliJ IDEA Community `2024.2.1` is the plugin build target, with compatibility declarations up to `2026.2`; bundled platform components `com.intellij.java` and `org.jetbrains.kotlin` are host-facing and their exact contents/versions require verification. Gradle Wrapper is `8.14.5` at repository root; example projects embed wrapper `9.0.0` and `8.8` binaries. The vendored `cytoscape.min.js` includes the Cytoscape Consortium MIT notice; its exact source version has not been established. Gradle, Kotlin plugin, IntelliJ Gradle plugin, Maven Publish, Spotless, Kover, Dokka and test frameworks are build-time unless binary analysis proves inclusion. Do not override upstream copyright or notice requirements.

## Packaging review

- **Maven:** three published libraries (`shamash-artifacts`, `shamash-export`, `shamash-asm-core`). Root `build.gradle.kts` currently declares Apache-2.0 POM license; the build embeds the root LICENSE in published JARs. These facts must be updated and checked against the approved library licenses before publishing.
- **CLI:** `shamash-cli:distZip` bundles a launchable distribution and runtime dependencies; the GitHub Action installs versioned CLI assets. Audit nested JAR licenses, notices, source pointer and checksums.
- **IntelliJ:** `buildPlugin` and `signPlugin` produce the plugin ZIP. The Apache plugin directly depends on four future AGPL library modules (`psi-core`, `asm-core`, `artifacts`, `export`). **The combined ZIP's licensing treatment is not approved.** Verify each shipped JAR, Kotlin/Java host-provided modules, and any narrowly approved integration permission.
- **Historical assets:** published `v0.92.0` ZIPs and other Apache-licensed releases remain unchanged.

**Pending evidence:** full selected transitive runtime trees (including Jackson, ASM, SLF4J, Kotlin, NetworkNT dependencies), each upstream license and notice, wrapper JAR provenance, original artwork rights, ZIP contents, signed plugin contents, and IntelliJ host/Marketplace compatibility. The connected source interface did not provide binary ZIP inspection or local dependency resolution. Do not represent any pending item as completed.

## Reproducible inspection

From the exact review commit on a trusted runner with JDK 17 and network access:

```bash
./gradlew --version
for project in shamash-artifacts shamash-export shamash-asm-core shamash-psi-core shamash-cli shamash-intellij-plugin; do
  ./gradlew ":$project:dependencies" --configuration runtimeClasspath > "runtime-$project.txt"
done
./gradlew :shamash-cli:distZip :shamash-intellij-plugin:buildPlugin publishLibrariesToTestRepository
unzip -Z1 shamash-cli/build/distributions/*.zip > cli-entries.txt
unzip -Z1 shamash-intellij-plugin/build/distributions/*.zip > plugin-entries.txt
find build/test-maven-repository -type f | LC_ALL=C sort > published-entries.txt
sha256sum shamash-cli/build/distributions/*.zip shamash-intellij-plugin/build/distributions/*.zip > archive-hashes.txt
```

Enumerate **nested JARs** and original `META-INF/LICENSE*` / `NOTICE*` resources, not just ZIP filenames. Repeat for signed release assets; capture selected GAV, version, SPDX, license text/notice location, artifact SHA-256 and distribution channel. Preserve evidence with the review, not in ignored private planning files. Do not proceed to release on a partially audited matrix.
