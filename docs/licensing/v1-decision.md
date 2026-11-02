# Shamash V1 — L00 licensing decision and rights packet

> **Status: MODULE LICENSE CHOICE APPROVED BY SOLE-OWNER ATTESTATION (2026-11-02); L00 DISTRIBUTION CLEARANCE STILL OPEN.** The maintainer approved `AGPL-3.0-only` for five non-plugin modules and retained `Apache-2.0` for the IntelliJ plugin module. This is a prospective decision, **not** a new effective copyright grant, an approved linking permission, or an authorization to proceed with L01 while its distribution boundary is unresolved. Current source, Maven metadata and existing releases remain Apache-2.0.
>
> **Snapshot:** `aalsanie/shamash` `main` at `0af4caac359877f11612d03e4dfffb920de6b7a0` (version `0.92.0`). The authoritative V1 instructions are the maintainer-supplied private context, particularly `01-agpl-migration.md` and ticket L00 in `06-roadmap.md`. The ignored `.codex-context` folder is **not** source for release archives.

## 1. Decision recorded: five AGPL modules, one Apache plugin

On **2026-11-02**, the maintainer stated: **“I am the sole owner of shamash and I approve AGPL-3.0-only for all modules except the intellij plugin module, Lets keep that under Apache.”** Record this as a first-party ownership/authority **attestation**, not as independent confirmation of third-party rights or employment/contract assignments.

| First-party module | **Approved prospective source license** | Current distribution concern |
| --- | --- | --- |
| `shamash-artifacts` | **`AGPL-3.0-only`** | Public Maven library and plugin dependency |
| `shamash-export` | **`AGPL-3.0-only`** | Public Maven library and plugin dependency |
| `shamash-asm-core` | **`AGPL-3.0-only`** | Public Maven library and plugin dependency |
| `shamash-psi-core` | **`AGPL-3.0-only`** | Direct plugin dependency; uses IntelliJ Platform APIs |
| `shamash-cli` | **`AGPL-3.0-only`** | Standalone CLI and GitHub Action runtime |
| `shamash-intellij-plugin` | **`Apache-2.0`** | Plugin's *own source code*, **not yet the combined ZIP's cleared license** |

**Unapproved boundary:** The IntelliJ plugin currently has direct Gradle `implementation(project(...))` dependencies on the other four library modules (`psi-core`, `asm-core`, `artifacts`, `export`). Apache-2.0 source is GPL/AGPL compatible in a combined work, but this does **not** automatically mean the **whole combined distributable ZIP is Apache-only**. The AGPL libraries can impose conditions on the combination. Distinguish the module's license from the distributed work's legal obligations.

**Required separate design/licensing decision — neither choice has been granted:**

1. **Precisely scoped licensing permission for the official Apache Shamash IntelliJ plugin** to include/use covered libraries under an approved distribution agreement, with no arbitrary third-party, proprietary embedding, hosted-service or external registry exemptions. A qualified licensing reviewer must choose a legally sound mechanism (possibly a separate dual-license grant or additional permission), exact affected modules and files, and packaging/notice language.
2. **Explicitly approved module/architecture separation** so the Apache plugin distribution no longer incorporates AGPL-covered modules. This entails architecture work not authorized by L00 and must not be silently introduced in L01.

Until that choice is approved, do not assume that making just the plugin module Apache settles the **actual plugin artifact**.

| Decision field | Status |
| --- | --- |
| Sole owner of original Shamash work | **Attested by maintainer**, 2026-11-02; third-party and any contract rights review remains separate |
| Source SPDX identifiers and module split | **APPROVED**: five `AGPL-3.0-only`, one `Apache-2.0` |
| Global/root docs, CI scripts, artwork, examples, shared build files | **Not separately approved** by a modules-only instruction; must be mapped intentionally in L01 |
| Binding plugin↔AGPL library permission or architectural split | **NOT APPROVED**; L00 blocker |
| First affected version | **Planned V1.0.0**, conditional on L01, release gates and owner approval |
| License effective commit | **Unset**; to be recorded at the reviewed L01 merge/release commit (never retroactive) |
| Historical releases | Retain their original Apache-2.0 license and already granted rights |
| Exact-version Corresponding Source | Plan release-tag source/archive, wrapper/build inputs, sources, third-party notices and binary links; L01/Q02 gate |
| Future contributions | Prospective mixed-license contribution terms; no automatic assignment |
| Published Maven metadata | Migrate all three library POMs to AGPL only during L01 |
| Plugin Marketplace listing | Keep truthful Apache **module source** description while resolving combined ZIP licensing and host issues |

**SPDX precision:** `AGPL-3.0-only` is not `AGPL-3.0-or-later`; never copy an “or later” boilerplate. Preserve full unmodified upstream texts and attribution. One root `LICENSE` cannot accurately describe a mixed-license tree without clearly marked per-module license boundaries and a separate Apache license for the plugin. No arbitrary additional permission or `LicenseRef` is currently approved.

## 2. First-party and vendored repository inventory

The tracked [path inventory](v1-path-inventory.csv) contains **every one of the 491 Git blobs** in the source snapshot with path, Git object SHA-1, byte size, provisional class and review action. This is a **complete tracked-file enumeration**, **not** a completed copyright clearance or an inventory of resolved external dependencies.

| Provisional class | Count | Interpretation |
| --- | ---: | --- |
| First-party candidates | 467 | Includes six modules, docs, examples, workflow/build files and scripts. Authorship/assignment not established solely from headers. |
| Existing root Apache license | 1 | Preserve historical grant; L01 replaces only after approval and retains license obligations for third-party content. |
| Artwork requiring source/creator check | 5 | Demo GIF, logo PNG, three identical-sha plugin SVGs; determine original design, contributions and trademarks. |
| Third-party Gradle wrapper JARs | 3 | Separate prebuilt binaries in root and both sample projects; verify genuine origin and upstream license/bytes. |
| Gradle wrapper configurations | 3 | Root Gradle 8.14.5; ASM sample 9.0.0; PSI sample 8.8. |
| Gradle-generated launch scripts | 2 | Preserve upstream attribution and licensing; verify origin and permitted modifications. |
| Third-party Cytoscape minified JS | 1 | Cytoscape Consortium MIT notice in `shamash-intellij-plugin/src/main/resources/web/cytoscape.min.js`; **never overwrite with an AGPL header**. |
| Generated/report samples | 9 | Independently verify provenance, sample inputs and data sensitivity. |
| **Total** | **491** | `git ls-files` at the recorded snapshot; not the future L01 repository. |

The three SVG paths have the same blob SHA. This is **deduplication evidence, not creation/ownership evidence**. Wrapper JAR binaries and the compressed sample are not proven by text-level inspection. Do not treat project code as owned merely because it is located within the maintainer's repository.

**Ownership attestation and evidence limits:** the maintainer self-attested sole ownership of original Shamash work on 2026-11-02. This does not establish rights in imported third-party code, tooling, artwork or externally authored material. The latest accessible 100-commit listing contains author strings `aalsanie`, `Ahmad Al-Sanie`, and `dependabot[bot]`; Dependabot merge trailers were observed. Commit authorship may reflect automation, rebases or employee work. It is **not** proof of copyright ownership, employer assignment, third-party snippet provenance, artwork rights, or contributor consent. Before L00 clearance, review full history (including original import and co-authored commits), any relevant employer/contract assignments and externally sourced code; preserve grants/notices, without publishing personal email addresses.

**Attribution separation:** first-party work may be offered under new approved terms where the licensor has authority; copyright/license notices from incorporated Apache, BSD, MIT and other third-party material must be retained as applicable. Historical Apache-licensed versions remain available under their original terms. Neither “everyone used Apache” nor “GPL-compatible permissive licensing” implies that mandatory attribution may be deleted.

## 3. Dependencies and actual distribution evidence

See [dependency and packaging ledger](v1-dependency-inventory.md) for exact **declared direct versions**, upstream license references, module classification, unresolved transitives, and inspection commands. It intentionally does not infer resolved transitive versions or package contents from the declared Gradle builds.

**Distribution paths established from source:**

| Channel | Current construction | License/source evidence status |
| --- | --- | --- |
| Maven Central (`shamash-artifacts`, `shamash-export`, `shamash-asm-core`) | `com.vanniktech.maven.publish`; root POM says Apache 2.0; published-library `Jar` tasks include `META-INF/LICENSE` | Source reviewed; exact deployed artifact contents and dependencies must be inspected per artifact |
| Standalone CLI | Application distribution `shamash-cli:distZip` containing CLI and runtime dependencies; GitHub release ZIP | Build logic reviewed; **ZIP bytes unavailable in this environment**; exact bundled JAR/notice inventory pending |
| IntelliJ plugin | `buildPlugin` / `signPlugin`; **Apache-2.0 module source** directly uses proposed **AGPL** PSI, ASM, artifacts/export, plus host components | **Combined ZIP not cleared**. Signed ZIP not inspected; distinguish bundled JARs from host-provided APIs/Kotlin and resolve additional licensing permission or architecture change |
| GitHub Action | `action.yml` downloads versioned standalone CLI through installer scripts | Uses CLI grant/distribution terms; invocation does not relicense scanned project |
| GitHub release | Current `v0.92.0` includes CLI ZIP, signed plugin ZIP and `SHA256SUMS.txt` | Published asset names/digests identified, binary contents not audited; immutable historical grant |
| Developer/source archive | Git tag source plus build tooling is intended source offer | Exact proposed source archive task, notices and reproducible rebuild remain L01/Q02 work |

**Evidence restriction:** the connected GitHub content interface is text-oriented, and the execution container cannot resolve `github.com`; the actual ZIP/JAR binaries and dependency-resolution output were **not** retrieved. Thus **the no-unresolved-rights and packaging portion of L00 acceptance is not yet met**. Never label these as inspected or use historic CI success as substitution. Run the ledger commands against the candidate and attach the generated reports or hashes to L00 review.

## 4. IntelliJ plugin mixed-license and host decision gates

**Blocker A — AGPL library combination, even before considering the IDE host.** Current `shamash-intellij-plugin/build.gradle.kts` has direct `implementation(project(...))` dependencies on `shamash-psi-core`, `shamash-asm-core`, `shamash-artifacts`, and `shamash-export`, all approved prospectively as AGPL. The plugin's original code may be offered under Apache-2.0, while the combination still has AGPL requirements. This cannot be resolved merely by changing header SPDX strings. **No additional license permission has been approved.**

**Blocker B — actual host + Marketplace distribution.** The platform core is open source under Apache-2.0, but assess bundled libraries and platform/plugin components for each supported IDE (including proprietary/unified hosts). Current compilation target is 2024.2.1, with declared build range through 2026.2; plugin depends on `com.intellij.java` and `org.jetbrains.kotlin`. Inspect the actual plugin ZIP and host classpath instead of assuming IntelliJ/Marketplace terms settle all AGPL host linkage questions.

**Required disposition:** a qualified review with evidence and a specific maintainer-approved (1) narrow permission/dual grant for the official plugin combined distribution, (2) architecture separation, or another owner-approved concrete alternative. Record exact terms, scope, source/notices and whether modified forks are covered. No implicit permission for hosted SaaS, proprietary arbitrary consumers or custom registries. Do not update the Marketplace listing or commence L01 header churn until the decision is recorded.

See [GNU GPL FAQ on linking](https://www.gnu.org/licenses/gpl-faq.en.html#IfLibraryIsGPL), [AGPL §5 combined-work terms](https://www.gnu.org/licenses/agpl-3.0.en.html), [JetBrains plugin dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html), and [Marketplace developer agreement](https://www.jetbrains.com/legal/docs/plugins_site/developer-agreement/).

Primary references: [AGPL-3.0-only](https://spdx.org/licenses/AGPL-3.0-only.html), [AGPL-3.0-or-later](https://spdx.org/licenses/AGPL-3.0-or-later.html), [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0), [JetBrains Developer Agreement](https://www.jetbrains.com/legal/docs/plugins_site/developer-agreement/), [Marketplace approval guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html), [IntelliJ open-source platform](https://www.jetbrains.com/opensource/intellij-platform/), [Kotlin plugin source](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin), [GNU System Libraries discussion](https://www.gnu.org/licenses/gpl-faq.en.html#SystemLibraryException).

## 5. User-scenario FAQ (proposed, requires license review)

**Can I analyze private/proprietary source code with Shamash?** Mere analysis by a separate command-line tool or IDE plugin does not automatically change the analyzed repository's copyright license. Distinguish running from incorporating or distributing Shamash code. Do not market this as advice for every form of linking or hosted use.

**Can I run the CLI in CI?** Running an unmodified standalone CLI is a distinct use from distributing a modified binary. CI logs/reports do not by themselves transfer Shamash's license to the scanned project. If a company redistributes Shamash or modifies it for network interaction, analyze distribution and AGPL §13 facts separately.

**Can I embed an ASM/export/artifacts library into proprietary software?** This is materially different from invoking an external CLI. The library modules are prospectively approved for AGPL-3.0-only. Linking and redistribution may invoke reciprocal obligations; no general permissive embedding exception has been approved.

**Can I implement a custom rule-registry provider?** A provider loaded in the same process is not automatically equivalent to a separate CLI invocation. The exact loading/linking model and redistribution require review. Do not imply a blanket plugin exception.

**Can I host a modified Shamash-backed analysis service?** AGPL §13 can require offering Corresponding Source to remote users interacting with a modified covered program; the actual architecture and interactions matter. No source-level or host-specific exception has been approved for hosted SaaS derivatives.

**What happens to 0.92.0 and earlier versions?** They remain offered under their then-current Apache-2.0 grant. A later first-party license decision does not retroactively remove those rights. Preserve applicable contributor and third-party notices.

## 6. L01 implementation surfaces — review required, NOT executed

L01 remains a **separate PR**. Resolve the mixed-plugin licensing choice before editing headers or packaging.

1. Full approved AGPL-3.0-only text in the appropriate root/default `LICENSE`, **full Apache-2.0 text under `shamash-intellij-plugin/LICENSE`**, plus an unambiguous module-license matrix and retained third-party terms in `THIRD_PARTY_NOTICES.md` / `LICENSES/`. If a narrow permission is separately approved, package the actual approved terms distinctly with accurate SPDX metadata.
2. Preserve Apache first-party headers inside `shamash-intellij-plugin`; convert first-party headers in exactly `shamash-artifacts`, `shamash-export`, `shamash-asm-core`, `shamash-psi-core` and `shamash-cli` under a reviewed tracked-file allowlist. Handle the **global** `spotless/HEADER.kt` convention without accidentally relicensing plugin source; retain third-party Cytoscape MIT and Gradle notices.
3. Update the root `build.gradle.kts` Maven POM metadata to AGPL for three published libraries and extend `scripts/verify-publications.py` to validate exact POM/license/package bytes, binary/sources/docs artifacts and notices.
4. Review root docs, scripts, examples, artwork, `gradle.properties`, GitHub Action and install scripts **individually**, because the maintainer approved a *module* split rather than automatically licensing all repository paths. Update README, `CONTRIBUTING.md`, versioned source links, license FAQ and truthful plugin listing.
5. Package approved module-level and combined-artifact licensing statements into CLI and plugin ZIPs, generate exact-commit source archives including build inputs, and validate packaging/host compatibility and legal notices.
6. Preserve historical Apache releases and `CHANGELOG.md` history; do not publish/re-tag in L01.

No architecture changes, general-purpose link exceptions, contributor assignments, historical changes, or release are approved by this L00 record.

## 7. Acceptance and outstanding evidence (L00)

- [x] Baseline and six first-party modules inventoried; 491 tracked baseline blobs classified.
- [x] Direct dependencies and distribution channels mapped without inventing resolved transitive versions.
- [x] Maintainer **attested sole ownership of Shamash original work**, 2026-11-02.
- [x] Maintainer **approved AGPL-3.0-only for five modules**, **Apache-2.0 for IntelliJ plugin module**.
- [x] Historical Apache rights retained; L01 exact module-header/packaging surfaces specified.
- [ ] **Maintainer selects and qualified reviewer clears** a mechanism by which the Apache plugin distribution may use AGPL modules, or explicitly authorizes architecture separation. Neither has been approved.
- [ ] Any imported/vendor, artwork, contribution and relevant employment/contract rights concerns cleared as applicable; original notices preserved.
- [ ] Resolve actual six-module runtime transitive dependency trees, original license/NOTICE content and packaging.
- [ ] Inspect actual CLI ZIP and signed plugin ZIP (bundled JARs, license texts, Kotlin and host separation) and record evidence.
- [ ] Complete host/Marketplace/license compatibility review against the actual distributable and selected permission/boundary.
- [ ] Approve any still-unassigned root-level docs/scripts/artwork source-license scope and exact version-specific Corresponding Source delivery.
- [ ] Resolve **all** rights/compatibility exceptions for the intended distributions and obtain final L00 clearance.

**Gate status: OPEN — module license choice approved, distribution permission/architecture and evidence unresolved.** No first-party license files, source headers, POMs, ZIPs, signatures, tags, published assets or Marketplace fields have been changed. **Do not begin L01** until the outstanding distribution decision is approved.

### Decision record

| Item | Status |
| --- | --- |
| Sole owner attestation | **Confirmed by maintainer, 2026-11-02; independent provenance checks remain** |
| `AGPL-3.0-only` | **Approved** for `artifacts`, `export`, `asm-core`, `psi-core`, `cli` |
| `Apache-2.0` | **Approved** for `shamash-intellij-plugin` source |
| Apache plugin + AGPL dependencies | **BLOCKED: permission or separation decision required** |
| All dependency/license/host distributions cleared | **Pending evidence** |
| Prospective version and effective commit | **V1.0.0 proposed; specific commit pending L01** |
| Final release/legal compatibility signoff | **Pending** |
