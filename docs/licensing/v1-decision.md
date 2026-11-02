# Shamash V1 — L00 licensing decision and rights packet

> **Status: PROPOSED — NOT APPROVED.** L00 engineering inventory is in progress; the L00 approval/clearance gate is **OPEN**. This document is not an effective license grant, a legal opinion, a host-linking exception, or permission to execute L01. Current source, Maven metadata, and published releases remain Apache-2.0.
>
> **Snapshot:** `aalsanie/shamash` `main` at `0af4caac359877f11612d03e4dfffb920de6b7a0` (version `0.92.0`). The authoritative V1 instructions are the maintainer-supplied private context, particularly `01-agpl-migration.md` and ticket L00 in `06-roadmap.md`. The ignored `.codex-context` folder is **not** source for release archives.

## 1. Decision proposed, not granted

| Decision field | Proposed V1 treatment | Approval status |
| --- | --- | --- |
| SPDX for first-party work | **`AGPL-3.0-only`**, not `AGPL-3.0-or-later` | **Maintainer decision required** |
| Scope | One policy for **all six first-party modules** (artifacts, export, ASM core, PSI core, CLI, IntelliJ plugin); accompanying first-party docs, scripts, examples, and owned artwork where rights permit | **Maintainer decision and rights clearance required** |
| Exception / linking permission | **None approved.** Do not invent or insert an additional permission or `LicenseRef`. | **Independent qualified review of IntelliJ host distribution required** |
| Commercial or permissive-library alternative | **None approved.** Would require a new explicit decision and corresponding document/build changes. | Not selected |
| First affected release | Proposed **V1.0.0**, **only if** L01 is approved, merged, built, verified, and authorized for release | Pending |
| Effective commit | **Not yet defined**. Record L01 merge/release commit here; do **not** backdate the grant to this L00 proposal, baseline, or historical tag. | Pending |
| Historical published versions | All existing Apache-2.0 rights and release artifacts remain intact; no rewriting tags/changelogs or revoking prior grants | Required invariant |
| Corresponding source | Release-tag/commit-specific GitHub source archive containing build inputs, wrapper files, schemas, scripts, relevant docs and notices, linked from all binary channels | Proposed; L01 implementation required |
| Future contributions | Maintain attribution and record prospective contribution terms only after decision; sign-off/DCO is **not** copyright assignment | Pending, L01 |
| Decision authority | Copyright owner(s)/authorized maintainer and a qualified licensing reviewer for unresolved host/rights cases | **Not signed off** |

**Meaning of the proposed grant.** `AGPL-3.0-only` is materially different from the `-or-later` choice. Do not copy the example “or any later version” notice into a `-only` header. If a further host permission is necessary, its wording, beneficiaries, precise host components, compatibility effect, and SPDX/notice treatment require separate review before any code/header changes. Preserve the unmodified approved full license text.

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

**Authorship evidence and limits:** the latest accessible 100-commit listing contains author strings `aalsanie`, `Ahmad Al-Sanie`, and `dependabot[bot]`; Dependabot merge trailers were observed. Commit authorship may reflect automation, rebases or employee work. It is **not** proof of copyright ownership, employer assignment, third-party snippet provenance, artwork rights, or contributor consent. Before approval, review full history (including original import and co-authored commits), employer/contract assignments and any externally sourced code, then record the maintainer's rights attestation and any grants/required notices without publishing personal email addresses.

**Attribution separation:** first-party work may be offered under new approved terms where the licensor has authority; copyright/license notices from incorporated Apache, BSD, MIT and other third-party material must be retained as applicable. Historical Apache-licensed versions remain available under their original terms. Neither “everyone used Apache” nor “GPL-compatible permissive licensing” implies that mandatory attribution may be deleted.

## 3. Dependencies and actual distribution evidence

See [dependency and packaging ledger](v1-dependency-inventory.md) for exact **declared direct versions**, upstream license references, module classification, unresolved transitives, and inspection commands. It intentionally does not infer resolved transitive versions or package contents from the declared Gradle builds.

**Distribution paths established from source:**

| Channel | Current construction | License/source evidence status |
| --- | --- | --- |
| Maven Central (`shamash-artifacts`, `shamash-export`, `shamash-asm-core`) | `com.vanniktech.maven.publish`; root POM says Apache 2.0; published-library `Jar` tasks include `META-INF/LICENSE` | Source reviewed; exact deployed artifact contents and dependencies must be inspected per artifact |
| Standalone CLI | Application distribution `shamash-cli:distZip` containing CLI and runtime dependencies; GitHub release ZIP | Build logic reviewed; **ZIP bytes unavailable in this environment**; exact bundled JAR/notice inventory pending |
| IntelliJ plugin | `buildPlugin` / `signPlugin`; includes PSI, ASM, artifacts/export and host-provided platform dependencies | Build/plugin.xml reviewed; **signed plugin ZIP not inspected**; distinguish bundled library JARs from host-provided APIs and Kotlin |
| GitHub Action | `action.yml` downloads versioned standalone CLI through installer scripts | Uses CLI grant/distribution terms; invocation does not relicense scanned project |
| GitHub release | Current `v0.92.0` includes CLI ZIP, signed plugin ZIP and `SHA256SUMS.txt` | Published asset names/digests identified, binary contents not audited; immutable historical grant |
| Developer/source archive | Git tag source plus build tooling is intended source offer | Exact proposed source archive task, notices and reproducible rebuild remain L01/Q02 work |

**Evidence restriction:** the connected GitHub content interface is text-oriented, and the execution container cannot resolve `github.com`; the actual ZIP/JAR binaries and dependency-resolution output were **not** retrieved. Thus **the no-unresolved-rights and packaging portion of L00 acceptance is not yet met**. Never label these as inspected or use historic CI success as substitution. Run the ledger commands against the candidate and attach the generated reports or hashes to L00 review.

## 4. IntelliJ host and Marketplace decision gate

- IntelliJ Platform's open-source core is under Apache-2.0, and Kotlin's open-source plugin code is generally Apache-2.0; nevertheless an actual plugin can interact with proprietary/unified IDE distribution components. A base-platform license is not a blanket license for every IntelliJ runtime module.
- The current plugin compiles against IntelliJ IDEA Community 2024.2.1, depends on `com.intellij.java` and `org.jetbrains.kotlin`, and advertises build range `242.21829.142`–`262.*`. The PSI module also uses IntelliJ Platform APIs. Determine the **actual classpath** supplied by each host, versus JARs distributed with Shamash.
- Assess **Community/open-source host**, **Ultimate/unified/proprietary IntelliJ hosts**, **bundled Java/Kotlin plugins**, **Marketplace transfer**, **offline/manual plugin ZIP**, and **custom registry extension linking** as distinct combinations. Do not assume the GPL/AGPL System Library exception covers all hosts or that all combinations are incompatible.
- JetBrains Marketplace Developer Agreement **v3.2, effective 2026-08-24**, requires developer-provided license terms and addresses Marketplace rights and precedence (especially §§9–10). Listing guidance requires source link for open-source plugins; neither proves the plugin's exact AGPL interoperability.
- **Required disposition:** obtain qualified open-source licensing review of a plugin covered by `AGPL-3.0-only` running/linking in each named IntelliJ distribution, of bundled/runtime libraries, and of Marketplace terms. Record **(a)** compatibility without a permission, **(b)** an approved *narrowly defined* additional host permission, **(c)** a differently licensed plugin module, or **(d)** retention of Apache. Option (b) is **not drafted or granted in L00**. No arbitrary hosted-service exemption.

Primary references: [AGPL-3.0-only](https://spdx.org/licenses/AGPL-3.0-only.html), [AGPL-3.0-or-later](https://spdx.org/licenses/AGPL-3.0-or-later.html), [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0), [JetBrains Developer Agreement](https://www.jetbrains.com/legal/docs/plugins_site/developer-agreement/), [Marketplace approval guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html), [IntelliJ open-source platform](https://www.jetbrains.com/opensource/intellij-platform/), [Kotlin plugin source](https://github.com/JetBrains/intellij-community/tree/master/plugins/kotlin), [GNU System Libraries discussion](https://www.gnu.org/licenses/gpl-faq.en.html#SystemLibraryException).

## 5. User-scenario FAQ (proposed, requires license review)

**Can I analyze private/proprietary source code with Shamash?** Mere analysis by a separate command-line tool or IDE plugin does not automatically change the analyzed repository's copyright license. Distinguish running from incorporating or distributing Shamash code. Do not market this as advice for every form of linking or hosted use.

**Can I run the CLI in CI?** Running an unmodified standalone CLI is a distinct use from distributing a modified binary. CI logs/reports do not by themselves transfer Shamash's license to the scanned project. If a company redistributes Shamash or modifies it for network interaction, analyze distribution and AGPL §13 facts separately.

**Can I embed an ASM/export/artifacts library into proprietary software?** This is materially different from invoking an external CLI. If the library becomes AGPL-covered, linking and redistribution may invoke reciprocal obligations; assess the complete integration before adoption. No permissive exception is promised.

**Can I implement a custom rule-registry provider?** A provider loaded in the same process is not automatically equivalent to a separate CLI invocation. The exact loading/linking model and redistribution require review. Do not imply a blanket plugin exception.

**Can I host a modified Shamash-backed analysis service?** AGPL §13 can require offering Corresponding Source to remote users interacting with a modified covered program; the actual architecture and interactions matter. An IntelliJ host exception, if later approved, must **not** silently exempt hosted SaaS derivatives.

**What happens to 0.92.0 and earlier versions?** They remain offered under their then-current Apache-2.0 grant. A later first-party license decision does not retroactively remove those rights. Preserve applicable contributor and third-party notices.

## 6. Concrete L01 change list — review required, NOT executed here

1. `LICENSE` (full approved license) and new `THIRD_PARTY_NOTICES.md`, `LICENSES/` with necessary upstream texts; separately linked approved permission if any.
2. `spotless/HEADER.kt`, `gradle.properties`, root `settings.gradle.kts` / `build.gradle.kts`, six module Gradle build files, and first-party source files **selected only after path-inventory rights approval**. Preserve external/vendor exceptions and contributor copyrights.
3. Root `build.gradle.kts` POM license metadata for all three published libraries; `scripts/verify-publications.py`, signature/packaging/source verification.
4. CLI/app distribution build and README/help license/source output, plugin build & About/help license/source entry, root `.github/workflows/release.yml` source/checksum packaging, GitHub Action/installer docs as necessary.
5. `README.md`, quick start, translated READMEs/licensing statements in `docs/`, `CONTRIBUTING.md`, new version-specific migration and FAQ documents; preserve historical `CHANGELOG.md` entries.
6. First-party examples, verification consumers, shell/PowerShell/Python scripts and header variants identified in `v1-path-inventory.csv`; **do not** mass-replace `cytoscape.min.js`, wrapper binaries/scripts or other upstream notices.
7. Marketplace license/source/EULA fields only during the **authorized release**. Do not modify the already-published assets or issue a release during L00/L01 review.

This is a **specific surface list** plus a per-path inventory, not an authorization to globally search-and-replace Apache boilerplate before clearance.

## 7. Acceptance checklist and explicit blockers

- [x] Baseline, six first-party modules and distribution channels identified from source.
- [x] Every baseline tracked blob classified in a versioned path inventory (491 entries).
- [x] Direct dependencies and external/vendor surfaces documented separately, with provenance references and non-inferred transitives.
- [x] Proposed SPDX, six-module scope, history, source delivery, host alternatives, FAQ and L01 change list recorded.
- [ ] Complete rights/assignment/source-material validation for first-party code, docs, graphics and samples; owner attestation.
- [ ] Resolved **runtime** dependency trees for all shipped modules plus exact license/notice contents for every transitive component.
- [ ] Inspect actual CLI ZIP and signed plugin ZIP (bundled JARs, MIT/Apache/BSD notices, Kotlin/host treatment).
- [ ] Qualified host and Marketplace compatibility review and any required additional permission precisely approved.
- [ ] Maintainer explicitly approves SPDX variant, module boundary, source offer and first effective release.
- [ ] All unresolved intended-distribution rights/compatibility entries closed and recorded; no pending unknown terms.

**Gate state: OPEN.** No L01 code/license changes, publication, historic-release modifications, contributor license reassignment or legal outreach are authorized by this document.

### Required decision record (complete only after evidence and approval)

| Field | Decision / signoff |
| --- | --- |
| Copyright holder(s), authority and relevant employer/contributor evidence | **Pending** |
| License: `AGPL-3.0-only`, `AGPL-3.0-or-later`, or keep Apache-2.0 | **Pending maintainer** |
| Six modules together, or explicit exceptions | **Pending maintainer** |
| IntelliJ host result and separate permission/alternative terms, if needed | **Pending qualified reviewer + maintainer** |
| All external transitive licenses, notices and distribution matrix cleared | **Pending package/rights audit** |
| Corresponding Source delivery method approved | **Pending maintainer** |
| Earliest affected tag / L01 commit | **Unassigned; not retrospective** |
| Reviewer, review date and evidence location | **Pending** |
| Final L00 maintainer approval | **Pending** |
