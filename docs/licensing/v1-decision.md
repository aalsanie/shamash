# Shamash licensing — decision record

**Snapshot:** `aalsanie/shamash` `main`, commit `0af4caac359877f11612d03e4dfffb920de6b7a0` (version `0.92.0`).

**Owner instruction, November 2, 2026:** The maintainer states that they are the sole owner of the original Shamash work and approves **AGPL-3.0-only for every module except the IntelliJ plugin, which remains Apache-2.0**. This records the prospective first-party licensing decision. The current repository and published releases remain Apache-2.0 until changes are explicitly implemented and verified.

## Module policy

| Module | Approved future source license |
| --- | --- |
| `shamash-artifacts` | `AGPL-3.0-only` |
| `shamash-export` | `AGPL-3.0-only` |
| `shamash-asm-core` | `AGPL-3.0-only` |
| `shamash-psi-core` | `AGPL-3.0-only` |
| `shamash-cli` | `AGPL-3.0-only` |
| `shamash-intellij-plugin` | `Apache-2.0` |

This decision applies to **the modules' original source code**, not third-party dependencies or an automatically Apache-only combined plugin ZIP. Root-level/shared documentation, scripts, images and examples require deliberate license placement and attribution review rather than blanket header replacement. Keep historical Apache-2.0 grants and existing releases intact. Use the exact `AGPL-3.0-only` SPDX identifier without any “or later” language.

## Combined IntelliJ plugin dependency boundary

The Apache-licensed plugin currently declares direct Gradle dependencies on four future AGPL modules: `shamash-psi-core`, `shamash-asm-core`, `shamash-artifacts`, and `shamash-export`. **Licensing the plugin's own source files under Apache-2.0 does not necessarily license the combined distributable ZIP under Apache-2.0 alone.** AGPL obligations and the actual interaction with IntelliJ host libraries must be considered.

**Distribution compatibility remains unresolved.** Do not silently change the architecture or create an exception. The maintainer must separately approve one reviewed approach:

- A precisely limited additional licensing permission or dual grant for the official Shamash IntelliJ plugin's inclusion/use of specified covered libraries, with exact terms and notices, or
- A separately scoped engineering change that establishes a genuine distribution boundary without incorporating those libraries.

A permission for the official plugin must not be represented as a general exception for proprietary library consumers, custom registries or hosted services. Review Community and proprietary/unified IntelliJ hosts, Kotlin/Java host plugins, Marketplace terms, and actual packaged JARs. Seek qualified licensing review before asserting the combined-artifact license.

## Distribution and provenance evidence

The [tracked-file inventory](v1-path-inventory.csv) captures all 491 baseline Git blobs with a provisional class and prospective scope. The [dependency inventory](v1-dependency-inventory.md) identifies declared dependencies and reproducible binary audit commands.

| Delivery | Required before release |
| --- | --- |
| Maven libraries (artifacts, export, ASM core) | AGPL POM metadata, complete license and source/Javadoc/binary artifacts, compatible dependency licenses and preserved notices |
| Standalone CLI and GitHub Action | Exact ZIP library inventory, notices, license and version-matched Corresponding Source/build inputs |
| Signed IntelliJ plugin | Approved mixed-license integration, accurate Apache source and AGPL library notices, exact ZIP/JAR inventory, IDE compatibility and Marketplace disclosure |
| All source and artwork | Owner's original-work attestation retained; any imported code, external assets, scripts and third-party terms independently reviewed |

Prior published versions retain their Apache-2.0 rights. No published tags or artifacts should be rewritten. No source-level licensing change becomes effective from this decision record alone.

## Remaining release blockers

- [x] Source module licenses and owner attestation explicitly recorded.
- [x] All 491 tracked baseline blobs classified; direct dependencies and distribution channels identified.
- [ ] Explicit owner approval of how the Apache plugin can distribute/consume the AGPL libraries, with qualified compatibility review.
- [ ] Verified complete transitive dependency/license/notice inventories and **actual** CLI and signed plugin ZIP contents.
- [ ] Cleared provenance for third-party and externally sourced material, images and shared files; verified correct notices.
- [ ] Release-specific source delivery, POM/package checks, host tests and final signoff.

**Status: licensing policy decided; distribution clearance open.** No change to `LICENSE`, production source, build metadata, release packages or existing grants has been authorized or made by this document.

References: [AGPL-3.0-only](https://spdx.org/licenses/AGPL-3.0-only.html) · [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) · [GNU combined work discussion](https://www.gnu.org/licenses/gpl-faq.en.html#IfLibraryIsGPL) · [JetBrains Marketplace developer agreement](https://www.jetbrains.com/legal/docs/plugins_site/developer-agreement/).
