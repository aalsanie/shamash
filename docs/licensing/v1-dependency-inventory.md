# Shamash V1 — L00 dependency, distribution, and notices ledger

> **Evidence level:** declared Gradle coordinates/build scripts and upstream published license metadata, **NOT** a resolved dependency graph or inspected release ZIP. Snapshot: `0af4caac359877f11612d03e4dfffb920de6b7a0`. No license changes have occurred. Every missing version/notice is intentionally marked **UNVERIFIED**; no dependency license is inherited merely from its parent.

## Owner-approved module scope, 2026-11-02

The owner approved **AGPL-3.0-only** as the *prospective first-party source license* for `shamash-artifacts`, `shamash-export`, `shamash-asm-core`, `shamash-psi-core`, and `shamash-cli`. The `shamash-intellij-plugin` module **remains Apache-2.0**. No new copyright grant is effective until approved L01 implementation.

**Mandatory distribution blocker:** the Apache-licensed plugin module directly depends on the proposed AGPL `psi-core`, `asm-core`, `artifacts`, and `export` modules. Its existing combined plugin ZIP **cannot be declared Apache-only merely from the source-module split**. Exact binary contents plus a specifically approved narrow licensing permission, or an owner-approved separate architecture, are required. No such additional license permission, module restructure or host compatibility conclusion has been authorized. See [V1 licensing decision](v1-decision.md) §1 and §4.

Root-level shared docs/scripts/examples/artwork need an explicit path-specific decision; the modules-only approval does not automatically assign them a new license. The [tracked source inventory](v1-path-inventory.csv) now records the approved module policy or remaining review status on each baseline path.

## A. Direct third-party dependencies of shipped code

| Component (declared coordinate) | Version | Declared module / role | Upstream license / attribution | Distribution treatment / evidence |
| --- | --- | --- | --- | --- |
| `org.ow2.asm:asm` | 9.10.1 | ASM core `api` | BSD-3-Clause, [ASM original license](https://asm.ow2.io/license.html) | API for Maven consumers; likely CLI/plugin runtime; copy BSD notice when distributed |
| `org.ow2.asm:asm-util` | 9.10.1 | ASM core `implementation` | BSD-3-Clause, [ASM/Maven metadata](https://central.sonatype.com/artifact/org.ow2.asm/asm-util/9.10.1) | Verify runtime ZIP/JAR presence, retained BSD notice and transitive `asm-tree`/`asm-analysis` |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.1 | artifacts `implementation` | Apache-2.0, [Maven metadata](https://central.sonatype.com/artifact/com.fasterxml.jackson.core/jackson-core/2.22.1) | Verify `META-INF/LICENSE`/`NOTICE` retained |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.1 | ASM core `api`; PSI core `implementation` | Apache-2.0, [FasterXML upstream](https://github.com/FasterXML/jackson-databind) | Include dependent Jackson artifacts in resolved audit; API for Maven consumers |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.22.1 | ASM core `implementation` | Apache-2.0, [FasterXML upstream](https://github.com/FasterXML/jackson-module-kotlin) | Check resolved kotlin-reflect / stdlib and notices |
| `com.networknt:json-schema-validator` | 1.5.9 | ASM/PSI core `implementation` | Apache-2.0, [POM](https://central.sonatype.com/artifact/com.networknt/json-schema-validator/1.5.9) | Inspect runtime transitives; POM alone does not settle all licenses |
| `org.snakeyaml:snakeyaml-engine` | 3.1.1 | ASM core `implementation` | Apache-2.0, [POM](https://central.sonatype.com/artifact/org.snakeyaml/snakeyaml-engine/3.1.1) | Inspect runtime ZIP and NOTICE |
| `org.jetbrains.kotlinx:kotlinx-cli` | 0.3.6 | CLI `implementation` | Apache-2.0, [POM](https://central.sonatype.com/artifact/org.jetbrains.kotlinx/kotlinx-cli/0.3.6) | May resolve platform-specific JVM module and transitive stdlib; verify precise coordinates |
| `org.slf4j:slf4j-nop` | 2.0.17 | CLI `runtimeOnly` | MIT, [SLF4J license](https://www.slf4j.org/license.html) | Include MIT copyright text and check `slf4j-api` |
| `org.jetbrains.kotlin:kotlin-stdlib` (implicit) | **resolved version unverified** | Kotlin JVM plugin across all modules | Apache-2.0, [Kotlin project](https://github.com/JetBrains/kotlin) | Do not equate Kotlin Gradle plugin 2.4.10 with every actually resolved runtime artifact |
| IntelliJ IDEA / platform and bundled `com.intellij.java` | 2024.2.1 compiler host (and wider verification range) | Plugin and PSI module `intellijPlatform` | IntelliJ Community core Apache-2.0; host may include separate/proprietary components, [IntelliJ Platform](https://www.jetbrains.com/opensource/intellij-platform/) | **Host-supplied, not assumed bundled**; prove via installed ZIP and IDE distribution inventory |
| IntelliJ Kotlin plugin `org.jetbrains.kotlin` | **host version(s) unverified** | Plugin and PSI module bundled plugin | Open-source Kotlin IDE source generally Apache-2.0, [Kotlin IntelliJ source](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin) | Distinguish actual host plugin files from Shamash-bundled Kotlin libraries; qualified compatibility review required |

These versions are from `build.gradle.kts` and six module `build.gradle.kts` files. Entries marked `api` may flow into Maven consumer compilation, but Java/Kotlin host components are not inferred to be shipped in the plugin ZIP.

## B. Build, test, vendored and tooling components

| Component | Version / location | License evidence and inclusion |
| --- | --- | --- |
| Gradle Wrapper launcher JAR + Gradle distribution | root wrapper **8.14.5**; `docs/asm/pit-violation` sample **9.0.0**; `docs/psi/pit-violation` sample **8.8** | Gradle wrapper distributions are third-party tools; the root distribution URL has SHA-256 pin. Record binary hashes and compare to official wrapper origin. Wrapper JARs themselves are committed binaries. Build/tool category, not a V1 runtime library. |
| Gradle-generated `gradlew`, `gradlew.bat` | root scripts | Upstream-derived launchers; preserve upstream terms/notices. |
| Cytoscape | vendored minified JS; version **not established** | `shamash-intellij-plugin/src/main/resources/web/cytoscape.min.js` begins with Cytoscape Consortium MIT copyright/permission. Potentially bundled plugin resource until UI retirement; inspect notice and purpose, and preserve attribution or remove in separate authorized ticket. |
| Kotlin Gradle plugin | **2.4.10** | Build plugin, not automatically a distributable runtime; verify publication/source builds and resolved build-plugin licenses if any build outputs incorporate code |
| IntelliJ Platform Gradle plugin | **2.10.5** | Build tooling; not automatically inside plugin distribution |
| Spotless / KtLint | Spotless **8.9.0**; transitive KtLint version unverified | Build formatting; verify exact tool dependency metadata |
| Kover | **0.9.9** | Test coverage plugin |
| Maven Publish plugin | **0.35.0** | Publication tooling; no runtime assumption |
| Dokka | **2.2.0** | Documentation generation; document artifact generation inputs |
| Foojay resolver plugin | **1.0.0** | Settings/toolchain build plugin |
| JUnit | **4.13.2** | Test only unless proven bundled; original license/NOTICE to verify only in relevant test tool distribution |
| `kotlin("test")` | resolved version unverified | Test only unless proven bundled |
| IntelliJ test framework | selected `TestFrameworkType.Platform` | Test fixtures, not runtime distribution |
| GitHub Actions/third-party CI actions | pinned commit SHAs in `action.yml` and workflows | Workflow invocation only; not a runtime component of Shamash CLI/plugin unless code copied |
| Reports/fixtures | paths in `v1-path-inventory.csv` | Generated sample output provenance and embedded content need review; not arbitrarily licensed by documentation headers |

**Notices rule:** retaining a copied/minified MIT notice in a source file is not equivalent to including the original notices with every relevant runtime distribution. Runtime artifact audits must inspect bundled and transitive JAR `META-INF/LICENSE*`, `META-INF/NOTICE*`, POM metadata and any vendor notice requirements; include the actual third-party terms in the L01 `THIRD_PARTY_NOTICES.md`/`LICENSES/` bundle.

## C. Unresolved runtime coordinates, version/source/notice and bundling

The declared Gradle scripts do **not** disclose the complete resolved graph. The following are **examples requiring resolution**, not a complete list or assertions of package contents:

- ASM utilities: `asm-tree`, `asm-analysis`, possibly `asm-commons` as the selected metadata dictates.
- Jackson modules: `jackson-annotations`, `kotlin-reflect` and each artifact's actual chosen version.
- NetworkNT validator: its logging, regexp, JSON and other transitive dependencies, if selected; check any non-Apache terms.
- SLF4J: `slf4j-api`, actual selected version and MIT text.
- kotlinx-cli platform/JVM variant: resolved stdlib and module artifacts.
- IntelliJ plugin ZIP: every `lib/*.jar` entry (including first-party jars and host-provided capabilities), nested `META-INF` notices, plugin resources, and whether Kotlin runtime binaries are bundled.
- CLI ZIP: exact `lib/*.jar` and launch-script contents.
- Maven publications: three JARs, three source JARs, three javadoc JARs, three POMs, ABI/API surfaces, and transitives in consumers; verify license bytes in **each** published artifact.

**Do not mark resolved without artifact evidence.** Gradle may select versions through metadata/platform constraints and bring new dependencies transitively. The declared versions above are not sufficient to assert the installed ZIPs' full license inventory.

## D. Reproducible evidence capture (run in trusted CI/workstation with network)

No external publishing or release action is needed to collect this evidence.

```bash
# From an unmodified checkout of the candidate commit, JDK 17:
./gradlew --version
for project in shamash-artifacts shamash-export shamash-asm-core shamash-psi-core shamash-cli shamash-intellij-plugin; do
  ./gradlew ":$project:dependencies" --configuration runtimeClasspath > "runtime-$project.txt"
done
./gradlew :shamash-cli:distZip :shamash-intellij-plugin:buildPlugin publishLibrariesToTestRepository

# Capture actual packaged paths, including nested jars:
unzip -Z1 shamash-cli/build/distributions/*.zip > cli-zip-entries.txt
unzip -Z1 shamash-intellij-plugin/build/distributions/*.zip > plugin-zip-entries.txt
find build/test-maven-repository -type f | LC_ALL=C sort > maven-artifact-entries.txt
git ls-files | LC_ALL=C sort > tracked-source-paths.txt
sha256sum shamash-cli/build/distributions/*.zip shamash-intellij-plugin/build/distributions/*.zip > candidate-zip-sha256.txt
```

For the signed Marketplace ZIP, inspect `signPlugin` output **without publishing** and compare its embedded content/notice manifest separately. Enumerate nested JARs, not only ZIP outer paths. Record **GAV, selected version, upstream URL, SPDX expression, copyright notice bytes, inclusion location and channel**, with an explicit reviewer disposition for every entry. Preserve logs and output in a tracked or review-accessible evidence attachment, not in ignored internal-only context. Check third-party license texts and dependency POM metadata; treat missing/ambiguous license metadata as a review item.

Verify root and example wrapper binaries independently against the publisher's checksums/distribution documentation. Do not conflate the Gradle distribution SHA pin with the wrapper JAR's own provenance.

## E. Source and license gates per delivery channel

| Candidate | Before L01 approval / merge | Before Q02/R01 release |
| --- | --- | --- |
| Maven binary, sources, javadoc and POM per module | Approved scope/host/library decisions; resolved NOTICE inventory | Exact POM SPDX/name/URL, notice files, published bytes and source hash match |
| CLI ZIP | Complete runtime JAR/license inventory and rights approval | Full approved texts, notices, exact-version source URI, usable source/build archive |
| IntelliJ plugin ZIP | Complete bundled/host dependency inventory; qualified host + Marketplace review | Signed ZIP notices/license/source pointer, tested supported IDE variants and reviewed listing/EULA |
| GitHub Action install | Verify released CLI grant and source link | Pin release checksum and make download/license links truthful |
| Tag source archive | Define precise source-offer scope | Exact commit, build inputs, wrappers, schemas, build instructions, third-party license sources, reproducible smoke |

No claim is made that current archives already meet future AGPL packaging requirements.

## F. Pending inspection evidence record

| Evidence required | Current state |
| --- | --- |
| Verified path manifest of 491 baseline Git-tracked blobs | **Recorded** in `v1-path-inventory.csv` |
| Declared direct runtime coordinates | **Recorded** from source |
| External direct upstream license references | **Recorded**, subject to exact binary/notice confirmation |
| Full selected runtime dependency graph across six modules | **Not available / not inspected** |
| Installed CLI ZIP JAR/license inventory | **Not available / not inspected** |
| Installed signed plugin ZIP and proprietary host coverage | **Not available / not inspected** |
| Owner/source/asset/assignment and contribution sign-off | **Not provided** |
| Qualified licensing/JetBrains Marketplace host decision | **Not obtained** |

L00 cannot honestly be signed off until the pending entries above are closed. The decision packet can be reviewed without prematurely changing `LICENSE`, Maven POMs or published artifacts.
