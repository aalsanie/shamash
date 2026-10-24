# Distribution channels

Distribute the CLI via SDKMAN, Homebrew, and WinGet. The IntelliJ plugin remains on [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29504-shamash); Java/Kotlin libraries remain on Maven Central. npm is not a planned CLI distribution channel.

| Channel | Target command | State |
| --- | --- | --- |
| SDKMAN | `sdk install shamash` | Candidate registration and State API integration required |
| Homebrew | `brew install aalsanie/tap/shamash` | Dedicated tap and complete JVM layout required |
| WinGet | `winget install Aalsanie.Shamash` | Upstream submission and ZIP installer support required |
| GitHub Releases | Download a checksum-verified CLI archive | Available |
| GitHub Action | `uses: aalsanie/shamash@v0.92.0` | Available |

The package-manager commands are targets, **not yet working installation commands**. Java 17+ is required; installation does not implicitly change project configuration. `shamash scan` discovers violations without config, and `shamash init` explicitly creates config.

## SDKMAN

Request the candidate from [SDKMAN vendor onboarding](https://github.com/sdkman/sdkman-state/wiki/Vendor-Onboarding). Email `info@sdkman.io` with:

- Identifier: `shamash`
- Name: `Shamash`
- Website: `https://github.com/aalsanie/shamash`
- Description: `Shamash is an open-source JVM architecture analysis tool for Java and Kotlin. It detects dependency cycles, checks architectural boundaries, and supports automated enforcement in CI.`

SDKMAN now provisions a **vendor email and password**, ideally encrypted using the vendor's ASCII-armored **public** PGP key. Its [State API](https://github.com/sdkman/sdkman-state/wiki/Vendor-API-Integration-Guide) exchanges these for a short-lived JWT. Do not use the older consumer-key/token secrets.

Create a GitHub Actions environment named `sdkman-distribution` in `aalsanie/shamash`. After SDKMAN provisions the account, store these as **environment secrets**:

- `SDKMAN_VENDOR_EMAIL`
- `SDKMAN_VENDOR_PASSWORD`

The [SDKMAN distribution workflow](../.github/workflows/distribute-sdkman.yml) is deliberately **plan-only** until the new Distroplane version supports the current SDKMAN State API. It cannot publish. The next integration must use the new provider's credential contract; do not add legacy `SDKMAN_CONSUMER_KEY` or `SDKMAN_CONSUMER_TOKEN`.

Future releases will produce a checksum-verified `shamash-sdkman-<version>.zip` archive with `bin/` and `lib/` at its root. Existing v0.92.0 releases do not contain this archive. After a future release publishes it, validate installation via a real SDKMAN client before advertising `sdk install shamash`.

## Homebrew

1. Create a **public** GitHub repository named `aalsanie/homebrew-tap`, with `main` as the default branch. This implements the target command `brew install aalsanie/tap/shamash`.
2. Create the `homebrew-distribution` GitHub Actions environment in `aalsanie/shamash`.
3. When publishing is ready, create a **fine-grained GitHub personal access token** scoped only to `aalsanie/homebrew-tap`. For reviewed formula pull requests, grant **Contents: Read and write** and **Pull requests: Read and write**.
4. Store the token as the `HOMEBREW_TAP_TOKEN` environment secret in `aalsanie/shamash`. Never commit it or pass it in Distroplane configuration.
5. Wait for the corrected Distroplane Homebrew provider. The existing provider installs only one binary; Shamash needs its launchers and runtime JARs installed together and the Java dependency declared. Test the generated formula by actually invoking `shamash version` and `shamash scan`.

A protected default branch and formula pull-request mode are preferred over directly pushing changes to the tap. No Homebrew account or central formula submission is required for a custom tap.

## WinGet

1. Fork the public [microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs) repository into `aalsanie/winget-pkgs` using the GitHub Fork UI.
2. Create the `winget-distribution` GitHub Actions environment in `aalsanie/shamash`.
3. Wait for the corrected Distroplane WinGet provider and a valid Windows installation manifest. WinGet ZIP installations need the appropriate nested installer metadata and a verified executable layout.
4. Decide authentication after verifying the new provider's supported GitHub credential flow. **A fine-grained token scoped only to your fork is not sufficient to create a pull request in `microsoft/winget-pkgs`**. A short-lived classic PAT with the `public_repo` scope or separate least-privilege credentials may be required. Avoid creating a broad-scoped token until the integration has been tested.
5. Store the eventual publisher token as the `WINGET_PUBLISH_TOKEN` environment secret. Submit through a reviewed upstream pull request and confirm catalog availability before documenting `winget install Aalsanie.Shamash`.

WinGet does not require a Microsoft Store or Partner Center publisher account to submit manifests to its public community repository.

## Release safety

- Publish package-manager manifests **only after** GitHub Release assets and checksums exist. Never rebuild or replace published bytes within distribution jobs.
- Prefer GitHub Actions environments with manual approval and the least permissions required for each destination.
- Treat Distroplane's plan/journal as release evidence. Preserve them for pending/ambiguous publication and reconcile without duplicating side effects.
- Do not merge or enable a publishing workflow that still uses the legacy SDKMAN vendor API. Update the integration when the corrected Distroplane release becomes available.
