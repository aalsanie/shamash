# Distribution channels

The CLI is the only package for SDKMAN, Homebrew, and WinGet. The IntelliJ plugin remains on [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29504-shamash); the Java/Kotlin libraries remain on Maven Central. npm is not a supported CLI distribution channel.

| Channel | Installation goal | State |
| --- | --- | --- |
| SDKMAN | `sdk install shamash` | Integration in progress; candidate approval required |
| Homebrew tap | `brew install aalsanie/tap/shamash` | Blocked on tap setup and correct Java runtime packaging |
| WinGet | `winget install Aalsanie.Shamash` | Blocked on a Windows-compatible installer manifest |
| GitHub Releases | Download verified CLI archive | Available |
| GitHub Action | `uses: aalsanie/shamash@v0.92.0` | Available |

The package-manager commands above are **targets**, not currently available installation instructions. Java 17 or newer is required. Installation must not silently create or modify project configuration; `shamash scan` already works without configuration, and `shamash init` explicitly enables enforcement.

## SDKMAN release process

The SDKMAN distribution is a separate archive with `bin/` and `lib/` at its root. Future GitHub releases will publish `shamash-sdkman-<version>.zip` and record its SHA-256 in `SHA256SUMS.txt`, after smoke-testing its CLI launcher.

The [SDKMAN distribution workflow](../.github/workflows/distribute-sdkman.yml) accepts a released version. Its default is a read-only Distroplane plan. Publishing is an explicit option and requires a protected `sdkman-distribution` GitHub environment and the `SDKMAN_CONSUMER_KEY` and `SDKMAN_CONSUMER_TOKEN` secrets.

Before enabling publishing:

1. Request candidate `shamash` and a vendor account through [SDKMAN vendor onboarding](https://sdkman.io/vendors/).
2. Confirm that the Distroplane SDKMAN provider's vendor API authentication remains supported for the provisioned account; the pinned release candidate uses the legacy consumer-key API.
3. Configure environment protection and vendor secrets. Do not publish until the distribution ZIP exists in a completed GitHub Release.
4. Run the workflow with publishing disabled; inspect the immutable plan and digest before explicitly publishing.
5. Verify candidate availability with the SDKMAN client after the provider reports acceptance; acceptance alone does not prove that users can install it.

The first archive containing the SDKMAN-specific layout will be produced by a release made after the packaging change is merged. Existing v0.92.0 release assets do not contain this archive.

Distroplane plans and journals must be retained for reconciliation. A pending provider result is **not** a completed publication.

## Other package managers

**Homebrew:** Use a dedicated public tap. Distroplane's current formula generator installs only one executable; Shamash also needs its `lib/` JARs. Extend and test the provider so it preserves the full application distribution before publishing any formula.

**WinGet:** The current ZIP manifest generator omits `NestedInstallerType` and `NestedInstallerFiles`. Provide a valid installable Windows artifact and update the provider to emit compliant manifests before submitting to `microsoft/winget-pkgs`.

Keep package-manager publication downstream of the tested, checksummed GitHub Release. Never rebuild or silently replace the release bytes in a distribution job.
