# 0.91.0 release checklist

This checklist maps the CTO adoption audit to an executable release gate.

## P0 distribution and compatibility

- [x] Source version is `0.91.0`.
- [x] IntelliJ `untilBuild` is `262.*`.
- [x] Plugin Verifier matrix includes IDEA 2026.2.
- [x] Release workflow blocks publication until tests, packaged CLI smoke and Plugin Verifier pass.
- [x] Marketplace publication is automated by `publishPlugin`.
- [x] GitHub release assets are checksumed in `SHA256SUMS.txt`.
- [x] Release builds use plugin signing.
- [ ] Repository secrets exist: `JETBRAINS_MARKETPLACE_TOKEN`, `JETBRAINS_CERTIFICATE_CHAIN`, `JETBRAINS_PRIVATE_KEY`, `JETBRAINS_PRIVATE_KEY_PASSWORD`.

The last item is an external GitHub/JetBrains credential prerequisite. It cannot be placed in source or this ZIP.

## Activation

- [x] `shamash scan` works without project configuration.
- [x] Configless discovery is report-only.
- [x] Discovery writes no config/report/baseline under the project.
- [x] Discovery findings do not fail the process.
- [x] Missing bytecode produces actionable Gradle/Maven guidance.
- [x] Findings print without `--print-findings`.
- [x] Default finding output is capped at 20 with `--all-findings` escape hatch.
- [x] Engine diagnostics live behind `--verbose`.
- [x] Legacy `--print-findings` remains accepted during 0.x.

## Configuration and brownfield adoption

- [x] Default starter config is under 35 lines (26 lines in this patch).
- [x] Framework-specific Spring policy is explicit (`--preset spring`).
- [x] Full advanced reference remains available (`--preset reference`).
- [x] `shamash baseline create` applies baseline generation in memory.
- [x] Baseline creation does not edit YAML mode.
- [x] Existing baseline is protected unless `--force` is explicit.

## CI / packaged product

- [x] First-party `action.yml` exists.
- [x] Action verifies SHA-256 before execution.
- [x] CI matrix includes Linux, Windows and macOS.
- [x] Packaged CLI smoke runs the downloaded-style distribution, not only the Gradle runtime classpath.
- [x] Smoke verifies `shamash version`, configless scan, and non-mutation.
- [x] Public launcher is explicitly named `shamash`.

## IntelliJ product shell

- [x] One public `Shamash` tool window.
- [x] Build Analysis / Source Analysis top-level vocabulary.
- [x] One public Tools menu entry.
- [x] Existing engine actions remain registered internally so dashboards/toolbars do not break.
- [x] Existing ASM/PSI engines remain separate internally.

## Trust / docs

- [x] Canonical Apache-2.0 `LICENSE` is added.
- [x] Shortened `LICENSE.md` is marked for removal.
- [x] Security policy requires private vulnerability disclosure.
- [x] Quick start uses the real `SHA256SUMS.txt` contract.
- [x] README begins with the user outcome and first successful scan.
- [x] Advanced engine concepts are below the activation path.
- [x] Telemetry is explicit opt-in, no endpoint is invented, and prohibited data is documented.

## Repository settings outside source control

- [ ] Apply repository description/topics from `docs/GITHUB_METADATA_0.91.0.md`.
- [ ] Confirm GitHub recognizes `LICENSE` as Apache-2.0 after merge.
- [ ] Enable/confirm private vulnerability reporting.

These are repository settings and cannot be represented as files in the requested change-only ZIP.

## Human activation gate

- [ ] Run `docs/USABILITY_TEST_0.91.0.md` with 10 first-time JVM developers.
- [ ] At least 8/10 complete the first scan using only README.
- [ ] No repeated P0/P1 onboarding failure remains unresolved.

This is deliberately not marked complete by automation. Faking a human usability result would invalidate the product-readiness assessment.
